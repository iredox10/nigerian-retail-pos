package pos.android.ui

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.domain.StaffSession
import pos.security.StaffAccess
import pos.ui.components.*

/** Recovery proof is intentionally memory-only and shown once after genuine full-password proof. */
@Composable
fun AndroidOwnerRecoveryScreen(access: StaffAccess, session: StaffSession) = key(session.token) {
    var revision by remember { mutableIntStateOf(0) }
    val status = remember(revision) { runCatching { access.ownerRecoveryEnabled(session) } }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Owner recovery", "Keep the recovery code away from this Android device")
        status.exceptionOrNull()?.let { InlineNotice("Recovery settings unavailable", it.message.orEmpty(), NoticeTone.Danger) }
        if (code != null) {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    InlineNotice("Keep this recovery code", "This code is shown once. Anyone with it can reset this shop's owner password.", NoticeTone.Warning)
                    SelectionContainer { Text(requireNotNull(code), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)) }
                    Text("Write it down or keep it in a secure place separately. A replacement code invalidates the previous one. It is not included in business backups.")
                    PosButton("I have kept this code", { code = null }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (status.isSuccess) {
            StatusPill(if (status.getOrThrow()) "Recovery code enrolled" else "Recovery code not enrolled")
            Text("Recover owner access on the sign-in screen uses this code once. Resetting the password signs out existing sessions and removes PIN authorization.")
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Current owner password") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            error?.let { InlineNotice("Recovery code not changed", it, NoticeTone.Danger) }
            PosButton(if (busy) "Confirming owner access…" else if (status.getOrThrow()) "Create replacement recovery code" else "Create recovery code", {
                busy = true; error = null
                scope.launch {
                    try {
                        val created = withContext(Dispatchers.IO) { access.configureOwnerRecovery(session, password) }
                        requireNotNull(created) { "Owner password was not accepted, or access is temporarily paused. After five unsuccessful attempts, wait 60 seconds." }
                        password = ""; code = created; revision++
                    } catch (e: Exception) { password = ""; error = e.message ?: "Recovery code could not be changed." }
                    finally { busy = false }
                }
            }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Setup/removal proves this staff member's full password, independent of any owner session. */
@Composable
fun AndroidPinSetupDialog(access: StaffAccess, tenantId: String, staffId: String, onDismiss: () -> Unit, onSaved: () -> Unit) = key(tenantId, staffId) {
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Staff convenience PIN") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The staff member must enter their own full password. A six-digit PIN is a temporary convenience for this device; full-password access must be refreshed regularly. It does not encrypt shop records.")
            Row { Checkbox(removing, { removing = it; pin = ""; repeated = ""; error = null }, enabled = !busy); Text("Remove my saved PIN", Modifier.padding(top = 12.dp)) }
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Staff member's full password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (!removing) {
                OutlinedTextField(pin, { pin = it; error = null }, label = { Text("New six-digit PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repeated, { repeated = it; error = null }, label = { Text("Enter PIN again") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            error?.let { InlineNotice("PIN not changed", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        TextButton({
            error = null
            if (!removing && (pin.length != 6 || pin.any { it !in '0'..'9' } || pin != repeated)) error = "Enter matching PINs containing exactly six digits."
            else {
                busy = true
                scope.launch {
                    try {
                        require(withContext(Dispatchers.IO) { access.configureStaffPin(tenantId, staffId, password, if (removing) null else pin) }) { "Full password was not accepted, or access is temporarily paused. After five unsuccessful attempts, wait 60 seconds." }
                        password = ""; pin = ""; repeated = ""; onSaved()
                    } catch (e: Exception) { password = ""; pin = ""; repeated = ""; error = e.message ?: "PIN could not be changed." }
                    finally { busy = false }
                }
            }
        }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "Checking password…" else if (removing) "Remove PIN" else "Save PIN") }
    }, dismissButton = { TextButton(onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}
