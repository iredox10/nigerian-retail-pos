package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import pos.domain.StaffPermission

/** A recorded disagreement over a credit receipt. Resolving it never changes the financial ledger. */
data class CustomerDisputeEvent(
    val id: String, val disputeId: String, val customerId: String, val saleId: String,
    val amountMinor: Long, val receiptBeforeMinor: Long, val customerBeforeMinor: Long,
    val reference: String?, val reason: String, val kind: String, val version: Int, val createdAt: String,
)

data class CustomerDispute(val opened: CustomerDisputeEvent, val resolution: CustomerDisputeEvent?) {
    val open: Boolean get() = resolution == null
    val version: Int get() = if (open) 1 else 2
}

data class CustomerDisputeBalance(val recordedDebtMinor: Long, val disputedMinor: Long?) {
    val undisputedMinor: Long? get() = disputedMinor?.let { recordedDebtMinor - it }
}

internal val customerDisputeSnapshotTables = listOf("customer_dispute_events")

internal fun PosRepository.initializeCustomerDisputes() {
    featureSchema("""CREATE TABLE IF NOT EXISTS customer_dispute_events(
        tenant_id TEXT NOT NULL,id TEXT NOT NULL,dispute_id TEXT NOT NULL,
        customer_id TEXT NOT NULL,sale_id TEXT NOT NULL,
        amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0),
        receipt_before_minor INTEGER NOT NULL CHECK(typeof(receipt_before_minor)='integer' AND receipt_before_minor>0),
        customer_before_minor INTEGER NOT NULL CHECK(typeof(customer_before_minor)='integer' AND customer_before_minor>0),
        reference TEXT,reason TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('opened','resolved')),
        version INTEGER NOT NULL CHECK(version IN(1,2)),created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,dispute_id,version),
        CHECK(amount_minor<=receipt_before_minor AND receipt_before_minor<=customer_before_minor),
        FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id),
        FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))""")
    featureSchema("CREATE INDEX IF NOT EXISTS customer_disputes_customer ON customer_dispute_events(tenant_id,customer_id)")
    for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS customer_disputes_no_${action.lowercase()}
        BEFORE $action ON customer_dispute_events BEGIN SELECT RAISE(ABORT,'Saved dispute history cannot be changed'); END""")
}

fun PosRepository.customerDisputes(tenantId: String, customerId: String? = null): List<CustomerDispute> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        if (customerId != null) requireDisputeCustomer(tenantId, customerId)
        disputeEvents(tenantId, customerId).groupBy { it.disputeId }.values.map { history ->
            CustomerDispute(history.first(), history.getOrNull(1))
        }.sortedByDescending { it.opened.createdAt }
    }

fun PosRepository.customerDisputeBalance(tenantId: String, customerId: String): CustomerDisputeBalance =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
        val customer = requireDisputeCustomer(tenantId, customerId)
        val reconciled = repayments(tenantId, customerId).all { unallocatedRepayment(tenantId, it.id) == 0L }
        val disputed = if (!reconciled) null else customerDisputes(tenantId, customerId).filter { it.open }
            .fold(0L) { sum, entry -> Math.addExact(sum, minOf(entry.opened.amountMinor, unallocatedCredit(tenantId, entry.opened.saleId))) }
        require(disputed == null || disputed <= customer.balanceMinor) { "Receipt coverage does not reconcile with this customer's debt. Review payment assignments." }
        CustomerDisputeBalance(customer.balanceMinor, disputed)
    }

fun PosRepository.openCustomerDispute(
    tenantId: String, customerId: String, saleId: String, amountMinor: Long,
    expectedReceiptMinor: Long, expectedCustomerMinor: Long, reason: String, reference: String? = null,
    operationId: String = UUID.randomUUID().toString(),
): CustomerDisputeEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    val cleanReason = reason.trim()
    val cleanReference = reference?.trim()?.ifEmpty { null }
    disputeEventById(tenantId, operationId)?.let { saved ->
        require(saved.kind == "opened" && saved.customerId == customerId && saved.saleId == saleId &&
            saved.amountMinor == amountMinor && saved.receiptBeforeMinor == expectedReceiptMinor &&
            saved.customerBeforeMinor == expectedCustomerMinor && saved.reason == cleanReason && saved.reference == cleanReference) {
            "This dispute was already saved with different details."
        }
        return@featureWrite saved
    }
    val customer = requireDisputeCustomer(tenantId, customerId)
    val sale = requireNotNull(sales(tenantId).singleOrNull { it.id == saleId && it.customerId == customerId && it.creditMinor > 0 }) {
        "Choose this customer's saved credit receipt."
    }
    require(repayments(tenantId, customerId).all { unallocatedRepayment(tenantId, it.id) == 0L }) {
        "Assign saved repayments to receipts before recording a dispute."
    }
    val outstanding = unallocatedCredit(tenantId, sale.id)
    require(outstanding == expectedReceiptMinor && customer.balanceMinor == expectedCustomerMinor) {
        "The amount owed changed. Review the current receipt balance before recording this dispute."
    }
    require(customerDisputes(tenantId, customerId).none { it.open && it.opened.saleId == saleId }) {
        "This receipt already has an open dispute. Resolve it before recording another."
    }
    val now = Instant.now()
    require(now >= Instant.parse(sale.createdAt)) { "Correct the clock before recording this dispute." }
    require(disputeEvents(tenantId, customerId).all { now > Instant.parse(it.createdAt) }) {
        "Correct the clock before changing dispute history."
    }
    val event = CustomerDisputeEvent(operationId, operationId, customerId, saleId, amountMinor,
        outstanding, customer.balanceMinor, cleanReference, cleanReason, "opened", 1, now.toString())
    saveDisputeEvent(tenantId, event)
    event
}

fun PosRepository.resolveCustomerDispute(
    tenantId: String, disputeId: String, expectedVersion: Int, reason: String,
    operationId: String = UUID.randomUUID().toString(),
): CustomerDisputeEvent = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    val cleanReason = reason.trim()
    disputeEventById(tenantId, operationId)?.let { saved ->
        require(saved.disputeId == disputeId && saved.kind == "resolved" && expectedVersion == 1 && saved.reason == cleanReason) {
            "This resolution was already saved with different details."
        }
        return@featureWrite saved
    }
    val dispute = requireNotNull(customerDisputes(tenantId).singleOrNull { it.opened.disputeId == disputeId }) {
        "This dispute is unavailable in this shop."
    }
    require(dispute.open && dispute.version == expectedVersion) { "This dispute changed. Refresh before recording the outcome." }
    val now = Instant.now()
    require(disputeEvents(tenantId, dispute.opened.customerId).all { now > Instant.parse(it.createdAt) }) {
        "Correct the clock before resolving this dispute."
    }
    val event = dispute.opened.copy(id = operationId, kind = "resolved", version = 2, reason = cleanReason, createdAt = now.toString())
    saveDisputeEvent(tenantId, event)
    event
}

private fun validateDisputeFields(event: CustomerDisputeEvent) {
    listOf(event.id, event.disputeId, event.customerId, event.saleId).forEach {
        require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl)) { "Dispute reference is invalid." }
    }
    require(event.amountMinor in 1..event.receiptBeforeMinor && event.receiptBeforeMinor <= event.customerBeforeMinor) {
        "Enter a disputed amount up to the remaining amount on this receipt."
    }
    require(event.reason == event.reason.trim() && event.reason.length in 3..500 && event.reason.none(Char::isISOControl)) {
        "Enter a reason from 3 to 500 printable characters."
    }
    require(event.reference == null || (event.reference == event.reference.trim() && event.reference.length in 1..150 && event.reference.none(Char::isISOControl))) {
        "Use a reference of up to 150 printable characters."
    }
    require((event.kind == "opened" && event.version == 1 && event.id == event.disputeId) ||
        (event.kind == "resolved" && event.version == 2 && event.id != event.disputeId)) { "Dispute history has an invalid sequence." }
    Instant.parse(event.createdAt)
}

private fun PosRepository.saveDisputeEvent(tenantId: String, event: CustomerDisputeEvent) {
    validateDisputeFields(event)
    featureExecute("""INSERT INTO customer_dispute_events(tenant_id,id,dispute_id,customer_id,sale_id,amount_minor,
        receipt_before_minor,customer_before_minor,reference,reason,kind,version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        tenantId,event.id,event.disputeId,event.customerId,event.saleId,event.amountMinor,event.receiptBeforeMinor,
        event.customerBeforeMinor,event.reference,event.reason,event.kind,event.version,event.createdAt)
    featureEvent(tenantId, "customer.dispute_${event.kind}", event.id)
}

