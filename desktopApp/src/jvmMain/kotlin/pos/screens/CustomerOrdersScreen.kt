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
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*

@Composable
fun CustomerOrdersScreen(repo: PosRepository, tenantId: String, onDispatch: ((String) -> Unit)? = null, onChanged: () -> Unit) = key(tenantId) {
    var revision by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var includeClosed by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = remember(revision) { runCatching { repo.customerOrders(tenantId) } }
    val orders = loaded.getOrNull().orEmpty()
    val selected = orders.firstOrNull { it.order.id == selectedId }
    val reservationHistory = remember(selected?.order?.id, revision) {
        selected?.let { runCatching { repo.customerOrderReservationHistory(tenantId, it.order.id) } }
    }
    val reservations = reservationHistory?.getOrNull().orEmpty()
    val latestReservation = reservations.lastOrNull()
    var today by remember(selected?.order?.id) { mutableStateOf(orderReservationDate(Instant.now())) }
    LaunchedEffect(selected?.order?.id) {
        while (selected != null) {
            delay(30_000)
            today = orderReservationDate(Instant.now())
        }
    }
    val stockHeld = selected?.open == true && latestReservation?.heldOn(today) == true
    fun refresh(message: String) {
        notice = message
        action = null
        revision++
        onChanged()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Customer orders", "Track goods ordered, deposits and collection.", Modifier.weight(1f))
            if (selected == null) PosButton("New order", { action = "create" })
            else NeutralButton("Back to orders", { selectedId = null })
        }
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        loaded.exceptionOrNull()?.let { InlineNotice("Orders unavailable", it.message.orEmpty(), NoticeTone.Danger) }
        if (selected == null && loaded.isSuccess) {
            PosTextField(search, { search = it }, "Find an order", Modifier.fillMaxWidth(), hint = "Customer name or order reference")
            FilterChip(includeClosed, { includeClosed = !includeClosed }, label = { Text("Include completed and cancelled orders") })
            val matches = orders.filter { (includeClosed || it.open) &&
                (it.order.customerName.contains(search, true) || it.order.id.contains(search, true)) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (matches.isEmpty()) item { EmptyState("No matching orders", "Create an order or change your search.") }
                items(matches, key = { it.order.id }) { state ->
                    Surface(onClick = { selectedId = state.order.id; notice = null }, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabelValueRow(state.order.customerName, money(state.order.totalMinor), emphasized = true)
                            Text("Order ${state.order.id.take(8)} · ${orderStatus(state)}", style = MaterialTheme.typography.bodySmall)
                            state.order.collectOn?.let { Text("Collection date $it (WAT)", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (selected != null) {
            val order = selected.order
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader(order.customerName, "Order ${order.id.take(8)} · ${orderStatus(selected)} · ${order.source}")
                            order.collectOn?.let { Text("Collection date $it (WAT)") }
                            if (order.note.isNotEmpty()) Text(order.note)
                            LabelValueRow("Original order total", money(order.totalMinor))
                            if (selected.open) {
                                LabelValueRow("Value of goods waiting for collection", money(selected.remainingValueMinor))
                                LabelValueRow("Deposit available", money(selected.depositLeftMinor))
                                LabelValueRow("Remaining amount to pay", money(selected.balanceMinor), emphasized = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PosButton("Record collection", { action = "collect" })
                                    NeutralButton("Record deposit", { action = "deposit" }, enabled = selected.balanceMinor > 0)
                                    TextButton({ action = "cancel" }) { Text("Cancel remaining order") }
                                }
                            }
                            if (onDispatch != null && selected.history.any { it.kind == "collected" }) {
                                NeutralButton("Dispatch & delivery", { onDispatch(order.id) })
                            }
                            val holdError = reservationHistory?.exceptionOrNull()
                            if (holdError != null) {
                                InlineNotice("Stock holds unavailable", holdError.message.orEmpty(), NoticeTone.Danger)
                            } else if (selected.open || latestReservation != null) {
                                LabelValueRow("Stock hold", orderReservationStatus(selected, latestReservation, today))
                                if (selected.open) {
                                    NeutralButton(if (stockHeld) "Release stock hold" else "Hold stock",
                                        { action = if (stockHeld) "release" else "hold" })
                                }
                            }
                        }
                    }
                }
                items(order.lines, key = { it.key }) { line ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(line.productName, style = MaterialTheme.typography.titleSmall)
                            LabelValueRow("Ordered", "${line.quantityLabel} ${line.sellingUnit}")
                            LabelValueRow("Agreed price", "${money(line.agreedUnitPriceMinor)} / ${line.sellingUnit}")
                            if (!selected.cancelled) LabelValueRow("Still to collect",
                                "${quantityLabel(selected.remaining.getValue(line.key).toLong(), line.quantityPrecision)} ${line.sellingUnit}")
                        }
                    }
                }
                item { Text("Order history", style = MaterialTheme.typography.titleMedium) }
                items(selected.history.reversed(), key = { it.id }) { event ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${event.reason} · ${receiptDate(event.createdAt)}")
                            if (event.amountMinor > 0) LabelValueRow(when (event.kind) {
                                "deposit" -> "Cash deposit"
                                "cancelled" -> "Deposit refunded"
                                else -> "Deposit used"
                            }, money(event.amountMinor))
                            event.saleId?.let { Text("Sale receipt $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item { Text("Order created · ${receiptDate(order.createdAt)}", style = MaterialTheme.typography.bodySmall) }
                if (reservations.isNotEmpty()) item { Text("Stock hold history", style = MaterialTheme.typography.titleMedium) }
                items(reservations.reversed(), key = { "reservation:${it.id}" }) { event ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val label = event.expiresOn?.let { "Stock held through $it (WAT)" } ?: "Stock hold released"
                            Text("$label · ${receiptDate(event.createdAt)}")
                            if (event.expiresOn == null) Text(event.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (action == "create") OrderEditor(repo, tenantId, { action = null }) {
        selectedId = it.id
        refresh("Order saved on this computer.")
    }
    selected?.let { state ->
        when (action) {
            "hold", "release" -> OrderReservationDialog(repo, state, reservations.size, action == "release",
                { action = null }, ::refresh)
            "collect" -> CollectOrderDialog(repo, state, { action = null }) {
                refresh("Collection saved. Receipt ${it.saleId?.take(8)}.")
            }
            "deposit", "cancel" -> OrderCashDialog(repo, state, action == "cancel", { action = null }) {
                refresh(if (it.kind == "cancelled") "Remaining order cancelled." else "Cash deposit saved.")
            }
        }
    }
}

private fun orderStatus(state: CustomerOrderState): String = when {
    state.cancelled -> "Cancelled"
    state.completed -> "Collected"
    state.history.any { it.kind == "collected" } -> "Part collected"
    else -> "Waiting for collection"
}

@Composable
private fun OrderEditor(repo: PosRepository, tenantId: String, dismiss: () -> Unit, saved: (CustomerOrder) -> Unit) {
    val data = remember { runCatching { Triple(repo.customers(tenantId), repo.products(tenantId), repo.sellingUnits(tenantId)) } }
    var customer by remember { mutableStateOf<Customer?>(null) }
    var customerMenu by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("In shop") }
    var sourceMenu by remember { mutableStateOf(false) }
    var collectOn by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<Product?>(null) }
    var sellingUnit by remember { mutableStateOf<SellingUnit?>(null) }
    var unitMenu by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateMapOf<String, CartLine>() }
    val operation = remember(customer, source, collectOn, note, lines.toMap()) { UUID.randomUUID().toString() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("New customer order") }, text = {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            data.exceptionOrNull()?.let { InlineNotice("Order details unavailable", it.message.orEmpty(), NoticeTone.Danger) }
            Box {
                NeutralButton(customer?.name ?: "Choose customer", { customerMenu = true })
                DropdownMenu(customerMenu, { customerMenu = false }, Modifier.heightIn(max = 250.dp)) {
                    data.getOrNull()?.first.orEmpty().forEach { option ->
                        DropdownMenuItem({ Text(option.phone?.let { "${option.name} · $it" } ?: option.name) }, {
                            customer = option
                            customerMenu = false
                        })
                    }
                }
            }
            if (data.isSuccess && data.getOrThrow().first.isEmpty()) Text("Add a profile under Customers to start an order.")
            Box {
                NeutralButton("Source · $source", { sourceMenu = true })
                DropdownMenu(sourceMenu, { sourceMenu = false }) {
                    listOf("In shop", "Phone", "WhatsApp", "Other").forEach { option ->
                        DropdownMenuItem({ Text(option) }, { source = option; sourceMenu = false })
                    }
                }
            }
            PosTextField(collectOn, { collectOn = it }, "Collection date (optional)", hint = "YYYY-MM-DD, West Africa Time")
            PosTextField(note, { note = it }, "Order note (optional)")
            HorizontalDivider()
            if (item == null) {
                PosTextField(search, { search = it }, "Find an item")
                data.getOrNull()?.second.orEmpty().filter { it.matchesCatalogueSearch(search) }.take(5).forEach { option ->
                    NeutralButton("${option.name} · ${money(option.priceMinor)} / ${option.unit}", {
                        item = option; sellingUnit = null; quantity = "1"
                    }, Modifier.fillMaxWidth())
                }
            }
            item?.let { product ->
                val alternatives = data.getOrNull()?.third.orEmpty().filter { it.productId == product.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(product.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton({ item = null }) { Text("Change item") }
                }
                if (alternatives.isNotEmpty()) Box {
                    NeutralButton("Sell as ${sellingUnit?.name ?: product.unit}", { unitMenu = true })
                    DropdownMenu(unitMenu, { unitMenu = false }) {
                        DropdownMenuItem({ Text(product.unit) }, { sellingUnit = null; unitMenu = false })
                        alternatives.forEach { unit ->
                            DropdownMenuItem({ Text("${unit.name} · ${unit.baseUnitsPerUnit} ${product.unit}") }, { sellingUnit = unit; unitMenu = false })
                        }
                    }
                }
                PosTextField(quantity, { quantity = it }, "Quantity in ${sellingUnit?.name ?: product.unit}")
                NeutralButton("Add to order", {
                    try {
                        val unit = sellingUnit
                        val precision = if (unit == null) product.quantityPrecision else 0
                        val count = requireNotNull(quantityCount(quantity, precision)) { "Check the quantity." }
                        val line = CartLine(product, count, unit?.id, unit?.name ?: product.unit,
                            unit?.priceMinor ?: product.priceMinor, unit?.baseUnitsPerUnit ?: 1)
                        lines[line.key] = line.copy(quantity = Math.addExact(lines[line.key]?.quantity ?: 0, count))
                        item = null; search = ""; error = null
                    } catch (failure: Exception) { error = failure.message ?: "Check the quantity." }
                })
            }
            lines.values.sortedBy { it.key }.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${line.quantityLabel} ${line.sellingUnit} · ${line.product.name} · ${money(line.totalMinor)}", Modifier.weight(1f))
                    TextButton({ lines.remove(line.key) }) { Text("Remove") }
                }
            }
            val total = runCatching { cartTotal(lines.values.toList()) }
            total.getOrNull()?.let { LabelValueRow("Order total", money(it), emphasized = true) }
            error?.let { InlineNotice("Order could not be saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        PosButton("Save order", {
            try {
                saved(repo.createCustomerOrder(tenantId, requireNotNull(customer).id, source, collectOn.trim().ifBlank { null },
                    note, lines.values.map { QuotationLineInput(it.product.id, it.sellingUnitId, it.quantity, it.unitPriceMinor) }, operation))
            } catch (failure: Exception) { error = failure.message ?: "Check the order details." }
        }, enabled = customer != null && lines.isNotEmpty())
    }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
private fun OrderCashDialog(
    repo: PosRepository, state: CustomerOrderState, cancel: Boolean, dismiss: () -> Unit, saved: (CustomerOrderEvent) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operation = remember(input) { UUID.randomUUID().toString() }
    val amount = parseNaira(input)
    val action = if (!cancel) "Record cash received" else if (state.depositLeftMinor > 0) "Record refund and cancel" else "Cancel order"
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (cancel) "Cancel remaining order" else "Record deposit") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.order.customerName)
            if (cancel) {
                LabelValueRow("Refund unspent deposit in cash", money(state.depositLeftMinor), emphasized = true)
                PosTextField(input, { input = it }, "Reason for cancelling")
            } else {
                LabelValueRow("Remaining amount on order", money(state.balanceMinor))
                PosTextField(input, { input = it }, "Cash received (₦)")
            }
            error?.let { InlineNotice("Order could not be updated", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        PosButton(action, {
            try {
                val order = state.order
                saved(if (cancel) repo.cancelCustomerOrder(order.tenantId, order.id, state.history.size, state.depositLeftMinor, input, operation)
                    else repo.recordCustomerOrderDeposit(order.tenantId, order.id, requireNotNull(amount), state.history.size, operation))
            } catch (failure: Exception) { error = failure.message ?: "Review the amount and try again." }
        }, enabled = if (cancel) input.trim().length in 3..250 else amount != null && amount in 1..state.balanceMinor)
    }, dismissButton = { TextButton(dismiss) { Text("Back") } })
}

@Composable
private fun CollectOrderDialog(
    repo: PosRepository, state: CustomerOrderState, dismiss: () -> Unit, saved: (CustomerOrderEvent) -> Unit,
) {
    val quantities = remember { mutableStateMapOf<String, String>() }
    var review by remember { mutableStateOf<OrderCollectionReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val checked = review
    val operation = remember(checked) { UUID.randomUUID().toString() }
    val order = state.order
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (checked == null) "Choose goods collected" else "Review collection") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(order.customerName)
            if (checked == null) {
                Text("Leave uncollected goods blank.", style = MaterialTheme.typography.bodySmall)
                order.lines.filter { state.remaining.getValue(it.key) > 0 }.forEach { line ->
                    PosTextField(quantities[line.key].orEmpty(), { quantities[line.key] = it },
                        "${line.productName} · ${line.sellingUnit}", hint = "Up to ${quantityLabel(state.remaining.getValue(line.key).toLong(), line.quantityPrecision)}")
                }
            } else {
                checked.lines.forEach { line -> LabelValueRow("${line.quantityLabel} ${line.sellingUnit} · ${line.product.name}", money(line.totalMinor)) }
                LabelValueRow("Deposit applied", money(checked.depositMinor))
                LabelValueRow("Cash to collect now", money(checked.cashMinor), emphasized = true)
            }
            error?.let { InlineNotice("Collection could not be saved", it, NoticeTone.Danger) }
        }
    }, confirmButton = {
        val action = if (checked == null) "Review collection" else if (checked.cashMinor > 0) "Record cash and collection" else "Save collection"
        PosButton(action, {
            try {
                if (checked != null) saved(repo.collectCustomerOrder(order.tenantId, checked, operation))
                else {
                    val chosen = quantities.filterValues { it.isNotBlank() }.mapValues { (key, value) ->
                        val line = order.lines.single { it.key == key }
                        requireNotNull(quantityCount(value, line.quantityPrecision)) { "Check the quantity for ${line.productName}." }
                    }
                    review = repo.reviewCustomerOrderCollection(order.tenantId, order.id, chosen)
                }
                error = null
            } catch (failure: Exception) { error = failure.message ?: "Review the goods and payment again." }
        }, enabled = checked != null || quantities.values.any { it.isNotBlank() })
    }, dismissButton = { TextButton({ if (checked == null) dismiss() else { review = null; error = null } }) {
        Text(if (checked == null) "Cancel" else "Edit collection")
    } })
}
