package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.output.CsvCell
import pos.output.CsvData
import pos.ui.components.*
import java.math.BigDecimal
import java.util.UUID

private data class AndroidBuyingData(val review: ReorderReview, val products: List<Product>, val policies: Map<String, ReplenishmentPolicy>, val history: List<ReplenishmentPolicy>)

@Composable
internal fun AndroidReplenishmentScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) {
    var cover by rememberSaveable { mutableStateOf("14") }
    var search by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, cover to revision) {
        repo.featureRead(tenantId, StaffPermission.MANAGE_STOCK) {
            val days = requireNotNull(cover.toIntOrNull()?.takeIf { it in 1..90 }) { "Choose 1 to 90 whole days of stock cover." }
            AndroidBuyingData(reorderSuggestions(tenantId, days), products(tenantId), replenishmentPolicies(tenantId), replenishmentPolicyHistory(tenantId))
        }
    }
    loaded?.getOrNull()?.let { data -> editing?.let { product ->
        AndroidBuyingRuleEditor(repo, tenantId, product, data.policies[product.id], data.history.filter { it.productId == product.id }, { editing = null }) {
            editing = null; revision++; notice = "Buying rules saved with history. No goods or cash were moved."; onChanged()
        }
    } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionHeader("Reorder suggestions", "Review saved demand and buying rules before placing an order") }
        item { PosTextField(cover, { cover = it }, "Days of stock cover", hint = "1 to 90 whole days") }
        item { OperationsLoadState(loaded, "reorder suggestions", { revision++ }) }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Success) } }
        loaded?.getOrNull()?.let { data ->
            item { Text("Observed sales: ${data.review.from} through ${data.review.through} · WAT. Suggestions allow for customer stock holds and approved incoming goods. They do not buy, reserve or receive goods.") }
            if (data.review.rows.isEmpty()) item { EmptyState("No suggested purchases", "Current saved stock, demand and buying rules do not suggest a shortage.") }
            items(data.review.rows, key = { "suggestion-" + it.product.id }) { row -> OperationsCard {
                Text(row.product.name, style = MaterialTheme.typography.titleMedium)
                fun quantity(value: Long) = "${quantityLabel(value, row.product.quantityPrecision)} ${row.product.unit}"
                LabelValueRow("Suggested buying quantity", quantity(row.shortage), emphasized = true)
                LabelValueRow("On hand", quantity(row.product.exactStockScaled))
                LabelValueRow("Reserved for orders", quantity(row.reserved))
                LabelValueRow("Sold and not returned in period", quantity(row.retainedSales))
                LabelValueRow("Approved incoming", quantity(row.approvedIncoming))
                LabelValueRow("Target stock", quantity(row.target))
                OutlinedButton(onClick = { editing = row.product }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review buying rules") }
            } }
            item { AndroidReviewedCsvSave(repo, tenantId, StaffPermission.MANAGE_STOCK, "Preview reorder CSV", "reorder-${data.review.through}.csv") {
                fun text(value: String) = CsvCell.Text(value)
                fun quantity(value: Long, precision: Int) = CsvCell.Number(BigDecimal.valueOf(value, precision).toPlainString())
                CsvData.encode(listOf("Product ID", "Item", "Base unit", "From WAT", "Through WAT", "Cover days", "On hand", "Reserved", "Retained sales", "Approved incoming", "Target", "Suggested buying quantity"), data.review.rows.map { row ->
                    listOf(text(row.product.id), text(row.product.name), text(row.product.unit), text(data.review.from.toString()), text(data.review.through.toString()), CsvCell.Number(data.review.coverDays.toString())) +
                        listOf(row.product.exactStockScaled, row.reserved, row.retainedSales, row.approvedIncoming, row.target, row.shortage).map { quantity(it, row.product.quantityPrecision) }
                })
            } }
            item { Text("Buying rules for your goods", style = MaterialTheme.typography.titleMedium) }
            item { PosTextField(search, { search = it }, "Find an item to manage its buying rules") }
            items(data.products.filter { search.isBlank() || it.name.contains(search, true) }, key = { "policy-" + it.id }) { product -> OperationsCard {
                Text(product.name, style = MaterialTheme.typography.titleSmall)
                val policy = data.policies[product.id]
                Text(if (policy == null) "No saved buying rule. Existing stock thresholds still apply." else "Revision ${policy.version} · ${policy.leadDays} lead days · ${quantityLabel(policy.safetyScaled, policy.precision)} ${policy.unit} safety stock")
                OutlinedButton(onClick = { editing = product }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage buying rules") }
            } }
        }
    }
}

