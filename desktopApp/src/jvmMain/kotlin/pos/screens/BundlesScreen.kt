package pos.screens

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.output.ReceiptDesignPrinter
import pos.output.receiptDate
import pos.output.savedReceiptPdf
import pos.ui.components.*
import java.math.BigDecimal
import java.util.UUID

@Composable
fun BundlesScreen(repo: PosRepository, tenantId: String, canManage: Boolean = true, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(revision) { runCatching { repo.bundleDefinitions(tenantId) } }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BundleDefinition?>(null) }
    var selling by remember { mutableStateOf<BundleDefinition?>(null) }
    var history by remember { mutableStateOf<BundleDefinition?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Bundles", "Review the component goods and cash price before each sale.", Modifier.weight(1f))
            if (canManage) PosButton("New bundle", { selected = null; editing = true })
        }
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        loaded.exceptionOrNull()?.let { InlineNotice("Bundles unavailable", it.message.orEmpty(), NoticeTone.Danger); NeutralButton("Refresh", { revision++ }) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loaded.isSuccess && loaded.getOrThrow().isEmpty()) item { EmptyState("No bundles saved yet", "An owner or manager can define the goods and price for a bundle.") }
            items(loaded.getOrDefault(emptyList()), key = { it.id }) { bundle ->
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(bundle.terms.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            StatusPill(if (bundle.terms.active) "Active" else "Inactive")
                        }
                        Text(bundle.terms.fixedPriceMinor?.let { "${money(it)} per bundle" } ?: "Uses component prices at sale review", style = MaterialTheme.typography.titleSmall)
                        bundle.terms.components.forEach { component -> Text("${quantityLabel(component.quantity.toLong(), component.quantityPrecision)} ${component.sellingUnit} · ${component.productName}", style = MaterialTheme.typography.bodySmall) }
                        Text("Revision ${bundle.version} · ${receiptDate(bundle.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PosButton("Sell bundle", { selling = bundle }, enabled = bundle.terms.active)
                            if (canManage) {
                                NeutralButton("Edit bundle", { selected = bundle; editing = true })
                                NeutralButton("History", { history = bundle })
                            }
                        }
                    }
                }
            }
        }
    }
    if (editing) BundleEditor(repo, tenantId, selected, { editing = false }) {
        editing = false; revision++; notice = "Bundle definition saved on this computer."; onChanged()
    }
    selling?.let { bundle -> BundleCashSaleDialog(repo, tenantId, bundle, { selling = null }) { revision++; onChanged() } }
    history?.let { bundle ->
        val events = remember(bundle.id, revision) { runCatching { repo.bundleHistory(tenantId, bundle.id) } }
        AlertDialog(onDismissRequest = { history = null }, title = { Text("${bundle.terms.name} history") },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                events.exceptionOrNull()?.let { InlineNotice("History unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                events.getOrDefault(emptyList()).asReversed().forEach { event ->
                    Text("Revision ${event.version} · ${receiptDate(event.createdAt)}", style = MaterialTheme.typography.titleMedium)
                    Text(event.reason)
                    Text("${event.terms.name} · ${if (event.terms.active) "Active" else "Inactive"} · ${event.terms.fixedPriceMinor?.let(::money) ?: "Component prices"}")
                    event.terms.components.forEach { Text("${quantityLabel(it.quantity.toLong(), it.quantityPrecision)} ${it.sellingUnit} · ${it.productName}", style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                }
            } }, confirmButton = { TextButton({ history = null }) { Text("Close") } })
    }
}

