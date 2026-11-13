package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pos.security.StaffAccess
import pos.ui.components.*

/** Convenience credentials stay on this computer and always require a full-password bootstrap. */
@Composable
fun PinSetupPanel(access:StaffAccess,tenantId:String,staffId:String,onDismiss:()->Unit,onSaved:()->Unit) {
    var password by remember(tenantId,staffId) { mutableStateOf("") }
    var pin by remember(tenantId,staffId) { mutableStateOf("") }
    var confirmation by remember(tenantId,staffId) { mutableStateOf("") }
    var error by remember(tenantId,staffId) { mutableStateOf<String?>(null) }
    fun save(remove:Boolean) {
        try {
            if(!remove) require(pin==confirmation) { "The two PINs do not match." }
            require(access.configureStaffPin(tenantId,staffId,password,if(remove) null else pin)) {
                "Could not confirm the password. After five unsuccessful attempts, wait 60 seconds before trying again."
            }
            password="";pin="";confirmation="";onSaved()
        } catch(e:Exception) { error=e.message ?: "PIN not changed. Try again with your full password." }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Staff PIN on this computer")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("A PIN is a quicker way to unlock your own account. Enter your full staff password to set up or remove it.")
            Text("PIN access lasts up to 12 hours after a full-password sign-in. Using the PIN does not extend that time. You can always use your full password.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(password,{password=it;error=null},label={Text("Full staff password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(pin,{pin=it;error=null},label={Text("New six-digit PIN")},supportingText={Text("Exactly six digits, 0–9")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(confirmation,{confirmation=it;error=null},label={Text("Enter the PIN again")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Text("Five unsuccessful PIN or password attempts pause this account for 60 seconds. Changing access or resetting its password removes its PIN.",style=MaterialTheme.typography.bodySmall)
            error?.let { InlineNotice("PIN not changed",it,NoticeTone.Danger) }
            TextButton({save(true)}) { Text("Remove PIN using full password") }
        }
    },confirmButton={PosButton("Save staff PIN",{save(false)})},dismissButton={TextButton(onDismiss) {Text("Cancel")}})
}
