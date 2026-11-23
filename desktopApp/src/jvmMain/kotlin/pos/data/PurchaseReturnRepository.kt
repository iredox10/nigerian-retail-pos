package pos.data

import pos.domain.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal val purchaseReturnSnapshotTables = listOf("purchase_returns", "purchase_cash_returns")
private const val purchaseReturnEvent = "supplier.purchase_returned"

internal fun PosRepository.initializePurchaseReturns() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS purchase_returns(
            id TEXT NOT NULL,tenant_id TEXT NOT NULL,receipt_id TEXT NOT NULL,
            quantity INTEGER NOT NULL CHECK(typeof(quantity)='integer' AND quantity BETWEEN 1 AND 2147483647),
            credit_minor INTEGER NOT NULL CHECK(typeof(credit_minor)='integer' AND credit_minor>0),
            owed_before_minor INTEGER NOT NULL CHECK(typeof(owed_before_minor)='integer' AND owed_before_minor>=credit_minor),
            stock_before INTEGER NOT NULL CHECK(typeof(stock_before)='integer' AND stock_before<=9007199254740991),
            stock_after INTEGER NOT NULL CHECK(typeof(stock_after)='integer' AND stock_before>stock_after AND stock_after>=0),
            reason TEXT NOT NULL,created_at TEXT NOT NULL,review_token TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id)
        )""".trimIndent()
    )
    featureSchema(
        """CREATE TABLE IF NOT EXISTS purchase_cash_returns(
            id TEXT NOT NULL,tenant_id TEXT NOT NULL,receipt_id TEXT NOT NULL,
            quantity INTEGER NOT NULL CHECK(typeof(quantity)='integer' AND quantity BETWEEN 1 AND 2147483647),
            credit_minor INTEGER NOT NULL CHECK(typeof(credit_minor)='integer' AND credit_minor>=0),
            owed_before_minor INTEGER NOT NULL CHECK(typeof(owed_before_minor)='integer' AND owed_before_minor>=credit_minor),
            stock_before INTEGER NOT NULL CHECK(typeof(stock_before)='integer' AND stock_before<=9007199254740991),
            stock_after INTEGER NOT NULL CHECK(typeof(stock_after)='integer' AND stock_before>stock_after AND stock_after>=0),
            reason TEXT NOT NULL,created_at TEXT NOT NULL,review_token TEXT NOT NULL,
            cash_received_minor INTEGER NOT NULL CHECK(typeof(cash_received_minor)='integer' AND cash_received_minor>0),
            cash_shift_id TEXT NOT NULL,
            PRIMARY KEY(tenant_id,id),
            FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id),
            FOREIGN KEY(tenant_id,cash_shift_id) REFERENCES cash_shifts(tenant_id,id)
        )"""
    )
    for (table in purchaseReturnSnapshotTables) {
        featureSchema("CREATE INDEX IF NOT EXISTS ${table}_receipt ON $table(tenant_id,receipt_id)")
        for (action in listOf("UPDATE", "DELETE")) {
            featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()}
                BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Saved purchase returns cannot be changed'); END""")
        }
    }
    featureSchema("""CREATE VIEW IF NOT EXISTS all_purchase_returns AS
        SELECT r.*, 0 AS cash_received_minor, NULL AS cash_shift_id FROM purchase_returns r
        UNION ALL SELECT * FROM purchase_cash_returns""")
}

data class PurchaseReturnReview(
    val receiptId: String,
    val quantity: Int,
    val creditMinor: Long,
    val owedBeforeMinor: Long,
    val stockBefore: Long,
    val stockAfter: Long,
    val token: String,
    val cashReceivedMinor: Long = 0,
    val cashShiftId: String? = null,
)

fun PosRepository.purchaseReturns(tenantId: String): List<PurchaseReturn> =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) { purchaseReturnRows(tenantId) }

fun PosRepository.reviewPurchaseReturn(
    tenantId: String, receiptId: String, quantity: Int, receiveCash: Boolean = false,
): PurchaseReturnReview =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
        featureRead(tenantId, StaffPermission.MANAGE_STOCK) { purchaseReturnCandidate(tenantId, receiptId, quantity, receiveCash).review }
    }

