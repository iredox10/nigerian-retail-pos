package pos.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.domain.*
import pos.security.StaffAccess
import pos.ui.components.*

@Composable
internal fun AndroidStaffAccountEditor(access: StaffAccess, session: StaffSession, member: StaffMember, dismiss: () -> Unit, saved: () -> Unit) {
    var name by rememberSaveable(member.id) { mutableStateOf(member.name) }
    var role by remember { mutableStateOf(member.role) }
    var active by remember { mutableStateOf(member.active) }
    var reviewed by remember { mutableStateOf(false) }
    OperationsDialog("Manage staff account", dismiss, if (reviewed) "Save access change" else "Review access change", action = {
        val request = member.copy(name = name.trim(), role = role, active = active)
        require(request != member) { "Change the name, role or active status before saving." }
        if (!reviewed) reviewed = true else {
            withContext(Dispatchers.IO) { access.updateMember(session, member.id, request.name, request.role, request.active, expected = member) }
            saved()
        }
    }) {
        Text("Access changes apply to this shop on this device. Existing business records and the owner account are retained.")
        PosTextField(name, { name = it; reviewed = false }, "Staff name")
        Text("Role", style = MaterialTheme.typography.titleMedium)
        FilterChip(role == StaffRole.CASHIER, { role = StaffRole.CASHIER; reviewed = false }, label = { Text("Cashier") })
        Text("Sell goods and view saved sales. Other shop tools remain unavailable.", style = MaterialTheme.typography.bodySmall)
        FilterChip(role == StaffRole.MANAGER, { role = StaffRole.MANAGER; reviewed = false }, label = { Text("Manager") })
        Text("Manage trading, stock, customers, money, reports and receipt settings. Staff administration and backups remain owner-only.", style = MaterialTheme.typography.bodySmall)
        OperationsCheck(active, { active = it; reviewed = false }, "Account active on this device")
        if (reviewed) {
            LabelValueRow("Name", name.trim())
            LabelValueRow("Role", if (role == StaffRole.MANAGER) "Manager" else "Cashier")
            LabelValueRow("Status", if (active) "Active" else "Inactive")
            Text("Saving revokes this person's previous sessions and PIN authorization. They must use their full password again if the account stays active.")
        }
    }
}

@Composable
internal fun AndroidStaffSecurityHistory(access: StaffAccess, session: StaffSession, dismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var visible by remember(search) { mutableIntStateOf(20) }
    var loaded by remember(session.token, revision) { mutableStateOf<Result<List<AuditEvent>>?>(null) }
    LaunchedEffect(session.token, revision) { loaded = withContext(Dispatchers.IO) { runCatching { access.auditEvents(session) } } }
    OperationsDialog("Security history", dismiss, "Close", action = { dismiss() }) {
        Text("Read-only local access history for this shop and device. It is separate from transferable business records.")
        PosTextField(search, { search = it }, "Find an access change", hint = "Person, event or saved description")
        OperationsLoadState(loaded, "security history", { revision++ })
        loaded?.getOrNull()?.let { events ->
            val rows = events.filter { search.isBlank() || listOf(it.kind, it.actor?.name.orEmpty(), it.description.orEmpty()).any { value -> value.contains(search, true) } }
            if (rows.isEmpty()) Text("No matching access history.")
            rows.take(visible).forEach { event ->
                HorizontalDivider()
                Text(securityActivityLabel(event.kind), style = MaterialTheme.typography.titleMedium)
                Text("${event.createdAt.take(19)} UTC")
                LabelValueRow("Recorded by", event.actor?.name ?: "Not recorded")
                event.description?.let { Text(it) }
                Text("Saved record ${event.id.takeLast(8)}", style = MaterialTheme.typography.bodySmall)
            }
            if (rows.size > visible) TextButton(onClick = { visible += 20 }) { Text("Show earlier events") }
        }
    }
}

private fun securityActivityLabel(kind: String): String = when (kind) {
    "staff.created" -> "Staff account created"
    "staff.updated" -> "Staff access changed"
    "staff.password_reset" -> "Staff password reset"
    "staff.pin_configured" -> "Staff PIN configured"
    "staff.pin_removed" -> "Staff PIN removed"
    "approval.sale_prices_granted" -> "Sale price exception approved"
    "approval.sale_prices_used" -> "Sale price approval used"
    "cash.handover_accepted" -> "Cash handover accepted"
    "cash.handover_consent_used" -> "Cash handover consent used"
    else -> kind.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
}
