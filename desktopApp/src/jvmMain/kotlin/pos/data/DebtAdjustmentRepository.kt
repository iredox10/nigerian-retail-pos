package pos.data

import java.math.BigInteger
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import pos.domain.StaffPermission

data class DebtAdjustmentReview(
    val saleId: String,
    val customerId: String,
    val amountMinor: Long,
    val receiptBeforeMinor: Long,
    val customerBeforeMinor: Long,
    val writeOffId: String? = null,
) {
    val deltaMinor: Long get() = if (writeOffId == null) -amountMinor else amountMinor
    val receiptAfterMinor: Long get() = Math.addExact(receiptBeforeMinor, deltaMinor)
    val customerAfterMinor: Long get() = Math.addExact(customerBeforeMinor, deltaMinor)
}

data class DebtAdjustment(
    val tenantId: String,
    val id: String,
    val review: DebtAdjustmentReview,
    val reason: String,
    val createdAt: String,
) {
    val eventKind: String get() = if (review.writeOffId == null) "customer.debt_written_off" else "customer.debt_reinstated"
    val activity: String get() = if (review.writeOffId == null) "Debt written off" else "Debt reinstated"
}

internal val debtAdjustmentSnapshotTables = listOf("customer_debt_adjustments")

internal fun PosRepository.initializeDebtAdjustments() {
    featureSchema("""CREATE TABLE IF NOT EXISTS customer_debt_adjustments(
        tenant_id TEXT NOT NULL, id TEXT NOT NULL, sale_id TEXT NOT NULL, customer_id TEXT NOT NULL,
        amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0),
        receipt_before_minor INTEGER NOT NULL CHECK(typeof(receipt_before_minor)='integer' AND receipt_before_minor>=0),
        customer_before_minor INTEGER NOT NULL CHECK(typeof(customer_before_minor)='integer' AND customer_before_minor>=0),
        write_off_id TEXT, reason TEXT NOT NULL, created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,write_off_id),
        FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),
        FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))""")
    featureSchema("CREATE INDEX IF NOT EXISTS customer_debt_adjustments_sale ON customer_debt_adjustments(tenant_id,sale_id)")
    featureSchema("CREATE INDEX IF NOT EXISTS customer_debt_adjustments_customer ON customer_debt_adjustments(tenant_id,customer_id)")
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema("""CREATE TRIGGER IF NOT EXISTS customer_debt_adjustments_no_${action.lowercase()}
            BEFORE $action ON customer_debt_adjustments BEGIN SELECT RAISE(ABORT,'Saved debt adjustments cannot be changed'); END""")
    }
}

fun PosRepository.debtAdjustments(tenantId: String, customerId: String? = null): List<DebtAdjustment> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) { debtAdjustmentRows(tenantId, customerId) }

fun PosRepository.canAdjustCustomerDebt(tenantId: String): Boolean =
    runCatching { featureRead(tenantId, StaffPermission.MANAGE_STAFF) { true } }.getOrDefault(false)

fun PosRepository.reviewDebtAdjustment(
    tenantId: String, saleId: String, amountMinor: Long, writeOffId: String? = null,
): DebtAdjustmentReview = featureRead(tenantId, StaffPermission.MANAGE_STAFF) {
    debtAdjustmentReview(tenantId, saleId, amountMinor, writeOffId)
}

