package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import pos.domain.Quantity
import pos.domain.StaffPermission
import pos.domain.isValidCreditDueDate

internal val orderReservationSnapshotTables = listOf("order_reservation_events")
private val reservationZone = ZoneId.of("Africa/Lagos")

internal fun PosRepository.initializeOrderReservations() {
    featureSchema("""CREATE TABLE IF NOT EXISTS order_reservation_events(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,order_id TEXT NOT NULL,
        revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 2147483647),
        order_history_size INTEGER NOT NULL CHECK(typeof(order_history_size)='integer' AND order_history_size BETWEEN 0 AND 2147483647),
        kind TEXT NOT NULL CHECK(kind IN ('held','released')),
        expires_on TEXT,reason TEXT NOT NULL,created_at TEXT NOT NULL,
        CHECK((kind='held')=(expires_on IS NOT NULL)),
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,order_id,revision),
        FOREIGN KEY(tenant_id,order_id) REFERENCES customer_orders(tenant_id,id))""")
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema("""CREATE TRIGGER IF NOT EXISTS order_reservations_no_${action.lowercase()}
            BEFORE $action ON order_reservation_events
            BEGIN SELECT RAISE(ABORT,'Saved stock hold history cannot be changed'); END""")
    }
}

data class OrderReservationEvent(
    val id: String, val orderId: String, val revision: Int, val orderHistorySize: Int,
    val kind: String, val expiresOn: String?, val reason: String, val createdAt: String,
) {
    internal fun heldOn(watDate: String): Boolean = expiresOn?.let { it >= watDate } == true
}

internal fun orderReservationDate(now: Instant): String = now.atZone(reservationZone).toLocalDate().toString()

fun PosRepository.customerOrderReservationHistory(tenantId: String, orderId: String): List<OrderReservationEvent> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) { reservationEvents(tenantId, orderId) }

fun PosRepository.holdCustomerOrderStock(
    tenantId: String, orderId: String, expiresOn: String, orderHistorySize: Int, reservationHistorySize: Int, operationId: String,
): OrderReservationEvent =
    changeOrderReservation(tenantId, orderId, "held", expiresOn, "Goods put aside for order",
        orderHistorySize, reservationHistorySize, operationId)

fun PosRepository.releaseCustomerOrderStock(
    tenantId: String, orderId: String, reason: String, orderHistorySize: Int, reservationHistorySize: Int, operationId: String,
): OrderReservationEvent =
    changeOrderReservation(tenantId, orderId, "released", null, reason.trim(),
        orderHistorySize, reservationHistorySize, operationId)

