package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.domain.StaffPermission

data class SupplierCreditNote(val id: String, val supplierId: String, val reference: String, val issuedOn: String,
    val amountMinor: Long, val reason: String, val createdAt: String)
data class SupplierCreditAllocation(val id: String, val creditId: String, val supplierId: String, val receiptId: String,
    val amountMinor: Long, val reason: String, val createdAt: String, val reversalOf: String?, val tenantId: String) {
    val signedAmountMinor: Long get() = if (reversalOf == null) amountMinor else -amountMinor
}
data class SupplierCreditVoid(val id: String, val creditId: String, val reason: String, val createdAt: String)
data class SupplierCreditBalance(val note: SupplierCreditNote, val appliedMinor: Long, val voided: Boolean) {
    val availableMinor: Long get() = if (voided) 0 else note.amountMinor - appliedMinor
}
data class SupplierCreditReview(val creditId: String, val receiptId: String, val amountMinor: Long,
    val availableBeforeMinor: Long, val owedBeforeMinor: Long, val historySize: Int)

internal val supplierCreditSnapshotTables = listOf("supplier_credit_notes", "supplier_credit_allocations", "supplier_credit_voids")
internal fun PosRepository.initializeSupplierCredits() {
    featureSchema("""CREATE TABLE IF NOT EXISTS supplier_credit_notes(tenant_id TEXT NOT NULL,id TEXT NOT NULL,
        supplier_id TEXT NOT NULL,reference TEXT NOT NULL,reference_key TEXT NOT NULL,issued_on TEXT NOT NULL,
        amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,
        PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,supplier_id,reference_key),
        FOREIGN KEY(tenant_id,supplier_id) REFERENCES supplier_profiles(tenant_id,id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS supplier_credit_allocations(tenant_id TEXT NOT NULL,id TEXT NOT NULL,
        credit_id TEXT NOT NULL,receipt_id TEXT NOT NULL,amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>0),
        reason TEXT NOT NULL,created_at TEXT NOT NULL,reversal_of TEXT,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,reversal_of),
        FOREIGN KEY(tenant_id,credit_id) REFERENCES supplier_credit_notes(tenant_id,id),
        FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id),
        FOREIGN KEY(tenant_id,reversal_of) REFERENCES supplier_credit_allocations(tenant_id,id))""")
    featureSchema("""CREATE TABLE IF NOT EXISTS supplier_credit_voids(tenant_id TEXT NOT NULL,id TEXT NOT NULL,credit_id TEXT NOT NULL,
        reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,credit_id),
        FOREIGN KEY(tenant_id,credit_id) REFERENCES supplier_credit_notes(tenant_id,id))""")
    supplierCreditSnapshotTables.forEach { table ->
        for (action in listOf("UPDATE", "DELETE")) featureSchema("""CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()}
            BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Saved supplier credits cannot be changed'); END""")
    }
}

fun PosRepository.supplierCreditBalances(tenantId: String): List<SupplierCreditBalance> = featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    val allocations = creditAllocations(tenantId)
    val voided = creditVoids(tenantId).map { it.creditId }.toSet()
    creditNotes(tenantId).map { note -> SupplierCreditBalance(note,
        allocations.filter { it.creditId == note.id }.fold(0L) { sum, event -> Math.addExact(sum, event.signedAmountMinor) }, note.id in voided) }
}
fun PosRepository.supplierCreditAllocations(tenantId: String): List<SupplierCreditAllocation> =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) { creditAllocations(tenantId) }
fun PosRepository.supplierCreditVoids(tenantId: String): List<SupplierCreditVoid> =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) { creditVoids(tenantId) }

