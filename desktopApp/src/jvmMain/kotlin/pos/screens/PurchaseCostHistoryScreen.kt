package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.data.PurchaseCostRecord
import pos.data.landedCostTotalsForPurchases
import pos.data.latestSupplierPrices
import pos.data.purchaseCostHistory
import pos.domain.money
import pos.output.CsvService
import pos.output.PurchaseCostCsv
import pos.output.receiptDate
import pos.ui.components.*

@Composable
fun PurchaseCostHistoryScreen(repo: PosRepository, tenantId: String) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var comparison by remember { mutableStateOf<PurchaseCostRecord?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val loaded = remember(repo, revision) {
        runCatching { repo.purchaseCostHistory(tenantId) to repo.landedCostTotalsForPurchases(tenantId) }
    }
    val records = loaded.getOrNull()?.first.orEmpty()
    val landed = loaded.getOrNull()?.second.orEmpty()
    val selected = comparison
    val visible = remember(records, selected, search) {
        if (selected != null) {
            records.latestSupplierPrices(tenantId, selected.receipt.productId, selected.receipt.baseUnit)
        } else {
            val query = search.trim()
            records.filter { row ->
                val receipt = row.receipt
                receipt.productName.contains(query, true) || receipt.supplierName.contains(query, true) ||
                    receipt.id.contains(query, true) || row.supplier?.details?.name?.contains(query, true) == true
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected == null) {
            SectionHeader("Purchase cost history", "See prices recorded when stock came in.")
            PosTextField(search, { search = it }, "Find a purchase", Modifier.fillMaxWidth(),
                hint = "Item, supplier or receipt ID")
        } else {
            SectionHeader("Compare suppliers", selected.receipt.productName)
            Text("Latest saved purchase per linked supplier, ordered by price per ${selected.receipt.baseUnit}.",
                style = MaterialTheme.typography.bodySmall)
            NeutralButton("Back to purchase history", {
                comparison = null
                exportStatus = null
                exportError = null
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NeutralButton("Refresh", {
                revision++
                exportStatus = null
                exportError = null
            })
            NeutralButton("Export receipt prices", {
                exportStatus = null
                exportError = null
                runCatching {
                    val filename = if (selected == null) "purchase-cost-history.csv" else "supplier-price-comparison.csv"
                    val values = PurchaseCostCsv.encode(tenantId, visible)
                    CsvService.save(values, filename)
                }.onSuccess { file ->
                    if (file != null) exportStatus = "Prices saved to ${file.name}"
                }.onFailure { failure ->
                    exportError = failure.message ?: "Check the destination and try again."
                }
            }, enabled = visible.isNotEmpty())
        }
        Text("Stock-unit prices use the buying-unit conversion saved on each receipt. Approximate prices are rounded to the nearest kobo.",
            style = MaterialTheme.typography.bodySmall)
        exportStatus?.let { InlineNotice(it, tone = NoticeTone.Success) }
        exportError?.let { InlineNotice("Could not save prices", it, NoticeTone.Danger) }
        loaded.exceptionOrNull()?.let {
            InlineNotice("Purchase costs unavailable", it.message ?: "Refresh to try again.", NoticeTone.Danger)
        }
        if (loaded.isSuccess) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) item {
                    when {
                        selected != null -> EmptyState("No linked supplier purchases",
                            "Link receipts to supplier accounts to compare their latest saved prices.")
                        records.isEmpty() -> EmptyState("No purchase costs recorded",
                            "Receive stock in Stock to see the buying prices here.")
                        else -> EmptyState("No matching purchases", "Search for a different item, supplier or receipt ID.")
                    }
                }
                items(visible, key = { it.receipt.id }) { row ->
                    PurchaseCostRow(row, if (selected == null) landed[row.receipt.id] else null, if (selected == null) {
                        {
                            comparison = row
                            exportStatus = null
                            exportError = null
                        }
                    } else null)
                }
            }
        }
    }
}

@Composable
private fun PurchaseCostRow(row: PurchaseCostRecord, allocatedMinor: Long?, compare: (() -> Unit)?) {
    val receipt = row.receipt
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(receipt.productName, fontWeight = FontWeight.SemiBold)
            Text(receiptDate(receipt.createdAt), style = MaterialTheme.typography.bodySmall)
            LabelValueRow("Supplier on receipt", receipt.supplierName.ifBlank { "Not recorded" })
            val supplier = row.supplier
            if (supplier == null) {
                Text("Supplier account unlinked", style = MaterialTheme.typography.bodySmall)
            } else {
                LabelValueRow("Linked supplier account", supplier.details.name + if (supplier.archived) " (archived)" else "")
                Text("Account ${supplier.id}", style = MaterialTheme.typography.bodySmall)
            }
            LabelValueRow("Received", "${receipt.quantityLabel} ${receipt.unit}")
            LabelValueRow("Price on receipt", "${money(receipt.unitCostMinor)} / ${receipt.unit}")
            val approximate = if (row.baseCostIsRounded) "Approx. " else ""
            LabelValueRow("Price per stock unit",
                "$approximate${money(row.roundedBaseCostMinor)} / ${receipt.baseUnit}", emphasized = true)
            if (receipt.purchaseUnitId != null) {
                Text("1 ${receipt.unit} = ${receipt.baseUnitsPerUnit} ${receipt.baseUnit}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (allocatedMinor != null && allocatedMinor > 0) {
                LabelValueRow("Allocated delivery costs", money(allocatedMinor))
                LabelValueRow("Purchase total including delivery", money(Math.addExact(receipt.totalCostMinor, allocatedMinor)),
                    emphasized = true)
            }
            Text("Receipt ${receipt.id}", style = MaterialTheme.typography.bodySmall)
            if (compare != null) NeutralButton("Compare suppliers", compare)
        }
    }
}