private fun PosRepository.changeOrderReservation(
    tenantId: String, orderId: String, kind: String, expiresOn: String?, reason: String,
    orderHistorySize: Int, reservationHistorySize: Int, operationId: String,
): OrderReservationEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { }
    validateOrderReference(operationId)
    validateReservationReason(reason)
    require(expiresOn == null || isValidCreditDueDate(expiresOn)) { "Enter the hold date as YYYY-MM-DD." }
    featureQuery("SELECT * FROM order_reservation_events WHERE tenant_id=? AND id=?",
        tenantId, operationId, map = ::reservationRow).singleOrNull()?.let {
        require(it.orderId == orderId && it.kind == kind && it.expiresOn == expiresOn && it.reason == reason &&
            it.orderHistorySize == orderHistorySize && it.revision - 1 == reservationHistorySize) {
            "This stock hold request was already saved with different details."
        }
        return@featureWrite it
    }
    val state = customerOrderState(tenantId, orderId)
    val previous = latestOrderReservation(tenantId, orderId)
    require(state.open && state.history.size == orderHistorySize && (previous?.revision ?: 0) == reservationHistorySize) {
        "The order or stock hold changed. Open the order again."
    }
    val now = orderTime(state)
    val today = orderReservationDate(now)
    val alreadyHeld = previous?.heldOn(today) == true
    if (kind == "held") {
        require(!alreadyHeld) { "Release the current stock hold before setting another one." }
        require(requireNotNull(expiresOn) >= today) { "Choose today or a later hold date." }
        val otherHolds = reservedQuantities(tenantId, orderId, now)
        val products = products(tenantId).associateBy { it.id }
        val units = sellingUnits(tenantId).associateBy { it.id }
        state.order.lines.filter { state.remaining.getValue(it.key) > 0 }.forEach { line ->
            val product = requireNotNull(products[line.productId]) { "Restore ${line.productName} before holding stock." }
            require(product.name == line.productName && product.unit == line.baseUnit && product.quantityPrecision == line.basePrecision) {
                "The saved definition of ${line.productName} changed."
            }
            line.sellingUnitId?.let { id ->
                val unit = requireNotNull(units[id]) { "Restore the saved selling pack for ${line.productName} before holding stock." }
                require(unit.productId == product.id && unit.name == line.sellingUnit && unit.baseUnitsPerUnit == line.baseUnitsPerUnit) {
                    "The saved selling pack for ${line.productName} changed."
                }
            }
        }
        remainingReservationQuantities(state).forEach { (productId, quantity) ->
            val product = products.getValue(productId)
            val available = (product.exactStockScaled - otherHolds.getOrDefault(productId, 0L)).coerceAtLeast(0)
            require(quantity <= available) { "There is not enough available stock to hold ${product.name} for this order." }
        }
    } else {
        require(alreadyHeld) { "There is no active hold to release." }
    }
    val event = OrderReservationEvent(operationId, orderId, Math.incrementExact(reservationHistorySize), orderHistorySize,
        kind, expiresOn, reason, now.toString())
    featureExecute("""INSERT INTO order_reservation_events(
        tenant_id,id,order_id,revision,order_history_size,kind,expires_on,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)""",
        tenantId, event.id, event.orderId, event.revision, event.orderHistorySize, event.kind, event.expiresOn, event.reason,
        event.createdAt)
    featureEvent(tenantId, "order_reservation.${event.kind}", event.id)
    event
}

fun PosRepository.orderReservedQuantities(
    tenantId: String, now: Instant = Instant.now(), permission: StaffPermission = StaffPermission.SELL,
): Map<String, Long> {
    require(permission == StaffPermission.SELL || permission == StaffPermission.MANAGE_STOCK)
    return featureRead(tenantId, permission) { reservedQuantities(tenantId, null, now) }
}

internal fun PosRepository.reservedStock(
    tenantId: String, productId: String, excludingOrderId: String? = null, now: Instant = Instant.now(),
): Long = reservedQuantities(tenantId, excludingOrderId, now, productId).getOrDefault(productId, 0L)

private fun PosRepository.reservedQuantities(
    tenantId: String, excludingOrderId: String?, now: Instant, productId: String? = null,
): Map<String, Long> {
    val productFilter = if (productId == null) "" else """ AND EXISTS(
        SELECT 1 FROM customer_order_lines l WHERE l.tenant_id=r.tenant_id AND l.order_id=r.order_id AND l.product_id=?)"""
    val args = listOfNotNull(tenantId, orderReservationDate(now), productId).toTypedArray()
    val holdOrders = featureQuery("""SELECT r.order_id FROM order_reservation_events r
        WHERE r.tenant_id=? AND r.expires_on>=? AND NOT EXISTS(
            SELECT 1 FROM order_reservation_events newer
            WHERE newer.tenant_id=r.tenant_id AND newer.order_id=r.order_id AND newer.revision>r.revision
        )$productFilter""", *args) { it.getString(1) }
    return buildMap {
        for (orderId in holdOrders) {
            if (orderId == excludingOrderId) continue
            remainingReservationQuantities(customerOrderState(tenantId, orderId)).forEach { (id, quantity) ->
                put(id, Math.addExact(getOrDefault(id, 0L), quantity))
            }
        }
    }
}

