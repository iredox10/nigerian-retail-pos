package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.LandedCostEvent
import pos.data.LandedCostMethod
import pos.data.LandedCostReview
import pos.data.PosRepository
import pos.data.landedCostBook
import pos.data.reviewLandedCost
import pos.data.saveLandedCost
import pos.domain.Expense
import pos.domain.StockReceipt
import pos.domain.money
import pos.output.receiptDate
import pos.ui.components.*

@Composable
fun LandedCostsScreen(repo: PosRepository, tenantId: String) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var expenseId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var editorRemoving by remember { mutableStateOf<Boolean?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = remember(repo, revision) { runCatching { repo.landedCostBook(tenantId) } }
    val book = loaded.getOrNull()
    val selected = book?.expenses?.singleOrNull { it.id == expenseId }
    val receiptById = remember(book) { book?.receipts.orEmpty().associateBy { it.id } }
    val latest = selected?.let { book?.latestByExpense?.get(it.id) }
    val active = latest?.takeIf { it.method != null }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        loaded.exceptionOrNull()?.let {
            InlineNotice("Landed costs could not be loaded", it.message ?: "Try refreshing the page.", NoticeTone.Danger)
            NeutralButton("Refresh", { revision++ })
        }
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (book != null && selected == null) {
            SectionHeader("Allocate delivery costs", "Choose a saved freight, loading or transport expense for stock purchases.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PosTextField(search, { search = it }, "Find an expense", Modifier.weight(1f))
                NeutralButton("Refresh", { revision++; notice = null })
            }
            val matches = book.expenses.filter {
                it.category.contains(search.trim(), ignoreCase = true) || it.description.contains(search.trim(), ignoreCase = true)
            }
            if (matches.isEmpty()) {
                if (book.expenses.isEmpty()) EmptyState("No recorded cash expenses", "Record the delivery expense under Money → Expenses first.")
                else EmptyState("No matching expenses", "Search by category or description.")
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches, key = { it.id }) { expense ->
                    val saved = book.latestByExpense[expense.id]
                    Surface(
                        onClick = { expenseId = expense.id; notice = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabelValueRow(expense.description, money(expense.amountMinor), emphasized = true)
                            Text("${expense.category} · ${receiptDate(expense.createdAt)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(when {
                                saved == null -> "Available to allocate"
                                saved.method == null -> "Allocation removed"
                                else -> "Allocated to ${saved.shares.size} ${if (saved.shares.size == 1) "receipt" else "receipts"}"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (book != null && selected != null) {
            NeutralButton("Back to expenses", { expenseId = null; notice = null })
            SectionHeader(selected.description, "${selected.category} · ${receiptDate(selected.createdAt)}")
            LabelValueRow("Recorded expense", money(selected.amountMinor), emphasized = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosButton(if (active == null) "Choose purchase receipts" else "Revise allocation", { editorRemoving = false },
                    enabled = book.receipts.isNotEmpty())
                if (active != null) NeutralButton("Remove allocation", { editorRemoving = true })
            }
            if (book.receipts.isEmpty()) Text("Receive stock before allocating an expense.", style = MaterialTheme.typography.bodySmall)
            val activeTotals = book.allocatedByReceipt
            val history = book.history.filter { it.expenseId == selected.id }.asReversed()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active != null) {
                    item { Text(active.method?.label.orEmpty(), style = MaterialTheme.typography.titleMedium) }
                    items(active.shares, key = { it.receiptId }) { share ->
                        val receipt = receiptById.getValue(share.receiptId)
                        val totalDeliveryCost = activeTotals.getValue(share.receiptId)
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PurchaseReceiptHeading(receipt)
                                LabelValueRow("Original purchase", money(receipt.totalCostMinor))
                                LabelValueRow("Share of this expense", money(share.amountMinor))
                                LabelValueRow("Total including delivery costs", money(Math.addExact(receipt.totalCostMinor, totalDeliveryCost)), emphasized = true)
                            }
                        }
                    }
                }
                item { Text("Allocation history", style = MaterialTheme.typography.titleMedium) }
                if (history.isEmpty()) item { Text("This expense has no allocation history.") }
                items(history, key = { it.id }) { event ->
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Version ${event.version} · ${event.method?.label ?: "Allocation removed"}",
                                fontWeight = FontWeight.SemiBold)
                            Text(receiptDate(event.createdAt), style = MaterialTheme.typography.bodySmall)
                            Text(event.reason)
                            for (share in event.shares) {
                                LabelValueRow(purchaseReceiptLabel(receiptById.getValue(share.receiptId)), money(share.amountMinor))
                            }
                        }
                    }
                }
            }
            editorRemoving?.let { removing ->
                LandedCostEditor(repo, selected, book.receipts, latest, removing, { editorRemoving = null }) {
                    editorRemoving = null
                    notice = if (removing) "Allocation removed. Its history is saved." else "Landed cost saved on this computer."
                    revision++
                }
            }
        }
    }
}

