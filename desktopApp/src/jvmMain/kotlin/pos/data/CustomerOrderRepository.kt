package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.domain.*

internal val customerOrderSnapshotTables = listOf("customer_orders", "customer_order_lines", "customer_order_events")

internal fun PosRepository.initializeCustomerOrders() {
    featureSchema("""CREATE TABLE IF NOT EXISTS customer_orders(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,customer_id TEXT NOT NULL,customer_name TEXT NOT NULL,
        source TEXT NOT NULL,collect_on TEXT,note TEXT NOT NULL,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS customer_order_lines(
        tenant_id TEXT NOT NULL,order_id TEXT NOT NULL,line_key TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,
        base_unit TEXT NOT NULL,base_precision INTEGER NOT NULL,selling_unit_id TEXT,selling_unit TEXT NOT NULL,
        base_units_per_unit INTEGER NOT NULL CHECK(base_units_per_unit BETWEEN 1 AND 2147483647),
        quantity_precision INTEGER NOT NULL,quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 2147483647),
        agreed_price_minor INTEGER NOT NULL,
        PRIMARY KEY(tenant_id,order_id,line_key),FOREIGN KEY(tenant_id,order_id) REFERENCES customer_orders(tenant_id,id),
        FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS customer_order_events(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,order_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('deposit','collected','cancelled')),
        amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),sale_id TEXT,cash_shift_id TEXT,reason TEXT NOT NULL,
        created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,sale_id),
        FOREIGN KEY(tenant_id,order_id) REFERENCES customer_orders(tenant_id,id),
        FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),
        FOREIGN KEY(tenant_id,cash_shift_id) REFERENCES cash_shifts(tenant_id,id))""")
    featureSchema("CREATE INDEX IF NOT EXISTS customer_order_events_order ON customer_order_events(tenant_id,order_id)")
    for (table in customerOrderSnapshotTables) for (action in listOf("UPDATE", "DELETE")) {
        featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()}
            BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Saved order history cannot be changed'); END""")
    }
}

data class CustomerOrderEvent(
    val id: String, val orderId: String, val kind: String, val amountMinor: Long,
    val saleId: String?, val cashShiftId: String?, val reason: String, val createdAt: String,
)

data class CustomerOrderState(
    val order: CustomerOrder, val history: List<CustomerOrderEvent>, val remaining: Map<String, Int>,
    val depositLeftMinor: Long, val remainingValueMinor: Long,
) {
    val cancelled: Boolean get() = history.lastOrNull()?.kind == "cancelled"
    val completed: Boolean get() = remaining.values.none { it > 0 }
    val open: Boolean get() = !cancelled && !completed
    val balanceMinor: Long get() = remainingValueMinor - depositLeftMinor
}

@ConsistentCopyVisibility
data class OrderCollectionReview internal constructor(
    val orderId: String, val historySize: Int, val lines: List<CartLine>, val depositMinor: Long,
    val cashMinor: Long, val shiftId: String?,
)

fun PosRepository.customerOrders(tenantId: String): List<CustomerOrderState> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        orderHeaders(tenantId).map { orderState(it) }
    }

fun PosRepository.createCustomerOrder(
    tenantId: String, customerId: String, source: String, collectOn: String?, note: String,
    items: List<QuotationLineInput>, operationId: String,
): CustomerOrder = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    validateOrderReference(operationId)
    orderHeaders(tenantId, operationId).singleOrNull()?.let {
        require(it.customerId == customerId && it.source == source && it.collectOn == collectOn && it.note == note.trim() &&
            it.lines.map(QuotationLine::input) == items.sortedBy { line -> line.key }) {
            "This order was already saved with different details."
        }
        return@featureWrite it
    }
    val now = Instant.now()
    require(collectOn == null || !LocalDate.parse(collectOn).isBefore(now.atZone(orderZone).toLocalDate())) {
        "Choose today or a later collection date."
    }
    val customer = requireNotNull(customers(tenantId).find { it.id == customerId }) { "Choose a customer from this shop." }
    val lines = snapshotQuoteLines(tenantId, items).sortedBy { it.key }
    val prices = products(tenantId).associateBy { it.id }
    val units = sellingUnits(tenantId).associateBy { it.id }
    require(lines.all { it.agreedUnitPriceMinor == (it.sellingUnitId?.let { id -> units.getValue(id).priceMinor }
        ?: prices.getValue(it.productId).priceMinor) }) { "A selling price changed. Review the order items again." }
    val order = CustomerOrder(operationId, tenantId, customerId, customer.name, source, collectOn,
        note.trim(), now.toString(), lines)
    insertCustomerOrderSnapshot(order)
    order
}