private fun remainingReservationQuantities(state: CustomerOrderState): Map<String, Long> = buildMap {
    if (!state.open) return@buildMap
    for (line in state.order.lines) {
        val remaining = state.remaining.getValue(line.key)
        if (remaining == 0) continue
        val base = Quantity(remaining.toLong(), line.quantityPrecision).rescale(line.basePrecision)
            .timesWhole(line.baseUnitsPerUnit.toLong()).scaledUnits
        put(line.productId, Math.addExact(getOrDefault(line.productId, 0L), base))
    }
}

private fun reservationRow(row: ResultSet) = OrderReservationEvent(
    row.getString("id"), row.getString("order_id"), row.getInt("revision"), row.getInt("order_history_size"),
    row.getString("kind"), row.getString("expires_on"), row.getString("reason"), row.getString("created_at"),
)

private fun PosRepository.reservationEvents(tenantId: String, orderId: String? = null): List<OrderReservationEvent> =
    featureQuery("SELECT * FROM order_reservation_events WHERE tenant_id=?" +
        (if (orderId == null) "" else " AND order_id=?") + " ORDER BY revision",
        *listOfNotNull(tenantId, orderId).toTypedArray(), map = ::reservationRow)

internal fun PosRepository.latestOrderReservation(tenantId: String, orderId: String): OrderReservationEvent? =
    featureQuery("SELECT * FROM order_reservation_events WHERE tenant_id=? AND order_id=? ORDER BY revision DESC LIMIT 1",
        tenantId, orderId, map = ::reservationRow).singleOrNull()

private fun validateReservationReason(reason: String) {
    require(reason == reason.trim() && reason.length in 3..250 && reason.none { it.isISOControl() }) {
        "Enter a reason from 3 to 250 printable characters."
    }
}

internal fun PosRepository.validateOrderReservationSnapshot(tenantId: String) {
    val entries = reservationEvents(tenantId)
    val expected = entries.associate { ("order_reservation.${it.kind}" to it.id) to Instant.parse(it.createdAt) }
    val witnessed = featureQuery("""SELECT o.kind,o.entity_id,o.created_at,a.id FROM outbox o
        LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind IN ('order_reservation.held','order_reservation.released')""", tenantId) {
        require(it.getString(4) != null) { "Backup stock hold is missing its activity record." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(3))
    }
    require(witnessed.size == expected.size && witnessed.map { it.first }.toSet() == expected.keys &&
        witnessed.all { (key, at) -> at >= expected.getValue(key) }) { "Backup stock hold history is incomplete." }

    for ((orderId, history) in entries.groupBy { it.orderId }) {
        val state = customerOrderState(tenantId, orderId)
        val orderEvents = state.history.map { Instant.parse(it.createdAt) }
        var previousTime = Instant.parse(state.order.createdAt)
        var previous: OrderReservationEvent? = null
        history.forEachIndexed { index, event ->
            validateOrderReference(event.id)
            validateReservationReason(event.reason)
            val at = Instant.parse(event.createdAt)
            val date = orderReservationDate(at)
            val orderHistorySize = orderEvents.count { it < at }
            require(event.revision == index + 1 && at > previousTime && at !in orderEvents &&
                event.orderHistorySize == orderHistorySize && (state.open || orderHistorySize < state.history.size)) {
                "Backup stock hold is out of order."
            }
            val priorWasActive = previous?.heldOn(date) == true
            val expiry = event.expiresOn
            when (event.kind) {
                "held" -> require(!priorWasActive && expiry != null && isValidCreditDueDate(expiry) && expiry >= date) {
                    "Backup stock hold has an invalid date."
                }
                "released" -> require(priorWasActive && expiry == null) { "Backup stock hold had already ended." }
                else -> error("Unknown stock hold activity.")
            }
            previousTime = at
            previous = event
        }
    }
}
