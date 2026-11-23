package pos.data

import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.domain.*

internal val quotationOrderSnapshotTables = listOf("quotation_order_links")
internal fun PosRepository.initializeQuotationOrders() {
    featureSchema("""CREATE TABLE IF NOT EXISTS quotation_order_links(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,quotation_id TEXT NOT NULL,quotation_version INTEGER NOT NULL,
        order_id TEXT NOT NULL,review_token TEXT NOT NULL,deposit_minor INTEGER NOT NULL,
        deposit_id TEXT,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,quotation_id),UNIQUE(tenant_id,order_id),
        CHECK(typeof(quotation_version)='integer' AND quotation_version>0),
        CHECK(typeof(deposit_minor)='integer' AND deposit_minor>=0),CHECK((deposit_minor>0)=(deposit_id IS NOT NULL)),
        FOREIGN KEY(tenant_id,quotation_id) REFERENCES quotations(tenant_id,id),
        FOREIGN KEY(tenant_id,order_id) REFERENCES customer_orders(tenant_id,id),
        FOREIGN KEY(tenant_id,deposit_id) REFERENCES customer_order_events(tenant_id,id))""")
    for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS quotation_order_links_no_${action.lowercase()}
        BEFORE $action ON quotation_order_links BEGIN SELECT RAISE(ABORT,'Quotation order links cannot be changed'); END""")
}

data class QuotationOrderLink(val id: String, val quotationId: String, val quotationVersion: Long,
    val orderId: String, val reviewToken: String, val depositMinor: Long, val depositId: String?, val createdAt: String)
@ConsistentCopyVisibility
data class QuotationOrderReview internal constructor(val quotation: Quotation, val customerId: String,
    val customerName: String, val collectOn: String?, val note: String, val token: String)

fun PosRepository.quotationOrderLinks(tenantId: String): List<QuotationOrderLink> =
    featureRead(tenantId, StaffPermission.VIEW_SALES) { quotationOrderRows(tenantId) }

internal fun PosRepository.requireQuotationUnordered(tenantId: String, quotationId: String) {
    require(quotationOrderRows(tenantId).none { it.quotationId == quotationId }) {
        "This quotation already created a customer order. Open that order to record payments or fulfilment."
    }
}

fun PosRepository.reviewQuotationOrder(tenantId: String, quotationId: String, expectedVersion: Long,
    customerId: String, collectOn: String?, note: String): QuotationOrderReview =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        featureRead(tenantId, StaffPermission.DISCOUNT) { }
        quotationOrderReview(tenantId, quotationId, expectedVersion, customerId, collectOn, note)
    }

fun PosRepository.convertQuotationToOrder(tenantId: String, review: QuotationOrderReview,
    cashDepositMinor: Long, operationId: String): CustomerOrder = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    featureRead(tenantId, StaffPermission.DISCOUNT) { }
    validateOrderReference(operationId)
    require(cashDepositMinor in 0..review.quotation.totalMinor) { "Enter a cash deposit up to the agreed quotation total." }
    if (cashDepositMinor > 0) featureRead(tenantId, StaffPermission.MANAGE_MONEY) { }
    quotationOrderRows(tenantId).singleOrNull { it.id == operationId }?.let { previous ->
        val order = customerOrderState(tenantId, previous.orderId).order
        require(previous.quotationId == review.quotation.id && previous.quotationVersion == review.quotation.version &&
            previous.reviewToken == review.token && previous.depositMinor == cashDepositMinor &&
            order.customerId == review.customerId && order.collectOn == review.collectOn && order.note == quotationOrderNote(review)) {
            "This quotation conversion was already saved with different details."
        }
        return@featureWrite order
    }
    require(featureQuery("SELECT id FROM customer_orders WHERE tenant_id=? AND id=?", tenantId, operationId) { it.getString(1) }.isEmpty()) {
        "This operation identifier is already used by an order."
    }
    val checked = quotationOrderReview(tenantId, review.quotation.id, review.quotation.version,
        review.customerId, review.collectOn, review.note)
    require(checked == review) { "Quotation, customer or catalogue details changed. Review the order again." }
    val now = Instant.now()
    require(now >= Instant.parse(review.quotation.updatedAt) && !quotationExpired(review.quotation.expiresOn, now)) {
        "Correct the clock or review the expired quotation before creating an order."
    }
    val order = CustomerOrder(operationId, tenantId, review.customerId, review.customerName, "Other",
        review.collectOn, quotationOrderNote(review), now.toString(), review.quotation.lines.sortedBy { it.key })
    insertCustomerOrderSnapshot(order)
    val depositId = if (cashDepositMinor > 0) UUID.nameUUIDFromBytes("quotation-order-deposit:$operationId".toByteArray()).toString() else null
    if (depositId != null) recordCustomerOrderDeposit(tenantId, order.id, cashDepositMinor, 0, depositId)
    featureExecute("INSERT INTO quotation_order_links VALUES(?,?,?,?,?,?,?,?,?)", tenantId, operationId,
        review.quotation.id, review.quotation.version, order.id, review.token, cashDepositMinor, depositId, now.toString())
    featureEvent(tenantId, "order.from_quotation", operationId)
    order
}

private fun quotationOrderNote(review: QuotationOrderReview): String =
    "Quotation ${review.quotation.id} revision ${review.quotation.version}" + if (review.note.isEmpty()) "" else " · ${review.note}"

private fun PosRepository.quotationOrderReview(tenantId: String, quotationId: String, expectedVersion: Long,
    customerId: String, collectOn: String?, note: String): QuotationOrderReview {
    requireQuotationUnordered(tenantId, quotationId)
    val quote = requireNotNull(quotations(tenantId).singleOrNull { it.id == quotationId }) { "Choose a quotation from this shop." }
    require(quote.status == QuotationStatus.ACCEPTED && quote.version == expectedVersion) { "Accept the current quotation before creating its order." }
    val now = Instant.now()
    require(now >= Instant.parse(quote.updatedAt) && !quotationExpired(quote.expiresOn, now)) { "This quotation has expired or the clock moved backwards." }
    require(collectOn == null || (isValidCreditDueDate(collectOn) && !LocalDate.parse(collectOn).isBefore(now.atZone(ZoneId.of("Africa/Lagos")).toLocalDate()))) {
        "Choose today or a later collection date in YYYY-MM-DD format."
    }
    require(note == note.trim() && note.length <= 300 && note.none { it.isISOControl() }) { "Use up to 300 printable characters for the order note." }
    val customer = requireNotNull(customers(tenantId).singleOrNull { it.id == customerId }) { "Choose an existing customer from this shop." }
    require(quote.customer?.customerId?.let { it == customer.id } != false) { "Use the customer linked to the accepted quotation." }
    val products = products(tenantId, includeArchived = true).associateBy { it.id }
    val units = sellingUnits(tenantId, includeArchived = true).associateBy { it.id }
    val current = quote.lines.map { line ->
        val product = requireNotNull(products[line.productId]) { "Quoted goods are unavailable." }
        require(!product.archived && product.name == line.productName && product.unit == line.baseUnit && product.quantityPrecision == line.basePrecision) {
            "Quoted goods were renamed, archived or redefined. Review the quotation before creating its order."
        }
        val unit = line.sellingUnitId?.let { requireNotNull(units[it]) { "The quoted selling pack is unavailable." } }
        require(unit == null || (!unit.archived && unit.productId == product.id && unit.name == line.sellingUnit &&
            unit.baseUnit == line.baseUnit && unit.baseUnitsPerUnit == line.baseUnitsPerUnit)) { "The quoted selling pack changed. Review the quotation." }
        val price = unit?.priceMinor ?: product.priceMinor
        require(line.agreedUnitPriceMinor <= price) { "A quoted price exceeds today's normal price. Review the quoted terms before creating an order." }
        CartLine(product, line.quantity, unit?.id, line.sellingUnit, price, line.baseUnitsPerUnit,
            price - line.agreedUnitPriceMinor,
            if (price > line.agreedUnitPriceMinor) quotationSaleSource(quote.id, quote.version) else null, line.quantityPrecision)
    }
    validateMinimumPricesAtCheckout(tenantId, current)
    require(cartTotal(current) == quote.totalMinor) { "The agreed quotation total cannot be preserved." }
    val fields = listOf(tenantId, quotationId, expectedVersion.toString(), customerId, customer.name, collectOn.orEmpty(), note) +
        current.flatMap { listOf(it.key, it.product.name, it.product.unit, it.product.quantityPrecision.toString(),
            it.sellingUnit, it.unitPriceMinor.toString(), it.quantity.toString(), it.baseUnitsPerUnit.toString(), it.discountPerUnitMinor.toString()) }
    val token = MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return QuotationOrderReview(quote, customer.id, customer.name, collectOn, note, token)
}

private fun quotationOrderRow(row: ResultSet) = QuotationOrderLink(row.getString("id"), row.getString("quotation_id"),
    row.getLong("quotation_version"), row.getString("order_id"), row.getString("review_token"), row.getLong("deposit_minor"), row.getString("deposit_id"), row.getString("created_at"))
private fun PosRepository.quotationOrderRows(tenantId: String) =
    featureQuery("SELECT * FROM quotation_order_links WHERE tenant_id=? ORDER BY created_at,id", tenantId, map = ::quotationOrderRow)

internal fun PosRepository.validateQuotationOrderSnapshot(tenantId: String) {
    val links = quotationOrderRows(tenantId)
    for (link in links) {
        validateOrderReference(link.id)
        val quote = storedQuotation(tenantId, link.quotationId)
        require(quote.status == QuotationStatus.ACCEPTED && quote.version == link.quotationVersion && quote.saleId == null) {
            "Backup quotation order was converted or changed after its accepted terms."
        }
        val state = customerOrderState(tenantId, link.orderId)
        val order = state.order
        val at = Instant.parse(link.createdAt)
        require(order.id == link.id && order.createdAt == link.createdAt && at >= Instant.parse(quote.updatedAt) &&
            !quotationExpired(quote.expiresOn, at) && order.source == "Other" && order.lines == quote.lines.sortedBy { it.key } &&
            order.note.startsWith("Quotation ${quote.id} revision ${quote.version}") &&
            (quote.customer?.customerId?.let { it == order.customerId } != false) &&
            link.reviewToken.matches(Regex("[a-f0-9]{64}"))) { "Backup order differs from its accepted quotation." }
        if (link.depositId == null) require(link.depositMinor == 0L) { "Backup order deposit is missing." }
        else {
            val deposit = requireNotNull(state.history.singleOrNull { it.id == link.depositId }) { "Backup quotation deposit is unavailable." }
            require(link.depositMinor in 1..quote.totalMinor && deposit.kind == "deposit" && deposit.amountMinor == link.depositMinor &&
                Instant.parse(deposit.createdAt) > Instant.parse(order.createdAt) && state.history.first() == deposit) { "Backup quotation deposit differs from its recorded cash event." }
        }
    }
    val expected = links.associate { it.id to Instant.parse(it.createdAt) }
    val witnesses = featureQuery("""SELECT o.entity_id,o.created_at,a.id FROM outbox o LEFT JOIN activity_audit a
        ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind='order.from_quotation'""", tenantId) {
        require(it.getString(3) != null) { "Backup quotation-order activity is missing." }
        it.getString(1) to Instant.parse(it.getString(2))
    }
    require(witnesses.size == expected.size && witnesses.map { it.first }.toSet() == expected.keys && witnesses.all { (id, time) -> time >= expected.getValue(id) }) {
        "Backup quotation-order conversion history is incomplete."
    }
}
