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
import java.util.UUID
import pos.data.*
import pos.domain.*
import pos.security.StaffAccess
import pos.ui.components.*

@Composable
fun CashHandoverScreen(repo:PosRepository,tenantId:String,access:StaffAccess,session:StaffSession,onChanged:()->Unit) = key(tenantId,session.token) {
    var revision by remember {mutableIntStateOf(0)}
    val state=remember(revision) {runCatching {repo.activeCashShift(tenantId)?.let {it to repo.expectedCash(tenantId,it.id)}}}
    val people=remember(revision) {runCatching {buildList<Pair<String?,String>> {
        if(session.staffId!=null) add(null to "Shop owner")
        access.signInMembers(tenantId).filter {it.id!=session.staffId}.forEach {add(it.id to it.name)}
    }}}
    val history=remember(revision) {runCatching {repo.cashHandovers(tenantId)}}
    var amount by remember {mutableStateOf("")}
    var denominations by remember {mutableStateOf(false)}
    var counts by remember {mutableStateOf<Map<Long,String>>(emptyMap())}
    var reason by remember {mutableStateOf("")}
    var selected by remember {mutableIntStateOf(0)}
    var password by remember {mutableStateOf("")}
    var consent by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var operation by remember {mutableStateOf(UUID.randomUUID().toString())}
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Hand over the cash drawer","Count together, close the current shift and start the recipient's shift")
        InlineNotice("One shared shop drawer","This computer supports one open cash drawer for this shop. Separate tills and simultaneous register shifts are not supported.",NoticeTone.Info)
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        val loadError=listOf(state,people,history).firstOrNull {it.isFailure}?.exceptionOrNull()?.message
        loadError?.let {InlineNotice("Could not load handover",it,NoticeTone.Danger);NeutralButton("Try again",{revision++})}
        val current=state.getOrNull()
        if(loadError==null && current==null) EmptyState("No open drawer","Open a cash shift in Money before handing over cash.")
        if(loadError==null && current!=null) {
            LabelValueRow("Cash expected",money(current.second))
            Text("Handed over by ${session.name}. Any difference between expected and counted cash stays recorded on the closing shift.")
            Row {Switch(denominations,{denominations=it;consent=false},enabled=!busy);Text("Count notes and coins",Modifier.padding(12.dp))}
            if(denominations) CashDenominationPanel(counts) {note,value ->if(!busy) {counts=counts+(note to value);consent=false}}
            else PosTextField(amount,{amount=it;consent=false},"Cash counted in naira",hint="0.00",enabled=!busy)
            val count=if(denominations) cashCountFromInputs(counts) else null
            val counted=if(denominations) count?.totalMinor else parseNaira(amount)
            if(counted!=null) {LabelValueRow("Recipient accepts",money(counted),emphasized=true);LabelValueRow("Difference",money(Math.subtractExact(counted,current.second)))}
            PosTextField(reason,{reason=it;consent=false},"Handover reason",hint="End of shift",enabled=!busy)
            val candidates=people.getOrDefault(emptyList())
            if(candidates.isEmpty()) InlineNotice("No other recipient available","Add another staff member under Staff access. You cannot hand cash over to yourself.",NoticeTone.Warning)
            candidates.forEachIndexed {index,person ->Row {
                RadioButton(selected==index,{selected=index;password="";consent=false},enabled=!busy)
                TextButton({selected=index;password="";consent=false},enabled=!busy) {Text(person.second)}
            }}
            Row {Checkbox(consent,{consent=it},enabled=!busy && counted!=null);Text("Recipient: I counted and accept ${counted?.let(::money) ?: "the cash shown above"}.",Modifier.padding(top=12.dp))}
            OutlinedTextField(password,{password=it},label={Text("Recipient's full password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
            Text("The recipient must personally enter their password. Their consent applies to this count and shift for five minutes.",style=MaterialTheme.typography.bodySmall)
            error?.let {InlineNotice("Drawer not handed over",it,NoticeTone.Danger)}
            PosButton(if(busy) "Checking recipient & saving…" else "Accept cash & hand over",{
                busy=true;error=null
                scope.launch {
                    try {
                        val request=CashHandoverRequest(tenantId,operation,current.first.id,current.second,requireNotNull(counted),count?.encode(),reason.trim())
                        val recipient=candidates[selected].first
                        val proof=withContext(Dispatchers.IO) {access.authorizeCashHandover(session,request,recipient,password)}
                        password=""
                        requireNotNull(proof) {"Recipient password was not accepted, or sign-in is temporarily paused. After five unsuccessful attempts, wait 60 seconds."}
                        val saved=withContext(Dispatchers.IO) {repo.handoverCash(tenantId,request,proof.id)}
                        message="${money(saved.witness.request.countedMinor)} accepted by ${saved.witness.receiverName}. Previous shift closed; receiving shift opened. Lock the shop so the recipient can sign in."
                        operation=UUID.randomUUID().toString();amount="";reason="";counts=emptyMap();consent=false;revision++;onChanged()
                    } catch(e:Exception) {password="";error=e.message ?: "Review the drawer and try again."}
                    finally {busy=false}
                }
            },enabled=!busy && consent && counted!=null && candidates.isNotEmpty() && password.isNotEmpty() && reason.trim().length in 3..250)
        }
        SectionHeader("Saved handovers","Both people's names and the counted cash remain in history")
        history.getOrDefault(emptyList()).asReversed().forEach {record ->
            Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("${record.witness.senderName} → ${record.witness.receiverName}",style=MaterialTheme.typography.titleMedium)
                MoneyText(record.witness.request.countedMinor)
                Text(record.witness.request.reason);Text(record.createdAt,style=MaterialTheme.typography.bodySmall)
                record.witness.request.countSheet?.let {SavedCashCount("handover count sheet",CashCount.decode(it))}
            }}
        }
    }
}
