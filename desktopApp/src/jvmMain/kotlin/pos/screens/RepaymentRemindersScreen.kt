package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.data.*
import pos.domain.Customer
import pos.domain.money
import pos.output.receiptDate
import pos.ui.components.*

@Composable
fun RepaymentRemindersScreen(repo: PosRepository, tenantId: String) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var closedOnly by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<Pair<ReminderEntry, ReminderState>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
    val loaded = remember(revision) {
        runCatching { repo.customers(tenantId) to repo.repaymentReminderHistory(tenantId) }
    }
    val customers = loaded.getOrNull()?.first.orEmpty()
    val byCustomer = remember(customers) { customers.associateBy { it.id } }
    val histories = loaded.getOrNull()?.second.orEmpty().groupBy { it.reminderId }
    val openDebtors = customers.filter { it.balanceMinor > 0 }
    val visible = histories.values
        .filter { (it.last().state != ReminderState.OPEN) == closedOnly }
        .filter { history -> byCustomer[history.first().customerId]?.let { it.name.contains(search, true) ||
            it.phone?.contains(search.trim()) == true } == true ||
            history.any { it.reason.contains(search, true) } }
        .sortedWith(compareBy<List<ReminderEntry>> { it.first().dueOn }.thenBy { it.first().createdAt })
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Repayment follow-ups", "Plan when to contact customers about money owed", Modifier.weight(1f))
            PosButton("Add follow-up", { adding = true }, enabled = loaded.isSuccess && openDebtors.isNotEmpty())
        }
        Text("Follow-ups are kept on this computer. Contact the customer yourself, then record the outcome.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!closedOnly, { closedOnly = false }, label = { Text("Open") })
            FilterChip(closedOnly, { closedOnly = true }, label = { Text("Completed and cancelled") })
            TextButton({ revision++ }) { Text("Refresh") }
        }
        PosTextField(search, { search = it }, "Find a follow-up",
            modifier = Modifier.fillMaxWidth(), hint = "Customer, phone number or reason")
        loaded.exceptionOrNull()?.let { InlineNotice("Follow-ups unavailable", it.message ?: "Refresh to try again.", NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (loaded.isSuccess) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (visible.isEmpty()) item {
                EmptyState("No matching follow-ups",
                    if (openDebtors.isEmpty()) "You can schedule a follow-up when a customer owes this shop."
                    else "Choose Add follow-up to record a date to contact a customer.")
            }
            items(visible, key = { it.first().reminderId }) { history ->
                val original = history.first()
                val latest = history.last()
                val customer = byCustomer.getValue(original.customerId)
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(customer.name, style = MaterialTheme.typography.titleMedium)
                        customer.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val status = when (latest.state) {
                            ReminderState.COMPLETED -> "Completed"
                            ReminderState.CANCELLED -> "Cancelled"
                            ReminderState.OPEN -> when {
                                original.dueOn < today -> "Overdue"
                                original.dueOn == today -> "Due today"
                                else -> "Upcoming"
                            }
                        }
                        Text("$status · ${original.dueOn} WAT")
                        LabelValueRow("Current money owed", money(customer.balanceMinor))
                        Text(original.reason)
                        Text("Scheduled ${receiptDate(original.createdAt.toString())}", style = MaterialTheme.typography.bodySmall)
                        if (latest.state == ReminderState.OPEN) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NeutralButton("Mark completed", { closing = original to ReminderState.COMPLETED })
                                TextButton({ closing = original to ReminderState.CANCELLED }) { Text("Cancel follow-up") }
                            }
                        } else {
                            Text("$status ${receiptDate(latest.createdAt.toString())} · ${latest.reason}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (adding) ReminderDialog(openDebtors, today, null, onDismiss = { adding = false }) { customer, date, reason, op ->
        repo.addRepaymentReminder(tenantId, requireNotNull(customer), date, reason, op)
        revision++; closedOnly = false; adding = false; message = "Follow-up scheduled."
    }
    closing?.let { (original, state) ->
        ReminderDialog(emptyList(), original.dueOn, state, byCustomer[original.customerId], onDismiss = { closing = null }) { _, _, reason, op ->
            repo.finishRepaymentReminder(tenantId, original.reminderId, state, reason, op)
            revision++; closing = null; closedOnly = true; message = "Follow-up history saved."
        }
    }
}

@Composable
private fun ReminderDialog(
    customers: List<Customer>, initialDate: LocalDate, state: ReminderState?,
    existingCustomer: Customer? = null,
    onDismiss: () -> Unit, save: (String?, LocalDate, String, String) -> Unit,
) {
    var customer by remember { mutableStateOf<Customer?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dateText by remember { mutableStateOf(initialDate.toString()) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(customer, dateText, reason) { UUID.randomUUID().toString() }
    val action = when (state) {
        ReminderState.COMPLETED -> "Complete follow-up"
        ReminderState.CANCELLED -> "Cancel follow-up"
        else -> "Schedule follow-up"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(action) }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state == null) {
                Box {
                    NeutralButton(customer?.name ?: "Choose customer", { menu = true })
                    DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 300.dp)) {
                        customers.forEach { option ->
                            DropdownMenuItem(text = {
                                Column {
                                    Text(option.name)
                                    Text(option.phone ?: "Customer ${option.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }, onClick = { customer = option; menu = false })
                        }
                    }
                }
                PosTextField(dateText, { dateText = it }, "Follow-up date (WAT)", hint = "YYYY-MM-DD")
            }
            existingCustomer?.let { Text(it.name) }
            PosTextField(reason, { reason = it }, if (state == null) "What should you follow up on?" else "Record the outcome or reason")
            error?.let { InlineNotice("Follow-up not saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        PosButton(action, {
            try {
                val due = LocalDate.parse(dateText)
                require(due.toString() == dateText) { "Enter the date as YYYY-MM-DD." }
                save(customer?.id, due, reason, operation)
            } catch (e: Exception) {
                error = e.message ?: "Check the date and reason."
            }
        }, enabled = reason.trim().length in 3..250 && (state != null || customer != null))
    }, dismissButton = { TextButton(onDismiss) { Text("Back") } })
}
