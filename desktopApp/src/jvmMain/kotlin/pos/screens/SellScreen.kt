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
import pos.data.HeldSale
import androidx.compose.ui.input.key.*
import java.math.BigDecimal
import java.util.UUID

/** Callback is a durable commit: null means saved, otherwise the returned error stays visible. */
@Composable
fun SellScreen(
    tenantId: String,
    products: List<Product>,
    customers: List<Customer>,
    onCheckout: (List<CartLine>, Long, String?, String, String?) -> String?,
) {
    val draftStoreResult = remember(tenantId) { runCatching { DraftStore(tenantId) } }
    val draftStore = draftStoreResult.getOrNull()
    val restoredDraft = remember(tenantId) { runCatching { draftStore?.load() } }
    var search by rememberSaveable { mutableStateOf("") }
    // Only IDs and integer quantities are retained by the UI. Prices come from the scoped catalogue.
    var encodedCart by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.cart ?: "") }
    var operationId by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.operationId ?: UUID.randomUUID().toString()) }
    var stage by rememberSaveable { mutableStateOf("items") }
    var method by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.method ?: "cash") }
    var cashText by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.cashText ?: "") }
    var selectedCustomer by rememberSaveable { mutableStateOf<String?>(restoredDraft.getOrNull()?.customerId) }
    var dueDateText by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.dueDateText ?: "") }
    var error by remember { mutableStateOf<String?>(draftStoreResult.exceptionOrNull()?.let { "Draft storage unavailable: ${it.message}" } ?: restoredDraft.exceptionOrNull()?.let { "Could not restore saved sale: ${it.message}. Original draft has not been changed." }) }
    var savedTotal by rememberSaveable { mutableStateOf(0L) }
    var savedChange by rememberSaveable { mutableStateOf(0L) }
    var savedCredit by rememberSaveable { mutableStateOf(0L) }
    var committing by remember { mutableStateOf(false) }
    var showSaleOnly by rememberSaveable { mutableStateOf(false) }
    val heldResult = remember(tenantId) { runCatching { draftStore?.heldSales().orEmpty() } }
    var heldSales by remember { mutableStateOf(heldResult.getOrDefault(emptyList())) }
    var showHeld by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var holdName by remember { mutableStateOf("") }
    var heldError by remember { mutableStateOf<String?>(heldResult.exceptionOrNull()?.message) }
    var discard by remember { mutableStateOf<HeldSale?>(null) }
    var reviewNotice by remember { mutableStateOf<String?>(null) }
    fun currentDraft() = SaleDraft(encodedCart,operationId,cashText,selectedCustomer,method,dueDateText)
    fun persistPayment(cash:String=cashText,customerId:String?=selectedCustomer,paymentMethod:String=method,due:String=dueDateText) {
        try {
            require(draftStore!=null && restoredDraft.isSuccess) { "Draft storage is unavailable." }
            draftStore.save(SaleDraft(encodedCart,operationId,cash,customerId,paymentMethod,due))
            cashText=cash;selectedCustomer=customerId;method=paymentMethod;dueDateText=due;error=null
        } catch(e:Exception) { error="Payment details not saved: ${e.message}" }
    }
    fun resetCurrent() { encodedCart="";operationId=UUID.randomUUID().toString();stage="items";cashText="";selectedCustomer=null;method="cash";dueDateText="";search="";showSaleOnly=false;error=null;reviewNotice=null }
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
            draftStore.save(SaleDraft(updated, operationId,cashText,selectedCustomer,method,dueDateText))
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
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        if(event.key==Key.Enter && event.type==KeyEventType.KeyDown) {
                            val match=products.singleOrNull { it.barcode==search.trim() && search.isNotBlank() }
                            if(match==null) error="No item has this barcode. Choose a matching item or add its barcode under Stock."
                            else if(stage=="saved") error="Start a new sale before scanning another item."
                            else if((quantities[match.id] ?: 0)>=match.stock) error="Not enough ${match.unit} of ${match.name} in stock."
                            else { update(match,(quantities[match.id] ?: 0)+1);search="" }
                            true
                        } else false
                    }, singleLine = true,
                        label = { Text("Search for an item") }, placeholder = { Text("Type a name or scan a barcode, then Enter") }, shape = MaterialTheme.shapes.small)
                    if(compact && !showSaleOnly) error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("All items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("${products.size} in your shop", style = MaterialTheme.typography.bodySmall)
                    }
                    val filtered = products.filter { it.name.contains(search, ignoreCase = true) || it.unit.contains(search,ignoreCase=true) || it.barcode?.contains(search.trim())==true }
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
                            Text("Print or export this receipt from Sales. Online sync is not connected yet.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp)) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { resetCurrent() }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("New sale") }
                        } else {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton({holding=true;holdName="";heldError=null},enabled=encodedCart.isNotEmpty()) { Text("Put sale aside") }
                                TextButton({showHeld=true;try {heldSales=requireNotNull(draftStore).heldSales();heldError=null} catch(e:Exception) {heldError=e.message ?: "Saved sales could not be read."}}) { Text("Saved sales (${heldSales.size})") }
                            }
                            reviewNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
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
                                    FilterChip(method == "cash", { persistPayment(customerId=null,paymentMethod="cash") }, label = { Text("Cash") })
                                    FilterChip(method == "credit", { persistPayment(paymentMethod="credit") }, label = { Text("Pay later / part cash") })
                                }
                                Text("Bank transfers and card terminals are not integrated. Record only cash or explicit customer credit here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(cashText, { persistPayment(cash=it) }, label = { Text(if (method == "cash") "Cash given by customer" else "Cash paid now (enter 0 if none)") }, prefix = { Text("₦ ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                TextButton({ persistPayment(cash=BigDecimal(total).movePointLeft(2).toPlainString()) }) { Text("Exact amount") }
                                if (method == "credit") {
                                    Text("Who will pay the balance?", fontWeight = FontWeight.Medium)
                                    if (customers.isEmpty()) Text("Add the customer under Customers before granting credit.", color = MaterialTheme.colorScheme.error)
                                    customers.forEach { c -> FilterChip(selectedCustomer == c.id, { persistPayment(customerId=c.id) }, label = { Text(c.name) }) }
                                    customer?.let {
                                        val newDebt = runCatching { Math.addExact(it.balanceMinor, credit) }.getOrNull()
                                        Text("Already owes ${money(it.balanceMinor)}\nAfter this sale: ${newDebt?.let(::money) ?: "Amount exceeds supported range"}", style = MaterialTheme.typography.bodySmall)
                                        Text(it.creditLimitMinor?.let { cap -> "Credit limit: ${money(cap)}" } ?: "Credit limit: not set",style=MaterialTheme.typography.bodySmall)
                                        if(credit>0 && it.creditLimitMinor!=null && (newDebt==null || newDebt>it.creditLimitMinor!!)) Text("This sale exceeds the customer's credit limit. Collect more cash or ask the owner to review the limit under Customers.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if(method=="credit") {
                                    OutlinedTextField(dueDateText,{persistPayment(due=it)},label={Text("Repayment due date (optional)")},placeholder={Text("YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                    Text("Leave blank if no date was agreed. Dates use West Africa Time. A due date does not send a reminder.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (cashGiven != null) {
                                    HorizontalDivider()
                                    Text(if (cashGiven!! >= total) "Change to give ${money(cashGiven!! - total)}" else "Still to pay ${money(total - cashGiven!!)}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (unavailableDraftItems) {
                                Text("Some saved items are archived or unavailable. Restore them under Stock or remove them before payment.",color=MaterialTheme.colorScheme.error)
                                TextButton({
                                    try {
                                        val updated=quantities.filterKeys { id -> products.any { it.id==id } }.entries.joinToString(";") { "${it.key}:${it.value}" }
                                        requireNotNull(draftStore).save(currentDraft().copy(cart=updated));encodedCart=updated;error=null
                                    }catch(e:Exception) {error="The sale could not be changed: ${e.message}"}
                                }) {Text("Remove unavailable items from this sale")}
                            }
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
                                    method=="credit" && credit>0 && dueDateText.isNotBlank() && runCatching { val date=java.time.LocalDate.parse(dueDateText.trim()); require(date.toString()==dueDateText.trim() && !date.isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")))) }.isFailure -> error="Use YYYY-MM-DD for a due date today or later."
                                    else -> {
                                        committing = true
                                        val result = try { onCheckout(lines, appliedCash, if (credit > 0) customer?.id else null, operationId, if(credit>0) dueDateText.trim().ifBlank { null } else null) } catch (e: Exception) { e.message ?: "Sale not saved" }
                                        committing = false
                                        if (result == null) {
                                            savedTotal = total; savedChange = maxOf(0, cash - total); savedCredit = credit; stage = "saved"
                                            try { draftStore?.clear(operationId) } catch(e: Exception) { error = "Sale saved, but the old draft could not be removed. Its operation reference prevents a duplicate checkout." }
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
    if(holding) AlertDialog(onDismissRequest={holding=false},title={Text("Put sale aside")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Save this unfinished sale on this computer. Stock is not reserved. Prices and availability will be checked when you return.")
        OutlinedTextField(holdName,{holdName=it},label={Text("Name to recognize this sale")},singleLine=true)
        heldError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton({
        try { requireNotNull(draftStore).hold(holdName,currentDraft());heldSales=draftStore.heldSales();resetCurrent();holding=false;reviewNotice="Sale put aside on this computer." }
        catch(e:Exception) {heldError=e.message ?: "Sale could not be put aside."}
    }) {Text("Save & start another sale")}},dismissButton={TextButton({holding=false}) {Text("Cancel")}})
    if(showHeld) AlertDialog(onDismissRequest={showHeld=false},title={Text("Saved sales (${heldSales.size})")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Saved on this computer only. Stock is not reserved. Resuming uses current prices and availability.")
        if(encodedCart.isNotEmpty()) Text("Put this sale aside first, then resume another.",color=MaterialTheme.colorScheme.error)
        if(heldSales.isEmpty())Text("No sales put aside yet.")
        heldSales.forEach { held ->
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(held.name,fontWeight=FontWeight.SemiBold)
                Text("${held.draft.cart.split(';').size} items · "+java.time.Instant.parse(held.savedAt).atZone(java.time.ZoneId.of("Africa/Lagos")).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'WAT'")),style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton({
                        try {
                            val resumed=requireNotNull(draftStore).resume(held.id)
                            encodedCart=resumed.cart;operationId=resumed.operationId;cashText=resumed.cashText;selectedCustomer=resumed.customerId;method=resumed.method;dueDateText=resumed.dueDateText;stage="items";error=null;heldSales=draftStore.heldSales();showHeld=false;showSaleOnly=true
                            reviewNotice="Resumed sale: check current prices, quantities and payment before finishing."
                        }catch(e:Exception) {heldError=e.message ?: "Sale could not be resumed."}
                    },enabled=encodedCart.isEmpty()) {Text("Resume sale")}
                    TextButton({discard=held}) {Text("Remove saved sale")}
                }
                HorizontalDivider()
            }
        }
        heldError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton({showHeld=false}) {Text("Close")}})
    discard?.let { held -> AlertDialog(onDismissRequest={discard=null},title={Text("Remove ${held.name}?")},text={Text("This removes the unfinished basket only. No sale, stock movement or payment will be recorded.")},confirmButton={TextButton({
        try {requireNotNull(draftStore).discardHeld(held.id);heldSales=draftStore.heldSales();discard=null;heldError=null} catch(e:Exception) {heldError=e.message;discard=null}
    }) {Text("Remove saved sale")}},dismissButton={TextButton({discard=null}) {Text("Keep sale")}}) }
}

private fun formatStock(stock: Double): String = if (stock % 1.0 == 0.0) stock.toLong().toString() else stock.toString()
