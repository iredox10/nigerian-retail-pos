package pos.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pos.domain.*
import pos.data.DraftStore
import pos.data.SaleDraft
import java.math.BigDecimal
import java.util.UUID

/** Callback is a durable commit: null means saved, otherwise the returned error stays visible. */
@Composable
fun SellScreen(
    tenantId: String,
    products: List<Product>,
    customers: List<Customer>,
    onCheckout: (List<CartLine>, Long, String?, String) -> String?,
) {
    val draftStoreResult = remember(tenantId) { runCatching { DraftStore(tenantId) } }
    val draftStore = draftStoreResult.getOrNull()
    val restoredDraft = remember(tenantId) { runCatching { draftStore?.load() } }
    var search by rememberSaveable { mutableStateOf("") }
    // Only IDs and integer quantities are retained by the UI. Prices come from the scoped catalogue.
    var encodedCart by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.cart ?: "") }
    var operationId by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.operationId ?: UUID.randomUUID().toString()) }
    var stage by rememberSaveable { mutableStateOf("items") }
    var method by rememberSaveable { mutableStateOf("cash") }
    var cashText by rememberSaveable { mutableStateOf("") }
    var selectedCustomer by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(draftStoreResult.exceptionOrNull()?.let { "Draft storage unavailable: ${it.message}" } ?: restoredDraft.exceptionOrNull()?.let { "Could not restore saved sale: ${it.message}. Original draft has not been changed." }) }
    var savedTotal by rememberSaveable { mutableStateOf(0L) }
    var savedChange by rememberSaveable { mutableStateOf(0L) }
    var savedCredit by rememberSaveable { mutableStateOf(0L) }
    var committing by remember { mutableStateOf(false) }
    var showSaleOnly by rememberSaveable { mutableStateOf(false) }
    val quantities = remember(encodedCart) {
        encodedCart.split(';').mapNotNull { item ->
            val index = item.lastIndexOf(':')
            if (index < 1) null else item.substring(index + 1).toIntOrNull()?.let { item.substring(0, index) to it }
        }.toMap()
    }
    val lines = products.mapNotNull { p -> quantities[p.id]?.takeIf { it > 0 }?.let { CartLine(p, it) } }
    val totalResult = runCatching { cartTotal(lines) }
    val total = totalResult.getOrDefault(0L)
    val unavailableDraftItems = quantities.keys.any { id -> products.none { it.id == id } }
    fun update(product: Product, quantity: Int) {
        val next = quantities.toMutableMap()
        if (quantity <= 0) next.remove(product.id) else next[product.id] = quantity
        val updated = next.entries.joinToString(";") { "${it.key}:${it.value}" }
        if (draftStore == null || restoredDraft.isFailure) {
            error = "Draft storage is unavailable. Resolve the saved-draft error before editing this sale."
            return
        }
        try {
            draftStore.save(SaleDraft(updated, operationId))
            encodedCart = updated
            error = null
        } catch(e: Exception) { error = "Item change not saved: ${e.message}" }
    }
    val cashGiven = parseNaira(cashText)
    val appliedCash = minOf(cashGiven ?: 0, total)
    val credit = if (method == "credit") total - appliedCash else 0
    val customer = customers.find { it.id == selectedCustomer }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 760.dp
        Column {
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !showSaleOnly, onClick = { showSaleOnly = false }, label = { Text("Choose items") })
                FilterChip(selected = showSaleOnly, onClick = { showSaleOnly = true }, label = { Text("This sale · ${lines.sumOf { it.quantity }}") })
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (!compact || !showSaleOnly) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Choose items", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Search for an item") }, placeholder = { Text("Type a name") }, shape = MaterialTheme.shapes.small)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("All items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("${products.size} in your shop", style = MaterialTheme.typography.bodySmall)
                    }
                    val filtered = products.filter { it.name.contains(search, ignoreCase = true) }
                    if (filtered.isEmpty()) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (products.isEmpty()) "Add your first item" else "No matching items", style = MaterialTheme.typography.titleLarge)
                            Text(if (products.isEmpty()) "Open Stock → Add item to enter goods, selling prices and opening quantities." else "Try a different name or clear your search.")
                            if (search.isNotEmpty()) TextButton(shape = MaterialTheme.shapes.small, onClick = { search = "" }) { Text("Clear search") }
                        }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.id }) { product ->
                            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                            Text(product.name.take(1).uppercase(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(product.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                        Text("${money(product.priceMinor)} / ${product.unit}", style = MaterialTheme.typography.bodyMedium)
                                        Text(if ((quantities[product.id] ?: 0) > 0) "In this sale: ${quantities[product.id]} ${product.unit}" else "${formatStock(product.stock)} ${product.unit} available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { update(product, (quantities[product.id] ?: 0) + 1) }, enabled = stage != "saved" && (quantities[product.id] ?: 0) < product.stock) {
                                        Text("Add to sale")
                                    }
                                }
                            }
                        }
                    }
                    Text("Prices are per selling unit. Stock shown is recorded on this computer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!compact || showSaleOnly) Surface(Modifier.then(if (compact) Modifier.weight(1f) else Modifier.width(380.dp)).fillMaxHeight(), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (stage == "saved") "Sale saved" else "This sale", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            if (stage == "payment") TextButton({ stage = "items"; error = null }) { Text("Back to items") }
                        }
                        if (stage == "saved") {
                            Spacer(Modifier.height(24.dp))
                            Text(if (savedCredit > 0) "Customer owes ${money(savedCredit)}" else "Paid in cash", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Text(money(savedTotal), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 24.dp))
                            if (savedChange > 0) {
                                Text("Change to give", style = MaterialTheme.typography.titleMedium)
                                Text(money(savedChange), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Saved on this computer. Find this receipt in Sales.", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyMedium)
                            Text("Receipt printing and online sync are not connected yet.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { encodedCart = ""; operationId = UUID.randomUUID().toString(); stage = "items"; cashText = ""; selectedCustomer = null; method = "cash"; showSaleOnly = false }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("New sale") }
                        } else {
                            Text("${lines.sumOf { it.quantity }} units · ${lines.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            if (stage == "items") LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (lines.isEmpty()) item {
                                    Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("No items yet", style = MaterialTheme.typography.titleMedium)
                                        Text("Choose an item on the left to start this sale.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                items(lines, key = { it.product.id }) { line ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(line.product.name, fontWeight = FontWeight.Medium)
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${line.quantity} ${line.product.unit}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            Text(money(line.totalMinor), fontWeight = FontWeight.Medium)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { update(line.product, line.quantity - 1) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (line.quantity == 1) "Remove" else "−") }
                                            Text("${line.quantity}")
                                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { update(line.product, line.quantity + 1) }, enabled = line.quantity < line.product.stock, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+") }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("How is the customer paying?", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(method == "cash", { method = "cash"; selectedCustomer = null; error = null }, label = { Text("Cash") })
                                    FilterChip(method == "credit", { method = "credit"; error = null }, label = { Text("Pay later / part cash") })
                                }
                                Text("Bank transfers and card terminals are not integrated. Record only cash or explicit customer credit here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(cashText, { cashText = it; error = null }, label = { Text(if (method == "cash") "Cash given by customer" else "Cash paid now (enter 0 if none)") }, prefix = { Text("₦ ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                TextButton({ cashText = BigDecimal(total).movePointLeft(2).toPlainString() }) { Text("Exact amount") }
                                if (method == "credit") {
                                    Text("Who will pay the balance?", fontWeight = FontWeight.Medium)
                                    if (customers.isEmpty()) Text("Add the customer under Customers before granting credit.", color = MaterialTheme.colorScheme.error)
                                    customers.forEach { c -> FilterChip(selectedCustomer == c.id, { selectedCustomer = c.id }, label = { Text(c.name) }) }
                                    customer?.let {
                                        val newDebt = runCatching { Math.addExact(it.balanceMinor, credit) }.getOrNull()
                                        Text("Already owes ${money(it.balanceMinor)}\nAfter this sale: ${newDebt?.let(::money) ?: "Amount exceeds supported range"}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (cashGiven != null) {
                                    HorizontalDivider()
                                    Text(if (cashGiven!! >= total) "Change to give ${money(cashGiven!! - total)}" else "Still to pay ${money(total - cashGiven!!)}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (unavailableDraftItems) Text("Some saved items are no longer in this shop's catalogue. This sale cannot be completed until those items are recovered.", color = MaterialTheme.colorScheme.error)
                            totalResult.exceptionOrNull()?.let { Text("Sale total exceeds the supported amount. Reduce a quantity before payment.", color = MaterialTheme.colorScheme.error) }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                            HorizontalDivider()
                            Spacer(Modifier.height(16.dp))
                            Text("Total", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(total), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
                            Button(shape = MaterialTheme.shapes.small, onClick = {
                                if (stage == "items") { stage = "payment"; return@Button }
                                val cash = cashGiven
                                when {
                                    cash == null -> error = "Enter a valid cash amount, with no more than two decimal places."
                                    method == "cash" && cash < total -> error = "Still to pay ${money(total - cash)}. Choose Pay later / part cash only if you are granting customer credit."
                                    method == "credit" && credit > 0 && customer == null -> error = "Choose the customer who will pay the balance."
                                    else -> {
                                        committing = true
                                        val result = try { onCheckout(lines, appliedCash, if (credit > 0) customer?.id else null, operationId) } catch (e: Exception) { e.message ?: "Sale not saved" }
                                        committing = false
                                        if (result == null) {
                                            savedTotal = total; savedChange = maxOf(0, cash - total); savedCredit = credit; stage = "saved"
                                            try { draftStore?.clear() } catch(e: Exception) { error = "Sale saved, but the old draft could not be removed. Its operation reference prevents a duplicate checkout." }
                                        } else error = result
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = lines.isNotEmpty() && !committing && totalResult.isSuccess && !unavailableDraftItems && draftStore != null && restoredDraft.isSuccess) {
                                Text(if (committing) "Saving…" else if (stage == "items") "Take payment" else if (method == "credit" && credit > 0) "Save with ${money(credit)} owed" else "Finish sale")
                            }
                            if (stage == "items" && lines.isEmpty()) Text("Add an item to take payment", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            if (stage == "items" && lines.isNotEmpty()) Text("Draft only · finish payment to record this sale", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun formatStock(stock: Double): String = if (stock % 1.0 == 0.0) stock.toLong().toString() else stock.toString()
