package pos.data

import java.sql.ResultSet
import java.security.MessageDigest
import java.time.Instant
import pos.domain.*

internal val orderDispatchSnapshotTables = listOf("order_dispatches", "order_dispatch_lines", "order_dispatch_events")

internal fun PosRepository.initializeOrderDispatches() {
    featureSchema("""CREATE TABLE IF NOT EXISTS order_dispatches(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,order_id TEXT NOT NULL,sale_id TEXT NOT NULL,
        recipient TEXT NOT NULL,address TEXT NOT NULL,courier TEXT NOT NULL,tracking TEXT NOT NULL,
        note TEXT NOT NULL,review_token TEXT NOT NULL,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,order_id) REFERENCES customer_orders(tenant_id,id),
        FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS order_dispatch_lines(
        tenant_id TEXT NOT NULL,dispatch_id TEXT NOT NULL,line_key TEXT NOT NULL,
        quantity INTEGER NOT NULL CHECK(typeof(quantity)='integer' AND quantity BETWEEN 1 AND 2147483647),
        PRIMARY KEY(tenant_id,dispatch_id,line_key),
        FOREIGN KEY(tenant_id,dispatch_id) REFERENCES order_dispatches(tenant_id,id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS order_dispatch_events(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,dispatch_id TEXT NOT NULL,revision INTEGER NOT NULL,
        kind TEXT NOT NULL CHECK(kind IN ('dispatched','delivered','failed','returned')),
        proof_name TEXT,note TEXT NOT NULL,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,dispatch_id,revision),
        CHECK(typeof(revision)='integer' AND revision>0),
        CHECK((kind='delivered')=(proof_name IS NOT NULL)),
        FOREIGN KEY(tenant_id,dispatch_id) REFERENCES order_dispatches(tenant_id,id))""")
    featureSchema("CREATE INDEX IF NOT EXISTS order_dispatches_source ON order_dispatches(tenant_id,sale_id)")
    for (table in orderDispatchSnapshotTables) for (action in listOf("UPDATE", "DELETE")) {
        featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()}
            BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Dispatch history cannot be changed'); END""")
    }
}

data class OrderDispatchDetails(val recipient: String, val address: String, val courier: String, val tracking: String, val note: String)
data class OrderDispatchEvent(val id: String, val dispatchId: String, val revision: Int, val kind: String,
    val proofName: String?, val note: String, val createdAt: String)
data class OrderDispatch(val id: String, val orderId: String, val saleId: String, val details: OrderDispatchDetails,
    val quantities: Map<String, Int>, val reviewToken: String, val createdAt: String, val history: List<OrderDispatchEvent>) {
    val status: String get() = history.last().kind
    val revision: Int get() = history.last().revision
}
@ConsistentCopyVisibility
data class OrderDispatchReview internal constructor(val orderId: String, val saleId: String,
    val lines: List<CartLine>, val available: Map<String, Int>, val token: String)

fun PosRepository.orderDispatches(tenantId: String, orderId: String? = null): List<OrderDispatch> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) { dispatchRows(tenantId, orderId) }

fun PosRepository.reviewOrderDispatch(tenantId: String, orderId: String, saleId: String): OrderDispatchReview =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) { dispatchReview(tenantId, orderId, saleId) }

fun PosRepository.createOrderDispatch(tenantId: String, review: OrderDispatchReview, quantities: Map<String, Int>,
    details: OrderDispatchDetails, operationId: String): OrderDispatch = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    validateOrderReference(operationId)
    validateDispatchDetails(details)
    require(quantities.isNotEmpty() && quantities.values.all { it > 0 }) { "Choose the goods leaving with this delivery." }
    dispatchRows(tenantId).singleOrNull { it.id == operationId }?.let { saved ->
        require(saved.orderId == review.orderId && saved.saleId == review.saleId && saved.details == details &&
            saved.quantities == quantities && saved.reviewToken == review.token) { "This dispatch was already saved with different details." }
        return@featureWrite saved
    }
    val checked = dispatchReview(tenantId, review.orderId, review.saleId)
    require(checked == review) { "The goods available for dispatch changed. Review this delivery again." }
    require(quantities.all { (key, quantity) -> quantity <= checked.available.getOrDefault(key, 0) }) {
        "A dispatch quantity exceeds the fulfilled goods still available for delivery."
    }
    val now = Instant.now()
    val sale = sourceDispatchSale(tenantId, review.orderId, review.saleId)
    val priorTimes = listOf(sale.createdAt) + saleReturns(tenantId, sale.id).map { it.createdAt } +
        dispatchRows(tenantId).filter { it.saleId == sale.id }.flatMap { it.history.map { event -> event.createdAt } }
    require(priorTimes.all { now > Instant.parse(it) }) { "Correct the clock before recording this dispatch." }
    featureExecute("INSERT INTO order_dispatches VALUES(?,?,?,?,?,?,?,?,?,?,?)", tenantId, operationId,
        review.orderId, review.saleId, details.recipient, details.address, details.courier, details.tracking,
        details.note, review.token, now.toString())
    quantities.forEach { (key, quantity) ->
        featureExecute("INSERT INTO order_dispatch_lines VALUES(?,?,?,?)", tenantId, operationId, key, quantity)
    }
    insertDispatchEvent(tenantId, OrderDispatchEvent(operationId, operationId, 1, "dispatched", null, details.note, now.toString()))
    dispatchRows(tenantId).single { it.id == operationId }
}