fun PosRepository.savePurchaseReturn(
    tenantId: String,
    expected: PurchaseReturnReview,
    reason: String,
    operationId: String = UUID.randomUUID().toString(),
): PurchaseReturn = featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    featureRead(tenantId, StaffPermission.MANAGE_STOCK) { }
    require(validPurchaseReturnId(operationId)) { "The purchase return reference is invalid." }
    val cleanReason = reason.trim()
    require(cleanReason.length in 3..500 && cleanReason.none { it.isISOControl() }) {
        "Enter a reason from 3 to 500 printable characters."
    }
    if (expected.cashReceivedMinor > 0) featureRead(tenantId, StaffPermission.MANAGE_MONEY) { }
    val saved = featureQuery("SELECT * FROM all_purchase_returns WHERE tenant_id=? AND id=?", tenantId, operationId) {
        readPurchaseReturn(it) to it.getString("review_token")
    }.singleOrNull()
    if (saved != null) {
        val (event, token) = saved
        require(event.asReview(token) == expected && event.reason == cleanReason) {
            "This purchase return request was already saved with different details."
        }
        return@featureWrite event
    }

    val candidate = purchaseReturnCandidate(tenantId, expected.receiptId, expected.quantity, expected.cashReceivedMinor > 0)
    require(candidate.review == expected) { "Stock or the amount owed changed. Review the return again." }
    val now = Instant.now()
    require(now > candidate.latest) { "Correct the computer clock before saving this return." }
    val item = candidate.item
    val event = PurchaseReturn(
        operationId, tenantId, expected.receiptId, expected.quantity, expected.creditMinor,
        expected.owedBeforeMinor, expected.stockBefore, expected.stockAfter, cleanReason, now.toString(),
        expected.cashReceivedMinor, expected.cashShiftId,
    )
    check(featureExecute(
        """UPDATE products SET stock_scaled=?,stock=? WHERE tenant_id=? AND id=? AND archived=0
            AND quantity_precision=? AND (stock_scaled=? OR (stock_scaled IS NULL AND stock=?))""",
        event.stockAfter, projectedQuantity(event.stockAfter, item.quantityPrecision), tenantId, item.id,
        item.quantityPrecision, item.exactStockScaled, item.stock,
    ) == 1) { "Stock changed. Review the return again." }
    val values = arrayOf<Any>(event.id, tenantId, event.receiptId, event.quantity, event.creditMinor,
        event.owedBeforeMinor, event.stockBefore, event.stockAfter, event.reason, event.createdAt, expected.token)
    if (event.cashReceivedMinor > 0) {
        featureExecute(
            """INSERT INTO purchase_cash_returns(
                id,tenant_id,receipt_id,quantity,credit_minor,owed_before_minor,stock_before,stock_after,reason,
                created_at,review_token,cash_received_minor,cash_shift_id
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            *values, event.cashReceivedMinor, event.cashShiftId,
        )
        recordSupplierCashReimbursement(tenantId, event)
    } else {
        featureExecute(
            """INSERT INTO purchase_returns(
                id,tenant_id,receipt_id,quantity,credit_minor,owed_before_minor,stock_before,stock_after,reason,created_at,review_token
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""", *values,
        )
    }
    featureEvent(tenantId, purchaseReturnEvent, event.id)
    event
}

internal fun PosRepository.purchaseReturnCredits(tenantId: String, receiptId: String): Long =
    purchaseReturnRows(tenantId, receiptId).fold(0L) { total, event -> Math.addExact(total, event.creditMinor) }

internal fun PosRepository.purchaseReturnLatestTime(tenantId: String, receiptId: String): Instant? =
    purchaseReturnRows(tenantId, receiptId).maxOfOrNull { Instant.parse(it.createdAt) }

internal fun PosRepository.purchaseReturnMovements(tenantId: String, productId: String): List<InventoryMovement> =
    featureQuery(
        """SELECT r.*,s.unit,s.base_precision,s.quantity_precision,s.base_units_per_unit FROM all_purchase_returns r
            JOIN stock_receipts s ON s.tenant_id=r.tenant_id AND s.id=r.receipt_id
            WHERE r.tenant_id=? AND s.product_id=?""",
        tenantId, productId,
    ) {
        val event = readPurchaseReturn(it)
        val precision = it.getInt("quantity_precision")
        val base = it.getInt("base_precision")
        val buyingQuantity = Quantity(event.quantity.toLong(), precision)
        val quantity = buyingQuantity.rescale(base).timesWhole(it.getInt("base_units_per_unit").toLong())
        InventoryMovement(
            "purchase-return:${event.id}", tenantId, productId, "purchase_return",
            -projectedQuantity(quantity.scaledUnits, base), event.id, event.createdAt,
            "Returned ${quantityLabel(buyingQuantity.scaledUnits, precision)} ${it.getString("unit")} from receipt ${event.receiptId}: ${event.reason}",
        )
    }

internal fun PosRepository.validatePurchaseReturns(tenantId: String) {
    val records = purchaseReturnRows(tenantId)
    val outbox = featureQuery(
        """SELECT o.entity_id,o.created_at,a.id AS audit_id FROM outbox o
            LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
            WHERE o.tenant_id=? AND o.kind=?""",
        tenantId, purchaseReturnEvent,
    ) {
        require(it.getString("audit_id") != null) { "Backup purchase return is missing its activity record." }
        it.getString("entity_id") to Instant.parse(it.getString("created_at"))
    }
    require(outbox.size == records.size && outbox.map { it.first }.toSet() == records.map { it.id }.toSet()) {
        "Backup purchase returns have incomplete activity."
    }
    require(featureQuery(
        "SELECT review_token FROM all_purchase_returns WHERE tenant_id=?", tenantId,
    ) { it.getString(1) }.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Backup purchase return review is invalid." }
    val cashShiftByReturn = featureQuery(
        """SELECT source_id,shift_id,created_at FROM cash_entries
            WHERE tenant_id=? AND kind='supplier_reimbursement'""", tenantId,
    ) { it.getString(1) to (it.getString(2) to Instant.parse(it.getString(3))) }.toMap()
    records.filter { it.cashReceivedMinor > 0 }.forEach { event ->
        val posting = cashShiftByReturn[event.id]
        require(posting != null && posting.first == event.cashShiftId && posting.second >= Instant.parse(event.createdAt)) {
            "Backup supplier reimbursement does not match its recorded drawer."
        }
    }
    val witnessedAt = outbox.toMap()
    val receipts = stockReceipts(tenantId).associateBy { it.id }
    val payments = supplierPayments(tenantId).groupBy { it.receiptId }
    val products = products(tenantId, includeArchived = true).associateBy { it.id }
    records.groupBy { it.receiptId }.forEach { (id, returned) ->
        val receipt = requireNotNull(receipts[id]) { "Backup purchase return has no stock receipt." }
        val item = requireNotNull(products[receipt.productId])
        require(receipt.baseUnit == item.unit && receipt.basePrecision == item.quantityPrecision) {
            "Backup purchase return unit differs from its stock item."
        }
        val paid = payments[id].orEmpty().map { Instant.parse(it.createdAt) to it.amountMinor }.sortedBy { it.first }
        var nextPayment = 0
        var owed = receipt.unpaidMinor
        var returnedQuantity = 0
        var latest = Instant.parse(receipt.createdAt)
        returned.sortedBy { Instant.parse(it.createdAt) }.forEach { event ->
            val time = Instant.parse(event.createdAt)
            require(time > latest && witnessedAt.getValue(event.id) >= time) { "Backup purchase return dates are inconsistent." }
            while (nextPayment < paid.size && paid[nextPayment].first < time) {
                owed = Math.subtractExact(owed, paid[nextPayment].second)
                nextPayment++
            }
            val value = receipt.purchaseReturnCredit(returnedQuantity, event.quantity)
            require(paid.getOrNull(nextPayment)?.first != time && owed == event.owedBeforeMinor &&
                event.creditMinor == minOf(value, owed) && event.returnedValueMinor == value) {
                "Backup purchase return credit differs from the receipt or the recorded amount owed."
            }
            require(event.stockBefore - event.stockAfter == receipt.purchaseReturnBaseQuantity(event.quantity) &&
                exactReturnProjection(event.stockBefore, receipt.basePrecision) &&
                exactReturnProjection(event.stockAfter, receipt.basePrecision)) {
                "Backup purchase return does not reconcile with the quantity removed from stock."
            }
            owed = Math.subtractExact(owed, event.creditMinor)
            returnedQuantity = Math.addExact(returnedQuantity, event.quantity)
            latest = time
        }
        require(paid.drop(nextPayment).fold(owed) { balance, payment -> Math.subtractExact(balance, payment.second) } >= 0) {
            "Backup supplier payments and returns exceed the receipt balance."
        }
    }
}

private data class PurchaseReturnCandidate(val review: PurchaseReturnReview, val item: Product, val latest: Instant)

private fun PosRepository.purchaseReturnCandidate(
    tenantId: String, receiptId: String, quantity: Int, receiveCash: Boolean,
): PurchaseReturnCandidate {
    val receipt = requireNotNull(stockReceipts(tenantId).singleOrNull { it.id == receiptId }) { "Receipt not found in this shop." }
    val item = requireNotNull(products(tenantId, includeArchived = true).singleOrNull { it.id == receipt.productId })
    require(!item.archived) { "Restore this item in Stock before returning it." }
    require(item.unit == receipt.baseUnit && item.quantityPrecision == receipt.basePrecision) {
        "This receipt uses a different stock unit or precision."
    }
    val stock = item.exactStockScaled
    require(exactReturnProjection(stock, item.quantityPrecision) && projectedQuantity(stock, item.quantityPrecision) == item.stock) {
        "Reconcile this item's stock quantity with a stock count before returning it."
    }
    val prior = purchaseReturnRows(tenantId, receiptId)
    val returnedQuantity = prior.fold(0) { total, event -> Math.addExact(total, event.quantity) }
    val value = receipt.purchaseReturnCredit(returnedQuantity, quantity)
    val owed = supplierOutstanding(tenantId, receiptId)
    require(value > 0) { "This quantity has no value at the buying price on the receipt." }
    val credit = minOf(value, owed)
    val cash = value - credit
    val shift = if (cash == 0L) null else {
        require(receiveCash) { "This return exceeds the amount owed. Choose a smaller quantity or record cash received from the supplier." }
        featureRead(tenantId, StaffPermission.MANAGE_MONEY) {
            requireNotNull(activeCashShift(tenantId)) { "Open today's cash drawer before receiving the reimbursement." }
        }
    }
    val removed = receipt.purchaseReturnBaseQuantity(quantity)
    require(removed <= stock) { "There is not enough of this item in stock to return that quantity." }
    requireUnreservedStock(tenantId, item, removed)
    val after = stock - removed
    require(exactReturnProjection(after, item.quantityPrecision)) { "The remaining stock exceeds the supported exact range." }
    val movements = inventoryMovements(tenantId, item.id)
    val payments = supplierPayments(tenantId, receiptId)
    val latest = (listOf(receipt.createdAt) + movements.map { it.createdAt } + payments.map { it.createdAt }).maxOf(Instant::parse)
    val fields = listOf(tenantId, receipt.id, item.name, stock.toString(), after.toString(), owed.toString(), credit.toString(), quantity.toString(), cash.toString(), shift?.id.orEmpty()) +
        movements.flatMap { listOf(it.id, it.createdAt, it.quantityDelta.toString()) } +
        payments.sortedBy { it.id }.flatMap { listOf(it.id, it.createdAt, it.amountMinor.toString()) }
    val token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
        fields.joinToString("") { "${it.length}:$it" }.toByteArray(),
    ))
    return PurchaseReturnCandidate(PurchaseReturnReview(receipt.id, quantity, credit, owed, stock, after, token, cash, shift?.id), item, latest)
}

private fun PosRepository.purchaseReturnRows(tenantId: String, receiptId: String? = null): List<PurchaseReturn> =
    if (receiptId == null) featureQuery("SELECT * FROM all_purchase_returns WHERE tenant_id=?", tenantId, map = ::readPurchaseReturn)
        .sortedByDescending { Instant.parse(it.createdAt) }
    else featureQuery("SELECT * FROM all_purchase_returns WHERE tenant_id=? AND receipt_id=?", tenantId, receiptId, map = ::readPurchaseReturn)
        .sortedByDescending { Instant.parse(it.createdAt) }

private fun readPurchaseReturn(row: ResultSet) = PurchaseReturn(
    row.getString("id"), row.getString("tenant_id"), row.getString("receipt_id"), row.getInt("quantity"),
    row.getLong("credit_minor"), row.getLong("owed_before_minor"), row.getLong("stock_before"), row.getLong("stock_after"),
    row.getString("reason"), row.getString("created_at"), row.getLong("cash_received_minor"), row.getString("cash_shift_id"),
)

private fun PurchaseReturn.asReview(token: String) =
    PurchaseReturnReview(receiptId, quantity, creditMinor, owedBeforeMinor, stockBefore, stockAfter, token, cashReceivedMinor, cashShiftId)

private fun exactReturnProjection(scaled: Long, precision: Int): Boolean =
    scaled in 0..9_007_199_254_740_991L &&
        BigDecimal.valueOf(projectedQuantity(scaled, precision)).movePointRight(precision).compareTo(BigDecimal.valueOf(scaled)) == 0