fun PosRepository.recordSupplierCredit(tenantId: String, supplierId: String, reference: String, issuedOn: String,
    amountMinor: Long, reason: String, operationId: String = UUID.randomUUID().toString()): SupplierCreditNote =
    featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
        creditText(operationId, 1, 150); creditText(reference.trim(), 1, 150); creditText(reason.trim(), 3, 500)
        require(amountMinor > 0) { "Enter a credit amount greater than zero." }
        val date = LocalDate.parse(issuedOn)
        require(date.toString() == issuedOn && !date.isAfter(LocalDate.now(ZoneId.of("Africa/Lagos")))) { "Credit date must be today or earlier in WAT." }
        val previous = creditNotes(tenantId).singleOrNull { it.id == operationId }
        if (previous != null) {
            require(previous.copy(createdAt = "") == SupplierCreditNote(operationId, supplierId, reference.trim(), issuedOn, amountMinor, reason.trim(), "")) { "This credit was already recorded with different details." }
            return@featureWrite previous
        }
        require(creditNotes(tenantId).none { it.supplierId == supplierId && it.reference.equals(reference.trim(), ignoreCase = true) }) {
            "This supplier already has a credit note with this reference. Review the saved note."
        }
        val supplier = requireNotNull(supplierProfiles(tenantId, true).singleOrNull { it.id == supplierId }) { "Choose a supplier from this shop." }
        require(!supplier.archived) { "Restore this supplier before recording credit." }
        val now = creditNow(tenantId, supplierId)
        require(now >= Instant.parse(supplier.createdAt)) { "Correct the clock before recording this credit." }
        val note = SupplierCreditNote(operationId, supplierId, reference.trim(), issuedOn, amountMinor, reason.trim(), now.toString())
        featureExecute("INSERT INTO supplier_credit_notes VALUES(?,?,?,?,?,?,?,?,?)", tenantId, note.id, supplierId,
            note.reference, note.reference.lowercase(java.util.Locale.ROOT), issuedOn, amountMinor, note.reason, note.createdAt)
        featureEvent(tenantId, "supplier.credit_recorded", note.id)
        note
    }

fun PosRepository.reviewSupplierCredit(tenantId: String, creditId: String, receiptId: String, amountMinor: Long): SupplierCreditReview =
    featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
        val balance = requireNotNull(supplierCreditBalances(tenantId).singleOrNull { it.note.id == creditId }) { "Credit note is unavailable." }
        require(!balance.voided) { "This credit note was voided." }
        require(supplierReceiptLinks(tenantId).any { it.receiptId == receiptId && it.supplierId == balance.note.supplierId }) { "Choose a receipt linked to this supplier." }
        val owed = supplierOutstanding(tenantId, receiptId)
        require(amountMinor > 0 && amountMinor <= minOf(owed, balance.availableMinor)) { "Credit must fit both the available note and the unpaid receipt." }
        SupplierCreditReview(creditId, receiptId, amountMinor, balance.availableMinor, owed,
            creditAllocations(tenantId).count { it.creditId == creditId })
    }

fun PosRepository.applySupplierCredit(tenantId: String, expected: SupplierCreditReview, reason: String,
    operationId: String = UUID.randomUUID().toString()): SupplierCreditAllocation = featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    creditText(operationId, 1, 150); creditText(reason.trim(), 3, 500)
    val previous = creditAllocations(tenantId).singleOrNull { it.id == operationId }
    if (previous != null) {
        require(previous.creditId == expected.creditId && previous.receiptId == expected.receiptId && previous.amountMinor == expected.amountMinor && previous.reason == reason.trim() && previous.reversalOf == null) { "This credit application already has different details." }
        return@featureWrite previous
    }
    require(reviewSupplierCredit(tenantId, expected.creditId, expected.receiptId, expected.amountMinor) == expected) { "The credit or unpaid receipt changed. Review again." }
    val note = creditNotes(tenantId).single { it.id == expected.creditId }
    val now = creditNow(tenantId, note.supplierId, expected.receiptId)
    saveCreditAllocation(tenantId, SupplierCreditAllocation(operationId, note.id, note.supplierId, expected.receiptId,
        expected.amountMinor, reason.trim(), now.toString(), null, tenantId))
}

