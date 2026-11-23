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
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID

@Composable
fun CustomerDisputesScreen(repo: PosRepository, tenantId: String, customerId: String? = null, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var selectedId by remember(customerId) { mutableStateOf(customerId) }
    var customerMenu by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<CustomerDispute?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val loaded = remember(revision, selectedId) { runCatching {
        val customers = repo.customers(tenantId)
        Triple(customers, repo.customerDisputes(tenantId, selectedId), selectedId?.let { repo.customerDisputeBalance(tenantId, it) })
    } }
    val customers = loaded.getOrNull()?.first.orEmpty()
    val customer = customers.singleOrNull { it.id == selectedId }
    val disputes = loaded.getOrNull()?.second.orEmpty()
    val balance = loaded.getOrNull()?.third
    fun refresh(text: String) { revision++; opening = false; resolving = null; message = text; onChanged() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Customer disputes", "Record a disagreement, review the receipt, and keep its outcome in history")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                NeutralButton(customer?.name ?: "Choose customer", { customerMenu = true })
                DropdownMenu(customerMenu, { customerMenu = false }) {
                    DropdownMenuItem({ Text("All customers") }, { selectedId = null; customerMenu = false })
                    customers.forEach { entry -> DropdownMenuItem({ Text(entry.name) }, { selectedId = entry.id; customerMenu = false }) }
                }
            }
            PosButton("Record dispute", { opening = true }, enabled = customer != null && balance?.disputedMinor != null && balance.recordedDebtMinor > 0)
            NeutralButton("Refresh", { revision++ })
        }
        loaded.exceptionOrNull()?.let { InlineNotice("Disputes could not be loaded", it.message.orEmpty(), NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        balance?.let {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelValueRow("Recorded amount owed", money(it.recordedDebtMinor), emphasized = true)
                    if (it.disputedMinor != null) {
                        LabelValueRow("Currently disputed", money(it.disputedMinor))
                        LabelValueRow("Remaining undisputed amount", money(requireNotNull(it.undisputedMinor)))
                    } else InlineNotice("Assign repayments first", "Some saved repayments are still unassigned. Assign them to receipts to see the disputed and undisputed breakdown.", NoticeTone.Warning)
                    Text("A dispute or its resolution records what was discussed. Use the separate payment or debt-adjustment action for an agreed financial correction.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loaded.isSuccess && disputes.isEmpty()) item { EmptyState("No disputes recorded", "Choose a customer and a credit receipt to record an amount being questioned.") }
            items(disputes, key = { it.opened.disputeId }) { dispute ->
                val original = dispute.opened
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(customers.singleOrNull { it.id == original.customerId }?.name ?: "Customer", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusPill(if (dispute.open) "Open dispute" else "Resolved", if (dispute.open) NoticeTone.Warning else NoticeTone.Neutral)
                            Text(money(original.amountMinor), style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Receipt ${original.saleId.take(12)} · ${receiptDate(original.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        Text(original.reason)
                        original.reference?.let { Text("Reference $it", style = MaterialTheme.typography.bodySmall) }
                        if (dispute.open) NeutralButton("Record resolution", { resolving = dispute })
                        dispute.resolution?.let { result ->
                            HorizontalDivider()
                            Text("Resolved ${receiptDate(result.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            Text(result.reason)
                        }
                    }
                }
            }
        }
    }
    if (opening && customer != null) {
        OpenCustomerDisputeDialog(repo, tenantId, customer, { opening = false }) { refresh("Dispute recorded on this computer") }
    }
    resolving?.let { dispute ->
        ResolveCustomerDisputeDialog(repo, tenantId, dispute, { resolving = null }) { refresh("Resolution recorded with the dispute history") }
    }
}

@Composable
private fun OpenCustomerDisputeDialog(repo: PosRepository, tenantId: String, customer: Customer, dismiss: () -> Unit, saved: () -> Unit) {
    val loaded = remember(customer.id) { runCatching {
        val openIds = repo.customerDisputes(tenantId, customer.id).filter { it.open }.map { it.opened.saleId }.toSet()
        repo.sales(tenantId).filter { it.customerId == customer.id && it.creditMinor > 0 && it.id !in openIds }
            .map { it to repo.unallocatedCredit(tenantId, it.id) }.filter { it.second > 0 }
    } }
    val receipts = loaded.getOrNull().orEmpty()
    var selected by remember { mutableStateOf<String?>(receipts.firstOrNull()?.first?.id) }
    val receipt = receipts.singleOrNull { it.first.id == selected }
    var menu by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(selected, amount, reason, reference) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Record customer dispute") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(customer.name, style = MaterialTheme.typography.titleMedium)
            loaded.exceptionOrNull()?.let { InlineNotice("Receipts could not be loaded", it.message.orEmpty(), NoticeTone.Danger) }
            if (receipts.isEmpty()) Text("This customer has no undisputed credit receipt with an amount remaining.")
            Box {
                NeutralButton(receipt?.let { "Receipt ${it.first.id.take(12)}" } ?: "Choose receipt", { menu = true })
                DropdownMenu(menu, { menu = false }) { receipts.forEach { entry ->
                    DropdownMenuItem({ Text("${entry.first.id.take(12)} · ${money(entry.second)} remaining") }, { selected = entry.first.id; menu = false })
                } }
            }
            receipt?.let { LabelValueRow("Still owed on this receipt", money(it.second)) }
            PosTextField(amount, { amount = it; error = null }, "Amount being disputed (₦)")
            PosTextField(reason, { reason = it; error = null }, "What is being questioned?")
            PosTextField(reference, { reference = it; error = null }, "Supporting reference (optional)")
            Text("The recorded receipt debt stays available for review. Record any agreed payment or adjustment using its own action.", style = MaterialTheme.typography.bodySmall)
            error?.let { InlineNotice("Dispute not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        TextButton({
            try {
                val actual = requireNotNull(receipt) { "Choose a credit receipt." }
                repo.openCustomerDispute(tenantId, customer.id, actual.first.id, requireNotNull(parseNaira(amount)) { "Enter a valid naira amount." },
                    actual.second, customer.balanceMinor, reason, reference, operation)
                saved()
            } catch (failure: Exception) { error = failure.message ?: "The dispute could not be saved." }
        }, enabled = receipt != null) { Text("Save dispute") }
    }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
private fun ResolveCustomerDisputeDialog(repo: PosRepository, tenantId: String, dispute: CustomerDispute, dismiss: () -> Unit, saved: () -> Unit) {
    var reason by remember(dispute.opened.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(reason) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Record dispute resolution") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Receipt ${dispute.opened.saleId.take(12)} · ${money(dispute.opened.amountMinor)} originally disputed")
            PosTextField(reason, { reason = it; error = null }, "What was agreed or concluded?")
            Text("Use a separate payment or debt adjustment to record a financial correction.", style = MaterialTheme.typography.bodySmall)
            error?.let { InlineNotice("Resolution not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = { TextButton({
        try { repo.resolveCustomerDispute(tenantId, dispute.opened.disputeId, dispute.version, reason, operation); saved() }
        catch (failure: Exception) { error = failure.message ?: "The outcome could not be saved." }
    }) { Text("Save resolution") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
