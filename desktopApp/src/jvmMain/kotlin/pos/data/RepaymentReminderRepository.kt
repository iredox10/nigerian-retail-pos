package pos.data

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.domain.StaffPermission

data class ReminderEntry(
    val id: String,
    val reminderId: String,
    val customerId: String,
    val dueOn: LocalDate,
    val state: ReminderState,
    val reason: String,
    val createdAt: Instant,
    val version: Int,
)

enum class ReminderState { OPEN, COMPLETED, CANCELLED }

private val shopZone = ZoneId.of("Africa/Lagos")
internal val reminderSnapshotTables = listOf("repayment_reminder_events")

internal fun PosRepository.initializeRepaymentReminders() {
    featureSchema(
        """CREATE TABLE IF NOT EXISTS repayment_reminder_events(
            tenant_id TEXT NOT NULL, id TEXT NOT NULL, reminder_id TEXT NOT NULL,
            customer_id TEXT NOT NULL, due_on TEXT NOT NULL, state TEXT NOT NULL,
            reason TEXT NOT NULL, created_at TEXT NOT NULL, version INTEGER NOT NULL,
            PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,reminder_id,version),
            FOREIGN KEY(tenant_id,customer_id) REFERENCES customers(tenant_id,id))"""
    )
    for (action in listOf("UPDATE", "DELETE")) {
        featureSchema(
            """CREATE TRIGGER IF NOT EXISTS repayment_reminder_no_${action.lowercase()}
                BEFORE $action ON repayment_reminder_events BEGIN
                SELECT RAISE(ABORT, 'Repayment follow-up history cannot be edited'); END"""
        )
    }
}

fun PosRepository.repaymentReminderHistory(tenantId: String): List<ReminderEntry> =
    featureRead(tenantId, StaffPermission.MANAGE_CUSTOMERS) { reminderEvents(tenantId) }

fun PosRepository.addRepaymentReminder(
    tenantId: String, customerId: String, dueOn: LocalDate, reason: String,
    operationId: String = UUID.randomUUID().toString(),
): ReminderEntry = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    val entry = ReminderEntry(operationId, operationId, customerId, dueOn, ReminderState.OPEN, reason.trim(), Instant.now(), 1)
    reminderByOperation(tenantId,operationId)?.let {
        require(it == entry.copy(createdAt = it.createdAt)) { "This follow-up request was saved with different details." }
        return@featureWrite it
    }
    require(customers(tenantId).any { it.id == customerId && it.balanceMinor > 0 }) {
        "Choose a customer who currently owes this shop."
    }
    saveReminderEvent(tenantId, entry)
    entry
}

fun PosRepository.finishRepaymentReminder(
    tenantId: String, reminderId: String, state: ReminderState, reason: String,
    operationId: String = UUID.randomUUID().toString(),
): ReminderEntry = featureWrite(tenantId, StaffPermission.MANAGE_CUSTOMERS) {
    require(state != ReminderState.OPEN) { "Choose completed or cancelled." }
    val history = reminderEvents(tenantId, reminderId)
    val original = requireNotNull(history.firstOrNull()) { "This follow-up is unavailable in this shop." }
    val entry = original.copy(id = operationId, state = state, reason = reason.trim(),
        createdAt = Instant.now(), version = 2)
    reminderByOperation(tenantId,operationId)?.let {
        require(it == entry.copy(createdAt = it.createdAt)) { "This follow-up request was saved with different details." }
        return@featureWrite it
    }
    require(history.size == 1) { "This follow-up was already closed. Refresh the list." }
    require(entry.createdAt >= original.createdAt) { "Correct the computer clock before closing this follow-up." }
    saveReminderEvent(tenantId, entry)
    entry
}

private fun PosRepository.saveReminderEvent(tenantId: String, event: ReminderEntry) {
    validateReminderEvent(event)
    featureExecute("""INSERT INTO repayment_reminder_events
        (tenant_id,id,reminder_id,customer_id,due_on,state,reason,created_at,version) VALUES(?,?,?,?,?,?,?,?,?)""",
        tenantId, event.id, event.reminderId, event.customerId, event.dueOn.toString(), event.state.name,
        event.reason, event.createdAt.toString(), event.version)
    featureEvent(tenantId, "customer.followup_${event.state.name.lowercase()}", event.id)
}

private fun validateReminderEvent(event: ReminderEntry) {
    require(event.id.length in 1..200 && event.id.none { it.isISOControl() }) { "Follow-up reference is invalid." }
    require(event.reason == event.reason.trim() && event.reason.length in 3..250 && event.reason.none { it.isISOControl() }) {
        "Enter a reason from 3 to 250 printable characters."
    }
    require(event.dueOn.year in 1..9999) { "Use a date within the supported calendar." }
    if (event.state == ReminderState.OPEN) {
        require(event.version == 1 && event.reminderId == event.id)
        require(event.dueOn >= event.createdAt.atZone(shopZone).toLocalDate()) { "Choose today or a later date in WAT." }
    } else {
        require(event.version == 2 && event.reminderId != event.id)
    }
}

private fun reminderRow(r: ResultSet) = ReminderEntry(
    r.getString("id"), r.getString("reminder_id"), r.getString("customer_id"),
    LocalDate.parse(r.getString("due_on")), ReminderState.valueOf(r.getString("state")),
    r.getString("reason"), Instant.parse(r.getString("created_at")), r.getInt("version"),
)

private fun PosRepository.reminderByOperation(tenantId: String, operationId: String) =
    featureQuery("SELECT * FROM repayment_reminder_events WHERE tenant_id=? AND id=?",
        tenantId, operationId, map = ::reminderRow).singleOrNull()

private fun PosRepository.reminderEvents(tenantId: String, reminderId: String? = null): List<ReminderEntry> =
    if (reminderId == null) featureQuery(
        "SELECT * FROM repayment_reminder_events WHERE tenant_id=? ORDER BY reminder_id,version", tenantId, map = ::reminderRow
    ) else featureQuery(
        "SELECT * FROM repayment_reminder_events WHERE tenant_id=? AND reminder_id=? ORDER BY version",
        tenantId, reminderId, map = ::reminderRow
    )

internal fun PosRepository.validateRepaymentReminders(tenantId: String) {
    val events = reminderEvents(tenantId)
    events.forEach(::validateReminderEvent)
    events.groupBy { it.reminderId }.values.forEach { history ->
        val original = history.first()
        require(history.size in 1..2 && original.state == ReminderState.OPEN) { "Backup follow-up history is incomplete." }
        history.getOrNull(1)?.let { closed ->
            require(closed.state != ReminderState.OPEN && closed.customerId == original.customerId &&
                closed.dueOn == original.dueOn && closed.createdAt >= original.createdAt) {
                "Backup follow-up differs from its original record."
            }
        }
    }
    val witnesses = featureQuery(
        "SELECT kind,entity_id FROM outbox WHERE tenant_id=? AND kind GLOB 'customer.followup_*'", tenantId
    ) { it.getString(1) to it.getString(2) }
    require(witnesses.size == events.size &&
        witnesses.toSet() == events.map { "customer.followup_${it.state.name.lowercase()}" to it.id }.toSet()) {
        "Backup follow-up activity is incomplete or repeated."
    }
}