internal fun PosRepository.insertCustomerOrderSnapshot(order: CustomerOrder) {
    val tenantId = order.tenantId
    val customerId = order.customerId
    val source = order.source
    val collectOn = order.collectOn
    featureExecute("INSERT INTO customer_orders VALUES(?,?,?,?,?,?,?,?)", tenantId, order.id, customerId, order.customerName,
        source, collectOn, order.note, order.createdAt)
    order.lines.forEach { line ->
        featureExecute("INSERT INTO customer_order_lines VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", tenantId, order.id, line.key,
            line.productId, line.productName, line.baseUnit, line.basePrecision, line.sellingUnitId, line.sellingUnit,
            line.baseUnitsPerUnit, line.quantityPrecision, line.quantity, line.agreedUnitPriceMinor)
    }
    featureEvent(tenantId, "order.created", order.id)
}

fun PosRepository.recordCustomerOrderDeposit(
    tenantId: String, orderId: String, amountMinor: Long, historySize: Int, operationId: String,
): CustomerOrderEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    featureRead(tenantId, StaffPermission.MANAGE_MONEY) { }
    validateOrderReference(operationId)
    eventById(tenantId, operationId)?.let {
        require(it.orderId == orderId && it.kind == "deposit" && it.amountMinor == amountMinor)
        return@featureWrite it
    }
    val state = orderState(requireCustomerOrder(tenantId, orderId))
    require(state.open && state.history.size == historySize) { "Review the current order before recording a deposit." }
    require(amountMinor in 1..state.balanceMinor) { "Enter cash received up to the remaining amount on this order." }
    val shift = requireNotNull(activeCashShift(tenantId)) { "Open today's drawer before receiving a deposit." }
    val event = CustomerOrderEvent(operationId, orderId, "deposit", amountMinor, null, shift.id, "Cash deposit received", orderTime(state).toString())
    recordOrderDrawerCash(tenantId, event)
    recordOrderEvent(tenantId, event)
    event
}

