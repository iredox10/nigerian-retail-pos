package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import pos.domain.StaffPermission

/** Customer-owned cash held by the shop. Applying it funds an explicit existing credit receipt. */
data class CustomerAdvanceEvent(
    val id: String, val customerId: String, val kind: String, val amountMinor: Long,
    val beforeMinor: Long, val afterMinor: Long, val version: Int, val reason: String,
    val createdAt: String, val cashShiftId: String? = null, val saleId: String? = null,
    val repaymentId: String? = null, val allocationId: String? = null,
) {
    val deltaMinor: Long get() = if (kind == "received") amountMinor else -amountMinor
}

internal val customerAdvanceSnapshotTables = listOf("customer_advance_events")

internal fun PosRepository.initializeCustomerAdvances() {
    featureSchema("""CREATE TABLE IF NOT EXISTS customer_advance_events(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,customer_id TEXT NOT NULL,
        kind TEXT NOT NULL CHECK(kind IN('received','refunded','applied')),
        amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0),
        before_minor INTEGER NOT NULL CHECK(typeof(before_minor)='integer' AND before_minor>=0),
        after_minor INTEGER NOT NULL CHECK(typeof(after_minor)='integer' AND after_minor>=0),
        version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,
        cash_shift_id TEXT,sale_id TEXT,repayment_id TEXT,allocation_id TEXT,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,customer_id,version),UNIQUE(tenant_id,repayment_id),UNIQUE(tenant_id,allocation_id),
        CHECK((kind='applied' AND cash_shift_id IS NULL AND sale_id IS NOT NULL AND repayment_id IS NOT NULL AND allocation_id IS NOT NULL)
            OR(kind<>'applied' AND cash_shift_id IS NOT NULL AND sale_id IS NULL AND repayment_id IS NULL AND allocation_id IS NULL)),
        FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id),
        FOREIGN KEY(tenant_id,cash_shift_id) REFERENCES cash_shifts(tenant_id,id),
        FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),
        FOREIGN KEY(tenant_id,repayment_id) REFERENCES repayments(tenant_id,id),
        FOREIGN KEY(tenant_id,allocation_id) REFERENCES repayment_allocations(tenant_id,id))""")
    for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS customer_advances_no_${action.lowercase()}
        BEFORE $action ON customer_advance_events BEGIN SELECT RAISE(ABORT,'Saved advance history cannot be changed'); END""")
}

fun PosRepository.customerAdvances(tenantId: String, customerId: String? = null): List<CustomerAdvanceEvent> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        if (customerId != null) requireAdvanceCustomer(tenantId, customerId)
        advanceRows(tenantId, customerId)
    }

fun PosRepository.customerAdvanceBalance(tenantId: String, customerId: String): Long =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        requireAdvanceCustomer(tenantId, customerId)
        advanceRows(tenantId, customerId).lastOrNull()?.afterMinor ?: 0L
    }

fun PosRepository.receiveCustomerAdvance(
    tenantId: String, customerId: String, amountMinor: Long, expectedBalanceMinor: Long, expectedVersion: Int,
    reason: String, operationId: String = UUID.randomUUID().toString(),
): CustomerAdvanceEvent = changeCustomerAdvance(tenantId, customerId, "received", amountMinor, expectedBalanceMinor,
    expectedVersion, reason, operationId)

fun PosRepository.refundCustomerAdvance(
    tenantId: String, customerId: String, amountMinor: Long, expectedBalanceMinor: Long, expectedVersion: Int,
    reason: String, operationId: String = UUID.randomUUID().toString(),
): CustomerAdvanceEvent = changeCustomerAdvance(tenantId, customerId, "refunded", amountMinor, expectedBalanceMinor,
    expectedVersion, reason, operationId)

private fun PosRepository.changeCustomerAdvance(
    tenantId: String, customerId: String, kind: String, amountMinor: Long, expectedBalanceMinor: Long,
    expectedVersion: Int, reason: String, operationId: String,
): CustomerAdvanceEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    featureRead(tenantId, StaffPermission.MANAGE_MONEY) { }
    val cleanReason = reason.trim()
    advanceById(tenantId, operationId)?.let { saved ->
        require(saved.customerId == customerId && saved.kind == kind && saved.amountMinor == amountMinor &&
            saved.beforeMinor == expectedBalanceMinor && saved.version == expectedVersion + 1 && saved.reason == cleanReason) {
            "This advance request was saved with different details."
        }
        return@featureWrite saved
    }
    requireAdvanceCustomer(tenantId, customerId)
    val history = advanceRows(tenantId, customerId)
    val before = history.lastOrNull()?.afterMinor ?: 0L
    require(before == expectedBalanceMinor && history.size == expectedVersion) { "The customer advance changed. Refresh and review the balance." }
    require(amountMinor > 0L) { "Enter an amount greater than zero." }
    val after = if (kind == "received") Math.addExact(before, amountMinor) else Math.subtractExact(before, amountMinor)
    require(after >= 0L) { "This refund exceeds the customer's available advance." }
    val shift = requireNotNull(activeCashShift(tenantId)) { "Open today's drawer before receiving or refunding an advance." }
    val cash = expectedCash(tenantId, shift.id)
    if (kind == "received") Math.addExact(cash, amountMinor) else require(cash >= amountMinor) { "This refund exceeds the cash expected in the drawer." }
    checkAdvanceClock(history, Instant.now())
    recordCustomerAdvanceCash(tenantId, operationId, customerId, amountMinor, kind == "refunded")
    val event = CustomerAdvanceEvent(operationId, customerId, kind, amountMinor, before, after,
        Math.incrementExact(expectedVersion), cleanReason, Instant.now().toString(), cashShiftId = shift.id)
    saveAdvanceEvent(tenantId, event)
    event
}

fun PosRepository.applyCustomerAdvance(
    tenantId: String, customerId: String, saleId: String, amountMinor: Long,
    expectedBalanceMinor: Long, expectedVersion: Int, expectedReceiptMinor: Long, expectedCustomerDebtMinor: Long,
    reason: String, operationId: String = UUID.randomUUID().toString(),
): CustomerAdvanceEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    val cleanReason = reason.trim()
    advanceById(tenantId, operationId)?.let { saved ->
        require(saved.customerId == customerId && saved.kind == "applied" && saved.saleId == saleId && saved.amountMinor == amountMinor &&
            saved.beforeMinor == expectedBalanceMinor && saved.version == expectedVersion + 1 && saved.reason == cleanReason) {
            "This advance application was saved with different details."
        }
        return@featureWrite saved
    }
    val customer = requireAdvanceCustomer(tenantId, customerId)
    val history = advanceRows(tenantId, customerId)
    val before = history.lastOrNull()?.afterMinor ?: 0L
    require(before == expectedBalanceMinor && history.size == expectedVersion) { "The customer advance changed. Refresh and review the balance." }
    val sale = requireNotNull(sales(tenantId).singleOrNull { it.id == saleId && it.customerId == customerId && it.creditMinor > 0 }) {
        "Choose this customer's saved credit receipt."
    }
    require(repayments(tenantId, customerId).all { unallocatedRepayment(tenantId, it.id) == 0L }) {
        "Assign existing repayments to receipts before applying an advance."
    }
    val receiptOwed = unallocatedCredit(tenantId, sale.id)
    require(customer.balanceMinor == expectedCustomerDebtMinor && receiptOwed == expectedReceiptMinor) {
        "The receipt balance changed. Review the application again."
    }
    require(amountMinor > 0L && amountMinor <= minOf(before, receiptOwed, customer.balanceMinor)) {
        "Choose an amount within the available advance and the amount owed on this receipt."
    }
    checkAdvanceClock(history, Instant.now())
    val repaymentOperation = UUID.nameUUIDFromBytes("customer-advance-payment:$operationId".toByteArray()).toString()
    val repayment = recordRepaymentFromAdvance(tenantId, customerId, amountMinor, repaymentOperation)
    val allocationId = UUID.nameUUIDFromBytes("customer-advance-allocation:$operationId".toByteArray()).toString()
    allocateRepayment(tenantId, repayment.id, saleId, amountMinor, allocationId)
    val event = CustomerAdvanceEvent(operationId, customerId, "applied", amountMinor, before, before - amountMinor,
        Math.incrementExact(expectedVersion), cleanReason, Instant.now().toString(), saleId = saleId,
        repaymentId = repayment.id, allocationId = allocationId)
    saveAdvanceEvent(tenantId, event)
    event
}

/** Restore cash-source reconciliation excludes these repayments because the cash was received earlier. */
internal fun PosRepository.advanceFundedRepaymentIds(tenantId: String): Set<String> =
    advanceRows(tenantId).mapNotNull { it.repaymentId }.toSet()

internal fun PosRepository.customerAdvanceCashSources(tenantId: String): Map<Pair<String, String>, Long> =
    advanceRows(tenantId).filter { it.kind != "applied" }.associate {
        (if (it.kind == "received") "customer_advance" else "customer_advance_refund") to it.id to it.deltaMinor
    }

private fun PosRepository.requireAdvanceCustomer(tenantId: String, customerId: String) =
    requireNotNull(customers(tenantId).singleOrNull { it.id == customerId }) { "This customer is unavailable in this shop." }

private fun checkAdvanceClock(history: List<CustomerAdvanceEvent>, now: Instant) {
    require(history.all { now > Instant.parse(it.createdAt) }) { "Correct the clock before changing customer advances." }
}

private fun validateAdvanceFields(event: CustomerAdvanceEvent) {
    listOfNotNull(event.id, event.customerId, event.saleId, event.repaymentId, event.allocationId, event.cashShiftId).forEach {
        require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl)) { "Advance reference is invalid." }
    }
    require(event.reason == event.reason.trim() && event.reason.length in 3..500 && event.reason.none(Char::isISOControl)) {
        "Enter a reason from 3 to 500 printable characters."
    }
    require(event.version > 0 && event.amountMinor > 0L && event.beforeMinor >= 0L && event.afterMinor >= 0L &&
        Math.addExact(event.beforeMinor,event.deltaMinor) == event.afterMinor) { "Customer advance amounts do not reconcile." }
    require(event.kind in setOf("received", "refunded", "applied")) { "Unsupported customer advance action." }
    require(if (event.kind == "applied") event.cashShiftId == null && event.saleId != null && event.repaymentId != null && event.allocationId != null
        else event.cashShiftId != null && event.saleId == null && event.repaymentId == null && event.allocationId == null) { "Advance source links are incomplete." }
    Instant.parse(event.createdAt)
}

private fun PosRepository.saveAdvanceEvent(tenantId: String, event: CustomerAdvanceEvent) {
    validateAdvanceFields(event)
    featureExecute("""INSERT INTO customer_advance_events(tenant_id,id,customer_id,kind,amount_minor,before_minor,after_minor,
        version,reason,created_at,cash_shift_id,sale_id,repayment_id,allocation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        tenantId,event.id,event.customerId,event.kind,event.amountMinor,event.beforeMinor,event.afterMinor,event.version,
        event.reason,event.createdAt,event.cashShiftId,event.saleId,event.repaymentId,event.allocationId)
    featureEvent(tenantId,"customer.advance_${event.kind}",event.id)
}

