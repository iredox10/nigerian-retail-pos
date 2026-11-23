package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import pos.data.CashExchange
import pos.data.CashExchangeReview
import pos.data.PosRepository
import pos.domain.*
import pos.ui.components.*

@Composable
internal fun CashExchangeDialog(
    repo: PosRepository, sale: Sale, returnable: Map<String, Int>,
    dismiss: () -> Unit, onSaved: (CashExchange) -> Unit,
) = key(sale.id) {
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(revision) {
        runCatching { repo.products(sale.tenantId) to repo.sellingUnits(sale.tenantId) }
    }
    val products = loaded.getOrNull()?.first.orEmpty()
    val units = loaded.getOrNull()?.second.orEmpty()
    val returnTexts = remember { mutableStateMapOf<String, String>() }
    val basket = remember { mutableStateMapOf<String, CartLine>() }
    var search by remember { mutableStateOf("") }
    var productId by remember { mutableStateOf<String?>(null) }
    var unitId by remember { mutableStateOf<String?>(null) }
    var quantity by remember { mutableStateOf("1") }
    var choosingUnit by remember { mutableStateOf(false) }
    var returnToStock by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<CashExchangeReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = review
    val scroll = remember(preview != null) { ScrollState(0) }
    val operationId = remember(preview) { UUID.randomUUID().toString() }
    val product = products.firstOrNull { it.id == productId }
    val productUnits = units.filter { it.productId == productId }
    val unit = productUnits.firstOrNull { it.id == unitId }
    val precision = if (unit == null) product?.quantityPrecision ?: 0 else 0
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (preview == null) "Exchange items" else "Review exchange") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Original receipt ${sale.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                if (preview == null) {
                    SectionHeader("Goods coming back", "Enter quantities in the units shown on the original receipt.")
                    sale.lines.filter { (returnable[it.key] ?: 0) > 0 }.forEach { line ->
                        PosTextField(returnTexts[line.key].orEmpty(), { returnTexts[line.key] = it; error = null },
                            "${line.product.name} · ${line.sellingUnit}", Modifier.fillMaxWidth(),
                            hint = "Up to ${quantityLabel(returnable.getValue(line.key).toLong(), line.quantityPrecision)}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(returnToStock, { returnToStock = it; error = null })
                        Text("Add the returned goods to sellable stock")
                    }
                    HorizontalDivider()
                    SectionHeader("Replacement goods", "Use the current selling price for each item.")
                    loaded.exceptionOrNull()?.let { InlineNotice("Items unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                    if (product == null) {
                        PosTextField(search, { search = it }, "Find a replacement item", Modifier.fillMaxWidth())
                        val query = search.trim()
                        val matches = products.filter { item ->
                            item.matchesCatalogueSearch(query) || units.any { it.productId == item.id && it.barcode == query }
                        }
                        matches.take(5).forEach { item ->
                            NeutralButton("${item.name} · ${money(item.priceMinor)} / ${item.unit}", {
                                productId = item.id; unitId = null; quantity = "1"; error = null
                            }, Modifier.fillMaxWidth())
                        }
                        if (matches.size > 5) Text("Type a name or barcode to narrow the list.", style = MaterialTheme.typography.bodySmall)
                        if (matches.isEmpty()) Text("No matching items.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(product.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton({ productId = null }) { Text("Change item") }
                        }
                        if (productUnits.isNotEmpty()) Box {
                            NeutralButton("Sell as ${unit?.name ?: product.unit}", { choosingUnit = true })
                            DropdownMenu(choosingUnit, { choosingUnit = false }) {
                                DropdownMenuItem({ Text(product.unit) }, { unitId = null; choosingUnit = false })
                                productUnits.forEach { option ->
                                    DropdownMenuItem({ Text("${option.name} · ${option.baseUnitsPerUnit} ${product.unit}") },
                                        { unitId = option.id; choosingUnit = false })
                                }
                            }
                        }
                        Text("${money(unit?.priceMinor ?: product.priceMinor)} per ${unit?.name ?: product.unit}",
                            style = MaterialTheme.typography.bodySmall)
                        PosTextField(quantity, { quantity = it }, "Quantity in ${unit?.name ?: product.unit}", Modifier.fillMaxWidth())
                        NeutralButton("Add replacement", {
                            try {
                                val count = requireNotNull(quantityCount(quantity, precision)) { "Enter a valid replacement quantity." }
                                val line = CartLine(product, count, unit?.id, unit?.name ?: product.unit,
                                    unit?.priceMinor ?: product.priceMinor, unit?.baseUnitsPerUnit ?: 1)
                                val total = Math.addExact(basket[line.key]?.quantity ?: 0, line.quantity)
                                basket[line.key] = line.copy(quantity = total)
                                productId = null; search = ""; error = null
                            } catch (failure: Exception) {
                                error = failure.message ?: "Check the quantity."
                            }
                        })
                    }
                    basket.values.sortedBy { it.key }.forEach { line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${line.quantityLabel} ${line.sellingUnit} · ${line.product.name} · ${money(line.totalMinor)}",
                                Modifier.weight(1f))
                            TextButton({ basket.remove(line.key) }) { Text("Remove") }
                        }
                    }
                    PosTextField(reason, { reason = it; error = null }, "Reason for exchange", Modifier.fillMaxWidth())
                } else {
                    Text("Returned goods", style = MaterialTheme.typography.titleSmall)
                    preview.returned.forEach { LabelValueRow("${it.quantityLabel} ${it.unit} · ${it.name}", money(it.totalMinor)) }
                    Text(if (preview.restock) "Add returned goods to sellable stock" else "Keep returned goods out of sellable stock",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Replacement goods", style = MaterialTheme.typography.titleSmall)
                    preview.replacements.forEach {
                        LabelValueRow("${it.quantityLabel} ${it.sellingUnit} · ${it.product.name}", money(it.totalMinor))
                    }
                    HorizontalDivider()
                    LabelValueRow("Returned value", money(preview.returnedValueMinor))
                    LabelValueRow("Replacement total", money(preview.replacementTotalMinor))
                    LabelValueRow("Value applied to replacement", money(preview.appliedMinor))
                    when {
                        preview.cashToCollectMinor > 0 -> LabelValueRow("Collect in cash", money(preview.cashToCollectMinor), emphasized = true)
                        preview.cashToRefundMinor > 0 -> LabelValueRow("Refund in cash", money(preview.cashToRefundMinor), emphasized = true)
                        else -> LabelValueRow("Cash difference", money(0), emphasized = true)
                    }
                    Text(preview.reason, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { InlineNotice("Exchange could not be saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            val action = when {
                preview == null -> "Review exchange"
                preview.cashToCollectMinor > 0 -> "Record cash collected and save"
                preview.cashToRefundMinor > 0 -> "Record cash refund and save"
                else -> "Save exchange"
            }
            PosButton(action, {
                error = null
                try {
                    if (preview == null) {
                        val returned = returnTexts.filterValues { it.isNotBlank() }.mapValues { (key, text) ->
                            val line = sale.lines.single { it.key == key }
                            requireNotNull(quantityCount(text, line.quantityPrecision)) {
                                "Check the returned quantity for ${line.product.name}."
                            }
                        }
                        review = repo.reviewCashExchange(sale.tenantId, sale.id, returned, basket.values.sortedBy { it.key },
                            returnToStock, reason)
                    } else onSaved(repo.saveCashExchange(sale.tenantId, preview, operationId))
                } catch (failure: Exception) {
                    error = failure.message ?: "Review the items and try again."
                }
            }, enabled = preview != null || (basket.isNotEmpty() && returnTexts.values.any { it.isNotBlank() } && reason.trim().length in 3..250))
        },
        dismissButton = {
            TextButton({
                if (preview == null) dismiss()
                else { review = null; revision++; error = null }
            }) { Text(if (preview == null) "Cancel" else "Edit exchange") }
        },
    )
}
