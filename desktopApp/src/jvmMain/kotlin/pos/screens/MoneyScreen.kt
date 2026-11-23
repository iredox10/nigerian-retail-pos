package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.*
import pos.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun MoneyScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) {
    var revision by remember(tenantId) { mutableIntStateOf(0) }
    var tab by rememberSaveable(tenantId) { mutableStateOf("Today's cash") }
    var action by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val activeResult = remember(tenantId, revision) { runCatching { repo.activeCashShift(tenantId) } }
    val active = activeResult.getOrNull()
    val shiftsResult = remember(tenantId, revision) { runCatching { repo.cashShifts(tenantId) } }
    val expensesResult = remember(tenantId, revision) { runCatching { repo.expenses(tenantId) } }
    val expectedResult = remember(tenantId, revision, active?.id) { runCatching { active?.let { repo.expectedCash(tenantId, it.id) } ?: 0L } }
    val entriesResult = remember(tenantId, revision, active?.id) { runCatching { active?.let { repo.cashEntries(tenantId, it.id) } ?: emptyList() } }
    val loadError = listOf(activeResult, shiftsResult, expensesResult, expectedResult, entriesResult).firstOrNull { it.isFailure }?.exceptionOrNull()?.message
    fun refresh(text: String) { message = text; revision++; onChanged() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Your shop's money", "Cash on this computer · Only this shop's records")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today's cash", "Expenses", "Owner money", "Past shifts").forEach { label ->
                FilterChip(tab == label, { tab = label }, label = { Text(label) }, leadingIcon = {
                    PosIcon(when (label) {
                        "Today's cash" -> PosIcons.Wallet
                        "Expenses" -> PosIcons.ReceiptText
                        "Owner money" -> PosIcons.Banknote
                        else -> PosIcons.History
                    })
                })
            }
        }
        loadError?.let { InlineNotice("Could not load money records", it, NoticeTone.Danger); NeutralButton("Try again", { revision++ }) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (loadError == null) when (tab) {
            "Today's cash" -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (active == null) item {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PosIcon(PosIcons.Wallet, Modifier.size(32.dp), MaterialTheme.colorScheme.primary)
                            Text("Start with the cash you can count", style = MaterialTheme.typography.titleLarge)
                            Text("Count the money in your drawer before opening this shift. Sales and payments recorded before a shift opens are not included in its cash count.")
                            PosButton("Open cash drawer", { action = "open" })
                        }
                    }
                } else {
                    item { Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { SectionHeader("Cash expected", "Shift opened ${cashTime(active.openedAt)}", Modifier.weight(1f)); StatusPill("Drawer open", NoticeTone.Success) }
                        MoneyText(expectedResult.getOrThrow(), large = true)
                        LabelValueRow("Opening cash counted", money(active.openingMinor))
                        active.openingCount?.let { SavedCashCount("opening count sheet", it) }
                        Text("Includes this shift's cash sales, repayments, expenses and money moved in or out. Bank transfers and earlier unassigned records are excluded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PosButton("Count cash & close shift", { action = "close" })
                    } } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NeutralButton("Record expense", { action = "expense" })
                        NeutralButton("Add money", { action = "in" })
                        NeutralButton("Take money out", { action = "out" })
                    } }
                    item { SectionHeader("Money in and out", "Each entry changes the cash expected for this shift.") }
                    if (entriesResult.getOrThrow().isEmpty()) item { EmptyState("No money movements yet", "This shift's cash activity will appear here.") }
                    items(entriesResult.getOrThrow().sortedByDescending { it.createdAt }, key = { it.id }) { entry ->
                        Surface(shape = MaterialTheme.shapes.small) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.description.ifBlank { cashKind(entry.kind) }, fontWeight = FontWeight.Medium)
                                Text("${cashKind(entry.kind)} · ${cashTime(entry.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${if (entry.amountMinor > 0) "+" else ""}${money(entry.amountMinor)}", fontWeight = FontWeight.SemiBold)
                        } }
                    }
                }
            }
            "Expenses" -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { SectionHeader("Expenses", "Cash business costs recorded for this shop", Modifier.weight(1f)); PosButton("Record expense", { action = "expense" }, enabled = active != null) } }
                if (active == null) item { InlineNotice("Open a cash drawer to record a cash expense", "This version records expenses paid from the open cash drawer only.", NoticeTone.Info) }
                if (expensesResult.getOrThrow().isEmpty()) item { EmptyState("No expenses recorded", "Fuel, transport and other shop costs appear here after you record them.") }
                items(expensesResult.getOrThrow().sortedByDescending { it.createdAt }, key = { it.id }) { expense ->
                    Surface(shape = MaterialTheme.shapes.small) { Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(expense.description, fontWeight = FontWeight.Medium)
                            Text("${expense.category} · ${cashTime(expense.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment=Alignment.End) {
                            MoneyText(expense.amountMinor)
                            ExpenseAttachmentsPanel(repo,tenantId,expense.id)
                        }
                    } }
                }
            }
            "Owner money" -> OwnerCashScreen(repo,tenantId) {refresh("Owner money saved on this computer")}
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SectionHeader("Past shifts", "Closing a shift preserves its counted cash and any difference. It does not change your sales.") }
                val closed = shiftsResult.getOrThrow().filter { it.closedAt != null }.sortedByDescending { it.closedAt }
                if (closed.isEmpty()) item { EmptyState("No closed shifts yet", "Count cash and close your current shift to keep its closing record here.") }
                items(closed, key = { it.id }) { shift ->
                    Surface(shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Closed ${cashTime(shift.closedAt!!)}", style = MaterialTheme.typography.titleMedium)
                        Text("Opened ${cashTime(shift.openedAt)}", style = MaterialTheme.typography.bodySmall)
                        LabelValueRow("Cash expected", shift.expectedMinor?.let(::money) ?: "Not recorded")
                        LabelValueRow("Cash counted", shift.countedMinor?.let(::money) ?: "Not recorded")
                        if (shift.blindClosing) Text("Expected cash hidden in the closing form", style = MaterialTheme.typography.bodySmall)
                        shift.openingCount?.let { SavedCashCount("opening count sheet", it) }
                        shift.closingCount?.let { SavedCashCount("closing count sheet", it) }
                        if (shift.expectedMinor != null && shift.countedMinor != null) Text(cashDifference(shift.countedMinor!!, shift.expectedMinor!!), fontWeight = FontWeight.SemiBold)
                    } }
                }
            }
        }
    }

    action?.let { kind ->
        var amount by remember(kind) { mutableStateOf("") }
        var description by remember(kind) { mutableStateOf("") }
        var category by remember(kind) { mutableStateOf("Other") }
        var error by remember(kind) { mutableStateOf<String?>(null) }
        var submitting by remember(kind) { mutableStateOf(false) }
        var countByDenomination by remember(kind) { mutableStateOf(false) }
        var denominationInputs by remember(kind) { mutableStateOf<Map<Long, String>>(emptyMap()) }
        var blindClosing by remember(kind) { mutableStateOf(kind == "close") }
        val operationId = remember(kind) { UUID.randomUUID().toString() }
        val countSheet = if (countByDenomination) cashCountFromInputs(denominationInputs) else null
        val parsed = if (countByDenomination) countSheet?.totalMinor else cashInput(amount)
        val expected = expectedResult.getOrNull() ?: 0L
        val title = when(kind) { "open" -> "Open cash drawer"; "close" -> "Count cash & close shift"; "expense" -> "Record expense"; "in" -> "Add money"; else -> "Take money out" }
        AlertDialog(onDismissRequest = { if (!submitting) action = null }, title = { Text(title) }, text = {
            Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (kind == "open") Text("Count all money physically in the drawer now. This is your starting cash, not a new sale.")
                if (kind == "close") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(blindClosing, { blindClosing = it })
                        Text("Hide cash expected while counting")
                    }
                    Text("This hides the expected amount in this form. You may already have seen it on Today's cash.", style = MaterialTheme.typography.bodySmall)
                    if (!blindClosing) LabelValueRow("Cash expected", money(expected))
                    Text("Count the physical cash and enter what is actually there. Any difference stays in the closing record.")
                }
                if (kind == "in" || kind == "out") Text(if (kind == "out") "Record money removed from the drawer. Owner withdrawals and bank deposits are money movements, not business expenses." else "Record cash added to the drawer. Owner contributions are not sales.")
                if (kind in listOf("open", "close")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(countByDenomination, { countByDenomination = it; error = null })
                        Text("Count notes and coins separately")
                    }
                }
                if (countByDenomination) {
                    CashDenominationPanel(denominationInputs) { denomination, text -> denominationInputs = denominationInputs + (denomination to text); error = null }
                    Text("The count sheet is saved with this shift.", style = MaterialTheme.typography.bodySmall)
                } else PosTextField(amount, { amount = it; error = null }, if (kind == "close") "Cash counted in naira" else if (kind == "open") "Opening cash in naira" else "Amount in naira", hint = "0.00")
                if (kind == "close" && !blindClosing && parsed != null) Text(cashDifference(parsed, expected), fontWeight = FontWeight.SemiBold)
                if (kind == "expense") PosTextField(category, { category = it }, "Expense category", hint = "Fuel, transport, electricity…")
                if (kind in listOf("expense", "in", "out")) PosTextField(description, { description = it }, if (kind == "expense") "What did you pay for?" else "Why is this money moving?")
                error?.let { InlineNotice("Not saved", it, NoticeTone.Danger) }
            }
        }, confirmButton = {
            PosButton(if (submitting) "Saving…" else if (kind == "close") "Save count & close shift" else title, {
                val value = parsed
                when {
                    value == null -> error = if (countByDenomination) "Enter whole counts of notes and coins within the supported amount." else "Enter a non-negative amount with no more than two decimal places."
                    kind in listOf("expense", "in", "out") && value == 0L -> error = "Enter an amount greater than zero."
                    kind in listOf("expense", "in", "out") && description.isBlank() -> error = "Describe what this money was for."
                    kind == "expense" && category.isBlank() -> error = "Enter an expense category."
                    else -> {
                        submitting = true
                        try {
                            when (kind) {
                                "open" -> repo.openCashShift(tenantId, value, openingCount = countSheet)
                                "close" -> repo.closeCashShift(tenantId, requireNotNull(active).id, value, closingCount = countSheet, blindClosing = blindClosing)
                                "expense" -> repo.recordExpense(tenantId, category.trim(), description.trim(), value, operationId)
                                "in" -> repo.recordCashMovement(tenantId, value, description.trim(), operationId)
                                else -> repo.recordCashMovement(tenantId, Math.negateExact(value), description.trim(), operationId)
                            }
                            action = null
                            refresh(if (kind == "close") "Cash count saved. Shift closed." else "Saved on this computer")
                        } catch (e: Exception) { error = e.message ?: "Money record could not be saved. Your entries have been kept." }
                        submitting = false
                    }
                }
            }, enabled = !submitting)
        }, dismissButton = { TextButton({ if (!submitting) action = null }) { Text("Cancel") } })
    }
}

private fun cashInput(text: String): Long? = parseNaira(text)

private fun cashDifference(counted: Long, expected: Long): String {
    val difference = try { Math.subtractExact(counted, expected) } catch (_: ArithmeticException) { return "Difference exceeds the supported amount." }
    return when { difference == 0L -> "Cash matches the amount expected"; difference < 0L -> "${money(Math.negateExact(difference))} less than expected"; else -> "${money(difference)} more than expected" }
}

private fun cashTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(value)) + " WAT"
}.getOrDefault(value)

private fun cashKind(value: String): String = when (value.lowercase()) {
    "opening", "opening_cash" -> "Opening cash"
    "sale", "cash_sale" -> "Cash sale"
    "repayment", "customer_repayment" -> "Customer payment"
    "expense" -> "Expense"
    "supplier_reimbursement" -> "Supplier cash reimbursement"
    "order_deposit" -> "Cash deposit for order"
    "order_deposit_refund" -> "Order deposit refund"
    "manual", "movement", "cash_movement" -> "Money moved"
    else -> value.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