@Composable
private fun BundleEditor(repo: PosRepository, tenantId: String, previous: BundleDefinition?, dismiss: () -> Unit, saved: () -> Unit) {
    val loaded = remember { runCatching { repo.products(tenantId) to repo.sellingUnits(tenantId) } }
    val products = loaded.getOrNull()?.first.orEmpty()
    val units = loaded.getOrNull()?.second.orEmpty()
    var name by remember { mutableStateOf(previous?.terms?.name.orEmpty()) }
    var active by remember { mutableStateOf(previous?.terms?.active ?: true) }
    var fixed by remember { mutableStateOf(previous?.terms?.fixedPriceMinor != null) }
    var price by remember { mutableStateOf(previous?.terms?.fixedPriceMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var productId by remember { mutableStateOf<String?>(null) }
    var unitId by remember { mutableStateOf<String?>(null) }
    var itemMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var quantity by remember(productId, unitId) { mutableStateOf("") }
    val components = remember { mutableStateListOf<BundleComponentInput>().also { list ->
        previous?.terms?.components?.forEach { list += BundleComponentInput(it.productId, it.sellingUnitId, it.quantity) }
    } }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val bundleId = remember { previous?.id ?: UUID.randomUUID().toString() }
    val operationId = remember(name, active, fixed, price, components.toList(), reason) { UUID.randomUUID().toString() }
    val item = products.firstOrNull { it.id == productId }
    val unit = units.firstOrNull { it.id == unitId && it.productId == productId }
    fun componentLabel(input: BundleComponentInput): String {
        val product = products.firstOrNull { it.id == input.productId }
        val sellingUnit = units.firstOrNull { it.id == input.sellingUnitId }
        val captured = previous?.terms?.components?.firstOrNull { it.productId == input.productId && it.sellingUnitId == input.sellingUnitId }
        val precision = if (input.sellingUnitId != null) 0 else product?.quantityPrecision ?: captured?.quantityPrecision ?: 0
        return "${quantityLabel(input.quantity.toLong(), precision)} ${sellingUnit?.name ?: product?.unit ?: captured?.sellingUnit.orEmpty()} · ${product?.name ?: captured?.productName ?: input.productId}"
    }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(560.dp).heightIn(max = 780.dp).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader(if (previous == null) "New bundle" else "Edit bundle", "Component quantities use their saved selling units.")
                loaded.exceptionOrNull()?.let { InlineNotice("Goods unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                PosTextField(name, { name = it }, "Bundle name")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }); Text("Active for sales") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(fixed, { fixed = it }); Text("Set a fixed bundle price") }
                if (fixed) PosTextField(price, { price = it }, "Price for one bundle (₦)")
                Text("A fixed price can match or reduce the component total. The final allocation must also meet the saved minimum selling prices.", style = MaterialTheme.typography.bodySmall)
                components.toList().forEach { component -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(componentLabel(component), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ components.remove(component) }) { Text("Remove") }
                } }
                Text("Add component goods", style = MaterialTheme.typography.titleMedium)
                Box {
                    NeutralButton(item?.name ?: "Choose goods", { itemMenu = true })
                    DropdownMenu(itemMenu, { itemMenu = false }) { products.forEach { product ->
                        DropdownMenuItem(text = { Text(product.name) }, onClick = { productId = product.id; unitId = null; itemMenu = false })
                    } }
                }
                if (item != null) {
                    Box {
                        NeutralButton(unit?.name ?: item.unit, { unitMenu = true })
                        DropdownMenu(unitMenu, { unitMenu = false }) {
                            DropdownMenuItem(text = { Text(item.unit) }, onClick = { unitId = null; unitMenu = false })
                            units.filter { it.productId == item.id }.forEach { pack -> DropdownMenuItem(text = { Text(pack.name) }, onClick = { unitId = pack.id; unitMenu = false }) }
                        }
                    }
                    PosTextField(quantity, { quantity = it }, "Quantity in ${unit?.name ?: item.unit}")
                    NeutralButton("Add component", {
                        try {
                            val parsed = requireNotNull(parseQuantity(quantity, if (unit == null) item.quantityPrecision else 0)) { "Enter the quantity in the chosen unit." }
                            val count = Math.toIntExact(parsed.scaledUnits)
                            require(count > 0) { "Enter a positive quantity." }
                            require(components.none { it.productId == item.id && it.sellingUnitId == unit?.id }) { "Remove this component before changing its quantity." }
                            components += BundleComponentInput(item.id, unit?.id, count)
                            productId = null; unitId = null; error = null
                        } catch (e: Exception) { error = e.message ?: "Check the component quantity." }
                    })
                }
                PosTextField(reason, { reason = it }, "Reason for this bundle revision")
                error?.let { InlineNotice("Bundle not saved", it, NoticeTone.Danger) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeutralButton("Cancel", dismiss)
                    PosButton("Save bundle", {
                        try {
                            val amount = if (fixed) requireNotNull(parseNaira(price)) { "Enter an exact bundle price." } else null
                            repo.saveBundle(tenantId, bundleId, name.trim(), active, amount, components.toList(), previous?.version ?: 0, reason.trim(), operationId)
                            saved()
                        } catch (e: Exception) { error = e.message ?: "Check the bundle details." }
                    }, enabled = loaded.isSuccess)
                }
            }
        }
    }
}