fun PosRepository.reverseSupplierCreditApplication(tenantId: String, allocationId: String, reason: String,
    operationId: String = UUID.randomUUID().toString()): SupplierCreditAllocation = featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    creditText(operationId, 1, 150); creditText(reason.trim(), 3, 500)
    val events = creditAllocations(tenantId)
    val original = requireNotNull(events.singleOrNull { it.id == allocationId && it.reversalOf == null }) { "Choose an original credit application." }
    events.singleOrNull { it.id == operationId }?.let {
        require(it.reversalOf == allocationId && it.reason == reason.trim()) { "This correction already has different details." }
        return@featureWrite it
    }
    require(events.none { it.reversalOf == allocationId }) { "This application was already reversed." }
    require(creditVoids(tenantId).none { it.creditId == original.creditId }) { "This credit note has been voided." }
    val now = creditNow(tenantId, original.supplierId, original.receiptId)
    saveCreditAllocation(tenantId, original.copy(id = operationId, reason = reason.trim(), createdAt = now.toString(), reversalOf = original.id))
}

fun PosRepository.voidSupplierCredit(tenantId: String, creditId: String, reason: String,
    operationId: String = UUID.randomUUID().toString()): SupplierCreditVoid = featureWrite(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
    creditText(operationId, 1, 150); creditText(reason.trim(), 3, 500)
    creditVoids(tenantId).singleOrNull { it.id == operationId }?.let {
        require(it.creditId == creditId && it.reason == reason.trim()) { "This void already has different details." }; return@featureWrite it
    }
    val balance = requireNotNull(supplierCreditBalances(tenantId).singleOrNull { it.note.id == creditId }) { "Credit note is unavailable." }
    require(!balance.voided && balance.appliedMinor == 0L) { "Undo active applications before voiding this note." }
    val event = SupplierCreditVoid(operationId, creditId, reason.trim(), creditNow(tenantId, balance.note.supplierId).toString())
    featureExecute("INSERT INTO supplier_credit_voids VALUES(?,?,?,?,?)", tenantId, event.id, creditId, event.reason, event.createdAt)
    featureEvent(tenantId, "supplier.credit_voided", event.id)
    event
}

internal fun PosRepository.supplierCreditAllocationHistory(tenantId: String): List<SupplierCreditAllocation> = creditAllocations(tenantId)

internal fun PosRepository.supplierCreditApplied(tenantId: String, receiptId: String): Long =
    creditAllocations(tenantId).filter { it.receiptId == receiptId }.fold(0L) { sum, event -> Math.addExact(sum, event.signedAmountMinor) }
internal fun PosRepository.supplierCreditLatestTime(tenantId: String, receiptId: String): Instant? =
    creditAllocations(tenantId).filter { it.receiptId == receiptId }.maxOfOrNull { Instant.parse(it.createdAt) }

private fun PosRepository.saveCreditAllocation(tenantId: String, event: SupplierCreditAllocation): SupplierCreditAllocation {
    featureExecute("INSERT INTO supplier_credit_allocations VALUES(?,?,?,?,?,?,?,?)", tenantId, event.id, event.creditId,
        event.receiptId, event.amountMinor, event.reason, event.createdAt, event.reversalOf)
    featureEvent(tenantId, if (event.reversalOf == null) "supplier.credit_applied" else "supplier.credit_application_reversed", event.id)
    return event
}
private fun creditText(value: String, min: Int, max: Int) {
    require(value == value.trim() && value.length in min..max && value.none { it.isISOControl() }) { "Enter printable details within the field limit." }
}
private fun PosRepository.creditNow(tenantId: String, supplierId: String, receiptId: String? = null): Instant {
    val times = creditNotes(tenantId).filter { it.supplierId == supplierId }.map { it.createdAt } +
        creditAllocations(tenantId).filter { it.supplierId == supplierId }.map { it.createdAt } +
        creditVoids(tenantId).filter { event -> creditNotes(tenantId).any { it.id == event.creditId && it.supplierId == supplierId } }.map { it.createdAt } +
        (if (receiptId == null) emptyList() else stockReceipts(tenantId).filter { it.id == receiptId }.map { it.createdAt } +
            supplierPayments(tenantId, receiptId).map { it.createdAt } + purchaseReturns(tenantId).filter { it.receiptId == receiptId }.map { it.createdAt } +
            supplierReceiptLinks(tenantId).filter { it.receiptId == receiptId }.map { it.createdAt })
    return Instant.now().also { now -> require(times.all { now > Instant.parse(it) }) { "Correct the clock before changing supplier credit." } }
}
private fun creditNote(row: ResultSet) = SupplierCreditNote(row.getString("id"), row.getString("supplier_id"), row.getString("reference"), row.getString("issued_on"), row.getLong("amount_minor"), row.getString("reason"), row.getString("created_at"))
private fun creditAllocation(row: ResultSet) = SupplierCreditAllocation(row.getString("id"), row.getString("credit_id"), row.getString("supplier_id"), row.getString("receipt_id"), row.getLong("amount_minor"), row.getString("reason"), row.getString("created_at"), row.getString("reversal_of"), row.getString("tenant_id"))
private fun PosRepository.creditNotes(tenantId: String) = featureQuery("SELECT * FROM supplier_credit_notes WHERE tenant_id=? ORDER BY created_at,id", tenantId, map = ::creditNote)
private fun PosRepository.creditAllocations(tenantId: String) = featureQuery("SELECT a.*,n.supplier_id FROM supplier_credit_allocations a JOIN supplier_credit_notes n ON n.tenant_id=a.tenant_id AND n.id=a.credit_id WHERE a.tenant_id=? ORDER BY a.created_at,a.id", tenantId, map = ::creditAllocation)
private fun PosRepository.creditVoids(tenantId: String) = featureQuery("SELECT * FROM supplier_credit_voids WHERE tenant_id=? ORDER BY created_at,id", tenantId) { SupplierCreditVoid(it.getString("id"), it.getString("credit_id"), it.getString("reason"), it.getString("created_at")) }

