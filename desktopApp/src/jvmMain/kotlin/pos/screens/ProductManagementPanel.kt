package pos.screens

import pos.domain.*

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.ProductMetadata
import pos.domain.metadata
import pos.domain.Product
import pos.domain.PurchaseUnit
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
        val count = parseQuantity(counted,product.quantityPrecision)?.scaledUnits?.takeIf {it<=Int.MAX_VALUE}?.toInt()
        val difference = count?.let {projectedQuantity(it.toLong(),product.quantityPrecision)-product.stock}
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionHeader(product.name, "Selling unit: ${product.unit} · ${product.stockLabel} available")
            if (product.archived) InlineNotice("Archived item", "Saved sales and history are kept. Restore this item before selling, receiving or counting it.", NoticeTone.Warning)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Details", "Photo", "Catalogue", "Selling units", "Buying units", "Labels", "Count stock", "History").forEach { label ->
                    FilterChip(selected = tab == label, onClick = { tab = label; error = null }, label = { Text(label) })
                }
            }
            error?.let { InlineNotice("Changes not saved", it, NoticeTone.Danger) }
            when (tab) {
                "Details" -> Surface(shape = MaterialTheme.shapes.medium, color = PosPalette.Surface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PosTextField(name, { name = it; error = null }, "Item name", enabled = !product.archived)
                            Text("Base stock unit: ${product.unit}. Use Selling units to set a carton or pack price and barcode. Use Buying units for packaging received from suppliers. Both use this same stock.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
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
                        ReorderSettingsPanel(repo, tenantId, product, onChanged)
                        HorizontalDivider(color = PosPalette.Border)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!product.archived) NeutralButton("Add stock", onReceiveStock)
                            NeutralButton(if (product.archived) "Restore item" else "Archive item", { confirmArchive = true; error = null })
                        }
                        Text(if (product.archived) "Restoring makes this item available in Sell and Add stock again."
                            else "Archive goods you no longer sell. They leave the active catalogue; their recorded stock and history stay saved.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    }
                }
                "Photo" -> LocalImageEditor(repo,tenantId,"product",product.id,"Product photo","Use a clear photo to help identify this item in Stock and Sell.",enabled=!product.archived,onChanged={onChanged("Product photo saved on this computer")})
                "Catalogue" -> ProductCataloguePanel(repo, tenantId, product, onChanged)
                "Selling units" -> SellingUnitsPanel(repo, tenantId, product)
                "Buying units" -> BuyingUnitsPanel(repo, tenantId, product)
                "Labels" -> BarcodeLabelsPanel(repo, tenantId, product)
                "Count stock" -> Surface(shape = MaterialTheme.shapes.medium, color = PosPalette.Surface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeader("What did you count?", "For a stock correction, loss or recount. Use Add stock for goods received from a supplier.")
                        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            LabelValueRow("Recorded quantity now", "${product.stockLabel} ${product.unit}")
                            PosTextField(counted, { counted = it; error = null }, "Actual quantity counted (${product.unit})", hint = "Up to ${product.quantityPrecision} decimal places, including 0", enabled = !product.archived)
                            PosTextField(reason, { reason = it; error = null }, "Why is the quantity different?", hint = "For example, 2 damaged bottles removed", enabled = !product.archived, singleLine = false)
                            if (difference != null) {
                                LabelValueRow("New recorded quantity", "${quantityLabel(count.toLong(),product.quantityPrecision)} ${product.unit}", emphasized = true)
                                LabelValueRow("Correction", when {
                                    difference > 0 -> "Add ${quantityText(difference)} ${product.unit}"
                                    difference < 0 -> "Remove ${quantityText(-difference)} ${product.unit}"
                                    else -> "No change"
                                })
                            }
                            Text("Saving replaces the recorded quantity with your count and keeps the previous quantity, difference and reason in history. It does not record a purchase or a cash movement.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                            PosButton("Save counted stock", {
                                try {
                                    require(count != null) { "Enter a non-negative quantity with up to ${product.quantityPrecision} decimal places." }
                                    require(reason.isNotBlank()) { "Explain why the counted quantity differs." }
                                    repo.adjustStock(tenantId, product.id, count, reason.trim(), countOperation, expectedStock = product.stock)
                                    onChanged("Counted stock and correction history saved on this computer")
                                } catch (e: Exception) { error = e.message ?: "The count could not be saved. Your entries are still here." }
                            }, enabled = !product.archived && count != null && difference != 0.0 && reason.isNotBlank())
                        }
                    }
                }
                else -> {
                    InventoryMovementHistory(repo, tenantId, product)
                    val history = remember(tenantId, product.id) { runCatching { repo.stockAdjustments(tenantId, product.id) to repo.priceHistory(tenantId, product.id) } }
                    history.exceptionOrNull()?.let { InlineNotice("History could not be loaded", it.message ?: "Return to stock and try again.", NoticeTone.Danger) }
                    history.getOrNull()?.let { (counts, prices) ->
                        SectionHeader("Stock count details", "Recorded quantities and reasons behind counted corrections")
                        if (counts.isEmpty()) Text("No stock corrections recorded for this item.", color = PosPalette.Secondary)
                        counts.forEach { adjustment ->
                            Surface(color = PosPalette.Surface) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${quantityText(adjustment.previousStock)} → ${adjustment.countedStockLabel} ${adjustment.unit}", style = MaterialTheme.typography.titleMedium)
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

/** Buying definitions are prospective and immutable; completed receipts keep their own snapshot. */
@Composable
private fun BuyingUnitsPanel(repo: PosRepository, tenantId: String, product: Product) {
    var revision by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var contains by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<PurchaseUnit?>(null) }
    val operationId = remember(name, contains) { UUID.randomUUID().toString() }
    val factor = contains.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()?.takeIf { it > 0 }
    val result = remember(tenantId, product.id, revision) { runCatching { repo.purchaseUnits(tenantId, product.id, includeArchived = true) } }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Buying units", "Receive cartons or packs; keep selling and counting in ${product.unit}")
        Text("Define what one buying unit contains. For example, 1 carton = 24 bottle. This does not change existing stock or the selling price. To sell cartons at checkout, configure them separately under Selling units.", style = MaterialTheme.typography.bodyMedium)
        result.exceptionOrNull()?.let { InlineNotice("Buying units could not be loaded", it.message ?: "Refresh to try again.", NoticeTone.Danger) }
        error?.let { InlineNotice("Buying unit not saved", it, NoticeTone.Danger) }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        NeutralButton("Refresh buying units", { revision++; error = null; message = null })
        result.getOrNull()?.let { units ->
            if (units.isEmpty()) Text("No buying units yet. You can still receive ${product.unit} directly.", color = PosPalette.Secondary)
            units.forEach { unit ->
                Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}", style = MaterialTheme.typography.titleMedium)
                        if (unit.archived) StatusPill("Archived buying unit", NoticeTone.Warning)
                        if (!unit.archived && product.stock % 1.0 == 0.0) {
                            val baseCount = product.stock.toLong()
                            val wholePacks = baseCount / unit.baseUnitsPerUnit
                            val loose = baseCount % unit.baseUnitsPerUnit
                            Text("Same recorded stock: $wholePacks ${unit.name} + $loose ${unit.baseUnit}. This is another view of ${product.stockLabel} ${product.unit}, not extra stock.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                        }
                        Text(if (unit.archived) "Unavailable for new stock receipts. Earlier receipts keep their saved conversion."
                            else "Available in Add stock. Enter buying prices per ${unit.name}.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                        NeutralButton(if (unit.archived) "Restore buying unit" else "Archive buying unit", { confirming = unit; error = null; message = null }, enabled = !product.archived)
                    }
                }
            }
        }
        Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("Add a buying unit", "Use the quantity written on this product's packaging; container sizes are not assumed")
                Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PosTextField(name, { name = it; error = null }, "Buying unit name", hint = "For example, carton of 24", enabled = !product.archived)
                    PosTextField(contains, { contains = it; error = null }, "How many ${product.unit} in one buying unit?", hint = "Whole units greater than 0", enabled = !product.archived)
                    if (name.isNotBlank() && factor != null) LabelValueRow("Conversion to save", "1 ${name.trim()} = $factor ${product.unit}", emphasized = true)
                    Text("Names and quantities cannot be edited after saving. If packaging changes, archive the old unit and add a differently named one. Existing receipts keep their original conversion.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    PosButton("Save buying unit", {
                        try {
                            require(name.isNotBlank()) { "Enter a name for the buying unit." }
                            require(factor != null) { "Enter a whole number from 1 to ${Int.MAX_VALUE}." }
                            repo.addPurchaseUnit(tenantId, product.id, name.trim(), factor, operationId)
                            revision++; name = ""; contains = ""; error = null; message = "Buying unit saved on this computer. Choose it when adding stock."
                        } catch (e: Exception) { error = e.message ?: "The buying unit could not be saved. Your entries are still here." }
                    }, enabled = !product.archived && result.isSuccess)
                }
            }
        }
    }
    confirming?.let { unit ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text(if (unit.archived) "Restore this buying unit?" else "Archive this buying unit?") },
            text = { Text("1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}. " +
                if (unit.archived) "Restoring makes this conversion available for new stock receipts again."
                else "Archiving removes it from new stock receipts. Existing stock and earlier receipt conversions stay unchanged.") },
            confirmButton = { PosButton(if (unit.archived) "Restore buying unit" else "Archive buying unit", {
                try {
                    repo.archivePurchaseUnit(tenantId, unit.id, !unit.archived)
                    revision++; error = null; message = if (unit.archived) "Buying unit restored" else "Buying unit archived; earlier receipts are preserved"
                } catch (e: Exception) { error = e.message ?: "The buying unit could not be changed." }
                confirming = null
            }) }, dismissButton = { TextButton({ confirming = null }) { Text("Cancel") } })
    }
}

