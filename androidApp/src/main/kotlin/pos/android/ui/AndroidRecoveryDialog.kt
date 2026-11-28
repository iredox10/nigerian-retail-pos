package pos.android.ui

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
import pos.security.StaffAccess
import pos.ui.components.*

@Composable
fun AndroidRecoveryDialog(access: StaffAccess, tenantId: String, onDismiss: () -> Unit, onRecovered: () -> Unit) = key(tenantId) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recovered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) { if (recovered) onRecovered() else onDismiss() } }, title = { Text("Recover owner access") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recovered) InlineNotice("Owner password changed", "Sign in with your new password. Create a fresh recovery code after signing in.", NoticeTone.Success)
            else {
                Text("Use the recovery code previously enrolled for this shop. It is consumed when the password changes, and existing sessions and PIN authorizations are revoked.")
                OutlinedTextField(code, { code = it; error = null }, label = { Text("Recovery code") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; error = null }, label = { Text("New owner password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repeated, { repeated = it; error = null }, label = { Text("Enter new password again") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("Use 8 to 1024 characters.", style = MaterialTheme.typography.bodySmall)
                error?.let { InlineNotice("Access not recovered", it, NoticeTone.Danger) }
            }
        }
    }, confirmButton = {
        if (recovered) TextButton(onRecovered, modifier = Modifier.heightIn(min = 48.dp)) { Text("Return to sign-in") }
        else TextButton({
            if (password != repeated || password.length !in 8..1024) error = "Enter matching passwords from 8 to 1024 characters."
            else {
                busy = true; error = null
                scope.launch {
                    try {
                        require(withContext(Dispatchers.IO) { access.recoverOwner(tenantId, code, password) }) { "Recovery code was not accepted, or access is temporarily paused. After five unsuccessful attempts, wait 60 seconds." }
                        code = ""; password = ""; repeated = ""; recovered = true
                    } catch (e: Exception) { code = ""; password = ""; repeated = ""; error = e.message ?: "Recovery was not completed." }
                    finally { busy = false }
                }
            }
        }, enabled = !busy && code.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "Checking recovery code…" else "Recover access") }
    }, dismissButton = { if (!recovered) TextButton(onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}