fun PosRepository.saveDebtAdjustment(
    tenantId: String, reviewed: DebtAdjustmentReview, reason: String, operationId: String = UUID.randomUUID().toString(),
): DebtAdjustment = featureWrite(tenantId, StaffPermission.MANAGE_STAFF) {
    val saved = featureQuery("SELECT * FROM customer_debt_adjustments WHERE tenant_id=? AND id=?",
        tenantId, operationId, map = ::readDebtAdjustment).singleOrNull()
    if (saved != null) {
        require(saved.review == reviewed && saved.reason == reason.trim()) { "This adjustment already has different saved details." }
        return@featureWrite saved
    }
    val actual = debtAdjustmentReview(tenantId, reviewed.saleId, reviewed.amountMinor, reviewed.writeOffId)
    require(actual == reviewed) { "The amount owed changed. Review the adjustment again." }
    val historyTimes = featureQuery("""SELECT created_at FROM sales WHERE tenant_id=? AND customer_id=? AND credit_minor>0
        UNION ALL SELECT created_at FROM repayments WHERE tenant_id=? AND customer_id=?
        UNION ALL SELECT created_at FROM credit_return_reductions WHERE tenant_id=? AND customer_id=?
        UNION ALL SELECT created_at FROM repayment_allocations WHERE tenant_id=? AND customer_id=?
        UNION ALL SELECT created_at FROM allocation_reversals WHERE tenant_id=? AND customer_id=?
        UNION ALL SELECT created_at FROM customer_debt_adjustments WHERE tenant_id=? AND customer_id=?""",
        *List(6) { listOf(tenantId, actual.customerId) }.flatten().toTypedArray()) { Instant.parse(it.getString(1)) }
    val now = Instant.now()
    require(historyTimes.all { now > it }) { "Correct the clock before saving this debt adjustment." }
    val event = DebtAdjustment(tenantId, operationId, actual, reason.trim(), now.toString())
    validateAdjustmentFields(event)
    check(featureExecute("UPDATE customers SET balance_minor=? WHERE tenant_id=? AND id=? AND balance_minor=?",
        actual.customerAfterMinor, tenantId, actual.customerId, actual.customerBeforeMinor) == 1) {
        "Customer debt changed. Review the adjustment again."
    }
    featureExecute("""INSERT INTO customer_debt_adjustments
        (tenant_id,id,sale_id,customer_id,amount_minor,receipt_before_minor,customer_before_minor,write_off_id,reason,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?)""", tenantId, operationId, actual.saleId, actual.customerId, actual.amountMinor,
        actual.receiptBeforeMinor, actual.customerBeforeMinor, actual.writeOffId, event.reason, event.createdAt)
    featureEvent(tenantId, event.eventKind, event.id)
    event
}

private fun PosRepository.debtAdjustmentReview(
    tenantId: String, saleId: String, amountMinor: Long, writeOffId: String?,
): DebtAdjustmentReview {
    val sale = sales(tenantId).singleOrNull { it.id == saleId && it.creditMinor > 0 }
    requireNotNull(sale) { "Choose a credit receipt that belongs to this shop." }
    val customer = customers(tenantId).single { it.id == sale.customerId }
    require(repayments(tenantId, customer.id).all { unallocatedRepayment(tenantId, it.id) == 0L }) {
        "Assign this customer's recorded repayments to receipts before adjusting the amount owed."
    }
    val receiptBefore = unallocatedCredit(tenantId, saleId)
    require(amountMinor > 0 && receiptBefore in 0..customer.balanceMinor) { "Review the receipt and the customer's current debt." }
    if (writeOffId == null) {
        require(amountMinor <= receiptBefore) { "A write-off cannot exceed the amount still owed on this receipt." }
    } else {
        val prior = debtAdjustmentRows(tenantId, customer.id)
        require(prior.any { it.id == writeOffId && it.review.saleId == saleId && it.review.writeOffId == null &&
            it.review.amountMinor == amountMinor } && prior.none { it.review.writeOffId == writeOffId }) {
            "Choose a saved write-off for this receipt that has not already been restored."
        }
    }
    return DebtAdjustmentReview(saleId, customer.id, amountMinor, receiptBefore, customer.balanceMinor, writeOffId).also {
        require(it.receiptAfterMinor in 0..sale.creditMinor && it.customerAfterMinor >= it.receiptAfterMinor) {
            "The adjustment would leave an invalid amount owed."
        }
    }
}

internal fun PosRepository.checkDebtAdjustmentClock(tenantId: String, customerId: String, now: Instant) {
    require(debtAdjustmentRows(tenantId, customerId).all { now > Instant.parse(it.createdAt) }) {
        "Correct the clock before recording more activity for this customer."
    }
}