@Composable
private fun AndroidBuyingRuleEditor(repo: PosRepository, tenantId: String, product: Product, current: ReplenishmentPolicy?, history: List<ReplenishmentPolicy>, dismiss: () -> Unit, saved: () -> Unit) {
    fun plain(value: Long) = BigDecimal.valueOf(value, product.quantityPrecision).toPlainString()
    var lead by rememberSaveable(product.id) { mutableStateOf((current?.leadDays ?: 0).toString()) }
    var safety by rememberSaveable(product.id) { mutableStateOf(plain(current?.safetyScaled ?: 0)) }
    var multiple by rememberSaveable(product.id) { mutableStateOf(plain(current?.multipleScaled ?: 1)) }
    var reason by rememberSaveable(product.id) { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var visibleHistory by remember { mutableIntStateOf(20) }
    val operation = rememberSaveable(product.id) { UUID.randomUUID().toString() }
    OperationsDialog("Buying rules · ${product.name}", dismiss, if (reviewed) "Save buying rules" else "Review buying rules", action = {
        val days = requireNotNull(lead.toIntOrNull()?.takeIf { it in 0..365 }) { "Enter 0 to 365 whole lead-time days." }
        val buffer = requireNotNull(parseQuantity(safety, product.quantityPrecision)) { "Enter non-negative safety stock in ${product.unit}." }
        val step = requireNotNull(parseQuantity(multiple, product.quantityPrecision)) { "Enter a positive buying multiple in ${product.unit}." }
        require(buffer.scaledUnits <= 2147483647L && step.scaledUnits <= 2147483647L) { "Buying-rule quantities exceed the supported exact range." }
        val cleanReason = reason.trim()
        require(step.scaledUnits > 0) { "The buying multiple must be greater than zero." }
        require(cleanReason.length in 3..250 && cleanReason.none { it.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." }
        if (!reviewed) reviewed = true else {
            withContext(Dispatchers.IO) { repo.saveReplenishmentPolicy(tenantId, product.id, days, buffer.scaledUnits, step.scaledUnits, current?.version ?: 0, cleanReason, operation) }
            saved()
        }
    }) {
        Text("All quantities use this item’s base unit: ${product.unit}. These rules affect suggestions only; they do not change stock, prices, supplier debt or cash.")
        PosTextField(lead, { lead = it; reviewed = false }, "Supplier lead time in days")
        PosTextField(safety, { safety = it; reviewed = false }, "Safety stock in ${product.unit}")
        PosTextField(multiple, { multiple = it; reviewed = false }, "Buy in multiples of ${product.unit}")
        PosTextField(reason, { reason = it; reviewed = false }, "Why are these buying rules changing?")
        if (reviewed) Text("Save $lead lead days, $safety ${product.unit} safety stock and $multiple ${product.unit} buying multiples. Existing history is retained.")
        Text("Previous buying rules", style = MaterialTheme.typography.titleSmall)
        if (history.isEmpty()) Text("No earlier rules recorded.")
        history.asReversed().take(visibleHistory).forEach { event ->
            HorizontalDivider(); Text("Revision ${event.version} · ${event.createdAt.take(19)} UTC")
            Text("${event.leadDays} lead days · ${quantityLabel(event.safetyScaled, event.precision)} ${event.unit} safety · multiples of ${quantityLabel(event.multipleScaled, event.precision)} ${event.unit}")
            Text(event.reason)
        }
        if (history.size > visibleHistory) TextButton(onClick = { visibleHistory += 20 }) { Text("Show earlier buying rules") }
    }
}
