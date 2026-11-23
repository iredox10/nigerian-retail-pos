package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

private data class AndroidAccountSnapshot(
    val customers: List<Customer>, val customer: Customer?, val advances: List<CustomerAdvanceEvent>,
    val disputes: List<CustomerDispute>, val disputeBalance: CustomerDisputeBalance?,
    val receipts: List<Pair<Sale, Long>>, val repaymentsAssigned: Boolean,
) {
    val advanceMinor: Long get() = advances.lastOrNull()?.afterMinor ?: 0L
    val advanceVersion: Int get() = advances.size
}

/** Balances and source versions are loaded together and checked again in each core transaction. */
private fun readAndroidAccount(repo: PosRepository, tenantId: String, customerId: String?): AndroidAccountSnapshot = synchronized(repo) {
    val customers = repo.customers(tenantId)
    val customer = customers.singleOrNull { it.id == customerId }
    if (customer == null) AndroidAccountSnapshot(customers, null, emptyList(), emptyList(), null, emptyList(), true)
    else {
        val assigned = repo.repayments(tenantId, customer.id).all { repo.unallocatedRepayment(tenantId, it.id) == 0L }
        AndroidAccountSnapshot(customers, customer, repo.customerAdvances(tenantId, customer.id), repo.customerDisputes(tenantId, customer.id),
            repo.customerDisputeBalance(tenantId, customer.id),
            repo.sales(tenantId).filter { it.customerId == customer.id && it.creditMinor > 0L }
                .map { it to repo.unallocatedCredit(tenantId, it.id) }.filter { it.second > 0L }, assigned)
    }
}

@Composable
fun AndroidCustomerAdvancesScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision to selectedId) { readAndroidAccount(repo, tenantId, selectedId) }
    val state = loaded?.getOrNull()
    if (state?.customer != null && action != null) AndroidAdvanceAction(repo, tenantId, state, requireNotNull(action), { action = null }) {
        action = null; revision++; notice = "Customer advance action saved on this device"; onChanged()
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Customer advances", "Money held for a customer, separate from their receipt debt.") }
        item { OperationsLoadState(loaded, "customer advances", { revision++ }) }
        state?.let { account ->
            item { AndroidAccountCustomerPicker(account.customers, selectedId) { selectedId = it; notice = null } }
            notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
            if (account.customers.isEmpty()) item { EmptyState("Add a customer first", "Customer advances must belong to an existing customer.") }
            account.customer?.let { customer ->
                item { OperationsCard {
                    LabelValueRow("You hold for this customer", money(account.advanceMinor), emphasized = true)
                    LabelValueRow("Customer owes on receipts", money(customer.balanceMinor))
                    Text("Receiving cash increases the held balance. Applying it to a receipt reduces both held money and debt without receiving cash again.")
                    PosButton("Receive cash advance", { action = "received" }, Modifier.fillMaxWidth())
                    OutlinedButton({ action = "refunded" }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = account.advanceMinor > 0L) { Text("Refund unused advance") }
                    OutlinedButton({ action = "applied" }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = account.advanceMinor > 0L && customer.balanceMinor > 0L && account.repaymentsAssigned) { Text("Apply advance to receipt") }
                    if (!account.repaymentsAssigned) InlineNotice("Receipt application unavailable", "Existing debt repayments must be assigned to their receipts first. No receipt balance is guessed.", NoticeTone.Warning)
                } }
                item { Text("Advance history", style = MaterialTheme.typography.titleMedium) }
                if (account.advances.isEmpty()) item { Text("No advance actions recorded for this customer") }
                items(account.advances.reversed(), key = { it.id }) { event -> OperationsCard {
                    Text(when (event.kind) { "received" -> "Cash advance received"; "refunded" -> "Advance refunded in cash"; else -> "Advance applied to receipt" }, style = MaterialTheme.typography.titleMedium)
                    LabelValueRow("Amount", money(event.amountMinor))
                    LabelValueRow("Held before", money(event.beforeMinor))
                    LabelValueRow("Held after", money(event.afterMinor), emphasized = true)
                    event.saleId?.let { Text("Receipt ${it.takeLast(8)}") }
                    Text(event.reason)
                    Text("${event.createdAt.take(19).replace('T', ' ')} UTC · Version ${event.version}", style = MaterialTheme.typography.bodySmall)
                } }
            } ?: item { Text("Choose the customer whose money you are recording.") }
        }
    }
}

