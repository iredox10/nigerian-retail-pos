package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.PosRepository
import pos.data.purchaseDiscrepancyReceipts
import pos.data.recordPurchaseDiscrepancy
import pos.data.resolvePurchaseDiscrepancy
import pos.domain.PurchaseDiscrepancy
import pos.domain.PurchaseDiscrepancyHistory
import pos.domain.PurchaseDiscrepancyKind
import pos.domain.PurchaseOrder
import pos.domain.quantityCount
import pos.domain.quantityLabel
import pos.output.receiptDate
import pos.ui.components.*

private val PurchaseDiscrepancyKind.label: String
    get() = when (this) {
        PurchaseDiscrepancyKind.SHORTAGE -> "Shortage"
        PurchaseDiscrepancyKind.DAMAGED -> "Damaged goods"
        PurchaseDiscrepancyKind.EXTRA -> "Unexpected goods"
    }

@Composable
internal fun PurchaseDiscrepancyCard(history: PurchaseDiscrepancyHistory, onResolve: () -> Unit) {
    val report = history.report
    val resolution = history.resolution
    Surface(shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${report.kind.label} · ${report.productName}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                StatusPill(if (resolution == null) "Open" else "Resolved", if (resolution == null) NoticeTone.Warning else NoticeTone.Success)
            }
            LabelValueRow("Quantity reported", "${report.quantityLabel} ${report.buyingUnit}")
            report.remainingAtReport?.let {
                LabelValueRow("Still due when reported", "${quantityLabel(it.toLong(), report.quantityPrecision)} ${report.buyingUnit}")
            }
            if (report.baseUnitsPerUnit > 1) {
                Text("1 ${report.buyingUnit} = ${report.baseUnitsPerUnit} ${report.baseUnit}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Reported ${receiptDate(report.createdAt)} · ${report.id.takeLast(8)}", style = MaterialTheme.typography.bodySmall)
            Text(report.reason)
            if (resolution == null) NeutralButton("Resolve discrepancy", onResolve)
            else {
                HorizontalDivider()
                Text("Resolved ${receiptDate(resolution.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text(resolution.reason)
                resolution.receiptId?.let { LabelValueRow("Linked stock receipt", it) }
            }
        }
    }
}

