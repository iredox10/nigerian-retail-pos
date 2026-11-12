package pos.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
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

/** Every read and write remains scoped to the selected business, including editor state. */
@Composable
fun StockScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit, onImport: (() -> Unit)? = null) {
    key(tenantId) { TenantStockScreen(repo, tenantId, onChanged, onImport) }
}

@Composable
private fun TenantStockScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit, onImport: (() -> Unit)?) {
    var revision by remember { mutableStateOf(0) }
    val allProducts = remember(tenantId, revision) { repo.products(tenantId, includeArchived = true) }
    val products = allProducts.filterNot { it.archived }
    val receipts = remember(tenantId, revision) { repo.stockReceipts(tenantId) }
    var search by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<String?>(null) }
    var selectedProductId by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    fun refresh(message: String) { revision++; savedMessage = message; editor = null; onChanged() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 900.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (compact) SectionHeader("Stock", "Know what is available and record what came in.")
                val toolbarScroll = rememberScrollState()
                Row(if (compact) Modifier.fillMaxWidth().horizontalScroll(toolbarScroll) else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!compact) SectionHeader("Stock", "Know what is available and record what came in.", Modifier.weight(1f))
                    if (editor == null) {
                        if(onImport!=null) NeutralButton("Import CSV",onImport)
                        NeutralButton("Add item", { savedMessage = null; editor = "item" })
                        PosButton("Add stock", {
                            selectedProductId = null; savedMessage = null; editor = "receive"
                        }, enabled = products.isNotEmpty())
                    } else NeutralButton("Back to stock", { editor = null })
                }
            }
        }
        when (editor) {
            "item" -> AddItemForm { name, unit, price, quantity, barcode ->
                repo.addProduct(tenantId, name, unit, price, quantity.toDouble(), barcode)
                refresh("Item saved on this computer")
            }
            "receive" -> ReceiveStockForm(repo, tenantId, products, selectedProductId) { product, quantity, cost, paid, supplier, operation, buyingUnit ->
                repo.receiveStock(tenantId, product.id, quantity, cost, paid, supplier, operation,
                    purchaseUnitId = buyingUnit?.id, expectedBaseUnitsPerUnit = buyingUnit?.baseUnitsPerUnit ?: 1)
                refresh("Stock received and saved on this computer")
            }
            "manage" -> {
                val selected = allProducts.find { it.id == selectedProductId }
                if (selected == null) EmptyState("Item is no longer available", "Return to stock and select the item again.")
                else ProductManagementPanel(repo, tenantId, selected, ::refresh) {
                    selectedProductId = selected.id; editor = "receive"
                }
            }
            else -> {
                savedMessage?.let { InlineNotice(it, tone = NoticeTone.Success, modifier = Modifier.fillMaxWidth()) }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !history && !archived, onClick = { history = false; archived = false }, label = { Text("Items in stock") })
                    FilterChip(selected = !history && archived, onClick = { history = false; archived = true }, label = { Text("Archived items") })
                    FilterChip(selected = history, onClick = { history = true }, label = { Text("Stock received (${receipts.size})") })
                }
                PosTextField(search, { search = it }, if (history) "Search stock received" else "Search for an item",
                    Modifier.fillMaxWidth(), hint = if (history) "Item name or supplier" else "Item name, selling unit or barcode")
                if (history) {
                    val matching = receipts.filter {
                        it.productName.contains(search, true) || it.supplierName.contains(search, true)
                    }
                    Text("These are individual stock receipts, not a complete supplier account statement.",
                        style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    if (matching.isEmpty()) {
                        EmptyState(if (receipts.isEmpty()) "No stock receipts yet" else "No matching stock receipts",
                            if (receipts.isEmpty()) "Use Add stock when goods arrive. Each receipt keeps the quantities, buying cost and cash paid."
                            else "Try another item name or supplier, or clear the search.")
                    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(matching, key = { it.id }) { receipt ->
                            Surface(shape = MaterialTheme.shapes.medium, color = PosPalette.Surface) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(receipt.productName, fontWeight = FontWeight.SemiBold)
                                            Text("${receipt.quantity} ${receipt.unit} · ${receipt.supplierName}", color = PosPalette.Secondary)
                                            if (receipt.purchaseUnitId != null || receipt.baseUnitsPerUnit != 1)
                                                Text("Added ${receipt.baseQuantity} ${receipt.baseUnit} to stock · 1 ${receipt.unit} = ${receipt.baseUnitsPerUnit} ${receipt.baseUnit}", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                        }
                                        Text(money(receipt.totalCostMinor), fontWeight = FontWeight.SemiBold)
                                    }
                                    Text("Buying price ${money(receipt.unitCostMinor)} / ${receipt.unit} · Cash paid ${money(receipt.paidCashMinor)}",
                                        style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                    if (receipt.unpaidMinor > 0) StatusPill("Unpaid at receipt: ${money(receipt.unpaidMinor)}", NoticeTone.Warning)
                                    Text("${stockReceiptDate(receipt.createdAt)} · Receipt ${receipt.id.take(8)}",
                                        style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                                }
                            }
                        }
                    }
                } else {
                    val visible = allProducts.filter { it.archived == archived }
                    val matching = visible.filter { it.name.contains(search, true) || it.unit.contains(search, true) || it.barcode?.contains(search, true) == true }
                    if (archived) Text("Archived items stay in saved sales and stock history. Restore an item to sell or receive it again.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    if (matching.isEmpty()) {
                        EmptyState(if (visible.isEmpty()) (if(archived) "No archived items" else "Add your first item") else "No matching items",
                            if (visible.isEmpty()) (if(archived) "Items you archive will appear here." else "Give it a name, selling unit, selling price and a counted opening quantity.")
                            else "Try another name, selling unit or barcode, or clear the search.")
                    } else BoxWithConstraints(Modifier.weight(1f)) {
                        val compact = maxWidth < 900.dp
                        Column(Modifier.fillMaxSize()) {
                            if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("Item", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                Text("Available", Modifier.width(130.dp), style = MaterialTheme.typography.labelMedium)
                                Text("Selling price", Modifier.width(170.dp), style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(230.dp))
                            }
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                items(matching, key = { it.id }) { product ->
                                    Surface(color = PosPalette.Surface) {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(product.name, fontWeight = FontWeight.Medium)
                                                    product.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary) }
                                                }
                                                if (!compact) {
                                                    Text("${stockQuantity(product.stock)} ${product.unit}", Modifier.width(130.dp))
                                                    Text("${money(product.priceMinor)} / ${product.unit}", Modifier.width(170.dp))
                                                    NeutralButton("Manage", {
                                                        selectedProductId = product.id; savedMessage = null; editor = "manage"
                                                    }, Modifier.width(100.dp))
                                                    NeutralButton("Add stock", {
                                                        selectedProductId = product.id; savedMessage = null; editor = "receive"
                                                    }, Modifier.width(110.dp), enabled = !product.archived)
                                                }
                                            }
                                            if (compact) {
                                                Text("${stockQuantity(product.stock)} ${product.unit} available · ${money(product.priceMinor)} / ${product.unit}", color = PosPalette.Secondary)
                                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    NeutralButton("Manage", { selectedProductId = product.id; savedMessage = null; editor = "manage" })
                                                    NeutralButton("Add stock", { selectedProductId = product.id; savedMessage = null; editor = "receive" }, enabled = !product.archived)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddItemForm(save: (String, String, Long, Int, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Add item", "Set the selling details. Opening quantity is the stock you have counted, not a purchase.")
            Column(Modifier.widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PosTextField(name, { name = it }, "Item name", hint = "For example, Malt drink 330 ml")
                PosTextField(unit, { unit = it }, "Selling unit", hint = "For example, bottle, pack or piece")
                PosTextField(price, { price = it }, "Selling price per unit (₦)", hint = "Enter an amount, for example 500.00")
                PosTextField(barcode, { barcode = it }, "Barcode (optional)", hint = "Scan or type the code on the item")
                PosTextField(quantity, { quantity = it }, "Counted opening quantity", hint = "Whole units; enter 0 only if you have none")
                Text("Stock and checkout use this selling unit. After saving, open Manage → Buying units to define cartons or packs for receiving stock. Fractional quantities and selling by another unit are not available yet.",
                    style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                error?.let { InlineNotice("Item not saved", it, NoticeTone.Danger, Modifier.fillMaxWidth()) }
                PosButton(if (saving) "Saving item…" else "Save item", {
                    saving = true
                    try {
                        require(name.isNotBlank()) { "Enter the item name." }
                        require(unit.isNotBlank()) { "Enter the selling unit." }
                        val amount = parseStockMoney(price, "selling price")
                        val count = quantity.trim().toIntOrNull()
                        require(count != null && count >= 0) { "Enter the counted opening quantity as a whole number, or 0 if you have none." }
                        save(name.trim(), unit.trim(), amount, count, barcode.trim().ifBlank { null })
                    } catch (e: Exception) { error = e.message ?: "The item could not be saved. Your entries are still here." }
                    finally { saving = false }
                }, enabled = !saving)
            }
        }
    }
}

@Composable
private fun ReceiveStockForm(
    repo: PosRepository,
    tenantId: String,
    products: List<Product>,
    initialProductId: String?,
    save: (Product, Int, Long, Long, String, String, PurchaseUnit?) -> Unit,
) {
    var selectedId by remember { mutableStateOf(initialProductId) }
    var choosing by remember { mutableStateOf(initialProductId == null) }
    var itemSearch by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var buyingUnitId by remember { mutableStateOf<String?>(null) }
    val product = products.find { it.id == selectedId }
    val buyingUnitsResult = remember(tenantId, selectedId) { runCatching {
        if (product == null) emptyList() else repo.purchaseUnits(tenantId, product.id)
    } }
    val buyingUnits = buyingUnitsResult.getOrDefault(emptyList())
    val buyingUnit = buyingUnits.find { it.id == buyingUnitId }
    val selectedUnitMissing = buyingUnitId != null && buyingUnit == null
    val unitName = buyingUnit?.name ?: product?.unit ?: "unit"
    val factor = buyingUnit?.baseUnitsPerUnit ?: 1
    val operationId = remember(selectedId, buyingUnitId, quantity, cost, paid, supplier) { UUID.randomUUID().toString() }
    val count = quantity.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()?.takeIf { it > 0 }
    val addedBaseQuantity = if (count != null) runCatching { Math.multiplyExact(count.toLong(), factor.toLong()) }.getOrNull() else null
    val conversionSupported = addedBaseQuantity == null || addedBaseQuantity <= 9_007_199_254_740_991L
    val total = runCatching {
        val count = quantity.trim().toIntOrNull() ?: return@runCatching null
        if (count <= 0) return@runCatching null
        Math.multiplyExact(parseStockMoney(cost, "buying price"), count.toLong())
    }.getOrNull()
    val cash = runCatching { parseStockMoney(paid, "cash paid") }.getOrNull()
    Surface(color = PosPalette.Surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("What came in?", "Record goods received, their buying cost and what you paid the supplier.")
            Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (product != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(product.name, fontWeight = FontWeight.SemiBold)
                            Text("Selling unit: ${product.unit}", color = PosPalette.Secondary)
                        }
                        NeutralButton(if (choosing) "Keep item" else "Change item", { choosing = !choosing })
                    }
                }
                if (choosing) {
                    PosTextField(itemSearch, { itemSearch = it }, "Choose an item", hint = "Search by name")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(products.filter { it.name.contains(itemSearch, true) }, key = { it.id }) { choice ->
                            OutlinedButton(onClick = { selectedId = choice.id; choosing = false; buyingUnitId = null; cost = ""; quantity = ""; paid = ""; error = null },
                                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, PosPalette.Border)) {
                                Text("${choice.name} · ${choice.unit}", Modifier.fillMaxWidth(), color = PosPalette.Ink)
                            }
                        }
                    }
                    if (products.none { it.name.contains(itemSearch, true) }) Text("No matching items. Try another name.", color = PosPalette.Secondary)
                }
                if (product != null) {
                    Text("How did these goods arrive?", style = MaterialTheme.typography.titleMedium)
                    Text("Choose the buying unit before entering quantities and cost. Stock and checkout remain in ${product.unit}.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(buyingUnitId == null, {
                            if (buyingUnitId != null) { buyingUnitId = null; quantity = ""; cost = ""; paid = ""; error = null }
                        }, label = { Text(product.unit) })
                        buyingUnits.forEach { choice ->
                            FilterChip(buyingUnitId == choice.id, {
                                if (buyingUnitId != choice.id) { buyingUnitId = choice.id; quantity = ""; cost = ""; paid = ""; error = null }
                            }, label = { Text("${choice.name} · ${choice.baseUnitsPerUnit} ${product.unit}") })
                        }
                    }
                    buyingUnit?.let { Text("1 ${it.name} contains ${it.baseUnitsPerUnit} ${product.unit}.", style = MaterialTheme.typography.bodyMedium) }
                    if (buyingUnits.isEmpty() && buyingUnitsResult.isSuccess) Text("Need a carton or pack? Return to Stock → Manage → Buying units to define what it contains.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                }
                buyingUnitsResult.exceptionOrNull()?.let { InlineNotice("Buying units could not be loaded", it.message ?: "Return to Stock and open this item again.", NoticeTone.Danger) }
                if (selectedUnitMissing) InlineNotice("Choose the buying unit again", "The selected buying unit is no longer available. No stock has been recorded.", NoticeTone.Danger)
                PosTextField(quantity, { quantity = it; error = null }, "Quantity received ($unitName)", hint = "Whole units greater than 0")
                PosTextField(cost, { cost = it; error = null }, "Buying price per $unitName (₦)", hint = "Enter the supplier's price for one $unitName")
                if (product != null && addedBaseQuantity != null && !selectedUnitMissing) {
                    Surface(color = PosPalette.SurfaceSubtle, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Stock to add", style = MaterialTheme.typography.titleMedium)
                            Text("$count $unitName × $factor ${product.unit} = $addedBaseQuantity ${product.unit}")
                            Text("The receipt keeps this unit and conversion. The selling unit stays ${product.unit}.", style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                        }
                    }
                }
                if (!conversionSupported) InlineNotice("Quantity is too large", "Reduce the received quantity. Its conversion exceeds the supported stock range.", NoticeTone.Danger)
                PosTextField(supplier, { supplier = it }, "Who supplied it?", hint = "Supplier name")
                PosTextField(paid, { paid = it }, "Cash paid now (₦)", hint = "Enter 0 only if fully on supplier credit")
                Text("Cash paid is taken from the open cash shift. Open Today's cash first if you are paying cash. Other payment methods are not supported in this form yet.",
                    style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                HorizontalDivider(color = PosPalette.Border)
                LabelValueRow("Total buying cost", total?.let(::money) ?: "Not calculated", emphasized = true)
                LabelValueRow("Cash paid", cash?.let(::money) ?: "Not recorded")
                LabelValueRow("Supplier still owed", if (total != null && cash != null && cash <= total) money(total - cash) else "Not calculated")
                Text("Any amount still owed is kept on this stock receipt. This is not a complete supplier account ledger.",
                    style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
                error?.let { InlineNotice("Stock not saved", it, NoticeTone.Danger, Modifier.fillMaxWidth()) }
                PosButton(if (saving) "Saving stock…" else "Save stock received", {
                    saving = true
                    try {
                        val chosen = requireNotNull(product) { "Choose which item came in." }
                        val count = quantity.trim().toIntOrNull()
                        require(count != null && count > 0) { "Enter the quantity received as a whole number greater than zero." }
                        val unitCost = parseStockMoney(cost, "buying price")
                        val cashPaid = parseStockMoney(paid, "cash paid")
                        val buyingTotal = Math.multiplyExact(unitCost, count.toLong())
                        require(cashPaid <= buyingTotal) { "Cash paid cannot exceed the total buying cost." }
                        require(supplier.isNotBlank()) { "Enter who supplied these goods." }
                        require(buyingUnitsResult.isSuccess && !selectedUnitMissing && conversionSupported) { "Choose an available buying unit before saving." }
                        save(chosen, count, unitCost, cashPaid, supplier.trim(), operationId, buyingUnit)
                    } catch (e: Exception) { error = e.message ?: "Stock could not be saved. Your entries are still here." }
                    finally { saving = false }
                }, enabled = !saving && product != null && buyingUnitsResult.isSuccess && !selectedUnitMissing && conversionSupported)
            }
        }
    }
}

private fun parseStockMoney(text: String, label: String): Long {
    require(text.isNotBlank()) { "Enter the $label; enter 0 only if the amount is zero." }
    return parseNaira(text) ?: throw IllegalArgumentException("Enter the $label in naira with at most two decimal places.")
}

private fun stockQuantity(quantity: Double): String = BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

private fun stockReceiptDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm 'WAT'").withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(iso))
}.getOrDefault(iso)