@Composable
private fun LandedCostEditor(
    repo: PosRepository,
    expense: Expense,
    receipts: List<StockReceipt>,
    latest: LandedCostEvent?,
    removing: Boolean,
    dismiss: () -> Unit,
    saved: () -> Unit,
) = key(expense.id, latest?.id, removing) {
    var method by remember { mutableStateOf(latest?.method ?: LandedCostMethod.PURCHASE_VALUE) }
    var selected by remember { mutableStateOf(latest?.shares.orEmpty().map { it.receiptId }.toSet()) }
    var filter by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<LandedCostReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val operationId = remember(preview, reason) { UUID.randomUUID().toString() }
    val receiptById = remember(receipts) { receipts.associateBy { it.id } }
    val review = preview
    val title = when {
        removing -> "Remove landed cost allocation"
        review != null -> "Review landed cost"
        else -> "Allocate landed cost"
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValueRow(expense.description, money(expense.amountMinor), emphasized = true)
                if (review == null && !removing) {
                    Text("Choose receipts for the goods this expense helped bring in.")
                    for (basis in LandedCostMethod.entries) {
                        Row(Modifier.fillMaxWidth().toggleable(
                            value = method == basis, role = Role.RadioButton,
                            onValueChange = { if (it) { method = basis; error = null } },
                        ).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = method == basis, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(basis.label)
                                Text(if (basis == LandedCostMethod.PURCHASE_VALUE) "Higher-value purchases get a larger share."
                                else "Each receipt gets the same share before rounding.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    PosTextField(filter, { filter = it }, "Find a purchase receipt", Modifier.fillMaxWidth())
                    Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall)
                    val matches = receipts.filter {
                        it.productName.contains(filter.trim(), ignoreCase = true) ||
                            it.supplierName.contains(filter.trim(), ignoreCase = true) ||
                            it.id.contains(filter.trim(), ignoreCase = true)
                    }
                    if (matches.isEmpty()) Text("No matching receipts.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                        items(matches, key = { it.id }) { receipt ->
                            Row(Modifier.fillMaxWidth().toggleable(
                                receipt.id in selected, role = Role.Checkbox,
                                onValueChange = { checked ->
                                    selected = if (checked) selected + receipt.id else selected - receipt.id
                                    error = null
                                },
                            ).heightIn(min = 52.dp).padding(vertical = 6.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Checkbox(receipt.id in selected, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(receipt.productName)
                                    Text(purchaseReceiptLabel(receipt), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(money(receipt.totalCostMinor), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (removing) {
                    Text("Remove the allocated delivery cost from these purchase receipts.")
                    for (share in latest?.shares.orEmpty()) {
                        LabelValueRow(purchaseReceiptLabel(receiptById.getValue(share.receiptId)), money(share.amountMinor))
                    }
                } else if (review != null) {
                    Text(review.method?.label.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    for (share in review.shares) {
                        val receipt = receiptById.getValue(share.receiptId)
                        LabelValueRow(receipt.productName, money(share.amountMinor))
                        Text(purchaseReceiptLabel(receipt), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    LabelValueRow("Total allocated", money(expense.amountMinor), emphasized = true)
                    Text("The expense was already recorded. Saving links its cost to these purchases.", style = MaterialTheme.typography.bodySmall)
                }
                if (review != null || removing) {
                    PosTextField(reason, { reason = it; error = null },
                        if (removing) "Reason for removal" else "Reason for allocation", Modifier.fillMaxWidth())
                }
                error?.let { InlineNotice("Could not finish", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton(when {
                removing -> "Remove allocation"
                review == null -> "Review allocation"
                else -> "Save allocation"
            }, {
                runCatching {
                    if (removing) {
                        val removal = repo.reviewLandedCost(expense.tenantId, expense.id, latest?.version ?: 0L, null, emptySet())
                        repo.saveLandedCost(expense.tenantId, removal, reason, operationId)
                        saved()
                    } else if (review == null) {
                        preview = repo.reviewLandedCost(expense.tenantId, expense.id, latest?.version ?: 0L, method, selected)
                    } else {
                        repo.saveLandedCost(expense.tenantId, review, reason, operationId)
                        saved()
                    }
                }.onFailure { error = it.message ?: "Review the expense and try again." }
            }, enabled = (removing || selected.isNotEmpty()) &&
                (review == null && !removing || reason.trim().length in 3..250))
        },
        dismissButton = {
            TextButton({
                if (review == null) dismiss() else { preview = null; error = null }
            }) { Text(if (review == null) "Cancel" else "Edit review") }
        },
    )
}

@Composable
private fun PurchaseReceiptHeading(receipt: StockReceipt) {
    Text(receipt.productName, fontWeight = FontWeight.Medium)
    Text(purchaseReceiptLabel(receipt), style = MaterialTheme.typography.bodySmall)
}

private fun purchaseReceiptLabel(receipt: StockReceipt): String =
    "${receipt.supplierName.ifBlank { "Supplier not recorded" }} · ${receipt.quantityLabel} ${receipt.unit} · Receipt ${receipt.id.takeLast(8).uppercase()}"