/** Optional catalogue organization stays outside the checkout payment flow. */
@Composable
private fun ProductCataloguePanel(repo: PosRepository, tenantId: String, product: Product, onChanged: (String) -> Unit) {
    var sku by remember { mutableStateOf(product.sku.orEmpty()) }
    var category by remember { mutableStateOf(product.category.orEmpty()) }
    var brand by remember { mutableStateOf(product.brand.orEmpty()) }
    var aliases by remember { mutableStateOf(product.aliases.joinToString("\n")) }
    var favourite by remember { mutableStateOf(product.favourite) }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(sku, category, brand, aliases, favourite) { UUID.randomUUID().toString() }
    val history = remember(tenantId, product.id) { runCatching { repo.productMetadataChanges(tenantId, product.id).asReversed() } }
    Surface(shape = MaterialTheme.shapes.medium, color = PosPalette.Surface) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SectionHeader("Catalogue details", "Help staff find this item by familiar names")
            Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                error?.let { InlineNotice("Catalogue details not saved", it, NoticeTone.Danger) }
                PosTextField(sku, { sku=it; error=null }, "SKU / item code (optional)", enabled=!product.archived, hint="Unique in this shop, including archived items")
                PosTextField(category, { category=it; error=null }, "Category (optional)", enabled=!product.archived, hint="For example, Drinks")
                PosTextField(brand, { brand=it; error=null }, "Brand (optional)", enabled=!product.archived)
                PosTextField(aliases, { aliases=it; error=null }, "Other search names (optional)", enabled=!product.archived, singleLine=false, hint="One familiar or local name per line; up to 20 names")
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(favourite, { favourite=it; error=null }, enabled=!product.archived)
                    Text("Show in Favourites at checkout")
                }
                Text("These details organize the catalogue. Prices, quantities, units and saved receipts stay unchanged. Clearing an optional field removes it from future catalogue searches.", style=MaterialTheme.typography.bodySmall, color=PosPalette.Secondary)
                PosButton("Save catalogue details", {
                    try {
                        val next=ProductMetadata(sku.trim().ifEmpty {null},category.trim().ifEmpty {null},brand.trim().ifEmpty {null},aliases.lines().map {it.trim()}.filter {it.isNotEmpty()},favourite)
                        repo.updateProductMetadata(tenantId,product.id,next,operation,product.metadata())
                        onChanged("Catalogue details saved on this computer")
                    } catch (e:Exception) {error=e.message ?: "Catalogue details could not be saved. Your entries are still here."}
                }, enabled=!product.archived)
            }
            HorizontalDivider(color=PosPalette.Border)
            SectionHeader("Catalogue change history", "Original details are kept for each saved change")
            history.exceptionOrNull()?.let { InlineNotice("History could not be loaded",it.message ?: "Reopen this item and try again.",NoticeTone.Danger) }
            history.getOrNull()?.let { changes ->
                if(changes.isEmpty()) Text("No catalogue changes recorded for this item.",color=PosPalette.Secondary)
                changes.forEach { change ->
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(managementDate(change.createdAt),style=MaterialTheme.typography.titleMedium)
                        fun summary(value:ProductMetadata):String = "SKU: ${value.sku ?: "None"} · Category: ${value.category ?: "None"} · Brand: ${value.brand ?: "None"} · Search names: ${value.aliases.joinToString("; ").ifEmpty {"None"}} · Favourite: ${if(value.favourite) "Yes" else "No"}"
                        Text("Before: ${summary(change.previous)}",style=MaterialTheme.typography.bodySmall)
                        Text("After: ${summary(change.current)}",style=MaterialTheme.typography.bodySmall)
                        HorizontalDivider(color=PosPalette.Border)
                    }
                }
            }
        }
    }
}