@Composable
private fun BundleCashSaleDialog(repo: PosRepository, tenantId: String, bundle: BundleDefinition, dismiss: () -> Unit, changed: () -> Unit) {
    var count by remember { mutableStateOf("1") }
    var review by remember { mutableStateOf<BundleSaleReview?>(null) }
    var cash by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Sale?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var printMessage by remember { mutableStateOf<String?>(null) }
    var printing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val operation = remember(review, cash) { UUID.randomUUID().toString() }
    Dialog(onDismissRequest = { if (!printing) dismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(540.dp).heightIn(max = 780.dp).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader(if (saved == null) bundle.terms.name else "Bundle sale saved", "Cash sale · Saved component stock and price checks")
                if (saved != null) {
                    val sale = requireNotNull(saved)
                    LabelValueRow("Sale total", money(sale.totalMinor), emphasized = true)
                    LabelValueRow("Cash given", money(requireNotNull(sale.cashGivenMinor)))
                    LabelValueRow("Change to give", money(requireNotNull(sale.changeMinor)))
                    Text("Receipt ${sale.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                    printMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    PosButton(if (printing) "Opening printer…" else "Print receipt", {
                        printing = true
                        scope.launch {
                            try {
                                val sent = withContext(Dispatchers.IO) { ReceiptDesignPrinter.print(savedReceiptPdf(repo, tenantId, sale.id)) }
                                printMessage = if (sent) "Receipt sent to the printer. Check the printer for delivery." else "Printing cancelled. You can print again from Sales."
                            } catch (e: Exception) { printMessage = e.message ?: "Printing failed. The sale remains saved." }
                            finally { printing = false }
                        }
                    }, enabled = !printing)
                    NeutralButton("Close", dismiss, enabled = !printing)
                } else {
                    val current = review
                    if (current == null) {
                        PosTextField(count, { count = it; error = null }, "Number of whole bundles")
                        bundle.terms.components.forEach { Text("${quantityLabel(it.quantity.toLong(), it.quantityPrecision)} ${it.sellingUnit} · ${it.productName}") }
                        PosButton("Review bundle sale", {
                            try { review = repo.reviewBundleSale(tenantId, bundle.id, requireNotNull(count.toIntOrNull()) { "Enter a whole number of bundles." }); error = null }
                            catch (e: Exception) { error = e.message ?: "Bundle review failed." }
                        })
                    } else {
                        Text("${current.count} bundle(s) · revision ${current.version}", style = MaterialTheme.typography.bodySmall)
                        current.lines.forEach { line ->
                            LabelValueRow("${line.quantityLabel} ${line.sellingUnit} · ${line.product.name}", money(line.totalMinor))
                        }
                        LabelValueRow("Normal component total", money(current.normalTotalMinor))
                        if (current.discount != null) LabelValueRow("Bundle saving", money(current.normalTotalMinor - current.totalMinor))
                        LabelValueRow("Cash sale total", money(current.totalMinor), emphasized = true)
                        PosTextField(cash, { cash = it; error = null }, "Cash given by customer (₦)")
                        TextButton({ cash = BigDecimal.valueOf(current.totalMinor, 2).toPlainString() }) { Text("Exact amount") }
                        parseNaira(cash)?.takeIf { it >= current.totalMinor }?.let { LabelValueRow("Change to give", money(it - current.totalMinor)) }
                        PosButton("Finish bundle sale", {
                            try {
                                val tender = requireNotNull(parseNaira(cash)) { "Enter cash given by the customer." }
                                saved = repo.completeBundleCashSale(tenantId, current, tender, operation); error = null; changed()
                            } catch (e: Exception) { error = e.message ?: "The sale was not saved. Review it again." }
                        })
                        NeutralButton("Review again", { review = null; error = null })
                    }
                    error?.let { InlineNotice("Bundle sale not saved", it, NoticeTone.Danger) }
                    TextButton(dismiss) { Text("Cancel") }
                }
            }
        }
    }
}