@Composable
internal fun ReportPurchaseDiscrepancyDialog(
    repo: PosRepository, order: PurchaseOrder, remaining: Map<String, Int>, dismiss: () -> Unit, saved: () -> Unit,
) {
    val outstanding = remaining.values.any { it > 0 }
    var kind by remember(order.id) { mutableStateOf(if (outstanding) PurchaseDiscrepancyKind.SHORTAGE else PurchaseDiscrepancyKind.EXTRA) }
    var otherItem by remember { mutableStateOf(false) }
    var itemId by remember { mutableStateOf<String?>(null) }
    var choosingItem by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(kind, itemId, quantity, reason) { UUID.randomUUID().toString() }
    val lines = order.lines.filter { kind == PurchaseDiscrepancyKind.EXTRA || remaining.getValue(it.id) > 0 }
    val catalogue = remember(order.id, otherItem) {
        runCatching { if (otherItem) repo.products(order.tenantId) else emptyList() }
    }
    val line = if (!otherItem) lines.singleOrNull { it.id == itemId } else null
    val product = if (otherItem) catalogue.getOrDefault(emptyList()).singleOrNull { it.id == itemId } else null
    val unit = line?.buyingUnit ?: product?.unit
    val precision = line?.quantityPrecision ?: product?.quantityPrecision
    val expectedRemaining = if (kind == PurchaseDiscrepancyKind.EXTRA || line == null) null else remaining.getValue(line.id)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Record delivery discrepancy") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Order ${order.id.takeLast(8)} · ${order.supplierName}", style = MaterialTheme.typography.bodySmall)
                Text("Record what was missing, damaged or unexpected. Receive accepted goods through the normal stock workflow.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PurchaseDiscrepancyKind.entries.forEach { option ->
                        FilterChip(
                            kind == option,
                            { kind = option; otherItem = false; itemId = null; quantity = ""; search = ""; error = null },
                            label = { Text(option.label) }, enabled = outstanding || option == PurchaseDiscrepancyKind.EXTRA,
                        )
                    }
                }
                if (kind == PurchaseDiscrepancyKind.EXTRA) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!otherItem, { otherItem = false; itemId = null; quantity = ""; search = "" }, label = { Text("Order item") })
                        FilterChip(otherItem, { otherItem = true; itemId = null; quantity = ""; search = "" }, label = { Text("Other catalogue item") })
                    }
                }
                catalogue.exceptionOrNull()?.let { InlineNotice("Items unavailable", it.message ?: "Reopen the order and try again.", NoticeTone.Danger) }
                val choices = if (otherItem) catalogue.getOrDefault(emptyList()).map { it.id to "${it.name} · ${it.unit}" }
                    else lines.map { it.id to "${it.productName} · ${it.buyingUnit}" }
                if (otherItem && choices.size > 8) PosTextField(search, { search = it }, "Find an item", hint = "Item name")
                Box {
                    NeutralButton(choices.firstOrNull { it.first == itemId }?.second ?: "Choose item", { choosingItem = true },
                        enabled = choices.isNotEmpty())
                    DropdownMenu(choosingItem, { choosingItem = false }, Modifier.heightIn(max = 260.dp)) {
                        choices.filter { it.second.contains(search.trim(), ignoreCase = true) }.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                itemId = id
                                choosingItem = false
                                quantity = ""
                                error = null
                            })
                        }
                    }
                }
                if (expectedRemaining != null && precision != null && unit != null) {
                    LabelValueRow("Still due", "${quantityLabel(expectedRemaining.toLong(), precision)} $unit")
                }
                if (unit != null) PosTextField(quantity, { quantity = it }, "Quantity in $unit")
                PosTextField(reason, { reason = it }, "What happened?")
                error?.let { InlineNotice("Discrepancy not saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton("Save discrepancy", {
                try {
                    val count = requireNotNull(quantityCount(quantity, requireNotNull(precision))) { "Enter a positive quantity in $unit." }
                    repo.recordPurchaseDiscrepancy(order.tenantId, order.id, order.version, kind, line?.id, product?.id,
                        count, expectedRemaining, reason, operation)
                    saved()
                } catch (failure: Exception) {
                    error = failure.message ?: "Review the discrepancy and try again."
                }
            }, enabled = unit != null && quantity.isNotBlank() && reason.trim().length in 3..250)
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ResolvePurchaseDiscrepancyDialog(
    repo: PosRepository, report: PurchaseDiscrepancy, dismiss: () -> Unit, saved: () -> Unit,
) {
    var reason by remember(report.id) { mutableStateOf("") }
    var receiptId by remember(report.id) { mutableStateOf<String?>(null) }
    var choosingReceipt by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded = remember(report.id) { runCatching { repo.purchaseDiscrepancyReceipts(report.tenantId, report.id) } }
    val choices = loaded.getOrDefault(emptyList())
    val operation = remember(report.id, reason, receiptId) { UUID.randomUUID().toString() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Resolve delivery discrepancy") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${report.kind.label} · ${report.quantityLabel} ${report.buyingUnit} · ${report.productName}",
                    style = MaterialTheme.typography.titleSmall)
                Text(report.reason)
                PosTextField(reason, { reason = it }, "How was it resolved?")
                Text("You can link goods already received under a matching stock receipt.", style = MaterialTheme.typography.bodySmall)
                if (choices.isNotEmpty()) Box {
                    NeutralButton(receiptId?.let { "Receipt ${it.takeLast(8)}" } ?: "Link stock receipt", { choosingReceipt = true })
                    DropdownMenu(choosingReceipt, { choosingReceipt = false }, Modifier.heightIn(max = 240.dp)) {
                        DropdownMenuItem(text = { Text("Skip receipt link") }, onClick = { receiptId = null; choosingReceipt = false })
                        choices.forEach { receipt ->
                            DropdownMenuItem(
                                text = { Text("Receipt ${receipt.id.takeLast(8)} · ${receipt.quantityLabel} ${receipt.unit} · ${receiptDate(receipt.createdAt)}") },
                                onClick = { receiptId = receipt.id; choosingReceipt = false },
                            )
                        }
                    }
                } else if (loaded.isSuccess) Text("No later receipt matches this supplier, item and quantity.", style = MaterialTheme.typography.bodySmall)
                loaded.exceptionOrNull()?.let { InlineNotice("Stock receipts unavailable", it.message ?: "Reopen the discrepancy.", NoticeTone.Warning) }
                error?.let { InlineNotice("Resolution not saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton("Save resolution", {
                try {
                    repo.resolvePurchaseDiscrepancy(report.tenantId, report.id, reason, receiptId, operation)
                    saved()
                } catch (failure: Exception) {
                    error = failure.message ?: "Review the resolution and try again."
                }
            }, enabled = reason.trim().length in 3..250)
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}