private fun PosRepository.advanceRows(tenantId: String, customerId: String? = null): List<CustomerAdvanceEvent> =
    featureQuery("SELECT * FROM customer_advance_events WHERE tenant_id=?" + (if(customerId == null) "" else " AND customer_id=?") +
        " ORDER BY customer_id,version", *listOfNotNull(tenantId,customerId).toTypedArray(), map=::advanceRow)

private fun PosRepository.advanceById(tenantId: String, id: String): CustomerAdvanceEvent? =
    featureQuery("SELECT * FROM customer_advance_events WHERE tenant_id=? AND id=?",tenantId,id,map=::advanceRow).singleOrNull()

private fun advanceRow(row: ResultSet) = CustomerAdvanceEvent(row.getString("id"),row.getString("customer_id"),row.getString("kind"),
    row.getLong("amount_minor"),row.getLong("before_minor"),row.getLong("after_minor"),row.getInt("version"),row.getString("reason"),
    row.getString("created_at"),row.getString("cash_shift_id"),row.getString("sale_id"),row.getString("repayment_id"),row.getString("allocation_id"))

internal fun PosRepository.validateCustomerAdvanceSnapshot(tenantId: String) {
    val events = advanceRows(tenantId)
    events.forEach(::validateAdvanceFields)
    events.groupBy { it.customerId }.values.forEach { history ->
        history.forEachIndexed { index,event ->
            require(event.version == index+1 && event.beforeMinor == (history.getOrNull(index-1)?.afterMinor ?: 0L) &&
                (index == 0 || Instant.parse(event.createdAt) > Instant.parse(history[index-1].createdAt))) {
                "Backup customer advance history is incomplete or out of order."
            }
        }
    }
    val repayments = repayments(tenantId).associateBy { it.id }
    val allocations = repaymentAllocations(tenantId,includeReversed=true).associateBy { it.id }
    val sales = sales(tenantId).associateBy { it.id }
    val cash = featureQuery("SELECT source_id,kind,amount_minor,shift_id,created_at FROM cash_entries WHERE tenant_id=?",tenantId) {
        AdvanceCashWitness(it.getString(1),it.getString(2),it.getLong(3),it.getString(4),it.getString(5))
    }
    events.forEach { event ->
        if (event.kind == "applied") {
            val payment = requireNotNull(repayments[event.repaymentId]) { "Backup advance is missing its funded repayment." }
            val allocation = requireNotNull(allocations[event.allocationId]) { "Backup advance is missing its original receipt assignment." }
            val sale = requireNotNull(sales[event.saleId]) { "Backup advance receipt is unavailable." }
            require(payment.customerId == event.customerId && payment.amountMinor == event.amountMinor &&
                sale.customerId == event.customerId && sale.creditMinor > 0 && allocation.repaymentId == payment.id &&
                allocation.saleId == sale.id && allocation.customerId == event.customerId && allocation.amountMinor == event.amountMinor &&
                Instant.parse(payment.createdAt) <= Instant.parse(allocation.createdAt) && Instant.parse(allocation.createdAt) <= Instant.parse(event.createdAt)) {
                "Backup advance differs from its funded receipt payment."
            }
            require(cash.none { it.kind == "repayment" && it.sourceId == payment.id }) { "Backup advance-funded repayment duplicates drawer cash." }
        } else {
            val expectedKind = if(event.kind == "received") "customer_advance" else "customer_advance_refund"
            val source = cash.singleOrNull { it.kind == expectedKind && it.sourceId == event.id }
            require(source != null && source.amountMinor == event.deltaMinor && source.shiftId == event.cashShiftId &&
                Instant.parse(source.createdAt) <= Instant.parse(event.createdAt)) { "Backup customer advance cash source is incomplete." }
        }
    }
    val expected = events.associate { ("customer.advance_${it.kind}" to it.id) to Instant.parse(it.createdAt) }
    val actual = featureQuery("""SELECT o.kind,o.entity_id,o.created_at,a.id FROM outbox o LEFT JOIN activity_audit a
        ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind IN('customer.advance_received','customer.advance_refunded','customer.advance_applied')""",tenantId) {
        require(it.getString(4) != null) { "Backup customer advance is missing recorder history." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(3))
    }
    require(actual.size == expected.size && actual.map { it.first }.toSet() == expected.keys &&
        actual.all { (key,time) -> time >= expected.getValue(key) }) { "Backup customer advance source history is incomplete." }
}

private data class AdvanceCashWitness(val sourceId: String, val kind: String, val amountMinor: Long, val shiftId: String?, val createdAt: String)