private fun PosRepository.requireDisputeCustomer(tenantId: String, customerId: String) =
    requireNotNull(customers(tenantId).singleOrNull { it.id == customerId }) { "This customer is unavailable in this shop." }

private fun PosRepository.disputeEvents(tenantId: String, customerId: String? = null): List<CustomerDisputeEvent> =
    featureQuery("SELECT * FROM customer_dispute_events WHERE tenant_id=?" +
        (if (customerId == null) "" else " AND customer_id=?") + " ORDER BY dispute_id,version",
        *listOfNotNull(tenantId, customerId).toTypedArray(), map = ::disputeRow)

private fun PosRepository.disputeEventById(tenantId: String, id: String): CustomerDisputeEvent? =
    featureQuery("SELECT * FROM customer_dispute_events WHERE tenant_id=? AND id=?", tenantId,id,map=::disputeRow).singleOrNull()

private fun disputeRow(row: ResultSet) = CustomerDisputeEvent(row.getString("id"),row.getString("dispute_id"),
    row.getString("customer_id"),row.getString("sale_id"),row.getLong("amount_minor"),row.getLong("receipt_before_minor"),
    row.getLong("customer_before_minor"),row.getString("reference"),row.getString("reason"),row.getString("kind"),
    row.getInt("version"),row.getString("created_at"))