internal fun PosRepository.netWrittenOffByReceipt(tenantId: String, saleId: String, through: Instant? = null): Long =
    featureQuery("SELECT * FROM customer_debt_adjustments WHERE tenant_id=? AND sale_id=?",
        tenantId, saleId, map = ::readDebtAdjustment)
        .filter { through == null || Instant.parse(it.createdAt) <= through }.netWrittenOff()

internal fun List<DebtAdjustment>.netWrittenOff(): Long =
    fold(BigInteger.ZERO) { total, event -> total - BigInteger.valueOf(event.review.deltaMinor) }.longValueExact()
        .also { require(it >= 0) { "Restored debt exceeds written-off debt." } }

internal fun PosRepository.debtAdjustmentDelta(tenantId: String, customerId: String): BigInteger =
    debtAdjustmentRows(tenantId, customerId).fold(BigInteger.ZERO) { total, event -> total + BigInteger.valueOf(event.review.deltaMinor) }

private fun PosRepository.debtAdjustmentRows(tenantId: String, customerId: String? = null): List<DebtAdjustment> =
    featureQuery("SELECT * FROM customer_debt_adjustments WHERE tenant_id=?" +
        if (customerId == null) "" else " AND customer_id=?",
        *listOfNotNull(tenantId, customerId).toTypedArray(), map = ::readDebtAdjustment)
        .sortedWith(compareBy({ Instant.parse(it.createdAt) }, { it.id }))

private fun readDebtAdjustment(row: ResultSet) = DebtAdjustment(row.getString("tenant_id"), row.getString("id"),
    DebtAdjustmentReview(row.getString("sale_id"), row.getString("customer_id"), row.getLong("amount_minor"),
        row.getLong("receipt_before_minor"), row.getLong("customer_before_minor"), row.getString("write_off_id")),
    row.getString("reason"), row.getString("created_at"))

private fun validateAdjustmentFields(event: DebtAdjustment) {
    require(event.id.isNotBlank() && event.id.length <= 200 && event.id.none { it.isISOControl() }) { "Invalid debt adjustment reference." }
    require(event.reason == event.reason.trim() && event.reason.length in 3..250 && event.reason.none { it.isISOControl() }) {
        "Enter a reason from 3 to 250 printable characters."
    }
    val review = event.review
    require(review.amountMinor > 0 && review.receiptBeforeMinor in 0..review.customerBeforeMinor &&
        review.receiptAfterMinor in 0..review.customerAfterMinor) { "Debt adjustment has invalid balances." }
}