fun PosRepository.recordOrderDeliveryStatus(tenantId: String, dispatchId: String, expectedRevision: Int,
    kind: String, proofName: String?, note: String, operationId: String): OrderDispatchEvent =
    featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        validateOrderReference(operationId)
        validateDeliveryNote(note)
        require((kind == "delivered") == (proofName != null)) { "Record the recipient's name for a delivered order." }
        proofName?.let { validateDispatchText(it, 1, 100, "Recipient proof name") }
        dispatchEventById(tenantId, operationId)?.let { saved ->
            require(saved.dispatchId == dispatchId && saved.revision == expectedRevision + 1 && saved.kind == kind &&
                saved.proofName == proofName && saved.note == note) { "This delivery update was already saved with different details." }
            return@featureWrite saved
        }
        val dispatch = requireNotNull(dispatchRows(tenantId).singleOrNull { it.id == dispatchId }) { "Choose a delivery from this shop." }
        require(dispatch.revision == expectedRevision) { "The delivery changed. Open it again before updating its status." }
        require(validDispatchTransition(dispatch.status, kind)) { "Choose a supported next status for this delivery." }
        val now = Instant.now()
        require(now > Instant.parse(dispatch.history.last().createdAt)) { "Correct the clock before updating this delivery." }
        OrderDispatchEvent(operationId, dispatchId, Math.incrementExact(expectedRevision), kind, proofName, note, now.toString()).also {
            insertDispatchEvent(tenantId, it)
        }
    }

private fun validDispatchTransition(before: String, after: String) = when (before) {
    "dispatched" -> after in setOf("delivered", "failed", "returned")
    "failed" -> after in setOf("dispatched", "returned")
    else -> false
}

private fun validateDispatchText(value: String, minimum: Int, maximum: Int, label: String) {
    require(value == value.trim() && value.length in minimum..maximum && value.none { it.isISOControl() }) {
        "$label must contain $minimum to $maximum printable characters."
    }
}
private fun validateDeliveryNote(note: String) = validateDispatchText(note, 3, 500, "Delivery note")
private fun validateDispatchDetails(details: OrderDispatchDetails) {
    validateDispatchText(details.recipient, 1, 100, "Recipient")
    validateDispatchText(details.address, 3, 500, "Delivery address")
    validateDispatchText(details.courier, 1, 100, "Courier or person delivering")
    validateDispatchText(details.tracking, 0, 150, "Tracking reference")
    validateDeliveryNote(details.note)
}

private fun PosRepository.sourceDispatchSale(tenantId: String, orderId: String, saleId: String): Sale {
    val order = customerOrderState(tenantId, orderId)
    require(order.history.any { it.kind == "collected" && it.saleId == saleId }) { "Choose a fulfilled sale receipt from this customer order." }
    return requireNotNull(sales(tenantId).singleOrNull { it.id == saleId && it.orderPayment?.orderId == orderId }) {
        "The fulfilled sale is unavailable for this customer order."
    }
}
private fun PosRepository.dispatchReview(tenantId: String, orderId: String, saleId: String): OrderDispatchReview {
    val sale = sourceDispatchSale(tenantId, orderId, saleId)
    val shipments = dispatchRows(tenantId).filter { it.saleId == saleId }
    val returns = saleReturns(tenantId, saleId)
    val available = sale.lines.associate { line ->
        val used = shipments.sumOf { it.quantities[line.key]?.toLong() ?: 0L } +
            returns.flatMap { it.lines }.filter { it.key == line.key }.sumOf { it.quantity.toLong() }
        line.key to (line.quantity.toLong() - used).coerceAtLeast(0L).toInt()
    }
    val fields = listOf(tenantId, orderId, saleId) + available.toSortedMap().flatMap { (key, value) -> listOf(key, value.toString()) } +
        shipments.sortedBy { it.id }.flatMap { listOf(it.id, it.createdAt) } + returns.sortedBy { it.id }.flatMap { listOf(it.id, it.createdAt) }
    val token = MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return OrderDispatchReview(orderId, saleId, sale.lines, available, token)
}
private fun dispatchEvent(row: ResultSet) = OrderDispatchEvent(row.getString("id"), row.getString("dispatch_id"),
    row.getInt("revision"), row.getString("kind"), row.getString("proof_name"), row.getString("note"), row.getString("created_at"))
private fun PosRepository.dispatchEventById(tenantId: String, id: String) =
    featureQuery("SELECT * FROM order_dispatch_events WHERE tenant_id=? AND id=?", tenantId, id, map = ::dispatchEvent).singleOrNull()
