package pos.screens

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

@Composable
fun OwnerRecoveryScreen(access: StaffAccess, session: StaffSession) = key(session.token) {
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(revision) { runCatching { access.ownerRecoveryEnabled(session) } }
    var password by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Owner recovery", "Keep a recovery code separately from this shop computer.")
        loaded.exceptionOrNull()?.let { InlineNotice("Recovery settings unavailable", it.message.orEmpty(), NoticeTone.Danger) }
        recoveryCode?.let { code ->
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    InlineNotice("Keep this recovery code", "This code is shown once. Someone who has it can reset this shop's owner password.", NoticeTone.Warning)
                    SelectionContainer {
                        Text(code, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                    Text("Keep a written or securely stored copy away from this computer. A new recovery code replaces the previous one.")
                    PosButton("I have kept this code", { recoveryCode = null })
                }
            }
        }
        if (loaded.isSuccess && recoveryCode == null) Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusPill(if (loaded.getOrThrow()) "Recovery code enrolled" else "Recovery code not enrolled")
                Text("If you forget the owner password, choose Recover owner access on the sign-in screen and enter the code. A used code stops working, and the new password signs out existing sessions and removes PIN authorization.")
                OutlinedTextField(password, { password = it; error = null }, label = { Text("Current owner password") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                error?.let { InlineNotice("Recovery code not changed", it, NoticeTone.Danger) }
                PosButton(if (busy) "Confirming owner access…" else if (loaded.getOrThrow()) "Create replacement recovery code" else "Create recovery code", {
                    busy = true
                    scope.launch {
                        try {
                            val code = withContext(Dispatchers.IO) { access.configureOwnerRecovery(session, password) }
                            requireNotNull(code) { "Owner password was not confirmed, or sign-in is temporarily paused. Wait 60 seconds after five unsuccessful attempts and try again." }
                            password = ""; error = null; recoveryCode = code; revision++
                        } catch (e: Exception) { error = e.message ?: "Review the owner password and try again." }
                        finally { busy = false }
                    }
                }, enabled = !busy && password.isNotEmpty())
            }
        }
    }
}