fun PosRepository.cancelCustomerOrder(
    tenantId: String, orderId: String, historySize: Int, reviewedRefundMinor: Long, reason: String, operationId: String,
): CustomerOrderEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    validateOrderReference(operationId)
    val cleanReason = reason.trim()
    require(cleanReason.length in 3..250 && cleanReason.none { it.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." }
    if (reviewedRefundMinor > 0) featureRead(tenantId, StaffPermission.MANAGE_MONEY) { }
    eventById(tenantId, operationId)?.let {
        require(it.orderId == orderId && it.kind == "cancelled" && it.amountMinor == reviewedRefundMinor && it.reason == cleanReason)
        return@featureWrite it
    }
    val state = orderState(requireCustomerOrder(tenantId, orderId))
    require(state.open && state.history.size == historySize && state.depositLeftMinor == reviewedRefundMinor) {
        "The order changed. Review the remaining goods and deposit again."
    }
    val shift = if (reviewedRefundMinor == 0L) null else requireNotNull(activeCashShift(tenantId)) {
        "Open today's drawer before refunding the deposit."
    }
    val event = CustomerOrderEvent(operationId, orderId, "cancelled", reviewedRefundMinor, null, shift?.id, cleanReason, orderTime(state).toString())
    if (reviewedRefundMinor > 0) recordOrderDrawerCash(tenantId, event)
    recordOrderEvent(tenantId, event)
    event
}

fun PosRepository.reviewCustomerOrderCollection(
    tenantId: String, orderId: String, quantities: Map<String, Int>,
): OrderCollectionReview = featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    featureRead(tenantId, StaffPermission.SELL) { }
    val state = orderState(requireCustomerOrder(tenantId, orderId))
    require(state.open && quantities.isNotEmpty() && quantities.all { (key, quantity) ->
        quantity in 1..(state.remaining[key] ?: 0)
    }) { "Choose quantities still waiting to be collected." }
    val products = products(tenantId).associateBy { it.id }
    val units = sellingUnits(tenantId).associateBy { it.id }
    val lines = state.order.lines.mapNotNull { frozen ->
        val quantity = quantities[frozen.key] ?: return@mapNotNull null
        val product = requireNotNull(products[frozen.productId]) { "Restore ${frozen.productName} before collection." }
        require(product.name == frozen.productName && product.unit == frozen.baseUnit && product.quantityPrecision == frozen.basePrecision) {
            "The saved definition of ${frozen.productName} changed."
        }
        val unit = frozen.sellingUnitId?.let { requireNotNull(units[it]) { "The saved selling pack is unavailable." } }
        require(unit == null || unit.productId == product.id && unit.name == frozen.sellingUnit && unit.baseUnitsPerUnit == frozen.baseUnitsPerUnit) {
            "The saved selling pack changed."
        }
        val cataloguePrice = unit?.priceMinor ?: product.priceMinor
        require(cataloguePrice >= frozen.agreedUnitPriceMinor) { "The current price is below the agreed order price. Review the terms with the customer." }
        val reduction = cataloguePrice - frozen.agreedUnitPriceMinor
        val line = CartLine(product, quantity, unit?.id, frozen.sellingUnit, cataloguePrice, frozen.baseUnitsPerUnit,
            reduction, if (reduction > 0) "Agreed customer order ${state.order.id}" else null, frozen.quantityPrecision)
        val already = frozen.quantity - state.remaining.getValue(frozen.key)
        require(line.totalMinor == marginalOrderValue(frozen, already, quantity)) {
            "This partial quantity rounds to a different price. Pick a different quantity or collect the rest together."
        }
        line
    }
    val stockCheckedAt = Instant.now()
    lines.groupBy { it.product.id }.forEach { (id, group) ->
        val needed = group.fold(0L) { total, line -> Math.addExact(total, line.baseQuantity) }
        val product = products.getValue(id)
        require(needed <= product.exactStockScaled) { "There is not enough of ${product.name} in stock." }
        requireUnreservedStock(tenantId, product, needed, excludingOrderId = orderId, now = stockCheckedAt)
    }
    validateMinimumPricesAtCheckout(tenantId, lines)
    val total = cartTotal(lines)
    val deposit = minOf(state.depositLeftMinor, total)
    val cash = total - deposit
    val shift = if (cash == 0L) null else featureRead(tenantId, StaffPermission.MANAGE_MONEY) {
        requireNotNull(activeCashShift(tenantId)) { "Open today's drawer before taking the remaining cash payment." }
    }
    OrderCollectionReview(orderId, state.history.size, lines, deposit, cash, shift?.id)
}

fun PosRepository.collectCustomerOrder(
    tenantId: String, expected: OrderCollectionReview, operationId: String,
): CustomerOrderEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    validateOrderReference(operationId)
    featureRead(tenantId, StaffPermission.SELL) { }
    if (expected.cashMinor > 0) featureRead(tenantId, StaffPermission.MANAGE_MONEY) { }
    eventById(tenantId, operationId)?.let {
        val saved = customerOrderSale(tenantId, requireNotNull(it.saleId))
        require(it.orderId == expected.orderId && it.kind == "collected" && it.amountMinor == expected.depositMinor &&
            saved.cashMinor == expected.cashMinor && saved.lines.map { line -> line.key to line.quantity } ==
            expected.lines.map { line -> line.key to line.quantity }) { "This collection already has different saved details." }
        return@featureWrite it
    }
    val checked = reviewCustomerOrderCollection(tenantId, expected.orderId, expected.lines.associate { it.key to it.quantity })
    require(expected == checked) { "The order, stock or drawer changed. Review the collection again." }
    val state = orderState(requireCustomerOrder(tenantId, expected.orderId))
    val id = UUID.nameUUIDFromBytes("customer-order-pickup:$operationId".toByteArray()).toString()
    val now = orderTime(state)
    val sale = checkoutCustomerOrder(tenantId, state.order.customerId, id, checked)
    require(Instant.parse(sale.createdAt) >= now) { "Correct the clock before saving this collection." }
    val event = CustomerOrderEvent(operationId, checked.orderId, "collected", checked.depositMinor, sale.id,
        checked.shiftId, "Goods collected", sale.createdAt)
    recordOrderEvent(tenantId, event)
    event
}

