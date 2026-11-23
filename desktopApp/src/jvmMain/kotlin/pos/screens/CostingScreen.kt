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
import pos.data.*
import pos.domain.*
import pos.output.CsvService
import pos.output.InventoryCostingCsv
import pos.output.receiptDate
import pos.ui.components.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable
fun CostingScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var through by remember { mutableStateOf(LocalDate.now(ZoneId.of("Africa/Lagos"))) }
    var date by remember { mutableStateOf(through.toString()) }
    var search by remember { mutableStateOf("") }
    var ledgerProduct by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<InventoryItemCost?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = remember(revision, through) { runCatching { repo.inventoryCosting(tenantId, through) } }
    val canEdit = remember(tenantId) { repo.canSetOpeningInventoryCost(tenantId) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Stock value & FIFO costs", "Purchase-basis review using saved stock sources")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            PosTextField(date, { date = it }, "Through WAT date", Modifier.weight(1f), hint = "YYYY-MM-DD")
            NeutralButton("Review date", {
                runCatching {
                    val selected = LocalDate.parse(date)
                    require(selected.toString() == date && !selected.isAfter(LocalDate.now(ZoneId.of("Africa/Lagos")))) { "Choose today or an earlier WAT date." }
                    through = selected; revision++; error = null
                }.onFailure { error = it.message }
            })
            NeutralButton("Export cost review CSV", {
                runCatching {
                    val report = loaded.getOrThrow()
                    CsvService.save(InventoryCostingCsv.review(report), "inventory-costing-${report.through}.csv")
                }.onSuccess { file -> notice = if (file == null) "CSV export cancelled" else "Cost review exported to ${file.absolutePath}" }
                    .onFailure { error = it.message }
            }, enabled = loaded.isSuccess)
        }
        InlineNotice("FIFO purchase-cost method", "Earlier lots are consumed first. Values use saved buying totals and the latest owner-declared opening cost. Historical reviews use that latest declaration. Delivery allocations remain separately recorded expenses.", NoticeTone.Info)
        loaded.exceptionOrNull()?.let { InlineNotice("Cost review unavailable", it.message.orEmpty(), NoticeTone.Danger) }
        error?.let { InlineNotice("Review not changed", it, NoticeTone.Warning) }
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        loaded.getOrNull()?.let { report ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Through ${report.through} · FIFO purchase basis", style = MaterialTheme.typography.titleMedium)
                    LabelValueRow("Stock value", report.stockValueMinor?.let(::money) ?: "Unavailable — review coverage", emphasized = true)
                    LabelValueRow("COGS after returns", report.netSalesCogsMinor?.let(::money) ?: "Unavailable — review coverage")
                    LabelValueRow("Non-restocked return losses", report.nonRestockedReturnCostMinor?.let(::money) ?: "Unavailable — review coverage")
                    LabelValueRow("Delivery expense allocations, separately reported", money(report.landedCostsExcludedMinor))
                    Text("Cost coverage ${report.items.count { it.stockValueMinor != null }} of ${report.items.size} items · Returns restore original FIFO cost slices. Repacking transfers declared input material cost; labor and overhead need separate sources.", style = MaterialTheme.typography.bodySmall)
                }
            }
            PosTextField(search, { search = it }, "Find an item", Modifier.fillMaxWidth())
            val matching = report.items.filter { it.product.matchesCatalogueSearch(search) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (matching.isEmpty()) item { EmptyState("No matching stock items", "Clear the search or choose another recorded date.") }
                items(matching, key = { it.product.id }) { row ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(row.product.name, style = MaterialTheme.typography.titleMedium)
                            LabelValueRow("On hand at review date", row.quantityScaled?.let { "${quantityLabel(it, row.product.quantityPrecision)} ${row.product.unit}" } ?: "Unavailable")
                            LabelValueRow("Purchase-basis stock value", row.stockValueMinor?.let(::money) ?: "Unavailable")
                            LabelValueRow("Original-sale COGS", row.originalSalesCogsMinor?.let(::money) ?: "Unavailable")
                            LabelValueRow("Cost restored to stock by returns", row.restoredReturnCostMinor?.let(::money) ?: "Unavailable")
                            LabelValueRow("Non-restocked return losses", row.nonRestockedReturnCostMinor?.let(::money) ?: "Unavailable")
                            LabelValueRow("COGS after returns", row.netSalesCogsMinor?.let(::money) ?: "Unavailable")
                            row.openingBasis?.let { basis ->
                                Text("Opening cost ${money(basis.totalCostMinor)} · Declaration ${basis.version} · ${receiptDate(basis.createdAt)}", style = MaterialTheme.typography.bodySmall)
                                Text(basis.reason, style = MaterialTheme.typography.bodySmall)
                            }
                            row.gaps.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (canEdit && row.product.openingStock != null && row.product.createdAt != null) NeutralButton("Declare opening cost", { editing = row })
                                NeutralButton(if (ledgerProduct == row.product.id) "Hide source ledger" else "View source ledger", { ledgerProduct = row.product.id.takeUnless { it == ledgerProduct } })
                            }
                            if (ledgerProduct == row.product.id) {
                                HorizontalDivider()
                                Text("Latest 100 source movements · Full source ledger is included in CSV", style = MaterialTheme.typography.bodySmall)
                                row.ledger.takeLast(100).asReversed().forEach { event ->
                                    Text("${event.kind} · ${receiptDate(event.createdAt)} · ${event.sourceId.take(12)}", style = MaterialTheme.typography.titleSmall)
                                    Text("${quantityLabel(event.quantityScaled, row.product.quantityPrecision)} ${row.product.unit} · Source cost ${event.costMinor?.let(::money) ?: "unavailable"}")
                                    Text(event.detail, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { row -> OpeningCostDialog(repo, tenantId, row, { editing = null }) {
        editing = null; revision++; notice = "Opening cost declaration saved with history"; onChanged()
    } }
}

@Composable
private fun OpeningCostDialog(repo: PosRepository, tenantId: String, row: InventoryItemCost, dismiss: () -> Unit, saved: () -> Unit) {
    var amount by remember { mutableStateOf(row.openingBasis?.totalCostMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { runCatching { repo.openingInventoryCostHistory(tenantId, row.product.id) } }
    val operation = remember(amount, reason) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Declare total opening cost") }, text = {
        Column(Modifier.widthIn(min = 380.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.product.name, style = MaterialTheme.typography.titleMedium)
            Text("Original counted opening quantity ${row.product.openingStock} ${row.product.unit}")
            Text("Enter the total buying value of that original opening quantity. A zero cost requires an explicit 0. New declarations recalculate historical reviews and retain the earlier reasons.")
            PosTextField(amount, { amount = it }, "Total opening-stock cost (₦)")
            PosTextField(reason, { reason = it }, "Source or reason for this opening cost")
            error?.let { InlineNotice("Opening cost not saved", it, NoticeTone.Danger) }
            history.getOrNull()?.asReversed()?.forEach { event ->
                Text("Declaration ${event.version} · ${money(event.totalCostMinor)} · ${receiptDate(event.createdAt)}", style = MaterialTheme.typography.titleSmall)
                Text(event.reason, style = MaterialTheme.typography.bodySmall)
            }
            history.exceptionOrNull()?.let { Text("Opening-cost history is unavailable.", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PosButton("Save opening cost", {
        try {
            repo.saveOpeningInventoryCost(tenantId, row.product.id, requireNotNull(parseNaira(amount)) { "Enter the total opening cost in naira." },
                row.openingBasis?.version ?: 0, reason, operation)
            saved()
        } catch (e: Exception) { error = e.message }
    }) }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