private fun PosRepository.dispatchHistory(tenantId: String, id: String) =
    featureQuery("SELECT * FROM order_dispatch_events WHERE tenant_id=? AND dispatch_id=? ORDER BY revision", tenantId, id, map = ::dispatchEvent)
private fun PosRepository.dispatchRows(tenantId: String, orderId: String? = null): List<OrderDispatch> =
    featureQuery("SELECT * FROM order_dispatches WHERE tenant_id=?" + (if (orderId == null) "" else " AND order_id=?") + " ORDER BY created_at,id",
        *listOfNotNull(tenantId, orderId).toTypedArray()) { row ->
        val id = row.getString("id")
        OrderDispatch(id, row.getString("order_id"), row.getString("sale_id"),
            OrderDispatchDetails(row.getString("recipient"), row.getString("address"), row.getString("courier"), row.getString("tracking"), row.getString("note")),
            featureQuery("SELECT line_key,quantity FROM order_dispatch_lines WHERE tenant_id=? AND dispatch_id=? ORDER BY line_key", tenantId, id) {
                it.getString(1) to it.getInt(2)
            }.toMap(), row.getString("review_token"), row.getString("created_at"), dispatchHistory(tenantId, id))
    }
private fun PosRepository.insertDispatchEvent(tenantId: String, event: OrderDispatchEvent) {
    featureExecute("INSERT INTO order_dispatch_events VALUES(?,?,?,?,?,?,?,?)", tenantId, event.id, event.dispatchId,
        event.revision, event.kind, event.proofName, event.note, event.createdAt)
    featureEvent(tenantId, "order_dispatch.${event.kind}", event.id)
}

internal fun PosRepository.validateOrderDispatchSnapshot(tenantId: String) {
    val dispatches = dispatchRows(tenantId)
    for (dispatch in dispatches) {
        validateOrderReference(dispatch.id)
        validateDispatchDetails(dispatch.details)
        require(dispatch.reviewToken.matches(Regex("[a-f0-9]{64}"))) { "Backup dispatch review is invalid." }
        val sale = sourceDispatchSale(tenantId, dispatch.orderId, dispatch.saleId)
        val created = Instant.parse(dispatch.createdAt)
        require(created > Instant.parse(sale.createdAt) && dispatch.quantities.isNotEmpty()) { "Backup dispatch predates its fulfilled sale." }
        val before = dispatches.filter { it.saleId == sale.id && Instant.parse(it.createdAt) < created }
        require(dispatches.none { it.id != dispatch.id && it.saleId == sale.id && it.createdAt == dispatch.createdAt }) { "Backup dispatch times overlap." }
        val returns = saleReturns(tenantId, sale.id).filter { Instant.parse(it.createdAt) <= created }
        for ((key, quantity) in dispatch.quantities) {
            val line = requireNotNull(sale.lines.singleOrNull { it.key == key }) { "Backup dispatch contains goods outside its sale." }
            val used = before.sumOf { it.quantities[key]?.toLong() ?: 0L } +
                returns.flatMap { it.lines }.filter { it.key == key }.sumOf { it.quantity.toLong() }
            require(quantity > 0 && quantity.toLong() <= line.quantity.toLong() - used) { "Backup dispatch exceeds its fulfilled goods." }
        }
        require(dispatch.history.isNotEmpty()) { "Backup dispatch status history is missing." }
        var previousTime = created
        var previousKind: String? = null
        dispatch.history.forEachIndexed { index, event ->
            validateOrderReference(event.id); validateDeliveryNote(event.note)
            val time = Instant.parse(event.createdAt)
            require(event.revision == index + 1 &&
                (if (index == 0) event.id == dispatch.id && event.kind == "dispatched" && event.createdAt == dispatch.createdAt && event.note == dispatch.details.note
                 else time > previousTime && validDispatchTransition(requireNotNull(previousKind), event.kind))) { "Backup delivery status history is invalid." }
            require((event.kind == "delivered") == (event.proofName != null)) { "Backup delivery proof is missing." }
            event.proofName?.let { validateDispatchText(it, 1, 100, "Recipient proof name") }
            previousTime = time; previousKind = event.kind
        }
    }
    val expected = dispatches.flatMap { it.history }.associate { ("order_dispatch.${it.kind}" to it.id) to Instant.parse(it.createdAt) }
    val actual = featureQuery("""SELECT o.kind,o.entity_id,o.created_at,a.id FROM outbox o LEFT JOIN activity_audit a
        ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind IN ('order_dispatch.dispatched','order_dispatch.delivered','order_dispatch.failed','order_dispatch.returned')""", tenantId) {
        require(it.getString(4) != null) { "Backup delivery activity is missing." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(3))
    }
    require(actual.size == expected.size && actual.map { it.first }.toSet() == expected.keys && actual.all { (key, time) -> time >= expected.getValue(key) }) {
        "Backup delivery history is incomplete."
    }
}