internal fun PosRepository.validateSupplierCredits(tenantId: String) {
    val notes = creditNotes(tenantId)
    val allocations = creditAllocations(tenantId)
    val voids = creditVoids(tenantId)
    val suppliers = featureQuery("SELECT id,created_at FROM supplier_profiles WHERE tenant_id=?", tenantId) { it.getString(1) to it.getString(2) }.toMap()
    val receipts = stockReceipts(tenantId).associateBy { it.id }
    val links = featureQuery("SELECT * FROM supplier_receipt_links WHERE tenant_id=?", tenantId) { SupplierCreditReceiptLink(it.getString("receipt_id"), it.getString("supplier_id"), it.getString("created_at")) }.associateBy { it.receiptId }
    val payments = supplierPayments(tenantId)
    val returns = featureQuery("SELECT receipt_id,credit_minor,created_at FROM all_purchase_returns WHERE tenant_id=?", tenantId) { SupplierCreditReturn(it.getString(1), it.getLong(2), it.getString(3)) }
    val expectedEvents = mutableMapOf<Pair<String, String>, Instant>()
    notes.forEach { note ->
        creditText(note.id, 1, 150); creditText(note.reference, 1, 150); creditText(note.reason, 3, 500)
        val supplier = requireNotNull(suppliers[note.supplierId]) { "Backup credit belongs to an unavailable supplier." }
        val at = Instant.parse(note.createdAt)
        require(note.amountMinor > 0 && at >= Instant.parse(supplier) &&
            LocalDate.parse(note.issuedOn).toString() == note.issuedOn &&
            LocalDate.parse(note.issuedOn) <= at.atZone(ZoneId.of("Africa/Lagos")).toLocalDate()) { "Backup supplier credit date or amount is invalid." }
        val key = featureQuery("SELECT reference_key FROM supplier_credit_notes WHERE tenant_id=? AND id=?", tenantId, note.id) { it.getString(1) }.single()
        require(key == note.reference.lowercase(java.util.Locale.ROOT)) { "Backup supplier credit reference is inconsistent." }
        expectedEvents["supplier.credit_recorded" to note.id] = at
        val entries = allocations.filter { it.creditId == note.id }
        val removed = voids.singleOrNull { it.creditId == note.id }
        var active = 0L
        var previous = at
        val originals = mutableMapOf<String, SupplierCreditAllocation>()
        val reversed = mutableSetOf<String>()
        entries.forEach { event ->
            creditText(event.id, 1, 150); creditText(event.reason, 3, 500)
            val time = Instant.parse(event.createdAt)
            require(time > previous && event.amountMinor > 0 && event.supplierId == note.supplierId &&
                (removed == null || time < Instant.parse(removed.createdAt))) { "Backup credit application history is out of order." }
            previous = time
            val receipt = requireNotNull(receipts[event.receiptId]) { "Backup credit receipt is unavailable." }
            val link = requireNotNull(links[event.receiptId]) { "Backup credit receipt has no supplier link." }
            require(link.supplierId == note.supplierId && time > Instant.parse(link.createdAt) && time > Instant.parse(receipt.createdAt)) { "Backup credit receipt belongs to another supplier." }
            if (event.reversalOf == null) {
                val earlierCredit = allocations.filter { it.receiptId == event.receiptId && Instant.parse(it.createdAt) < time }
                    .fold(0L) { sum, entry -> Math.addExact(sum, entry.signedAmountMinor) }
                val paid = payments.filter { it.receiptId == event.receiptId && Instant.parse(it.createdAt) < time }
                    .fold(0L) { sum, entry -> Math.addExact(sum, entry.amountMinor) }
                val returned = returns.filter { it.receiptId == event.receiptId && Instant.parse(it.createdAt) < time }
                    .fold(0L) { sum, entry -> Math.addExact(sum, entry.creditMinor) }
                val owed = Math.subtractExact(Math.subtractExact(Math.subtractExact(receipt.unpaidMinor, paid), returned), earlierCredit)
                require(event.amountMinor <= owed) { "Backup supplier credit exceeds the unpaid receipt." }
                active = Math.addExact(active, event.amountMinor)
                require(active <= note.amountMinor) { "Backup supplier credit is overallocated." }
                originals[event.id] = event
            } else {
                val original = requireNotNull(originals[event.reversalOf]) { "Backup credit reversal has no earlier application." }
                require(reversed.add(original.id) && original.receiptId == event.receiptId && original.amountMinor == event.amountMinor) { "Backup supplier credit reversal differs from its application." }
                active = Math.subtractExact(active, event.amountMinor)
            }
            require(active >= 0) { "Backup supplier credit balance is negative." }
            require(payments.none { it.receiptId == event.receiptId && Instant.parse(it.createdAt) == time } &&
                returns.none { it.receiptId == event.receiptId && Instant.parse(it.createdAt) == time }) { "Backup supplier credit chronology is ambiguous." }
            expectedEvents[(if (event.reversalOf == null) "supplier.credit_applied" else "supplier.credit_application_reversed") to event.id] = time
        }
        removed?.let { event ->
            creditText(event.id, 1, 150); creditText(event.reason, 3, 500)
            require(active == 0L && Instant.parse(event.createdAt) > previous) { "Backup voided credit still has active applications." }
            expectedEvents["supplier.credit_voided" to event.id] = Instant.parse(event.createdAt)
        }
    }
    require(voids.all { event -> notes.any { it.id == event.creditId } }) { "Backup credit void refers to a missing note." }
    val witnessed = featureQuery("""SELECT o.kind,o.entity_id,o.created_at,a.id FROM outbox o
        LEFT JOIN activity_audit a ON a.tenant_id=o.tenant_id AND a.id=o.id AND a.kind=o.kind AND a.entity_id=o.entity_id
        WHERE o.tenant_id=? AND o.kind IN ('supplier.credit_recorded','supplier.credit_applied','supplier.credit_application_reversed','supplier.credit_voided')""", tenantId) {
        require(it.getString(4) != null) { "Backup supplier credit is missing its activity history." }
        (it.getString(1) to it.getString(2)) to Instant.parse(it.getString(3))
    }
    require(witnessed.size == expectedEvents.size && witnessed.map { it.first }.toSet() == expectedEvents.keys &&
        witnessed.all { (key, time) -> time >= expectedEvents.getValue(key) }) { "Backup supplier credit source history is incomplete." }
    allocations.map { it.receiptId }.distinct().forEach { supplierOutstanding(tenantId, it) }
}

private data class SupplierCreditReceiptLink(val receiptId: String, val supplierId: String, val createdAt: String)
private data class SupplierCreditReturn(val receiptId: String, val creditMinor: Long, val createdAt: String)