internal fun PosRepository.orderPaymentForSale(tenantId: String, saleId: String): OrderPayment? =
    featureQuery("SELECT order_id,amount_minor FROM customer_order_events WHERE tenant_id=? AND sale_id=?",
        tenantId, saleId) { OrderPayment(it.getString(1), it.getLong(2)) }.singleOrNull()

private val orderZone = ZoneId.of("Africa/Lagos")

internal fun validateOrderReference(value: String) {
    require(value.isNotBlank() && value.length <= 150 && value.none { it.isISOControl() }) { "Invalid order reference." }
}

internal fun PosRepository.orderTime(state: CustomerOrderState): Instant = Instant.now().also { now ->
    val lastOrderChange = state.history.lastOrNull()?.createdAt ?: state.order.createdAt
    val lastHoldChange = latestOrderReservation(state.order.tenantId, state.order.id)?.createdAt
    require(listOfNotNull(lastOrderChange, lastHoldChange).all { now > Instant.parse(it) }) {
        "Correct the clock before changing this order."
    }
}

internal fun PosRepository.customerOrderState(tenantId: String, orderId: String): CustomerOrderState =
    orderState(requireCustomerOrder(tenantId, orderId))

private fun marginalOrderValue(line: QuotationLine, prior: Int, quantity: Int): Long =
    Quantity((prior + quantity).toLong(), line.quantityPrecision).lineTotalMinor(line.agreedUnitPriceMinor) -
        Quantity(prior.toLong(), line.quantityPrecision).lineTotalMinor(line.agreedUnitPriceMinor)

private fun PosRepository.requireCustomerOrder(tenantId: String, orderId: String): CustomerOrder =
    requireNotNull(orderHeaders(tenantId, orderId).singleOrNull()) { "Order is unavailable in this shop." }

private fun PosRepository.orderHeaders(tenantId: String, orderId: String? = null): List<CustomerOrder> =
    featureQuery("SELECT * FROM customer_orders WHERE tenant_id=?" + if (orderId == null) "" else " AND id=?",
        *listOfNotNull(tenantId, orderId).toTypedArray()) { row ->
        val id = row.getString("id")
        CustomerOrder(id, tenantId, row.getString("customer_id"), row.getString("customer_name"), row.getString("source"),
            row.getString("collect_on"), row.getString("note"), row.getString("created_at"),
            featureQuery("SELECT * FROM customer_order_lines WHERE tenant_id=? AND order_id=? ORDER BY line_key", tenantId, id) {
                QuotationLine(it.getString("product_id"), it.getString("product_name"), it.getString("base_unit"),
                    it.getInt("base_precision"), it.getString("selling_unit_id"), it.getString("selling_unit"),
                    it.getInt("base_units_per_unit"), it.getInt("quantity_precision"), it.getInt("quantity"),
                    it.getLong("agreed_price_minor")).also { line -> require(line.key == it.getString("line_key")) }
            })
    }.sortedByDescending { Instant.parse(it.createdAt) }

private fun orderEventRow(row: ResultSet) = CustomerOrderEvent(
    row.getString("id"), row.getString("order_id"), row.getString("kind"), row.getLong("amount_minor"),
    row.getString("sale_id"), row.getString("cash_shift_id"), row.getString("reason"), row.getString("created_at"),
)

private fun PosRepository.eventById(tenantId: String, id: String): CustomerOrderEvent? =
    featureQuery("SELECT * FROM customer_order_events WHERE tenant_id=? AND id=?", tenantId, id, map = ::orderEventRow).singleOrNull()

private fun PosRepository.recordOrderEvent(tenantId: String, event: CustomerOrderEvent) {
    featureExecute("INSERT INTO customer_order_events VALUES(?,?,?,?,?,?,?,?,?)", tenantId, event.id, event.orderId,
        event.kind, event.amountMinor, event.saleId, event.cashShiftId, event.reason, event.createdAt)
    featureEvent(tenantId, "order.${event.kind}", event.id)
}

