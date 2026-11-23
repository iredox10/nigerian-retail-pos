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
import pos.security.StaffAccess
import pos.ui.components.*

@Composable
fun OwnerRecoveryDialog(access: StaffAccess, tenantId: String, onDismiss: () -> Unit, onRecovered: () -> Unit) = key(tenantId) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recovered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() { if (!busy) { if (recovered) onRecovered() else onDismiss() } }
    AlertDialog(onDismissRequest = ::close, title = { Text("Recover owner access") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (recovered) InlineNotice("Owner password changed", "Sign in using your new password. Create a fresh recovery code after signing in.", NoticeTone.Success)
            else {
                Text("Enter the recovery code previously enrolled for this shop. Using it changes the owner password and signs out existing sessions.")
                OutlinedTextField(code, { code = it; error = null }, label = { Text("Recovery code") },
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; error = null }, label = { Text("New owner password") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repeated, { repeated = it; error = null }, label = { Text("Enter the new password again") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("Use 8 to 1024 characters. The recovery code is consumed when the password changes.", style = MaterialTheme.typography.bodySmall)
                error?.let { InlineNotice("Access not recovered", it, NoticeTone.Danger) }
            }
        }
    }, confirmButton = {
        if (recovered) PosButton("Return to sign-in", onRecovered)
        else PosButton(if (busy) "Checking recovery code…" else "Recover access", {
            error = null
            if (password != repeated || password.length !in 8..1024) {
                error = "Enter matching passwords from 8 to 1024 characters."
            } else {
                busy = true
                scope.launch {
                    try {
                        require(withContext(Dispatchers.IO) { access.recoverOwner(tenantId, code, password) }) {
                            "Recovery code was not accepted, or sign-in is temporarily paused. Wait 60 seconds after five unsuccessful attempts and try again."
                        }
                        code = ""; password = ""; repeated = ""; recovered = true
                    } catch (e: Exception) { error = e.message ?: "Review the recovery code and try again." }
                    finally { busy = false }
                }
            }
        }, enabled = !busy && code.isNotBlank())
    }, dismissButton = { if (!recovered) TextButton(::close, enabled = !busy) { Text("Cancel") } })
}
