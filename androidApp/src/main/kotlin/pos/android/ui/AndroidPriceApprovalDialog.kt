package pos.android.ui

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

@Composable
fun AndroidPriceApprovalDialog(access:StaffAccess,requester:StaffSession,request:MinimumPriceApprovalRequest,dismiss:()->Unit,approved:(String)->Unit) {
    val members=remember(requester.token) {runCatching {access.signInMembers(requester.tenantId).filter {it.id!=requester.staffId && it.role.allows(StaffPermission.DISCOUNT)}}}
    var selected by remember {mutableStateOf<String?>(null)}
    var password by remember {mutableStateOf("")};var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Approve this sale price")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("A different owner or manager must personally enter their full password. Approval covers only this sale and expires after five minutes.")
            LabelValueRow("Sale total",money(request.totalMinor),emphasized=true)
            Text("Requested by ${requester.name}")
            if(requester.role!=StaffRole.OWNER) FilterChip(selected==null,{selected=null;password=""},label={Text("Owner")})
            members.getOrNull().orEmpty().forEach {m ->FilterChip(selected==m.id,{selected=m.id;password=""},label={Text("${m.name} · ${m.role}")})}
            members.exceptionOrNull()?.let {Text("Approvers could not be loaded.")}
            OutlinedTextField(password,{password=it},label={Text("Approver's full password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            PosTextField(reason,{reason=it},"Reason for approving these prices")
            request.checks.filter {check -> request.lines.single { it.key == check.lineKey }.totalMinor < check.requiredMinor}.forEach {check ->Text("Minimum-price exception · ${check.productId.takeLast(8)}")}
            error?.let {InlineNotice("Price not approved",it,NoticeTone.Danger)}
        }
    },confirmButton={TextButton({busy=true;scope.launch {
        try {val proof=withContext(Dispatchers.IO){requireNotNull(access.authorizeMinimumPrice(requester,request,selected,password,reason.trim())) {"Approver password was not accepted, or access is temporarily locked."}};password="";approved(proof.id)}
        catch(e:Exception){error=e.message ?: "Approval could not be recorded.";password=""}finally{busy=false}
    }},enabled=!busy){Text(if(busy)"Checking…" else "Approve prices")}},dismissButton={TextButton(dismiss,enabled=!busy){Text("Cancel")}})
}
