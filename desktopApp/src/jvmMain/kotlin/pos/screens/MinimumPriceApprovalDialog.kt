package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.domain.*
import pos.security.StaffAccess
import pos.ui.components.*

/** Passwords stay in this dialog's transient state and are cleared before delivering the grant. */
@Composable
fun MinimumPriceApprovalDialog(
    access: StaffAccess, requester: StaffSession, request: MinimumPriceApprovalRequest,
    onDismiss: () -> Unit, onApproved: (MinimumPriceApprovalWitness) -> Unit,
) = key(requester.token, request.fingerprint) {
    val people = remember(access, requester.token) {
        buildList<Pair<String?, String>> {
            if (requester.staffId != null) add(null to "Shop owner")
            access.signInMembers(requester.tenantId).filter { it.role == StaffRole.MANAGER && it.id != requester.staffId }
                .forEach { add(it.id to it.name) }
        }
    }
    var selected by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() { if (!busy) { password = ""; onDismiss() } }
    AlertDialog(onDismissRequest = ::close, title = { Text("Approve these sale prices") }, text = {
        Column(Modifier.widthIn(min = 360.dp, max = 560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A different owner or manager must review every item and enter their full password. Approval lasts five minutes and applies only to this sale.")
            request.lines.forEach {line ->
                Column {
                    Text("${line.name} · ${line.quantity} ${line.unit}", style = MaterialTheme.typography.titleSmall)
                    Text("Sale price: ${money(line.totalMinor)}")
                    val floor = request.checks.filter { it.lineKey == line.key }.maxOfOrNull { it.requiredMinor }
                    if (floor != null && floor > line.totalMinor) Text("Below minimum of ${money(floor)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Total ${money(request.totalMinor)}", style = MaterialTheme.typography.titleMedium)
            if (people.isEmpty()) InlineNotice("No other approver available", "Add a manager under Staff access before requesting approval. You cannot approve your own sale.", NoticeTone.Warning)
            else {
                Text("Approving person", style = MaterialTheme.typography.labelLarge)
                people.forEachIndexed {index, person ->
                    Row {
                        RadioButton(selected == index, { selected = index; password = ""; error = null }, enabled = !busy)
                        TextButton({ selected = index; password = ""; error = null }, enabled = !busy) { Text(person.second) }
                    }
                }
                OutlinedTextField(reason, { reason = it; error = null }, label = { Text("Reason for approving these prices") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; error = null }, label = { Text("Approver's full password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            error?.let { InlineNotice("Prices not approved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        PosButton(if (busy) "Checking approval…" else "Approve this sale", {
            busy = true; error = null
            scope.launch {
                try {
                    val proof = withContext(Dispatchers.IO) { access.authorizeMinimumPrice(requester, request, people[selected].first, password, reason) }
                    password = ""
                    requireNotNull(proof) { "Password was not accepted, or sign-in is temporarily paused. After five unsuccessful attempts, wait 60 seconds before retrying." }
                    onApproved(proof)
                } catch (e: Exception) { password = ""; error = e.message ?: "Review the approving account and try again." }
                finally { busy = false }
            }
        }, enabled = !busy && people.isNotEmpty() && password.isNotEmpty() && reason.trim().length in 3..250)
    }, dismissButton = { TextButton(::close, enabled = !busy) { Text("Cancel") } })
}
