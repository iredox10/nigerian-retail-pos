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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import pos.data.PosRepository
import pos.data.Promotion
import pos.data.PromotionQuote
import pos.data.promotionHistory
import pos.data.promotions
import pos.data.quotePromotions
import pos.data.savePromotion
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*

private val promotionZone = ZoneId.of("Africa/Lagos")

@Composable
fun PromotionsScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit): Unit = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Promotion?>(null) }
    var editing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<Promotion?>(null) }
    var search by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = remember(repo, revision) {
        runCatching { Triple(repo.promotions(tenantId), repo.products(tenantId, true), repo.sellingUnits(tenantId, includeArchived = true)) }
    }
    val result = loaded.getOrNull()
    val products = remember(result) { result?.second.orEmpty().associateBy { it.id } }
    val units = remember(result) { result?.third.orEmpty().associateBy { it.id } }
    val today = LocalDate.now(promotionZone).toString()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Promotions", "Schedule a discount for an item and apply it when selling.", Modifier.weight(1f))
            PosButton("New promotion", { selected = null; editing = true }, enabled = loaded.isSuccess)
        }
        loaded.exceptionOrNull()?.let {
            InlineNotice("Promotions unavailable", it.message ?: "Try again.", NoticeTone.Danger)
            NeutralButton("Reload promotions", { revision++ })
        }
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        PosTextField(search, { search = it }, "Find a promotion", Modifier.fillMaxWidth(), hint = "Promotion or item name")
        val matching = result?.first.orEmpty().filter { usage ->
            val terms = usage.promotion.terms
            terms.name.contains(search.trim(), true) || products[terms.productId]?.name?.contains(search.trim(), true) == true
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (matching.isEmpty() && loaded.isSuccess) item {
                EmptyState("No matching promotions", "Create a promotion to set its item, dates and discount.")
            }
            items(matching, key = { it.promotion.id }) { usage ->
                val promotion = usage.promotion
                val terms = promotion.terms
                val product = products[terms.productId]
                val unit = terms.sellingUnitId?.let { units[it] }
                val state = when {
                    terms.archived -> "Archived"
                    product?.archived == true || unit?.archived == true -> "Item unavailable"
                    today < terms.from -> "Scheduled"
                    today > terms.through -> "Ended"
                    terms.maximumSales?.let { usage.sales >= it.toLong() } == true -> "Sale limit reached"
                    else -> "Available"
                }
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(terms.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            StatusPill(state, NoticeTone.Neutral)
                        }
                        val unitName = unit?.name ?: product?.unit.orEmpty()
                        val quantity = quantityLabel(terms.minimumQuantity.toLong(), terms.quantityPrecision)
                        Text("${product?.name.orEmpty()} · From $quantity $unitName · ${promotionReductionLabel(terms)}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${terms.from} to ${terms.through} · WAT · ${usage.sales}${terms.maximumSales?.let { " of $it" }.orEmpty()} sales",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NeutralButton("Edit promotion", { selected = promotion; editing = true })
                            NeutralButton("History", { history = promotion })
                        }
                    }
                }
            }
        }
    }
    if (editing) {
        PromotionEditor(repo, tenantId, selected, result?.second.orEmpty(), result?.third.orEmpty(),
            dismiss = { editing = false }) { saved ->
            notice = "${saved.terms.name} saved on this computer."
            editing = false
            revision++
            onChanged()
        }
    }
    history?.let { promotion ->
        val revisions = remember(promotion.id, revision) { runCatching { repo.promotionHistory(tenantId, promotion.id) } }
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("Promotion history") },
            text = {
                Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    revisions.exceptionOrNull()?.let { InlineNotice("History unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                    revisions.getOrDefault(emptyList()).asReversed().forEach { saved ->
                        val terms = saved.terms
                        Text("${terms.name} · Revision ${saved.version}", style = MaterialTheme.typography.titleSmall)
                        val item = products[terms.productId]
                        val unit = terms.sellingUnitId?.let { units[it]?.name } ?: item?.unit.orEmpty()
                        Text("${item?.name.orEmpty()} · From ${quantityLabel(terms.minimumQuantity.toLong(), terms.quantityPrecision)} $unit")
                        Text("${promotionReductionLabel(terms)} · ${terms.from} to ${terms.through} WAT")
                        Text(terms.maximumSales?.let { "$it sales allowed" } ?: "Unlimited sales",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${receiptDate(saved.recordedAt.toString())} · ${if (terms.archived) "Archived" else "Saved"}")
                        Text(saved.reason)
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton({ history = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun PromotionEditor(
    repo: PosRepository,
    tenantId: String,
    existing: Promotion?,
    products: List<Product>,
    units: List<SellingUnit>,
    dismiss: () -> Unit,
    onSaved: (Promotion) -> Unit,
) {
    val terms = existing?.terms
    val today = remember { LocalDate.now(promotionZone).toString() }
    val id = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(terms?.name.orEmpty()) }
    var productId by remember { mutableStateOf(terms?.productId) }
    var unitId by remember { mutableStateOf(terms?.sellingUnitId) }
    var query by remember { mutableStateOf("") }
    var itemMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(terms?.from ?: today) }
    var through by remember { mutableStateOf(terms?.through ?: today) }
    var reduction by remember { mutableStateOf(terms?.reduction ?: PromotionReduction.PERCENTAGE) }
    var amount by remember { mutableStateOf(terms?.let { minorText(it.amount) }.orEmpty()) }
    var quantity by remember {
        mutableStateOf(terms?.let { quantityLabel(it.minimumQuantity.toLong(), it.quantityPrecision) } ?: "1")
    }
    var limit by remember { mutableStateOf(terms?.maximumSales?.toString().orEmpty()) }
    var archived by remember { mutableStateOf(terms?.archived ?: false) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operationId = remember(name, productId, unitId, from, through, reduction, amount, quantity, limit, archived, reason) {
        UUID.randomUUID().toString()
    }
    val product = products.find { it.id == productId }
    val unit = units.find { it.id == unitId && it.productId == productId }
    val precision = if (unit == null) product?.quantityPrecision ?: 0 else 0
    val unitName = unit?.name ?: product?.unit.orEmpty()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (existing == null) "New promotion" else "Edit promotion") },
        text = {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PosTextField(name, { name = it }, "Promotion name")
                PosTextField(query, { query = it }, "Find an item", hint = "Name, SKU or barcode")
                Box {
                    NeutralButton(product?.name ?: "Choose item", { itemMenu = true })
                    DropdownMenu(itemMenu, { itemMenu = false }, Modifier.heightIn(max = 230.dp)) {
                        products.filter { !it.archived && it.matchesCatalogueSearch(query) }.forEach { item ->
                            DropdownMenuItem({ Text(item.name) }, {
                                productId = item.id
                                unitId = null
                                quantity = "1"
                                itemMenu = false
                            })
                        }
                    }
                }
                product?.let { item ->
                    Box {
                        NeutralButton("Sell as $unitName", { unitMenu = true })
                        DropdownMenu(unitMenu, { unitMenu = false }) {
                            DropdownMenuItem({ Text(item.unit) }, { unitId = null; quantity = "1"; unitMenu = false })
                            units.filter { it.productId == item.id && !it.archived }.forEach { pack ->
                                DropdownMenuItem({ Text(pack.name) }, { unitId = pack.id; quantity = "1"; unitMenu = false })
                            }
                        }
                    }
                    Text("Normal price ${money(unit?.priceMinor ?: item.priceMinor)} per $unitName",
                        style = MaterialTheme.typography.bodySmall)
                    PosTextField(quantity, { quantity = it }, "Minimum quantity in $unitName")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(reduction == PromotionReduction.PERCENTAGE, {
                        if (reduction != PromotionReduction.PERCENTAGE) amount = ""
                        reduction = PromotionReduction.PERCENTAGE
                    }, label = { Text("Percentage off") })
                    FilterChip(reduction == PromotionReduction.FIXED, {
                        if (reduction != PromotionReduction.FIXED) amount = ""
                        reduction = PromotionReduction.FIXED
                    }, label = { Text("Amount off") })
                }
                PosTextField(amount, { amount = it }, if (reduction == PromotionReduction.PERCENTAGE) "Percentage off (%)"
                    else "Amount off each selling unit (₦)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosTextField(from, { from = it }, "Start date (WAT)", Modifier.weight(1f), hint = "YYYY-MM-DD")
                    PosTextField(through, { through = it }, "End date (WAT)", Modifier.weight(1f), hint = "YYYY-MM-DD")
                }
                PosTextField(limit, { limit = it }, "Maximum sales (optional)", hint = "Leave blank for unlimited")
                if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(archived, { archived = it })
                    Text("Archive this promotion")
                }
                PosTextField(reason, { reason = it }, "Reason for saving this promotion")
                error?.let { InlineNotice("Promotion could not be saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton("Save promotion", {
                try {
                    val item = requireNotNull(product) { "Choose an item." }
                    val count = requireNotNull(quantityCount(quantity, precision)) { "Enter the minimum quantity in $unitName." }
                    val value = if (reduction == PromotionReduction.PERCENTAGE) {
                        requireNotNull(parseDiscountPercentage(amount)) { "Enter a percentage up to 100." }.basisPoints.toLong()
                    } else requireNotNull(parseNaira(amount)) { "Enter a valid amount in naira." }
                    val maximum = limit.trim().takeIf { it.isNotEmpty() }?.let {
                        requireNotNull(it.toIntOrNull()) { "Enter a positive whole sale limit or leave it blank." }
                    }
                    val next = PromotionTerms(name.trim(), item.id, unitId, count, precision, from.trim(),
                        through.trim(), reduction, value, maximum, archived)
                    onSaved(repo.savePromotion(tenantId, id, next, existing?.version ?: 0, reason.trim(), operationId))
                } catch (failure: Exception) {
                    error = failure.message ?: "Review the promotion details."
                }
            })
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PromotionSelectionDialog(
    repo: PosRepository,
    tenantId: String,
    line: CartLine,
    hasBasketDiscount: Boolean,
    apply: (PromotionQuote) -> Unit,
    dismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded = remember(tenantId, line) { runCatching { repo.quotePromotions(tenantId, listOf(line)) } }
    val offers = loaded.getOrDefault(emptyList())
    val proposed = offers.find { it.promotion.id == selected }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Choose promotion") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${line.product.name} · ${line.quantityLabel} ${line.sellingUnit}")
                if (hasBasketDiscount) InlineNotice("Basket discount applied",
                    "Remove the basket discount to use a promotion.", NoticeTone.Warning)
                loaded.exceptionOrNull()?.let { InlineNotice("Promotions unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                if (loaded.isSuccess && offers.isEmpty()) Text("No promotion qualifies for this item and quantity today.")
                for (offer in offers) {
                    val terms = offer.promotion.terms
                    FilterChip(proposed == offer, { selected = offer.promotion.id; error = null }, label = { Text(terms.name) })
                    if (proposed == offer) {
                        LabelValueRow("Normal price per ${line.sellingUnit}", money(offer.discount.cataloguePriceMinor))
                        LabelValueRow("Customer pays per ${line.sellingUnit}",
                            money(offer.discount.cataloguePriceMinor - offer.discount.amountMinor), emphasized = true)
                        Text("${terms.from} to ${terms.through} WAT", style = MaterialTheme.typography.bodySmall)
                        terms.maximumSales?.let { Text("${offer.sales} of $it sales used", style = MaterialTheme.typography.bodySmall) }
                        if (line.discountPerUnitMinor > 0) Text("Applying this promotion replaces the current discount on this item.")
                    }
                }
                error?.let { InlineNotice("Promotion could not be applied", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton("Apply promotion", {
                try {
                    val wanted = requireNotNull(proposed)
                    val fresh = repo.quotePromotions(tenantId, listOf(line)).singleOrNull { it.promotion.id == wanted.promotion.id }
                    require(fresh != null && fresh.discount == wanted.discount) {
                        "The promotion changed. Reopen this review."
                    }
                    apply(fresh)
                } catch (failure: Exception) {
                    error = failure.message ?: "Review the promotion."
                }
            }, enabled = proposed != null && !hasBasketDiscount)
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

private fun promotionReductionLabel(terms: PromotionTerms): String =
    if (terms.reduction == PromotionReduction.PERCENTAGE) "${DiscountPercentage(terms.amount.toInt()).display()} off"
    else "${money(terms.amount)} off each"

private fun minorText(amount: Long): String =
    "${amount / 100}.${(amount % 100).toString().padStart(2, '0')}"
