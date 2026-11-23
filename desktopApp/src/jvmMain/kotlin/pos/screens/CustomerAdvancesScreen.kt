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
fun CustomerAdvancesScreen(repo: PosRepository, tenantId: String, customerId: String? = null, onChanged: () -> Unit) = key(tenantId) {
    var selectedId by remember(customerId) { mutableStateOf(customerId) }
    var revision by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val loaded = remember(revision, selectedId) { runCatching {
        repo.customers(tenantId) to repo.customerAdvances(tenantId, selectedId)
    } }
    val customers = loaded.getOrNull()?.first.orEmpty()
    val customer = customers.singleOrNull { it.id == selectedId }
    val events = loaded.getOrNull()?.second.orEmpty()
    val available = if (customer == null) 0L else events.lastOrNull()?.afterMinor ?: 0L
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Customer advances", "Money held for customers, with receipt applications and refund history")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                NeutralButton(customer?.name ?: "Choose customer", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("All customers") }, { selectedId = null; menu = false })
                    customers.forEach { entry -> DropdownMenuItem({ Text(entry.name) }, { selectedId = entry.id; menu = false }) }
                }
            }
            PosButton("Receive cash advance", { action = "received" }, enabled = customer != null)
            NeutralButton("Refund advance", { action = "refunded" }, enabled = customer != null && available > 0L)
            NeutralButton("Apply to receipt", { action = "applied" }, enabled = customer != null && available > 0L && customer.balanceMinor > 0L)
            NeutralButton("Refresh", { revision++ })
        }
        loaded.exceptionOrNull()?.let { InlineNotice("Advances could not be loaded", it.message.orEmpty(), NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        customer?.let { current ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelValueRow("Advance available for this customer", money(available), emphasized = true)
                    LabelValueRow("Customer's recorded receipt debt", money(current.balanceMinor))
                    Text("The advance belongs to the customer. Apply it to a selected unpaid receipt or refund the available cash explicitly.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loaded.isSuccess && events.isEmpty()) item { EmptyState("No customer advances recorded", "Choose a customer to record cash received for later use.") }
            items(events.sortedByDescending { it.createdAt }, key = { it.id }) { event ->
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(customers.singleOrNull { it.id == event.customerId }?.name ?: "Customer", style = MaterialTheme.typography.titleMedium)
                        LabelValueRow(when (event.kind) {
                            "received" -> "Cash advance received"
                            "refunded" -> "Advance refunded in cash"
                            else -> "Advance applied to receipt"
                        }, money(event.amountMinor), emphasized = true)
                        Text(receiptDate(event.createdAt), style = MaterialTheme.typography.bodySmall)
                        Text(event.reason)
                        event.saleId?.let { Text("Receipt ${it.take(12)}", style = MaterialTheme.typography.bodySmall) }
                        LabelValueRow("Advance before this action", money(event.beforeMinor))
                        LabelValueRow("Advance after this action", money(event.afterMinor))
                    }
                }
            }
        }
    }
    val currentAction = action
    if (currentAction != null && customer != null) {
        CustomerAdvanceDialog(repo, tenantId, customer, available, events.size, currentAction, { action = null }) {
            action = null; message = "Customer advance action saved on this computer"; revision++; onChanged()
        }
    }
}

@Composable
private fun CustomerAdvanceDialog(
    repo: PosRepository, tenantId: String, customer: Customer, available: Long, version: Int, kind: String,
    dismiss: () -> Unit, saved: () -> Unit,
) {
    val receiptsResult = remember(customer.id, kind) { runCatching {
        if (kind == "applied") repo.sales(tenantId).filter { it.customerId == customer.id && it.creditMinor > 0L }
            .map { it to repo.unallocatedCredit(tenantId, it.id) }.filter { it.second > 0L } else emptyList()
    } }
    val receipts = receiptsResult.getOrNull().orEmpty()
    var receiptId by remember { mutableStateOf(receipts.firstOrNull()?.first?.id) }
    val receipt = receipts.singleOrNull { it.first.id == receiptId }
    var menu by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = parseNaira(amount)
    val after = parsed?.let { value -> runCatching { if (kind == "received") Math.addExact(available,value) else Math.subtractExact(available,value) }.getOrNull() }
    val operation = remember(receiptId, amount, reason) { UUID.randomUUID().toString() }
    val title = when (kind) { "received" -> "Receive cash advance"; "refunded" -> "Refund customer advance"; else -> "Apply advance to receipt" }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(customer.name, style = MaterialTheme.typography.titleMedium)
            LabelValueRow("Advance currently available", money(available))
            if (kind == "applied") {
                receiptsResult.exceptionOrNull()?.let { InlineNotice("Receipts could not be loaded",it.message.orEmpty(),NoticeTone.Danger) }
                if (receipts.isEmpty()) Text("There is no unpaid credit receipt available for this customer.")
                Box {
                    NeutralButton(receipt?.let { "Receipt ${it.first.id.take(12)}" } ?: "Choose receipt", { menu = true })
                    DropdownMenu(menu, { menu = false }) { receipts.forEach { entry ->
                        DropdownMenuItem({ Text("${entry.first.id.take(12)} · ${money(entry.second)} remaining") }, { receiptId = entry.first.id; menu = false })
                    } }
                }
                receipt?.let { LabelValueRow("Still owed on this receipt",money(it.second)) }
            }
            PosTextField(amount, { amount = it; error = null }, when (kind) {
                "received" -> "Cash received (₦)"
                "refunded" -> "Cash to return to customer (₦)"
                else -> "Advance to apply (₦)"
            })
            PosTextField(reason, { reason = it; error = null }, "Reason or customer instruction")
            if (after != null && after >= 0L) LabelValueRow("Advance after this action",money(after),emphasized = true)
            Text(when (kind) {
                "received" -> "Receiving this advance adds cash to the open drawer and records money held for the customer."
                "refunded" -> "Hand the recorded refund to the customer from the open drawer."
                else -> "This uses money already held for the customer and records its assignment to the selected credit receipt."
            }, style = MaterialTheme.typography.bodySmall)
            error?.let { InlineNotice("Advance action not saved",it,NoticeTone.Danger) }
        }
    }, confirmButton = { TextButton({
        try {
            val value = requireNotNull(parsed) { "Enter a valid naira amount." }
            when (kind) {
                "received" -> repo.receiveCustomerAdvance(tenantId,customer.id,value,available,version,reason,operation)
                "refunded" -> repo.refundCustomerAdvance(tenantId,customer.id,value,available,version,reason,operation)
                else -> {
                    val target = requireNotNull(receipt) { "Choose an unpaid receipt." }
                    repo.applyCustomerAdvance(tenantId,customer.id,target.first.id,value,available,version,target.second,customer.balanceMinor,reason,operation)
                }
            }
            saved()
        } catch (failure: Exception) { error = failure.message ?: "The customer advance could not be saved." }
    }, enabled = kind != "applied" || receipt != null) { Text(when (kind) {"received" -> "Save cash received"; "refunded" -> "Save cash refund"; else -> "Apply advance"}) } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
