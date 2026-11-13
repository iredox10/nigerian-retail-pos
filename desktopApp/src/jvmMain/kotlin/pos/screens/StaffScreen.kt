package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pos.domain.StaffMember
import pos.domain.StaffRole
import pos.domain.StaffSession
import pos.security.StaffAccess
import pos.ui.components.*
import java.util.UUID

/** Local owner-managed access. Staff credentials are deliberately outside business backups. */
@Composable
fun StaffScreen(access: StaffAccess, session: StaffSession, onChanged: () -> Unit) {
    key(session.token) { OwnerStaffScreen(access,session,onChanged) }
}

@Composable
private fun OwnerStaffScreen(access: StaffAccess, session: StaffSession, onChanged: () -> Unit) {
        var revision by remember { mutableIntStateOf(0) }
        var name by remember { mutableStateOf("") }
        var role by remember { mutableStateOf(StaffRole.CASHIER) }
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        var createOperation by remember { mutableStateOf(UUID.randomUUID().toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var editing by remember { mutableStateOf<StaffMember?>(null) }
        var editName by remember { mutableStateOf("") }
        var editRole by remember { mutableStateOf(StaffRole.CASHIER) }
        var editActive by remember { mutableStateOf(true) }
        var editError by remember { mutableStateOf<String?>(null) }
        var resetting by remember { mutableStateOf<StaffMember?>(null) }
        var resetPassword by remember { mutableStateOf("") }
        var resetConfirmation by remember { mutableStateOf("") }
        var resetError by remember { mutableStateOf<String?>(null) }
        val loaded = remember(session, revision) { runCatching { access.members(session) } }
        fun changed() { revision++; onChanged() }
        fun newCreateIntent() { createOperation = UUID.randomUUID().toString(); error = null }

        if (session.role != StaffRole.OWNER) {
            InlineNotice("Only the owner can manage staff", "Lock this workspace and sign in with the shop owner's password.", NoticeTone.Warning)
            return
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                SectionHeader("Staff access", "Accounts for this shop on this computer")
                Spacer(Modifier.height(12.dp))
                InlineNotice("Keep a separate sign-in for each person", "Staff accounts work offline on this computer. They are not online accounts and their passwords are excluded from shop backups. The owner can change access or reset a staff password here.", NoticeTone.Info)
            }
            item {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader("What each person can do")
                        Text("Cashier", fontWeight = FontWeight.SemiBold)
                        Text("Make cash sales and view, print or export individual receipts. No customer credit, discounts, refunds, stock changes, cash management, reports or shop backups.")
                        Text("Manager", fontWeight = FontWeight.SemiBold)
                        Text("Manage stock, customers, customer credit, discounts, refunds, cash, reports and suppliers. Cannot manage staff or create shop backups.")
                        Text("Owner", fontWeight = FontWeight.SemiBold)
                        Text("Has all shop permissions and manages staff access. The owner account is unlocked using the existing shop owner password.")
                    }
                }
            }
            error?.let { value -> item { InlineNotice("Staff account not saved", value, NoticeTone.Danger) } }
            message?.let { value -> item { InlineNotice(value, tone = NoticeTone.Success) } }
            loaded.exceptionOrNull()?.let { failure -> item { InlineNotice("Staff accounts could not be loaded", failure.message ?: "Refresh to try again.", NoticeTone.Danger) } }
            item { NeutralButton("Refresh staff accounts", { revision++; error = null; message = null }) }
            loaded.getOrNull()?.let { members ->
                item { SectionHeader("People in this shop", "${members.count { it.active }} active · ${members.count { !it.active }} disabled") }
                if (members.isEmpty()) item { EmptyState("No staff accounts yet", "Add a cashier or manager below. Each person needs their own password.") }
                items(members, key = { it.id }) { member ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(member.name, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatusPill(staffRoleLabel(member.role))
                                StatusPill(if (member.active) "Active" else "Disabled", if (member.active) NoticeTone.Neutral else NoticeTone.Warning)
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NeutralButton("Change access", {
                                    editing = member; editName = member.name; editRole = member.role
                                    editActive = member.active; editError = null
                                })
                                NeutralButton("Reset password", {
                                    resetting = member; resetPassword = ""; resetConfirmation = ""; resetError = null
                                })
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeader("Add a staff account", "Use a name you will recognize when signing in")
                        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PosTextField(name, { name = it; newCreateIntent() }, "Staff name", hint = "For example, Chika")
                            StaffRoleChoice(role) { role = it; newCreateIntent() }
                            StaffPasswordField(password, { password = it; newCreateIntent() }, "Staff password")
                            StaffPasswordField(confirmation, { confirmation = it; newCreateIntent() }, "Enter the password again")
                            Text("Use 8–1024 characters. This is a password, not a short cashier PIN.", style = MaterialTheme.typography.bodySmall)
                            PosButton("Create staff account", {
                                try {
                                    require(name.trim().isNotEmpty()) { "Enter the staff member's name." }
                                    require(password.length in 8..1024) { "Use a password between 8 and 1024 characters." }
                                    require(password == confirmation) { "The two passwords do not match." }
                                    access.addMember(session, name.trim(), role, password, createOperation)
                                    password = ""; confirmation = ""; name = ""; role = StaffRole.CASHIER
                                    createOperation = UUID.randomUUID().toString(); error = null
                                    message = "Staff account saved on this computer. They can sign in to this shop with their own password."
                                    changed()
                                } catch (e: Exception) { error = e.message ?: "The account could not be saved. Your entries are still here." }
                            }, enabled = loaded.isSuccess)
                        }
                    }
                }
            }
        }
        editing?.let { member ->
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Change access for ${member.name}?") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PosTextField(editName, { editName = it; editError = null }, "Staff name")
                        StaffRoleChoice(editRole) { editRole = it; editError = null }
                        Row {
                            Checkbox(editActive, { editActive = it; editError = null })
                            Text("Allow this person to sign in", Modifier.weight(1f).padding(top = 12.dp))
                        }
                        Text("Saving an access change ends this person's existing sessions. They must sign in again. A disabled account cannot sign in; previous shop records remain saved.")
                        editError?.let { InlineNotice("Access not changed", it, NoticeTone.Danger) }
                    }
                },
                confirmButton = { PosButton("Save access", {
                    try {
                        require(editName.trim().isNotEmpty()) { "Enter the staff member's name." }
                        access.updateMember(session, member.id, editName.trim(), editRole, editActive)
                        editing = null; error = null; message = "Staff access updated on this computer."
                        changed()
                    } catch (e: Exception) { editError = e.message ?: "Access could not be changed. Your entries are still here." }
                }) },
                dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
            )
        }
        resetting?.let { member ->
            AlertDialog(
                onDismissRequest = { resetting = null },
                title = { Text("Reset password for ${member.name}?") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("The old password will stop working and existing sessions will end. Give the new password to this staff member privately.")
                        StaffPasswordField(resetPassword, { resetPassword = it; resetError = null }, "New staff password")
                        StaffPasswordField(resetConfirmation, { resetConfirmation = it; resetError = null }, "Enter the new password again")
                        Text("Use 8–1024 characters. Resetting a password does not enable a disabled account.", style = MaterialTheme.typography.bodySmall)
                        resetError?.let { InlineNotice("Password not reset", it, NoticeTone.Danger) }
                    }
                },
                confirmButton = { PosButton("Reset staff password", {
                    try {
                        require(resetPassword.length in 8..1024) { "Use a password between 8 and 1024 characters." }
                        require(resetPassword == resetConfirmation) { "The two passwords do not match." }
                        access.resetPassword(session, member.id, resetPassword)
                        resetting = null; resetPassword = ""; resetConfirmation = ""; error = null
                        message = "Staff password reset on this computer. Existing sessions have ended."
                        changed()
                    } catch (e: Exception) { resetError = e.message ?: "The password could not be reset. Your entries are still here." }
                }) },
                dismissButton = { TextButton({ resetting = null; resetPassword = ""; resetConfirmation = "" }) { Text("Cancel") } },
            )
        }
}

@Composable
private fun StaffRoleChoice(role: StaffRole, onChange: (StaffRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Access level", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(StaffRole.CASHIER, StaffRole.MANAGER).forEach { choice ->
                FilterChip(role == choice, { onChange(choice) }, label = { Text(staffRoleLabel(choice)) })
            }
        }
    }
}

@Composable
private fun StaffPasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
}

private fun staffRoleLabel(role: StaffRole): String = when (role) {
    StaffRole.OWNER -> "Owner"
    StaffRole.MANAGER -> "Manager"
    StaffRole.CASHIER -> "Cashier"
}
