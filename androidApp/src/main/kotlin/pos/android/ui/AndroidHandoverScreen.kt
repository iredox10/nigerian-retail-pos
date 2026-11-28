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
import pos.data.*
import pos.domain.*
import pos.security.StaffAccess
import pos.ui.components.*
import java.util.UUID

/** Recipient consent and closing/opening remain in the same shared durable repository operation. */
@Composable
fun AndroidHandoverScreen(repo: PosRepository, tenantId: String, access: StaffAccess, session: StaffSession, onChanged: () -> Unit) = key(tenantId, session.token) {
    var revision by remember { mutableIntStateOf(0) }
    val current = remember(revision) { runCatching { repo.activeCashShift(tenantId)?.let { it to repo.expectedCash(tenantId, it.id) } } }
    val people = remember(revision) { runCatching { buildList<Pair<String?, String>> {
        if (session.staffId != null) add(null to "Shop owner")
        access.signInMembers(tenantId).filter { it.id != session.staffId }.forEach { add(it.id to it.name) }
    } } }
    val history = remember(revision) { runCatching { repo.cashHandovers(tenantId) } }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var recipient by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Cash handover", "Count together and record who accepted the shared drawer")
        InlineNotice("One shared drawer on this device", "This closes the current shift and opens the recipient's shift with the cash actually counted. Separate tills and simultaneous registers are not implemented.", NoticeTone.Info)
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        val loadingError = listOf(current, people, history).firstOrNull { it.isFailure }?.exceptionOrNull()
        loadingError?.let {
            InlineNotice("Handover unavailable", it.message.orEmpty(), NoticeTone.Danger)
            NeutralButton("Refresh records", { revision++ })
        }
        val active = current.getOrNull()
        if (loadingError == null && active == null) EmptyState("No open drawer", "Open and count the cash drawer in Money before recording a handover.")
        if (loadingError == null && active != null) {
            val candidates = people.getOrDefault(emptyList())
            val counted = parseNaira(amount)
            LabelValueRow("Cash expected", money(active.second))
            Text("Handed over by ${session.name}. Any shortage or excess stays on the closed shift.")
            PosTextField(amount, { amount = it; consent = false; error = null }, "Cash counted (₦)", hint = "0.00", enabled = !busy)
            if (counted != null) {
                LabelValueRow("Recipient accepts", money(counted), emphasized = true)
                LabelValueRow("Difference retained", money(Math.subtractExact(counted, active.second)))
            }
            PosTextField(reason, { reason = it; consent = false; error = null }, "Reason for handing over cash", hint = "End of shift", enabled = !busy)
            Text("Who is accepting this cash?", style = MaterialTheme.typography.titleSmall)
            if (candidates.isEmpty()) InlineNotice("No other recipient", "Add another staff member in Staff. You cannot accept your own handover.", NoticeTone.Warning)
            candidates.forEachIndexed { index, person ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    RadioButton(recipient == index, { recipient = index; password = ""; consent = false }, enabled = !busy)
                    TextButton({ recipient = index; password = ""; consent = false }, enabled = !busy) { Text(person.second) }
                }
            }
            Row {
                Checkbox(consent, { consent = it }, enabled = !busy && counted != null && candidates.isNotEmpty())
                Text("Recipient: I counted and accept ${counted?.let(::money) ?: "the cash shown above"}.", Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(password, { password = it }, label = { Text("Recipient's full password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("The recipient must personally enter their password. Their consent is limited to this count and shift and expires after five minutes.", style = MaterialTheme.typography.bodySmall)
            error?.let { InlineNotice("Cash not handed over", it, NoticeTone.Danger) }
            PosButton(if (busy) "Checking recipient & saving…" else "Accept cash & hand over", {
                busy = true; error = null
                scope.launch {
                    try {
                        val request = CashHandoverRequest(tenantId, operation, active.first.id, active.second, requireNotNull(counted), null, reason.trim())
                        val person = candidates.getOrNull(recipient) ?: throw IllegalArgumentException("Choose the recipient again.")
                        val proof = withContext(Dispatchers.IO) { access.authorizeCashHandover(session, request, person.first, password) }
                        password = ""
                        requireNotNull(proof) { "Recipient password was not accepted, or access is temporarily paused. After five unsuccessful attempts, wait 60 seconds." }
                        val saved = withContext(Dispatchers.IO) { repo.handoverCash(tenantId, request, proof.id) }
                        notice = "${money(saved.witness.request.countedMinor)} accepted by ${saved.witness.receiverName}. Previous shift closed and receiving shift opened. Lock the shop so the recipient can sign in."
                        amount = ""; reason = ""; consent = false; operation = UUID.randomUUID().toString(); revision++; onChanged()
                    } catch (e: Exception) { password = ""; consent = false; error = e.message ?: "Refresh the drawer, recount and try again." }
                    finally { busy = false }
                }
            }, enabled = !busy && counted != null && consent && password.isNotEmpty() && candidates.isNotEmpty() && reason.trim().length in 3..250, modifier = Modifier.fillMaxWidth())
            NeutralButton("Recount / refresh balance", { amount = ""; password = ""; consent = false; error = null; revision++ }, enabled = !busy)
        }
        SectionHeader("Saved handovers", "Original people, counts and differences stay recorded")
        history.getOrDefault(emptyList()).asReversed().forEach { record ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${record.witness.senderName} → ${record.witness.receiverName}", style = MaterialTheme.typography.titleMedium)
                    LabelValueRow("Accepted cash", money(record.witness.request.countedMinor), emphasized = true)
                    LabelValueRow("Difference", money(Math.subtractExact(record.witness.request.countedMinor, record.witness.request.expectedMinor)))
                    Text(record.witness.request.reason)
                    Text("Recorded UTC · ${record.createdAt}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
