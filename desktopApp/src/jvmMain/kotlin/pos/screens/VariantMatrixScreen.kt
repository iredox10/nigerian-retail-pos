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
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID

private data class MatrixRowDraft(val first: String, val second: String?, val sku: String,
    val barcode: String = "", val price: String, val opening: String = "0", val selected: Boolean = true)

@Composable
fun VariantMatrixScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    val history = remember(revision) { runCatching { repo.variantMatrixBatches(tenantId) } }
    var historyOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var family by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("piece") }
    var precision by remember { mutableIntStateOf(0) }
    var firstName by remember { mutableStateOf("Size") }
    var firstValues by remember { mutableStateOf("S, M, L") }
    var secondEnabled by remember { mutableStateOf(true) }
    var secondName by remember { mutableStateOf("Colour") }
    var secondValues by remember { mutableStateOf("Black, White") }
    var skuPrefix by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf<VariantMatrixDefinition?>(null) }
    var rows by remember { mutableStateOf(emptyList<MatrixRowDraft>()) }
    var reviewed by remember { mutableStateOf<VariantMatrixReview?>(null) }
    var reason by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(reviewed, reason) { UUID.randomUUID().toString() }
    fun values(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun selectedRows(): List<VariantMatrixRow> {
        val current = requireNotNull(definition)
        return rows.filter { it.selected }.map { row ->
            VariantMatrixRow(row.first, row.second, row.sku.trim(), row.barcode.trim().ifEmpty { null },
                requireNotNull(parseNaira(row.price)) { "Enter an exact selling price for ${row.first}." },
                requireNotNull(parseQuantity(row.opening, current.precision)) { "Enter a counted quantity for ${row.first}." }.scaledUnits)
        }
    }
    fun update(index: Int, value: MatrixRowDraft) {
        rows = rows.toMutableList().also { it[index] = value }
        reviewed = null; error = null
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Variant matrix", "Create separately stocked goods from named options.", Modifier.weight(1f))
            NeutralButton(if (historyOpen) "New matrix" else "Previous matrices", { historyOpen = !historyOpen; error = null })
        }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        error?.let { InlineNotice("Matrix not saved", it, NoticeTone.Danger) }
        if (historyOpen) {
            history.exceptionOrNull()?.let { InlineNotice("History unavailable", it.message.orEmpty(), NoticeTone.Danger) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (history.getOrDefault(emptyList()).isEmpty()) item { EmptyState("No matrices saved yet", "Create a matrix to retain its option and opening-count history.") }
                items(history.getOrDefault(emptyList()), key = { it.id }) { batch ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(batch.definition.family, style = MaterialTheme.typography.titleMedium)
                            Text("${batch.rows.size} new goods · ${receiptDate(batch.createdAt)}", style = MaterialTheme.typography.bodySmall)
                            Text(batch.reason)
                            NeutralButton(if (expanded == batch.id) "Hide variants" else "View variants", { expanded = if (expanded == batch.id) null else batch.id })
                            if (expanded == batch.id) batch.rows.forEach { row ->
                                Text(variantMatrixLabel(batch.definition, row.values), style = MaterialTheme.typography.titleSmall)
                                Text("SKU ${row.values.sku} · ${money(row.values.priceMinor)} / ${batch.definition.unit} · opening ${quantityLabel(row.values.openingScaled, batch.definition.precision)} ${batch.definition.unit}", style = MaterialTheme.typography.bodySmall)
                                row.values.barcode?.let { Text("Barcode $it", style = MaterialTheme.typography.bodySmall) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        } else if (definition == null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InlineNotice("Each combination becomes a separate item", "Every item gets its own SKU, price and stock count. Existing goods and their stock stay in their own records.", NoticeTone.Info)
                PosTextField(family, { family = it }, "Product family", hint = "For example, Cotton T-shirt")
                PosTextField(unit, { unit = it }, "Selling unit", hint = "piece, pair or kg")
                Text("Quantity decimal places", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..6).forEach { count -> FilterChip(precision == count, { precision = count }, label = { Text(if (count == 0) "Whole units" else count.toString()) }) }
                }
                PosTextField(firstName, { firstName = it }, "First option name")
                PosTextField(firstValues, { firstValues = it }, "First option values", hint = "Separate values with commas")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(secondEnabled, { secondEnabled = it })
                    Text("Use a second option")
                }
                if (secondEnabled) {
                    PosTextField(secondName, { secondName = it }, "Second option name")
                    PosTextField(secondValues, { secondValues = it }, "Second option values", hint = "Separate values with commas")
                }
                PosTextField(skuPrefix, { skuPrefix = it }, "SKU prefix", hint = "Each generated SKU can be edited before saving")
                PosTextField(price, { price = it }, "Starting selling price (₦)", hint = "Each combination can have its own price")
                PosButton("Build variant rows", {
                    try {
                        val candidate = VariantMatrixDefinition(family.trim(), unit.trim(), precision,
                            VariantOptionAxis(firstName.trim(), values(firstValues)),
                            if (secondEnabled) VariantOptionAxis(secondName.trim(), values(secondValues)) else null)
                        val combinations = variantMatrixCombinations(candidate)
                        require(skuPrefix.trim().isNotEmpty()) { "Enter an SKU prefix." }
                        require(parseNaira(price) != null) { "Enter an exact starting price." }
                        rows = combinations.mapIndexed { index, pair -> MatrixRowDraft(pair.first, pair.second, "${skuPrefix.trim()}-${index + 1}", price = price) }
                        definition = candidate; reviewed = null; reason = ""; error = null
                    } catch (e: Exception) { error = e.message ?: "Check the options." }
                })
            }
        } else {
            val current = requireNotNull(definition)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${current.family} · ${rows.count { it.selected }} of ${rows.size} combinations selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                NeutralButton("Start again", { definition = null; rows = emptyList(); reviewed = null; error = null })
            }
            Text("Enter the opening count actually counted for each new item. Zero leaves it empty until stock is received.", style = MaterialTheme.typography.bodySmall)
            if (reviewed == null) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows.indices.toList(), key = { it }) { index ->
                        val row = rows[index]
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(row.selected, { update(index, row.copy(selected = it)) })
                                    Text("${current.first.name} ${row.first}" + (current.second?.let { " · ${it.name} ${row.second}" } ?: ""), style = MaterialTheme.typography.titleMedium)
                                }
                                if (row.selected) {
                                    PosTextField(row.sku, { update(index, row.copy(sku = it)) }, "SKU")
                                    PosTextField(row.barcode, { update(index, row.copy(barcode = it)) }, "Barcode (optional)")
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PosTextField(row.price, { update(index, row.copy(price = it)) }, "Selling price (₦)", Modifier.weight(1f))
                                        PosTextField(row.opening, { update(index, row.copy(opening = it)) }, "Opening count in ${current.unit}", Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                PosButton("Review selected variants", {
                    try { reviewed = repo.reviewVariantMatrix(tenantId, current, selectedRows()); error = null }
                    catch (e: Exception) { error = e.message ?: "Check the selected variants." }
                }, Modifier.fillMaxWidth())
            } else {
                val review = requireNotNull(reviewed)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(review.rows, key = { it.sku }) { row ->
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(variantMatrixLabel(current, row), style = MaterialTheme.typography.titleMedium)
                                LabelValueRow("SKU", row.sku)
                                row.barcode?.let { LabelValueRow("Barcode", it) }
                                LabelValueRow("Selling price", "${money(row.priceMinor)} / ${current.unit}")
                                LabelValueRow("Counted opening stock", "${quantityLabel(row.openingScaled, current.precision)} ${current.unit}")
                            }
                        }
                    }
                }
                PosTextField(reason, { reason = it; error = null }, "Reason for creating these variants")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NeutralButton("Edit rows", { reviewed = null }, Modifier.weight(1f))
                    PosButton("Create ${review.rows.size} items", {
                        try {
                            val batch = repo.createVariantMatrix(tenantId, review, reason, operation)
                            message = "${batch.rows.size} variants saved on this computer."
                            definition = null; rows = emptyList(); reviewed = null; reason = ""; error = null
                            revision++; historyOpen = true; onChanged()
                        } catch (e: Exception) { error = e.message ?: "Review the matrix and try again." }
                    }, Modifier.weight(1f))
                }
            }
        }
    }
}