internal fun PosRepository.validateDebtAdjustments(tenantId: String) {
    val events = debtAdjustmentRows(tenantId)
    val witnesses = featureQuery("""SELECT o.entity_id,o.kind,o.created_at,a.id FROM outbox o
        LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id
        WHERE o.tenant_id=? AND o.kind IN ('customer.debt_written_off','customer.debt_reinstated')""", tenantId) {
        require(it.getString(4) != null) { "Debt adjustment audit history is missing." }
        it.getString(1) to (it.getString(2) to Instant.parse(it.getString(3)))
    }
    require(events.size == witnesses.size && events.map { it.id }.toSet() == witnesses.map { it.first }.toSet()) {
        "Saved debt adjustment history is missing or repeated."
    }
    if (events.isEmpty()) return
    val witnessedById = witnesses.toMap()
    val sales = sales(tenantId).associateBy { it.id }
    val repayments = repayments(tenantId).groupBy { it.customerId }
    val assignments = repaymentAllocations(tenantId, includeReversed = true).groupBy { it.customerId }
    val reversals = allocationReversals(tenantId).associateBy { it.allocationId }
    val returns = featureQuery("SELECT customer_id,sale_id,created_at,amount_minor FROM credit_return_reductions WHERE tenant_id=?",
        tenantId) { DebtReturnSource(it.getString(1), it.getString(2), Instant.parse(it.getString(3)), it.getLong(4)) }
    validateDebtAdjustmentLinks(events)
    events.groupBy { it.review.customerId }.forEach { (customerId, history) ->
        val prior = mutableListOf<DebtAdjustment>()
        val customerSales = sales.values.filter { it.customerId == customerId && it.creditMinor > 0 }
        val received = repayments[customerId].orEmpty()
        val receivedById = received.associateBy { it.id }
        val assigned = assignments[customerId].orEmpty()
        val returned = returns.filter { it.customerId == customerId }
        history.forEach { event ->
            validateAdjustmentFields(event)
            val time = Instant.parse(event.createdAt)
            val witness = witnessedById.getValue(event.id)
            require(witness.first == event.eventKind && witness.second >= time &&
                (prior.lastOrNull()?.let { time > Instant.parse(it.createdAt) } ?: true)) { "Debt adjustment timestamps are inconsistent." }
            val review = event.review
            val sale = sales[review.saleId]
            require(sale != null && sale.customerId == customerId && sale.creditMinor > 0 && Instant.parse(sale.createdAt) < time) {
                "Debt adjustment receipt is unavailable."
            }
            val previousPayments = received.filter { Instant.parse(it.createdAt) < time }
            val previousAssignments = assigned.filter {
                Instant.parse(it.createdAt) < time && (reversals[it.id]?.let { reversal -> Instant.parse(reversal.createdAt) > time } ?: true)
            }
            require(previousPayments.all { payment ->
                previousAssignments.filter { it.repaymentId == payment.id }.fold(0L) { sum, row -> Math.addExact(sum,row.amountMinor) } == payment.amountMinor
            } && previousAssignments.all { Instant.parse(receivedById.getValue(it.repaymentId).createdAt) < time }) {
                "A debt adjustment was saved before repayments were fully assigned."
            }
            val previousReturns = returned.filter { it.time < time }
            val beforeCustomer = customerSales.filter { Instant.parse(it.createdAt) < time }.sumExact { it.creditMinor } -
                previousPayments.sumExact { it.amountMinor } - previousReturns.sumExact { it.amountMinor } +
                prior.sumExact { it.review.deltaMinor }
            val beforeReceipt = BigInteger.valueOf(sale.creditMinor) -
                previousAssignments.filter { it.saleId == sale.id }.sumExact { it.amountMinor } -
                previousReturns.filter { it.saleId == sale.id }.sumExact { it.amountMinor } +
                prior.filter { it.review.saleId == sale.id }.sumExact { it.review.deltaMinor }
            require(beforeCustomer == BigInteger.valueOf(review.customerBeforeMinor) &&
                beforeReceipt == BigInteger.valueOf(review.receiptBeforeMinor) && review.receiptAfterMinor <= sale.creditMinor) {
                "A debt adjustment does not match the recorded receipt and customer balances."
            }
            prior += event
        }
    }
}

internal fun validateDebtAdjustmentLinks(events: List<DebtAdjustment>) {
    require(events.map { it.id }.distinct().size == events.size) { "Debt adjustments contain repeated records." }
    val byId = events.associateBy { it.id }
    val restored = mutableSetOf<String>()
    for (event in events) {
        val writeOffId = event.review.writeOffId ?: continue
        val previous = byId[writeOffId]
        require(previous != null && previous.tenantId == event.tenantId && previous.review.writeOffId == null &&
            previous.review.customerId == event.review.customerId && previous.review.saleId == event.review.saleId &&
            previous.review.amountMinor == event.review.amountMinor &&
            Instant.parse(previous.createdAt) < Instant.parse(event.createdAt) && restored.add(writeOffId)) {
            "Restored debt must match one earlier write-off."
        }
    }
}

private data class DebtReturnSource(val customerId: String, val saleId: String, val time: Instant, val amountMinor: Long)
private fun <T> List<T>.sumExact(amount: (T) -> Long): BigInteger =
    fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(amount(value)) }
