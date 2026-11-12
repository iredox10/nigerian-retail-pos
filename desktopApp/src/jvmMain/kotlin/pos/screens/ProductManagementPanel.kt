package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.Product
import pos.domain.money
import pos.domain.parseNaira
import pos.ui.components.*
import pos.ui.theme.PosPalette
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A contextual editor: historical quantities, prices and sales are never edited in place. */
@Composable
internal fun ProductManagementPanel(
    repo: PosRepository,
    tenantId: String,
    product: Product,
    onChanged: (String) -> Unit,
    onReceiveStock: () -> Unit,
) {
    key(tenantId, product.id) {
        var tab by remember { mutableStateOf("Details") }
        var name by remember { mutableStateOf(product.name) }
        var price by remember { mutableStateOf(BigDecimal.valueOf(product.priceMinor, 2).toPlainString()) }
        var barcode by remember { mutableStateOf(product.barcode.orEmpty()) }
        var counted by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var confirmArchive by remember { mutableStateOf(false) }
        val editOperation = remember(name, price, barcode) { UUID.randomUUID().toString() }
        val countOperation = remember(counted, reason) { UUID.randomUUID().toString() }
        val archiveOperation = remember(product.archived) { UUID.randomUUID().toString() }
        val count = counted.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
        val difference = count?.toDouble()?.minus(product.stock)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionHeader(product.name, "Selling unit: ${product.unit} · ${quantityText(product.stock)} available")
            if (product.archived) InlineNotice("Archived item", "Saved sales and history are kept. Restore this item before selling, receiving or counting it.", NoticeTone.Warning)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Details", "Count stock", "History").forEach { label ->
                    FilterChip(selected = tab == label, onClick = { tab = label; error = null }, label = { Text(label) })
                }
            }
            error?.let { InlineNotice("Changes not saved", it, NoticeTone.Danger) }
            when (tab) {
                "Details" -> Surface(shape = MaterialTheme.shapes.medium, color = PosPalette.Surface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PosTextField(name, { name = it; error = null }, "Item name", enabled = !product.archived)
                            Text("Selling unit: ${product.unit}. To sell in another unit, create a separate item. Unit conversions are not available yet.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            PosTextField(price, { price = it; error = null }, "Selling price per ${product.unit} (₦)", enabled = !product.archived)
                            PosTextField(barcode, { barcode = it; error = null }, "Barcode (optional)", hint = "Scan or type the code; leave empty to remove it", enabled = !product.archived)
                            Text("A price change applies to future sales. Saved receipts keep the price used at the time of sale.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            PosButton("Save item changes", {
                                try {
                                    val amount = requireNotNull(parseNaira(price)) { "Enter the selling price in naira with at most two decimal places." }
                                    require(name.isNotBlank()) { "Enter the item name." }
                                    repo.updateProduct(tenantId, product.id, name.trim(), product.unit, amount, barcode.trim().ifBlank { null }, editOperation)
                                    onChanged("Item changes saved on this computer")
                                } catch (e: Exception) { error = e.message ?: "The item could not be saved. Your entries are still here." }
                            }, enabled = !product.archived && (name.trim() != product.name || parseNaira(price) != product.priceMinor || barcode.trim().ifBlank { null } != product.barcode))
                        }
                        HorizontalDivider(color = PosPalette.Border)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!product.archived) NeutralButton("Add stock", onReceiveStock)
                            NeutralButton(if (product.archived) "Restore item" else "Archive item", { confirmArchive = true; error = null })
                        }
                        Text(if (product.archived) "Restoring makes this item available in Sell and Add stock again."
                            else "Archive goods you no longer sell. They leave the active catalogue; their recorded stock and history stay saved.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    }
                }
                "Count stock" -> Surface(shape = MaterialTheme.shapes.medium, color = PosPalette.Surface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeader("What did you count?", "For a stock correction, loss or recount. Use Add stock for goods received from a supplier.")
                        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            LabelValueRow("Recorded quantity now", "${quantityText(product.stock)} ${product.unit}")
                            PosTextField(counted, { counted = it; error = null }, "Actual quantity counted (${product.unit})", hint = "Whole units, including 0", enabled = !product.archived)
                            PosTextField(reason, { reason = it; error = null }, "Why is the quantity different?", hint = "For example, 2 damaged bottles removed", enabled = !product.archived, singleLine = false)
                            if (difference != null) {
                                LabelValueRow("New recorded quantity", "$count ${product.unit}", emphasized = true)
                                LabelValueRow("Correction", when {
                                    difference > 0 -> "Add ${quantityText(difference)} ${product.unit}"
                                    difference < 0 -> "Remove ${quantityText(-difference)} ${product.unit}"
                                    else -> "No change"
                                })
                            }
                            Text("Saving replaces the recorded quantity with your count and keeps the previous quantity, difference and reason in history. It does not record a purchase or a cash movement.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            PosButton("Save counted stock", {
                                try {
                                    require(count != null) { "Enter a whole quantity from 0 to ${Int.MAX_VALUE}." }
                                    require(reason.isNotBlank()) { "Explain why the counted quantity differs." }
                                    repo.adjustStock(tenantId, product.id, count, reason.trim(), countOperation, expectedStock = product.stock)
                                    onChanged("Counted stock and correction history saved on this computer")
                                } catch (e: Exception) { error = e.message ?: "The count could not be saved. Your entries are still here." }
                            }, enabled = !product.archived && count != null && difference != 0.0 && reason.isNotBlank())
                        }
                    }
                }
                else -> {
                    val history = remember(tenantId, product.id) { runCatching { repo.stockAdjustments(tenantId, product.id) to repo.priceHistory(tenantId, product.id) } }
                    history.exceptionOrNull()?.let { InlineNotice("History could not be loaded", it.message ?: "Return to stock and try again.", NoticeTone.Danger) }
                    history.getOrNull()?.let { (counts, prices) ->
                        SectionHeader("Stock corrections", "Counted changes only. Purchases are listed under Stock received; sales and returns remain on their receipts.")
                        if (counts.isEmpty()) Text("No stock corrections recorded for this item.", color = PosPalette.Secondary)
                        counts.forEach { adjustment ->
                            Surface(color = PosPalette.Surface) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${quantityText(adjustment.previousStock)} → ${adjustment.countedStock} ${adjustment.unit}", style = MaterialTheme.typography.titleMedium)
                                    Text(adjustment.reason)
                                    Text("${managementDate(adjustment.createdAt)} · ${adjustment.productName}", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                }
                            }
                        }
                        HorizontalDivider(color = PosPalette.Border)
                        SectionHeader("Selling price changes", "Saved sale prices are unchanged")
                        if (prices.isEmpty()) Text("No selling price changes recorded for this item.", color = PosPalette.Secondary)
                        prices.forEach { change ->
                            Surface(color = PosPalette.Surface) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${money(change.previousPriceMinor)} → ${money(change.priceMinor)} / ${product.unit}", style = MaterialTheme.typography.titleMedium)
                                    Text(managementDate(change.createdAt), style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (confirmArchive) AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(if (product.archived) "Restore this item?" else "Archive this item?") },
            text = { Text(if (product.archived) "${product.name} will return to the active catalogue with its saved quantity and price."
                else "${product.name} will no longer appear in Sell or Add stock. Its stock and completed records stay saved. You can restore it from Archived items.") },
            confirmButton = { PosButton(if (product.archived) "Restore item" else "Archive item", {
                try {
                    repo.archiveProduct(tenantId, product.id, !product.archived, archiveOperation)
                    onChanged(if (product.archived) "Item restored to the active catalogue" else "Item archived; saved history is kept")
                    confirmArchive = false
                } catch (e: Exception) { error = e.message ?: "The item status could not be saved."; confirmArchive = false }
            }) },
            dismissButton = { TextButton({ confirmArchive = false }) { Text("Cancel") } },
        )
    }
}

private fun quantityText(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun managementDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm 'WAT'").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(iso))
}.getOrDefault(iso)