/** Replay order funding and quantities to validate the same state used for live review and recovery. */
private fun PosRepository.orderState(order: CustomerOrder): CustomerOrderState {
    val history = featureQuery("SELECT * FROM customer_order_events WHERE tenant_id=? AND order_id=?", order.tenantId,
        order.id, map = ::orderEventRow).sortedBy { Instant.parse(it.createdAt) }
    val lines = order.lines.associateBy { it.key }
    val remaining = order.lines.associate { it.key to it.quantity }.toMutableMap()
    var unspent = 0L
    var remainingValue = order.totalMinor
    var latest = Instant.parse(order.createdAt)
    var cancelled = false
    for (event in history) {
        val time = Instant.parse(event.createdAt)
        validateOrderReference(event.id)
        require(!cancelled && remaining.values.any { it > 0 } && time > latest &&
            event.reason == event.reason.trim() && event.reason.length in 3..250 && event.reason.none { it.isISOControl() }) {
            "Order activity is out of sequence."
        }
        when (event.kind) {
            "deposit" -> {
                require(event.saleId == null && event.cashShiftId != null && event.amountMinor in 1..(remainingValue - unspent))
                unspent = Math.addExact(unspent, event.amountMinor)
            }
            "cancelled" -> {
                require(event.saleId == null && event.amountMinor == unspent && (event.cashShiftId != null) == (unspent > 0))
                cancelled = true
                unspent = 0
            }
            "collected" -> {
                val sale = customerOrderSale(order.tenantId, requireNotNull(event.saleId))
                require(sale.customerId == order.customerId && sale.orderPayment == OrderPayment(order.id, event.amountMinor) &&
                    sale.createdAt == event.createdAt && (event.cashShiftId != null) == (sale.cashMinor > 0))
                require(event.amountMinor == minOf(unspent, sale.totalMinor))
                for (sold in sale.lines) {
                    val original = lines.getValue(sold.key)
                    val left = remaining.getValue(sold.key)
                    require(sold.quantity in 1..left && sold.product.name == original.productName &&
                        sold.product.unit == original.baseUnit && sold.product.quantityPrecision == original.basePrecision &&
                        sold.sellingUnit == original.sellingUnit && sold.quantityPrecision == original.quantityPrecision &&
                        sold.baseUnitsPerUnit == original.baseUnitsPerUnit && sold.netUnitPriceMinor == original.agreedUnitPriceMinor &&
                        sold.totalMinor == marginalOrderValue(original, original.quantity - left, sold.quantity)) {
                        "A collected sale differs from the saved order."
                    }
                    remaining[sold.key] = left - sold.quantity
                }
                unspent -= event.amountMinor
                remainingValue -= sale.totalMinor
            }
            else -> error("Unknown order activity.")
        }
        require(unspent in 0..remainingValue && (remaining.values.any { it > 0 } || remainingValue == 0L))
        latest = time
    }
    return CustomerOrderState(order, history, remaining, unspent, remainingValue)
}

internal fun PosRepository.validateCustomerOrders(tenantId: String) {
    val orders = orderHeaders(tenantId).map { orderState(it) }
    val expected = buildMap {
        for (state in orders) {
            put("order.created" to state.order.id, Instant.parse(state.order.createdAt))
            state.history.forEach { put("order.${it.kind}" to it.id, Instant.parse(it.createdAt)) }
        }
    }
    val witnessed = featureQuery(
        """SELECT o.kind,o.entity_id,o.created_at,a.id FROM outbox o
            LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
            WHERE o.tenant_id=? AND o.kind IN ('order.created','order.deposit','order.collected','order.cancelled')""", tenantId,
    ) {
        require(it.getString(4) != null) { "Backup order activity is missing its audit record." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(3))
    }
    require(witnessed.size == expected.size && witnessed.all { (key, at) -> expected[key]?.let { at >= it } == true }) {
        "Backup order history is incomplete."
    }
    if (orders.isEmpty()) return
    val shifts = featureQuery("SELECT kind,source_id,shift_id FROM cash_entries WHERE tenant_id=?", tenantId) {
        (it.getString(1) to it.getString(2)) to it.getString(3)
    }.toMap()
    orders.flatMap { it.history }.filter { it.cashShiftId != null }.forEach { event ->
        val key = when (event.kind) {
            "deposit" -> "order_deposit" to event.id
            "cancelled" -> "order_deposit_refund" to event.id
            else -> "sale" to requireNotNull(event.saleId)
        }
        require(shifts[key] == event.cashShiftId) { "Backup order cash is linked to a different drawer." }
    }
}
