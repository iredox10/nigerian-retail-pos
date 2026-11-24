package pos.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Android uses the same durable checkout, quantities and liabilities as the desktop. */
@Composable
fun AndroidSellScreen(
    repo: PosRepository, tenantId: String, onChanged: () -> Unit, onReceipt: (String) -> Unit,
    onRequestApproval: ((List<CartLine>, String, (String) -> Unit) -> Unit)? = null,
    allowDiscounts: Boolean = true,
) = key(tenantId) {
    val storeResult = remember { runCatching { DraftStore(tenantId) } }
    val restored = remember { runCatching { storeResult.getOrThrow().load() } }
    var draft by remember { mutableStateOf(restored.getOrNull() ?: SaleDraft("", UUID.randomUUID().toString())) }
    var revision by remember { mutableIntStateOf(0) }
    val cloudRevision=pos.cloud.ui.LocalCloudRevision.current
    val catalogue = remember(revision,cloudRevision) { runCatching { repo.products(tenantId) to repo.sellingUnits(tenantId) } }
    val products = catalogue.getOrNull()?.first.orEmpty()
    val units = catalogue.getOrNull()?.second.orEmpty()
    val customers = remember(revision,cloudRevision) { runCatching { repo.customers(tenantId) } }
    val reservations = remember(revision,cloudRevision) { runCatching { repo.orderReservedQuantities(tenantId) } }
    var search by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("items") }
    var editing by remember { mutableStateOf<CartLine?>(null) }
    var adding by remember { mutableStateOf<Product?>(null) }
    var holding by remember { mutableStateOf(false) }
    var heldOpen by remember { mutableStateOf(false) }
    var holdName by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf<SellPaymentReview?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var approvalId by remember { mutableStateOf<String?>(null) }
    var approvalFingerprint by remember { mutableStateOf<String?>(null) }
    var approvalRefresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(runCatching {
        if (draft.cart.isEmpty()) null else repo.sales(tenantId).firstOrNull { it.id == draft.operationId }
    }.getOrNull()) }
    val scope = rememberCoroutineScope()
    val lineResult = remember(draft, catalogue) { runCatching { sellDraftLines(draft, products, units) } }
    val lines = lineResult.getOrDefault(emptyList())
    val totalResult = remember(lines) { runCatching { cartTotal(lines) } }
    val total = totalResult.getOrDefault(0L)
    val approvalResult = remember(lines, draft.operationId, revision, approvalRefresh) {
        runCatching { if (lines.isEmpty()) null else repo.minimumPriceApprovalRequest(tenantId, draft.operationId, lines) }
    }
    val priceRequest = approvalResult.getOrNull()
    val validApproval = approvalId?.takeIf { approvalFingerprint == priceRequest?.fingerprint }
    val approvalNeeded = priceRequest?.let { it.belowFloor || (!allowDiscounts && it.hasDiscounts) } ?: false
    val loadError = restored.exceptionOrNull() ?: storeResult.exceptionOrNull() ?: catalogue.exceptionOrNull() ?: reservations.exceptionOrNull()
    fun persist(next: SaleDraft): Boolean {
        if (busy || completed != null || restored.isFailure) return false
        return try {
            storeResult.getOrThrow().save(next)
            if (next.method != draft.method || next.externalMethod != draft.externalMethod ||
                next.externalAmountText != draft.externalAmountText || next.externalReference != draft.externalReference ||
                next.externalAccount != draft.externalAccount) acknowledged = false
            draft = next; reviewed = null; error = null; notice = null
            true
        } catch (failure: Exception) { error = failure.message ?: "This sale could not be saved on this phone."; false }
    }
    fun changeLines(next: List<CartLine>): Boolean {
        val encoded = next.joinToString(";") { "${it.key}:${it.quantity}" }
        val discounts = next.filter { it.discountPerUnitMinor > 0 }.associate { it.key to UnitDiscount(it.unitPriceMinor, it.discountPerUnitMinor, requireNotNull(it.discountReason)) }
        return persist(draft.copy(cart = encoded, discounts = discounts, basketDiscount = null, basketBasis = null))
    }
    fun resetAfterClear() {
        draft = SaleDraft("", UUID.randomUUID().toString()); completed = null; stage = "items"
        reviewed = null; acknowledged = false; search = ""; error = null; notice = null; revision++
        approvalId = null; approvalFingerprint = null; approvalRefresh++
    }
    fun addBarcode() {
        val barcode = search.trim()
        val pack = units.firstOrNull { it.barcode == barcode }
        val product = products.firstOrNull { it.barcode == barcode } ?: pack?.let { p -> products.firstOrNull { it.id == p.productId } }
        if (product == null) { error = "No item matches this barcode. Search by name or add it in Stock."; return }
        try {
            val step = if (pack == null) Math.toIntExact(quantityScaleFactor(product.quantityPrecision)) else 1
            val option = if (pack == null) CartLine(product, step) else CartLine(product, 1, pack.id, pack.name, pack.priceMinor, pack.baseUnitsPerUnit)
            val previous = lines.firstOrNull { it.key == option.key }
            val updated = previous?.copy(quantity = Math.addExact(previous.quantity, step)) ?: option
            val candidate = lines.filterNot { it.key == option.key } + updated
            val available = product.unreservedStock(reservations.getOrThrow()[product.id] ?: 0L)
            require(candidate.filter { it.product.id == product.id }.fold(0L) { sum, line -> Math.addExact(sum, line.baseQuantity) } <= available) { "Not enough available stock for this quantity." }
            if (changeLines(candidate)) search = ""
        } catch (failure: Exception) { error = failure.message }
    }
    val held = remember(revision, heldOpen) { runCatching { storeResult.getOrThrow().heldSales() } }
    if (completed != null) {
        val sale = requireNotNull(completed)
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Sale saved", "Saved on this phone · ${sale.id.takeLast(8)}")
            MoneyText(sale.totalMinor, large = true)
            LabelValueRow("Cash applied", money(sale.cashMinor))
            sale.cashGivenMinor?.let { LabelValueRow("Cash given", money(it)) }
            sale.changeMinor?.let { LabelValueRow("Change to give", money(it), emphasized = true) }
            sale.externalPayments.forEach { LabelValueRow(it.label, money(it.amountMinor)) }
            if (sale.creditMinor > 0) LabelValueRow("Customer still owes", money(sale.creditMinor), emphasized = true)
            if (sale.externalPayments.isNotEmpty()) InlineNotice("Payment not confirmed", "The app has not confirmed that the transfer or card money arrived.", NoticeTone.Warning)
            PosButton("Print / share receipt", {
                runCatching { onReceipt(sale.id) }.onFailure { error = "Sale saved. Receipt could not open: ${it.message.orEmpty()}" }
            }, Modifier.fillMaxWidth())
            NeutralButton("New sale", {
                try { storeResult.getOrThrow().clear(sale.id); resetAfterClear() }
                catch (failure: Exception) { error = "Sale saved. The old basket could not be cleared: ${failure.message.orEmpty()}" }
            }, Modifier.fillMaxWidth())
            error?.let { InlineNotice("Sale remains saved", it, NoticeTone.Warning) }
        }
    }
    if (completed == null) {
    fun finish(review: SellPaymentReview) {
        if (busy || review != reviewed || review.draft != draft) return
        // The exact grant is checked again by the repository against the current cart, policy and identities.
        require(approvalResult.isSuccess) { approvalResult.exceptionOrNull()?.message ?: "Current price rules could not be checked." }
        require(!approvalNeeded || validApproval != null) { "Ask a manager to approve this exact sale price before finishing." }
        val priceApproval = validApproval
        busy = true; error = null
        scope.launch {
            try {
                val sale = withContext(Dispatchers.IO) {
                    repo.checkout(tenantId, review.lines, review.cashMinor, review.customerId, draft.operationId,
                        review.dueDate, draft.basketDiscount, review.external, review.cashGivenMinor,
                        draft.note.trim().ifEmpty { null }, priceApprovalId = priceApproval)
                }
                // Financial success is recorded before any optional receipt/navigation callback.
                completed = sale; reviewed = null
                runCatching { storeResult.getOrThrow().clear(sale.id) }.onFailure {
                    error = "Sale saved. Its old draft remains until New sale clears it."
                }
                runCatching(onChanged)
            } catch (failure: Exception) {
                error = failure.message ?: "Sale not saved. Your basket is retained for retry."
                // Expired/revoked approvals must never trap the cashier behind a stale ready state.
                approvalId = null; approvalFingerprint = null; approvalRefresh++; reviewed = null
            }
            finally { busy = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val tablet = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (stage == "payment") "Take payment" else "Sell", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton({ heldOpen = true }, enabled = !busy) { Text("Saved sales (${held.getOrDefault(emptyList()).size})") }
            }
            loadError?.let { InlineNotice("Sale unavailable", it.message ?: "Local records could not be loaded. Nothing was replaced.", NoticeTone.Danger) }
            error?.let { InlineNotice(if (completed == null) "Review this sale" else "Sale saved", it, NoticeTone.Danger) }
            notice?.let { InlineNotice(it, tone = NoticeTone.Info) }
            lineResult.exceptionOrNull()?.let { InlineNotice("Basket needs review", it.message.orEmpty(), NoticeTone.Warning) }
            if (lineResult.isFailure && draft.cart.isNotEmpty()) NeutralButton("Discard this unfinished basket", {
                try { storeResult.getOrThrow().clear(draft.operationId); resetAfterClear() }
                catch (failure: Exception) { error = failure.message }
            })
            val enabled = loadError == null && lineResult.isSuccess && totalResult.isSuccess && !busy
            val browser: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose items", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(search, { search = it }, label = { Text("Search for an item") },
                        placeholder = { Text("Name, SKU or barcode") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        enabled = !busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (enabled) addBarcode() }))
                    if (search.isNotBlank()) NeutralButton("Add matching barcode", { addBarcode() }, enabled = enabled)
                    val visible = products.filter { it.matchesCatalogueSearch(search) || units.any { u -> u.productId == it.id && u.barcode == search.trim() } }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (visible.isEmpty()) item { EmptyState("No matching items", "Try another name or add goods under Stock.") }
                        items(visible, key = { it.id }) { product ->
                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AndroidProductPicture(repo, tenantId, product.id, revision)
                                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${money(product.priceMinor)} / ${product.unit}")
                                    }
                                    Text("${quantityLabel(product.unreservedStock(reservations.getOrDefault(emptyMap())[product.id] ?: 0L), product.quantityPrecision)} ${product.unit} available", style = MaterialTheme.typography.bodySmall)
                                    NeutralButton("Add to sale", { adding = product }, Modifier.fillMaxWidth(), enabled = enabled)
                                }
                            }
                        }
                    }
                }
            }
            val salePanel: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(stage != "payment") {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("This sale", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                TextButton({ holding = true; holdName = "" }, enabled = enabled && lines.isNotEmpty()) { Text("Put sale aside") }
                            }
                        }
                        if (lines.isEmpty()) item { EmptyState("No items yet", "Choose an item to start this sale.") }
                        items(lines, key = { it.key }) { line ->
                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(line.product.name, style = MaterialTheme.typography.titleMedium)
                                    LabelValueRow("${line.quantityLabel} ${line.sellingUnit} × ${money(line.netUnitPriceMinor)}", money(line.totalMinor))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton({ editing = line }, enabled = enabled) { Text("Quantity / price") }
                                        TextButton({ changeLines(lines.filterNot { it.key == line.key }) }, enabled = enabled) { Text("Remove") }
                                    }
                                }
                            }
                        }
                        }
                        if (stage == "payment") item {
                            SellPaymentFields(draft, customers.getOrDefault(emptyList()), busy, acknowledged,
                                { acknowledged = it; reviewed = null }, { persist(it) })
                            customers.exceptionOrNull()?.let { Text("Customer records unavailable: credit cannot be selected.", color = MaterialTheme.colorScheme.error) }
                        }
                        item { PosTextField(draft.note, { persist(draft.copy(note = it)) }, "Receipt note (optional)", enabled = !busy, hint = "Up to 300 characters") }
                        reviewed?.let { review -> item {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Review payment", style = MaterialTheme.typography.titleMedium)
                                    LabelValueRow("Cash applied", money(review.cashMinor))
                                    review.external.forEach { LabelValueRow(it.label, money(it.amountMinor)) }
                                    if (review.creditMinor > 0) LabelValueRow("Customer will pay later", money(review.creditMinor))
                                    LabelValueRow("Change to give", money(review.changeMinor), emphasized = true)
                                    Text("Saving records this sale and deducts its goods once.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } }
                    }
                    HorizontalDivider()
                    Column {
                        Text("Total", style = MaterialTheme.typography.labelLarge)
                        Text(money(total), fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                    }
                    reviewed?.let { review ->
                        LabelValueRow("Change to give", money(review.changeMinor), emphasized = true)
                        if(review.creditMinor>0) LabelValueRow("Customer will pay later",money(review.creditMinor))
                        if(review.external.isNotEmpty()) Text("External payment recorded · not confirmed",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    }
                    if (approvalNeeded) {
                        if (onRequestApproval != null) NeutralButton(
                            if (validApproval == null) "Ask manager to approve price" else "Request fresh price approval",
                            {
                                try {
                                    // Read fresh policy versions at the moment the manager is asked.
                                    val request = repo.minimumPriceApprovalRequest(tenantId, draft.operationId, lines)
                                    val requestedLines = lines.toList()
                                    approvalRefresh++
                                    onRequestApproval(requestedLines, draft.operationId) { grant ->
                                        approvalId = grant; approvalFingerprint = request.fingerprint
                                        approvalRefresh++; reviewed = null; error = null
                                    }
                                } catch (failure: Exception) { error = failure.message ?: "Price approval could not be requested." }
                            }, Modifier.fillMaxWidth(), enabled = enabled && lines.isNotEmpty())
                        else Text("This sale needs a separate manager price approval. Remove its discounts or request an authorized sign-in.", color = MaterialTheme.colorScheme.error)
                    }
                    if (validApproval != null) Text("Price approval ready for this sale", style = MaterialTheme.typography.bodySmall)
                    if (stage == "payment") {
                        PosButton(if (busy) "Saving…" else if (reviewed == null) "Review payment" else "Finish sale", {
                            try {
                                if (reviewed == null) {
                                    val value = reviewSellPayment(draft, lines, acknowledged)
                                    value.dueDate?.let { require(!LocalDate.parse(it).isBefore(LocalDate.now(ZoneId.of("Africa/Lagos")))) { "Choose today or a later due date." } }
                                    reviewed = value; error = null
                                } else finish(requireNotNull(reviewed))
                            } catch (failure: Exception) { error = failure.message ?: "Review the payment details." }
                        }, Modifier.fillMaxWidth(), enabled && lines.isNotEmpty())
                        NeutralButton("Back to items", { stage = if (tablet) "items" else "basket"; reviewed = null }, Modifier.fillMaxWidth(), enabled = !busy)
                    } else {
                        PosButton("Take payment", { stage = "payment"; reviewed = null }, Modifier.fillMaxWidth(), enabled && lines.isNotEmpty())
                        if (!tablet) NeutralButton("Choose more items", { stage = "items" }, Modifier.fillMaxWidth(), enabled = !busy)
                    }
                }
            }
            if (tablet) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                browser(Modifier.weight(1f).fillMaxHeight())
                salePanel(Modifier.weight(1f).fillMaxHeight())
            } else if (stage == "items") {
                browser(Modifier.weight(1f))
                PosButton("This sale · ${lines.size} items · ${money(total)}", { stage = "basket" }, Modifier.fillMaxWidth(), enabled && lines.isNotEmpty())
            } else salePanel(Modifier.weight(1f))
        }
    }
    adding?.let { product ->
        SellItemDialog(product, units.filter { it.productId == product.id }, null,
            dismiss = { adding = null }, apply = { line ->
                val candidate = addSellLine(lines, line)
                require(candidate.filter { it.product.id == product.id }.fold(0L) { sum, line -> Math.addExact(sum, line.baseQuantity) } <= product.unreservedStock(reservations.getOrThrow()[product.id] ?: 0L)) { "There is not enough available stock." }
                if (changeLines(candidate)) adding = null
            })
    }
    editing?.let { line ->
        SellItemDialog(line.product, units.filter { it.productId == line.product.id }, line,
            dismiss = { editing = null }, apply = { next ->
                val candidate = lines.filterNot { it.key == line.key } + next
                require(candidate.map { it.key }.distinct().size == candidate.size) { "This selling unit already has a line. Change that line's quantity instead." }
                require(candidate.filter { it.product.id == line.product.id }.fold(0L) { sum, item -> Math.addExact(sum, item.baseQuantity) } <= line.product.unreservedStock(reservations.getOrThrow()[line.product.id] ?: 0L)) { "There is not enough available stock." }
                if (changeLines(candidate)) editing = null
            })
    }
    if (holding) AlertDialog(onDismissRequest = { holding = false }, title = { Text("Put sale aside") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The basket stays on this phone. Its stock is not reserved.")
            PosTextField(holdName, { holdName = it }, "Name for this sale")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        try { storeResult.getOrThrow().hold(holdName, draft); resetAfterClear(); holding = false; notice = "Sale put aside on this phone." }
        catch (failure: Exception) { error = failure.message }
    }) { Text("Put sale aside") } }, dismissButton = { TextButton({ holding = false }) { Text("Cancel") } })
    if (heldOpen) AlertDialog(onDismissRequest = { heldOpen = false }, title = { Text("Saved sales") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            held.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
            if (held.getOrDefault(emptyList()).isEmpty()) Text("No sales have been put aside.")
            if (draft.cart.isNotEmpty()) Text("Put this sale aside before resuming another.")
            held.getOrDefault(emptyList()).forEach { saved ->
                Text(saved.name, style = MaterialTheme.typography.titleMedium)
                Text(saved.savedAt, style = MaterialTheme.typography.bodySmall)
                NeutralButton("Resume sale", {
                    try { draft = storeResult.getOrThrow().resume(saved.id); stage = "basket"; reviewed = null; heldOpen = false; revision++
                        acknowledged = false; approvalId = null; approvalFingerprint = null; approvalRefresh++ }
                    catch (failure: Exception) { error = failure.message }
                }, enabled = draft.cart.isEmpty())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ heldOpen = false }) { Text("Close") } })
    }
}

