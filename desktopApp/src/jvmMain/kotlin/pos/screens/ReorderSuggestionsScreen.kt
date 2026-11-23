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
import pos.data.reorderSuggestions
import pos.domain.matchesCatalogueSearch
import pos.domain.quantityLabel
import pos.output.CsvCell
import pos.output.CsvService
import pos.ui.components.*

@Composable
fun ReorderSuggestionsScreen(repo: PosRepository, tenantId: String) = key(tenantId) {
    var editingPolicy by remember { mutableStateOf(false) }
    var daysText by remember { mutableStateOf("7") }
    var coverDays by remember { mutableIntStateOf(7) }
    var revision by remember { mutableIntStateOf(0) }
    if (editingPolicy) ReplenishmentPolicyDialog(repo, tenantId, { editingPolicy = false }) { editingPolicy = false; revision++ }
    var search by remember { mutableStateOf("") }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val loaded = remember(repo, coverDays, revision) { runCatching { repo.reorderSuggestions(tenantId, coverDays) } }
    val review = loaded.getOrNull()
    val visible = review?.rows.orEmpty().filter { it.product.matchesCatalogueSearch(search) }
    val enteredDays = daysText.trim().toIntOrNull()?.takeIf { it in 1..90 }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Reorder suggestions", "Review how much to buy using the records on this computer.")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosTextField(daysText, { daysText = it }, "Days of stock cover", Modifier.widthIn(max = 190.dp), hint = "1 to 90")
            NeutralButton("Update suggestions", {
                coverDays = requireNotNull(enteredDays)
                savedMessage = null
                exportError = null
                revision++
            }, enabled = enteredDays != null)
            NeutralButton("Buying rules", { editingPolicy = true })
        }
        review?.let {
            Text("Sales kept by customers from ${it.from} to ${it.through} (WAT). Recent returns reduce the quantities used.")
            Text(
                "The target covers ${it.coverDays} days at this sales rate, with at least one sellable increment above any reorder level. " +
                    "Suggestions include saved lead times and safety stock, subtract reserved quantities, allow for approved incoming goods and round up to saved buying multiples.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PosTextField(search, { search = it }, "Find an item", Modifier.fillMaxWidth(), hint = "Name, SKU, category or barcode")
        NeutralButton("Export visible suggestions", {
            try {
                val displayed = requireNotNull(review)
                val csv = CsvService.encode(listOf(
                    "Item", "Product ID", "SKU", "Stock unit", "On hand", "Still on approved orders",
                    "Retained sales quantity", "Target", "Suggested quantity to buy", "Sales from WAT",
                    "Sales through WAT", "Days of stock cover", "Reserved for orders", "Supplier lead days", "Safety stock", "Buying multiple",
                ), visible.map { row ->
                    val product = row.product
                    fun quantity(amount: Long) = CsvCell.Number(quantityLabel(amount, product.quantityPrecision))
                    listOf(
                        CsvCell.Text(product.name), CsvCell.Text(product.id), CsvCell.Text(product.sku.orEmpty()),
                        CsvCell.Text(product.unit), CsvCell.Number(product.stockLabel), quantity(row.approvedIncoming),
                        quantity(row.retainedSales), quantity(row.target), quantity(row.shortage),
                        CsvCell.Text(displayed.from.toString()), CsvCell.Text(displayed.through.toString()),
                        CsvCell.Number(displayed.coverDays.toString()), quantity(row.reserved),
                        CsvCell.Number((row.policy?.leadDays ?: 0).toString()),
                        quantity(row.policy?.safetyScaled ?: 0), quantity(row.policy?.multipleScaled ?: 1),
                    )
                })
                CsvService.save(csv, "reorder-suggestions-${displayed.through}.csv")?.let {
                    savedMessage = "Suggestions saved to ${it.name}"
                    exportError = null
                }
            } catch (failure: Exception) {
                exportError = failure.message ?: "Check the destination and try again."
                savedMessage = null
            }
        }, enabled = review != null && visible.isNotEmpty() && enteredDays == coverDays)
        savedMessage?.let { InlineNotice(it, tone = NoticeTone.Success) }
        exportError?.let { InlineNotice("Could not save suggestions", it, NoticeTone.Danger) }
        loaded.exceptionOrNull()?.let { InlineNotice("Suggestions unavailable", it.message ?: "Update suggestions to try again.", NoticeTone.Danger) }
        if (review != null) BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val compact = maxWidth < 830.dp
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) item {
                    if (review.rows.isEmpty()) EmptyState("No items need a reorder", "Available and expected quantities cover your current targets.")
                    else EmptyState("No matching items", "Clear the search to see all suggestions.")
                }
                if (!compact && visible.isNotEmpty()) item {
                    ReorderTableRow("Item", listOf("On hand", "On order", "Sold in 30 days", "Target", "Suggested to buy"), header = true)
                }
                items(visible, key = { it.product.id }) { row ->
                    val product = row.product
                    fun display(quantity: Long) = "${quantityLabel(quantity, product.quantityPrecision)} ${product.unit}"
                    val quantities = listOf("${product.stockLabel} ${product.unit}", display(row.approvedIncoming),
                        display(row.retainedSales), display(row.target), display(row.shortage))
                    if (compact) Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(product.name, fontWeight = FontWeight.SemiBold)
                            if (row.reserved > 0) Text("${display(row.reserved)} reserved for orders", style = MaterialTheme.typography.bodySmall)
                            row.policy?.let { Text("${it.leadDays} lead days · ${display(it.safetyScaled)} safety stock · buy in ${display(it.multipleScaled)}", style = MaterialTheme.typography.bodySmall) }
                            LabelValueRow("On hand", quantities[0])
                            LabelValueRow("On order", quantities[1])
                            LabelValueRow("Sold in 30 days", quantities[2])
                            LabelValueRow("Target", quantities[3])
                            LabelValueRow("Suggested to buy", quantities[4], emphasized = true)
                        }
                    } else ReorderTableRow(product.name, quantities, header = false)
                }
            }
        }
    }
}

@Composable
private fun ReorderTableRow(name: String, quantities: List<String>, header: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = if (header) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1.9f), fontWeight = FontWeight.Medium)
            quantities.forEachIndexed { index, quantity ->
                Text(quantity, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (index == quantities.lastIndex || header) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