internal fun PosRepository.validateCustomerDisputeSnapshot(tenantId: String) {
    val events = disputeEvents(tenantId)
    val sales = sales(tenantId).associateBy { it.id }
    events.forEach { event ->
        validateDisputeFields(event)
        val sale = requireNotNull(sales[event.saleId]) { "Backup dispute refers to an unavailable receipt." }
        require(sale.customerId == event.customerId && event.receiptBeforeMinor <= sale.creditMinor &&
            Instant.parse(event.createdAt) >= Instant.parse(sale.createdAt)) { "Backup dispute disagrees with its source credit sale." }
    }
    events.groupBy { it.disputeId }.values.forEach { history ->
        require(history.size in 1..2 && history.first().kind == "opened") { "Backup dispute history is incomplete." }
        val original = history.first()
        history.getOrNull(1)?.let { closed ->
            require(closed.copy(id=original.id,kind=original.kind,version=original.version,reason=original.reason,createdAt=original.createdAt) == original &&
                Instant.parse(closed.createdAt) > Instant.parse(original.createdAt)) { "Backup resolution differs from its dispute." }
        }
    }
    require(events.groupBy { it.disputeId }.values.map { CustomerDispute(it.first(),it.getOrNull(1)) }.filter { it.open }
        .groupBy { it.opened.saleId }.values.all { it.size == 1 }) { "Backup contains overlapping open receipt disputes." }
    val expected = events.associate { ("customer.dispute_${it.kind}" to it.id) to Instant.parse(it.createdAt) }
    val actual = featureQuery("""SELECT o.kind,o.entity_id,o.created_at,a.id FROM outbox o
        LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind IN ('customer.dispute_opened','customer.dispute_resolved')""",tenantId) {
        require(it.getString(4) != null) { "Backup dispute is missing its recorder history." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(3))
    }
    require(actual.size == expected.size && actual.map { it.first }.toSet() == expected.keys &&
        actual.all { (key,time) -> time >= expected.getValue(key) }) { "Backup dispute activity is incomplete." }
}