@Composable
private fun SellPaymentFields(draft: SaleDraft, customers: List<Customer>, busy: Boolean,
    acknowledged: Boolean, acknowledge: (Boolean) -> Unit, update: (SaleDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("How is the customer paying?", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cash" to "Cash", "credit" to "Pay later / part cash", "split" to "Transfer / card + cash", "split_credit" to "Split + pay later").forEach { (mode, label) ->
                FilterChip(draft.method == mode, { update(draft.copy(method = mode, dueDateText = if (mode in setOf("credit", "split_credit")) draft.dueDateText else "")) },
                    label = { Text(label) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (draft.method in setOf("split", "split_credit")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(draft.externalMethod == "transfer", { update(draft.copy(externalMethod = "transfer")) }, label = { Text("Bank transfer") }, enabled = !busy)
                FilterChip(draft.externalMethod == "card", { update(draft.copy(externalMethod = "card")) }, label = { Text("Card terminal") }, enabled = !busy)
            }
            SellMoneyField(draft.externalAmountText, { update(draft.copy(externalAmountText = it)) }, "Recorded external amount", !busy)
            PosTextField(draft.externalReference, { update(draft.copy(externalReference = it)) }, "Payment reference", enabled = !busy)
            PosTextField(draft.externalAccount, { update(draft.copy(externalAccount = it)) }, "Receiving account / terminal label", enabled = !busy)
            InlineNotice(if (draft.externalMethod == "transfer") "Transfer not confirmed" else "Card payment not confirmed",
                "This app has not confirmed that the money arrived. Only continue if you checked the receiving account yourself.", NoticeTone.Warning)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledge(it) }, enabled = !busy)
                Text("Record without bank confirmation", Modifier.weight(1f))
            }
        }
        SellMoneyField(draft.cashText, { update(draft.copy(cashText = it)) }, "Cash given by customer", !busy)
        if (draft.method == "cash") Text("Short cash is not converted into customer debt. Choose Pay later explicitly if credit is agreed.", style = MaterialTheme.typography.bodySmall)
        if (draft.method in setOf("credit", "split_credit")) {
            var menu by remember { mutableStateOf(false) }
            Text("Who will pay the balance?", style = MaterialTheme.typography.titleMedium)
            Box {
                NeutralButton(customers.firstOrNull { it.id == draft.customerId }?.name ?: "Choose customer", { menu = true }, enabled = !busy)
                DropdownMenu(menu, { menu = false }) {
                    customers.forEach { customer -> DropdownMenuItem(text = { Text(customer.name) }, onClick = {
                        update(draft.copy(customerId = customer.id)); menu = false
                    }) }
                }
            }
            customers.firstOrNull { it.id == draft.customerId }?.let {
                LabelValueRow("Already owes", money(it.balanceMinor))
                it.creditLimitMinor?.let { limit -> LabelValueRow("Credit limit", money(limit)) }
            }
            PosTextField(draft.dueDateText, { update(draft.copy(dueDateText = it)) }, "Agreed due date (optional)", hint = "YYYY-MM-DD · West Africa Time", enabled = !busy)
            Text("Saving credit requires permission and checks the customer's current credit limit.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SellMoneyField(value: String, update: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, update, label = { Text(label) }, prefix = { Text("₦ ") },
        visualTransformation = MoneyInputTransformation, singleLine = true, modifier = Modifier.fillMaxWidth(),
        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun SellItemDialog(product: Product, units: List<SellingUnit>, previous: CartLine?, dismiss: () -> Unit, apply: (CartLine) -> Unit) {
    var unitId by remember { mutableStateOf(previous?.sellingUnitId) }
    val unit = units.firstOrNull { it.id == unitId }
    val precision = if (unit == null) product.quantityPrecision else 0
    var quantity by remember(unitId) { mutableStateOf(if (previous?.sellingUnitId == unitId && previous != null) previous.quantityLabel else "1") }
    val price = unit?.priceMinor ?: product.priceMinor
    var discounting by remember { mutableStateOf(previous?.discountPerUnitMinor?.let { it > 0 } ?: false) }
    var agreed by remember(unitId) { mutableStateOf(if (previous?.sellingUnitId == unitId && previous != null) sellNaira(previous.netUnitPriceMinor) else sellNaira(price)) }
    var reason by remember { mutableStateOf(previous?.discountReason.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (previous == null) "Add to sale" else "Change sale item") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleLarge)
            Text("How are you selling it?", style = MaterialTheme.typography.labelLarge)
            Box {
                NeutralButton(unit?.name ?: product.unit, { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("${product.unit} · ${money(product.priceMinor)}") }, onClick = { unitId = null; menu = false; error = null })
                    units.filterNot { it.archived }.forEach { pack -> DropdownMenuItem(text = { Text("${pack.name} · ${pack.baseUnitsPerUnit} ${pack.baseUnit} · ${money(pack.priceMinor)}") },
                        onClick = { unitId = pack.id; menu = false; error = null }) }
                }
            }
            if (unit != null) Text("1 ${unit.name} contains ${unit.baseUnitsPerUnit} ${unit.baseUnit}.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity in ${unit?.name ?: product.unit}") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (precision == 0) KeyboardType.Number else KeyboardType.Decimal))
            LabelValueRow("Normal price per ${unit?.name ?: product.unit}", money(price))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(discounting, { discounting = it })
                Text("Change agreed price", Modifier.weight(1f))
            }
            if (discounting) {
                SellMoneyField(agreed, { agreed = it }, "Agreed price per ${unit?.name ?: product.unit}", true)
                PosTextField(reason, { reason = it }, "Reason for lower price")
                Text("Only permitted staff can save a discount. Saved minimum selling prices still apply.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        try {
            val scaled = requireNotNull(parseQuantity(quantity, precision)) { "Enter an exact quantity in the displayed unit." }.scaledUnits
            require(scaled in 1..Int.MAX_VALUE.toLong()) { "Enter a positive quantity within the supported range." }
            val agreedPrice = if (discounting) requireNotNull(parseNaira(agreed)) { "Enter an exact agreed price." } else price
            require(agreedPrice <= price) { "The agreed price cannot exceed the current catalogue price." }
            val discount = if (agreedPrice < price) UnitDiscount(price, price - agreedPrice, reason.trim()) else null
            apply(CartLine(product, scaled.toInt(), unit?.id, unit?.name ?: product.unit, price,
                unit?.baseUnitsPerUnit ?: 1, discount?.amountMinor ?: 0, discount?.reason))
        } catch (failure: Exception) { error = failure.message ?: "Review the quantity and price." }
    }) { Text(if (previous == null) "Add to sale" else "Update sale item") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

private fun sellNaira(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