@Composable
private fun AndroidAdvanceAction(repo: PosRepository, tenantId: String, state: AndroidAccountSnapshot, kind: String, dismiss: () -> Unit, saved: () -> Unit) {
    val customer = requireNotNull(state.customer)
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var receiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptMenu by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    val selectedReceipt = state.receipts.singleOrNull { it.first.id == receiptId }
    val title = when (kind) { "received" -> "Receive cash advance"; "refunded" -> "Refund unused advance"; else -> "Apply advance to receipt" }
    OperationsDialog(title, dismiss, if (reviewed) "Save advance action" else "Review advance action", action = {
        val value = requireNotNull(parseNaira(amount)) { "Enter an exact naira amount." }
        require(value > 0L) { "Enter an amount greater than zero." }
        require(reason.trim().length in 3..500) { "Give a reason from 3 to 500 characters." }
        if (kind != "received") require(value <= state.advanceMinor) { "The amount exceeds the available advance." }
        if (kind == "received") Math.addExact(state.advanceMinor, value)
        if (kind == "applied") {
            require(state.repaymentsAssigned) { "Assign existing repayments before applying an advance." }
            val receipt = requireNotNull(selectedReceipt) { "Choose the credit receipt to fund." }
            require(value <= minOf(receipt.second, customer.balanceMinor)) { "The amount exceeds this receipt's reconciled debt." }
        }
        if (!reviewed) reviewed = true
        else {
            require(confirmed) { "Confirm the actual cash action or selected receipt application." }
            withContext(Dispatchers.IO) {
                when (kind) {
                    "received" -> repo.receiveCustomerAdvance(tenantId, customer.id, value, state.advanceMinor, state.advanceVersion, reason, operation)
                    "refunded" -> repo.refundCustomerAdvance(tenantId, customer.id, value, state.advanceMinor, state.advanceVersion, reason, operation)
                    else -> {
                        val receipt = requireNotNull(selectedReceipt)
                        repo.applyCustomerAdvance(tenantId, customer.id, receipt.first.id, value, state.advanceMinor, state.advanceVersion,
                            receipt.second, customer.balanceMinor, reason, operation)
                    }
                }
            }
            saved()
        }
    }) {
        Text(customer.name, style = MaterialTheme.typography.titleMedium)
        LabelValueRow("Advance available", money(state.advanceMinor))
        if (kind == "applied") {
            Box {
                OutlinedButton({ receiptMenu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(selectedReceipt?.let { "Receipt ${it.first.id.takeLast(8)} · ${money(it.second)} owed" } ?: "Choose credit receipt") }
                DropdownMenu(receiptMenu, { receiptMenu = false }) {
                    state.receipts.forEach { (sale, owed) -> DropdownMenuItem(text = { Text("${sale.id.takeLast(8)} · ${sale.createdAt.take(10)} · ${money(owed)}") }, onClick = { receiptId = sale.id; receiptMenu = false; reviewed = false; confirmed = false }) }
                }
            }
            if (state.receipts.isEmpty()) Text("No reconciled unpaid credit receipts are available.")
        }
        PosTextField(amount, { amount = it; reviewed = false; confirmed = false }, "Amount (₦)")
        PosTextField(reason, { reason = it; reviewed = false; confirmed = false }, "Reason for this action")
        if (reviewed) {
            val value = requireNotNull(parseNaira(amount))
            LabelValueRow("Advance after this action", money(if (kind == "received") Math.addExact(state.advanceMinor, value) else state.advanceMinor - value), emphasized = true)
            if (kind == "applied") {
                LabelValueRow("Selected receipt debt after", money(requireNotNull(selectedReceipt).second - value))
                LabelValueRow("Customer total debt after", money(customer.balanceMinor - value))
                Text("No new cash is received. This uses money already held for the customer.")
            } else Text(if (kind == "received") "This actual cash receipt increases the open drawer and customer advance liability. It does not record a sale." else "This actual cash refund reduces the open drawer and held advance. It does not reduce customer receipt debt.")
            OperationsCheck(confirmed, { confirmed = it }, when (kind) { "received" -> "I counted and received this cash."; "refunded" -> "I counted and am refunding this cash."; else -> "Apply this held money to the selected credit receipt." })
        }
        if (kind != "applied") Text("An open cash drawer is required. The app checks cash availability and the current advance version again before saving.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AndroidCustomerDisputesScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<CustomerDispute?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision to selectedId) { readAndroidAccount(repo, tenantId, selectedId) }
    val state = loaded?.getOrNull()
    if (opening && state?.customer != null) AndroidOpenDispute(repo, tenantId, state, { opening = false }) {
        opening = false; revision++; notice = "Dispute recorded; money and debt are unchanged"; onChanged()
    }
    resolving?.let { dispute -> AndroidResolveDispute(repo, tenantId, dispute, { resolving = null }) {
        resolving = null; revision++; notice = "Resolution recorded; original financial history retained"; onChanged()
    } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Customer disputes", "Keep disagreements visible without changing the recorded debt.") }
        item { OperationsLoadState(loaded, "customer disputes", { revision++ }) }
        state?.let { account ->
            item { AndroidAccountCustomerPicker(account.customers, selectedId) { selectedId = it; notice = null } }
            notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
            if (account.customers.isEmpty()) item { EmptyState("Add a customer first", "A dispute must identify a customer and saved credit receipt.") }
            account.customer?.let {
                item { OperationsCard {
                    LabelValueRow("Recorded customer debt", money(it.balanceMinor), emphasized = true)
                    LabelValueRow("Currently disputed", account.disputeBalance?.disputedMinor?.let(::money) ?: "Unavailable · assign repayments first")
                    LabelValueRow("Currently undisputed", account.disputeBalance?.undisputedMinor?.let(::money) ?: "Unavailable · assign repayments first")
                    Text("A dispute is a recorded disagreement. It does not write off debt, issue a refund or collect payment.")
                    PosButton("Record receipt dispute", { opening = true }, Modifier.fillMaxWidth(), enabled = account.repaymentsAssigned && account.receipts.isNotEmpty())
                    if (!account.repaymentsAssigned) Text("Assign existing repayments to receipts before opening a dispute.")
                } }
                if (account.disputes.isEmpty()) item { Text("No disputes recorded for this customer") }
                items(account.disputes, key = { it.opened.disputeId }) { dispute -> OperationsCard {
                    Text("Receipt ${dispute.opened.saleId.takeLast(8)}", style = MaterialTheme.typography.titleMedium)
                    LabelValueRow("Originally disputed", money(dispute.opened.amountMinor))
                    Text(if (dispute.open) "Open dispute" else "Resolved · original history retained")
                    Text("Opened: ${dispute.opened.reason}")
                    dispute.opened.reference?.let { reference -> Text("Reference · $reference") }
                    dispute.resolution?.let { resolution -> Text("Resolution: ${resolution.reason}"); Text(resolution.createdAt.take(19).replace('T', ' ') + " UTC", style = MaterialTheme.typography.bodySmall) }
                    if (dispute.open) OutlinedButton({ resolving = dispute }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Record resolution") }
                } }
            } ?: item { Text("Choose a customer to review their disputes.") }
        }
    }
}

@Composable
private fun AndroidOpenDispute(repo: PosRepository, tenantId: String, state: AndroidAccountSnapshot, dismiss: () -> Unit, saved: () -> Unit) {
    val customer = requireNotNull(state.customer)
    val receipts = state.receipts.filter { (sale, _) -> state.disputes.none { it.open && it.opened.saleId == sale.id } }
    var receiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    val receipt = receipts.singleOrNull { it.first.id == receiptId }
    OperationsDialog("Record receipt dispute", dismiss, if (reviewed) "Save dispute" else "Review dispute", action = {
        val chosen = requireNotNull(receipt) { "Choose an unpaid credit receipt without an open dispute." }
        val value = requireNotNull(parseNaira(amount)) { "Enter an exact disputed amount." }
        require(value > 0L && value <= chosen.second) { "Disputed amount must fit within the current receipt debt." }
        require(reason.trim().length in 3..500) { "Explain what is disputed, from 3 to 500 characters." }
        if (!reviewed) reviewed = true
        else {
            require(confirmed) { "Confirm the disagreement and unchanged financial records." }
            withContext(Dispatchers.IO) { repo.openCustomerDispute(tenantId, customer.id, chosen.first.id, value, chosen.second,
                customer.balanceMinor, reason, reference.trim().ifBlank { null }, operation) }
            saved()
        }
    }) {
        Text(customer.name, style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(receipt?.let { "Receipt ${it.first.id.takeLast(8)} · ${money(it.second)} owed" } ?: "Choose credit receipt") }
            DropdownMenu(menu, { menu = false }) { receipts.forEach { (sale, owed) -> DropdownMenuItem(text = { Text("${sale.id.takeLast(8)} · ${money(owed)}") }, onClick = { receiptId = sale.id; menu = false; reviewed = false; confirmed = false }) } }
        }
        if (receipts.isEmpty()) Text("Every available receipt already has an open dispute, or no receipt debt remains.")
        PosTextField(amount, { amount = it; reviewed = false; confirmed = false }, "Amount disputed (₦)")
        PosTextField(reason, { reason = it; reviewed = false; confirmed = false }, "What is disputed?")
        PosTextField(reference, { reference = it; reviewed = false; confirmed = false }, "Supporting reference (optional)")
        if (reviewed) {
            LabelValueRow("Claim recorded", money(requireNotNull(parseNaira(amount))))
            LabelValueRow("Receipt debt remains", money(requireNotNull(receipt).second))
            OperationsCheck(confirmed, { confirmed = it }, "Record this disagreement without changing money, stock or debt.")
        }
    }
}

@Composable
private fun AndroidResolveDispute(repo: PosRepository, tenantId: String, dispute: CustomerDispute, dismiss: () -> Unit, saved: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    OperationsDialog("Record dispute resolution", dismiss, if (reviewed) "Save resolution" else "Review resolution", action = {
        require(reason.trim().length in 3..500) { "Explain the outcome, from 3 to 500 characters." }
        if (!reviewed) reviewed = true
        else {
            require(confirmed) { "Confirm this recorded outcome." }
            withContext(Dispatchers.IO) { repo.resolveCustomerDispute(tenantId, dispute.opened.disputeId, dispute.version, reason, operation) }
            saved()
        }
    }) {
        Text("Receipt ${dispute.opened.saleId.takeLast(8)} · ${money(dispute.opened.amountMinor)} originally disputed")
        Text("Opened: ${dispute.opened.reason}")
        PosTextField(reason, { reason = it; reviewed = false; confirmed = false }, "What was agreed or concluded?")
        Text("A resolution preserves the claim and outcome. Use a separate payment or debt correction to change financial amounts.")
        if (reviewed) OperationsCheck(confirmed, { confirmed = it }, "Save this resolution with the original dispute retained.")
    }
}

@Composable
private fun AndroidAccountCustomerPicker(customers: List<Customer>, selectedId: String?, select: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ menu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = customers.isNotEmpty()) { Text(customers.singleOrNull { it.id == selectedId }?.name ?: "Choose customer") }
        DropdownMenu(menu, { menu = false }) { customers.forEach { customer -> DropdownMenuItem(text = { Text(customer.name) }, onClick = { select(customer.id); menu = false }) } }
    }
}
