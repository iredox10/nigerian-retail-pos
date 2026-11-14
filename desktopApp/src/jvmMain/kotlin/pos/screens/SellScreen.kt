package pos.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import pos.data.DraftDiscountCodec
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
    sellingUnits: List<SellingUnit> = emptyList(),
    allowCredit: Boolean = true,
    allowDiscounts: Boolean = true,
    repository:pos.data.PosRepository?=null,
    onCheckout: (List<CartLine>, Long, String?, String, String?, BasketDiscount?, List<ExternalPayment>) -> String?,
) {
    val draftStoreResult = remember(tenantId) { runCatching { DraftStore(tenantId) } }
    val draftStore = draftStoreResult.getOrNull()
    val restoredDraft = remember(tenantId) { runCatching { draftStore?.load() } }
    var search by rememberSaveable { mutableStateOf("") }
    var favouritesOnly by rememberSaveable(tenantId) { mutableStateOf(false) }
    var selectedCategory by rememberSaveable(tenantId) { mutableStateOf<String?>(null) }
    var categoryMenu by remember { mutableStateOf(false) }
    // Catalogue prices are refreshed; explicit discounts retain a reference price for review.
    var encodedCart by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.cart ?: "") }
    var encodedDiscounts by rememberSaveable { mutableStateOf(DraftDiscountCodec.encode(restoredDraft.getOrNull()?.discounts.orEmpty())) }
    val discounts = remember(encodedDiscounts) { DraftDiscountCodec.decode(encodedDiscounts) }
    var basketKind by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.basketDiscount?.kind.orEmpty()) }
    var basketValue by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.basketDiscount?.value ?: 0L) }
    var basketReason by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.basketDiscount?.reason.orEmpty()) }
    var basketBasis by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.basketBasis.orEmpty()) }
    val basketRule=if(basketKind.isEmpty()) null else BasketDiscount(basketKind,basketValue,basketReason)
    var editingBasket by remember { mutableStateOf(false) }
    val quantityErrors = remember(tenantId) { mutableStateMapOf<String,Boolean>() }
    var selectingPrices by remember {mutableStateOf(false)}
    var selectingPriceCustomer by remember {mutableStateOf(false)}
    var changingPrice by remember { mutableStateOf<String?>(null) }
    var agreedPrice by remember { mutableStateOf("") }
    var percentageMode by remember { mutableStateOf(false) }
    var percentageText by remember { mutableStateOf("") }
    var discountReason by remember { mutableStateOf("") }
    var priceError by remember { mutableStateOf<String?>(null) }
    var operationId by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.operationId ?: UUID.randomUUID().toString()) }
    var stage by rememberSaveable { mutableStateOf("items") }
    var method by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.method ?: "cash") }
    var cashText by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.cashText ?: "") }
    var selectedCustomer by rememberSaveable { mutableStateOf<String?>(restoredDraft.getOrNull()?.customerId) }
    var dueDateText by rememberSaveable { mutableStateOf(restoredDraft.getOrNull()?.dueDateText ?: "") }
    var externalMethod by rememberSaveable {mutableStateOf(restoredDraft.getOrNull()?.externalMethod ?: "transfer")}
    var externalAmountText by rememberSaveable {mutableStateOf(restoredDraft.getOrNull()?.externalAmountText.orEmpty())}
    var externalReference by rememberSaveable {mutableStateOf(restoredDraft.getOrNull()?.externalReference.orEmpty())}
    var externalAccount by rememberSaveable {mutableStateOf(restoredDraft.getOrNull()?.externalAccount.orEmpty())}
    var savedExternal by rememberSaveable {mutableStateOf(0L)}
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
    fun currentDraft() = SaleDraft(encodedCart,operationId,cashText,selectedCustomer,method,dueDateText,discounts,basketRule,if(basketRule==null) null else basketBasis,externalMethod,externalAmountText,externalReference,externalAccount)
    fun persistPayment(cash:String=cashText,customerId:String?=selectedCustomer,paymentMethod:String=method,due:String=dueDateText) {
        try {
            require(draftStore!=null && restoredDraft.isSuccess) { "Draft storage is unavailable." }
            draftStore.save(SaleDraft(encodedCart,operationId,cash,customerId,paymentMethod,due,discounts,basketRule,if(basketRule==null) null else basketBasis,externalMethod,externalAmountText,externalReference,externalAccount))
            cashText=cash;selectedCustomer=customerId;method=paymentMethod;dueDateText=due;error=null
        } catch(e:Exception) { error="Payment details not saved: ${e.message}" }
    }
    fun resetCurrent() { basketKind="";basketValue=0;basketReason="";basketBasis="";encodedCart="";encodedDiscounts="";changingPrice=null;operationId=UUID.randomUUID().toString();stage="items";cashText="";selectedCustomer=null;method="cash";externalMethod="transfer";externalAmountText="";externalReference="";externalAccount="";dueDateText="";search="";showSaleOnly=false;error=null;reviewNotice=null }
    val quantities = remember(encodedCart) {
        encodedCart.split(';').mapNotNull { item ->
            val index = item.lastIndexOf(':')
            if (index < 1) null else item.substring(index + 1).toIntOrNull()?.let { item.substring(0, index) to it }
        }.toMap()
    }
    val options = products.flatMap { product ->
        listOf(CartLine(product, quantityScaleFactor(product.quantityPrecision).toInt())) + sellingUnits.filter { it.productId == product.id && !it.archived }.map {
            CartLine(product, 1, it.id, it.name, it.priceMinor, it.baseUnitsPerUnit)
        }
    }.associateBy { it.key }
    val selectedUnits = remember(tenantId) { mutableStateMapOf<String, String>() }
    val staleDiscounts = discounts.any { (key, discount) -> options[key]?.unitPriceMinor != discount.cataloguePriceMinor }
    val rawLines = quantities.mapNotNull { (key, quantity) ->
        options[key]?.takeIf { quantity > 0 }?.let { option ->
            val discount = discounts[key]?.takeIf { it.cataloguePriceMinor == option.unitPriceMinor }
            option.copy(quantity = quantity, discountPerUnitMinor = discount?.amountMinor ?: 0L, discountReason = discount?.reason)
        }
    }
    val staleBasket=basketRule!=null && basketBasis!=basketDiscountBasis(rawLines)
    val basketAllocation=runCatching {if(basketRule!=null && !staleBasket) allocateBasketDiscount(rawLines,basketRule) else rawLines}
    val lines=basketAllocation.getOrDefault(rawLines)
    fun persistBasketRule(rule:BasketDiscount?) {
        val basis=rule?.let {basketDiscountBasis(rawLines)}
        requireNotNull(draftStore).save(SaleDraft(encodedCart,operationId,cashText,selectedCustomer,method,dueDateText,discounts,rule,basis,externalMethod,externalAmountText,externalReference,externalAccount))
        basketKind=rule?.kind.orEmpty();basketValue=rule?.value ?: 0;basketReason=rule?.reason.orEmpty();basketBasis=basis.orEmpty()
    }
    fun fitsStock(line: CartLine, quantity: Int): Boolean = runCatching {
        if (quantity < 0) return@runCatching false
        val other = lines.filter { it.product.id == line.product.id && it.key != line.key }.fold(0L) { sum, item -> Math.addExact(sum, item.baseQuantity) }
        Math.addExact(other, if(quantity==0) 0 else line.copy(quantity=quantity).baseQuantity) <= line.product.exactStockScaled
    }.getOrDefault(false)
    fun nextAddedQuantity(line:CartLine):Int = runCatching {
        val current=quantities[line.key] ?: 0
        val step=quantityScaleFactor(line.quantityPrecision).toInt()
        if(current>0) Math.addExact(current,step) else {
            val used=lines.filter {it.product.id==line.product.id}.fold(0L) {sum,item -> Math.addExact(sum,item.baseQuantity)}
            val available=(line.product.exactStockScaled-used).coerceAtLeast(0)
            val perSmallest=Quantity(1,line.quantityPrecision).rescale(line.product.quantityPrecision).timesWhole(line.baseUnitsPerUnit.toLong()).scaledUnits
            minOf(step.toLong(),available/perSmallest).toInt()
        }
    }.getOrDefault(0)
    val totalResult = runCatching {basketAllocation.getOrThrow();cartTotal(lines)}
    val total = totalResult.getOrDefault(0L)
    val unavailableDraftItems = quantities.keys.any { it !in options }
    fun persistBasket(next: Map<String,Int>, nextDiscounts: Map<String,UnitDiscount>) {
        val updated = next.entries.joinToString(";") { "${it.key}:${it.value}" }
        require(draftStore != null && restoredDraft.isSuccess) { "Draft storage is unavailable. Resolve the saved-draft error first." }
        val keptRule=if(next.isEmpty()) null else basketRule
        draftStore.save(SaleDraft(updated, operationId,cashText,selectedCustomer,method,dueDateText,nextDiscounts,keptRule,if(keptRule==null) null else basketBasis,externalMethod,externalAmountText,externalReference,externalAccount))
        if(keptRule==null) {basketKind="";basketValue=0;basketReason="";basketBasis=""}
        encodedCart = updated
        encodedDiscounts = DraftDiscountCodec.encode(nextDiscounts)
        error = null
    }
    fun update(line: CartLine, quantity: Int) {
        val next = quantities.toMutableMap()
        quantityErrors.remove(line.key)
        if (quantity <= 0) next.remove(line.key) else next[line.key] = quantity
        try { persistBasket(next, discounts.filterKeys { it in next }) }
        catch(e: Exception) { error = "Item change not saved: ${e.message}" }
    }

    val forbiddenDraft = (!allowCredit && method in setOf("credit","split_credit")) || (!allowDiscounts && (discounts.isNotEmpty() || basketRule!=null))
    val cashGiven = parseNaira(cashText)
    val isSplit=method in setOf("split","split_credit")
    val externalResult=runCatching {if(isSplit) listOf(ExternalPayment(externalMethod,requireNotNull(parseNaira(externalAmountText)) {"Enter a valid external payment amount."},externalReference.trim(),externalAccount.trim())) else emptyList()}
    val externalPayments=externalResult.getOrDefault(emptyList())
    val externalAmount=externalPayments.sumOf {it.amountMinor}
    val cashNeeded=(total-externalAmount).coerceAtLeast(0)
    val appliedCash = minOf(cashGiven ?: 0, cashNeeded)
    val credit = if (method in setOf("credit","split_credit")) cashNeeded - appliedCash else 0
    val customer = customers.find { it.id == selectedCustomer }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 760.dp
        Column {
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !showSaleOnly, onClick = { showSaleOnly = false }, label = { Text("Choose items") })
                FilterChip(selected = showSaleOnly, onClick = { showSaleOnly = true }, label = { Text("This sale · ${lines.size} lines") })
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (!compact || !showSaleOnly) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Choose items", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        if(event.key==Key.Enter && event.type==KeyEventType.KeyDown) {
                            val match=options.values.singleOrNull { option ->
                                val barcode = if (option.sellingUnitId == null) option.product.barcode else sellingUnits.find { it.id == option.sellingUnitId }?.barcode
                                barcode == search.trim() && search.isNotBlank()
                            }
                            if(match==null) error="No item has this barcode. Choose a matching item or add its barcode under Stock."
                            else if(stage=="saved") error="Start a new sale before scanning another item."
                            else if(nextAddedQuantity(match)<=0 || !fitsStock(match,nextAddedQuantity(match))) error="Not enough ${match.product.unit} of ${match.product.name} in stock."
                            else { update(match,nextAddedQuantity(match));search="" }
                            true
                        } else false
                    }, singleLine = true,
                        label = { Text("Search for an item") }, placeholder = { Text("Type a name or scan a barcode, then Enter") }, shape = MaterialTheme.shapes.small)
                    if(compact && !showSaleOnly) error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!favouritesOnly,{favouritesOnly=false},label={Text("All items")})
                        FilterChip(favouritesOnly,{favouritesOnly=true},label={Text("Favourites")})
                        Box {
                            OutlinedButton({categoryMenu=true},shape=MaterialTheme.shapes.small) {Text(selectedCategory ?: "All categories")}
                            DropdownMenu(categoryMenu,{categoryMenu=false}) {
                                DropdownMenuItem(text={Text("All categories")},onClick={selectedCategory=null;categoryMenu=false})
                                products.mapNotNull {it.category}.distinct().sorted().forEach { category -> DropdownMenuItem(text={Text(category)},onClick={selectedCategory=category;categoryMenu=false}) }
                            }
                        }
                    }
                    val filtered = products.filter { product -> (!favouritesOnly || product.favourite) && (selectedCategory==null || product.category==selectedCategory) && (product.matchesCatalogueSearch(search) || sellingUnits.any { unit -> unit.productId==product.id && !unit.archived && (unit.name.contains(search,true) || unit.barcode?.contains(search.trim())==true) }) }
                    if (filtered.isEmpty()) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (products.isEmpty()) "Add your first item" else "No matching items", style = MaterialTheme.typography.titleLarge)
                            Text(if (products.isEmpty()) "Open Stock → Add item to enter goods, selling prices and opening quantities." else "Try another name or code, or clear the search and filters.")
                            if (search.isNotEmpty() || favouritesOnly || selectedCategory!=null) TextButton(shape = MaterialTheme.shapes.small, onClick = { search = ""; favouritesOnly=false; selectedCategory=null }) { Text("Clear search and filters") }
                        }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.id }) { product ->
                            val choices = options.values.filter { it.product.id == product.id }
                            val choice = choices.find { it.key == selectedUnits[product.id] } ?: choices.first()
                            var choosingUnit by remember(product.id) { mutableStateOf(false) }
                            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                            Text(product.name.take(1).uppercase(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(product.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                        Text("${money(choice.unitPriceMinor)} / ${choice.sellingUnit}", style = MaterialTheme.typography.bodyMedium)
                                        if (choices.size > 1) Box {
                                            TextButton({ choosingUnit = true }, contentPadding = PaddingValues(0.dp)) { Text("Sell as: ${choice.sellingUnit} ▾") }
                                            DropdownMenu(choosingUnit, { choosingUnit = false }) {
                                                choices.forEach { option ->
                                                    DropdownMenuItem(text = { Text("${option.sellingUnit} · ${money(option.unitPriceMinor)}" + if(option.baseUnitsPerUnit > 1) " · ${option.baseUnitsPerUnit} ${product.unit}" else "") },
                                                        onClick = { selectedUnits[product.id] = option.key; choosingUnit = false })
                                                }
                                            }
                                        }
                                        if (choice.baseUnitsPerUnit > 1) Text("1 ${choice.sellingUnit} = ${choice.baseUnitsPerUnit} ${product.unit}", style = MaterialTheme.typography.bodySmall)
                                        Text(if ((quantities[choice.key] ?: 0) > 0) "In this sale: ${quantityLabel((quantities[choice.key] ?: 0).toLong(),choice.quantityPrecision)} ${choice.sellingUnit}" else "${product.stockLabel} ${product.unit} available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { update(choice, nextAddedQuantity(choice)) }, enabled = stage != "saved" && nextAddedQuantity(choice)>0 && fitsStock(choice, nextAddedQuantity(choice))) {
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
                            Text(if (savedCredit > 0) "Customer owes ${money(savedCredit)}" else if(savedExternal>0) "External payment recorded · not confirmed" else "Paid in cash", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            if(savedCredit>0 && savedExternal>0) Text("External payment recorded · not confirmed",color=MaterialTheme.colorScheme.error)
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
                            if(stage=="items" && allowDiscounts && repository!=null) Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                TextButton({selectingPriceCustomer=true}) {Text(customer?.name ?: "Add customer")}
                                TextButton({selectingPrices=true},enabled=rawLines.isNotEmpty()) {Text("Choose price list")}
                            }
                            reviewNotice?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
                            Text("${lines.size} sale lines · ${lines.map { it.product.id }.distinct().size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            if (stage == "items") LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (lines.isEmpty()) item {
                                    Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("No items yet", style = MaterialTheme.typography.titleMedium)
                                        Text("Choose an item on the left to start this sale.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                items(lines, key = { it.key }) { line ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(line.product.name, fontWeight = FontWeight.Medium)
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("${line.quantityLabel} ${line.sellingUnit}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            Text(money(line.totalMinor), fontWeight = FontWeight.Medium)
                                        }
                                        if (line.basketDiscountMinor>0) Text("Basket discount on this line: ${money(line.basketDiscountMinor)}",style=MaterialTheme.typography.bodySmall)
                                        if (line.discountPerUnitMinor > 0) {
                                            Text("Was ${money(line.unitPriceMinor)} · Now ${money(line.netUnitPriceMinor)} / ${line.sellingUnit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                            Text("Reason: ${line.discountReason?.let(::displayPriceListReason)}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (allowDiscounts) TextButton({
                                            changingPrice = line.key
                                            percentageMode = false
                                            percentageText = ""
                                            agreedPrice = BigDecimal(line.netUnitPriceMinor).movePointLeft(2).toPlainString()
                                            discountReason = discounts[line.key]?.reason.orEmpty()
                                            priceError = null
                                        }) { Text(if (discounts.containsKey(line.key)) "Review discount" else "Change price") }
                                        if (line.baseUnitsPerUnit > 1) Text("${quantityLabel(line.baseQuantity,line.product.quantityPrecision)} ${line.product.unit} from stock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val wholeStep=quantityScaleFactor(line.quantityPrecision).toInt()
                                        var quantityText by remember(line.key,line.quantity) { mutableStateOf(line.quantityLabel) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(shape = MaterialTheme.shapes.small,onClick={update(line,(line.quantity-wholeStep).coerceAtLeast(0))},contentPadding=PaddingValues(horizontal=12.dp)) {Text(if(line.quantity<=wholeStep) "Remove" else "−1")}
                                            OutlinedTextField(quantityText,{value -> quantityText=value;quantityErrors[line.key]=value!=line.quantityLabel},modifier=Modifier.width(100.dp),singleLine=true,label={Text(line.sellingUnit)},isError=quantityCount(quantityText,line.quantityPrecision)?.let { !fitsStock(line,it) } != false)
                                            OutlinedButton(shape=MaterialTheme.shapes.small,onClick={update(line,Math.addExact(line.quantity,wholeStep))},enabled=line.quantity<=Int.MAX_VALUE-wholeStep && fitsStock(line,line.quantity+wholeStep),contentPadding=PaddingValues(horizontal=12.dp)) {Text("+1")}
                                        }
                                        if(quantityText!=line.quantityLabel) {
                                            val entered=quantityCount(quantityText,line.quantityPrecision)
                                            TextButton({if(entered!=null && fitsStock(line,entered)) update(line,entered)},enabled=entered!=null && fitsStock(line,entered)) {Text("Apply quantity")}
                                            if(entered==null) Text("Enter more than 0, with up to ${line.quantityPrecision} decimal places.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                                            else if(!fitsStock(line,entered)) Text("Not enough stock for this quantity.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                                        }
                                        TextButton({update(line,0)}) {Text("Remove item")}
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("How is the customer paying?", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(method == "cash", { persistPayment(paymentMethod="cash") }, label = { Text("Cash") })
                                    if(allowCredit) FilterChip(method in setOf("credit","split_credit"), { persistPayment(paymentMethod="credit") }, label = { Text("Pay later / part cash") })
                                }
                                TextButton({persistPayment(paymentMethod="split")}) {Text("Bank transfer / card / use more than one method")}
                                if(isSplit) {
                                    Text("This app has not confirmed that the money arrived. Only record a payment you checked yourself.",color=MaterialTheme.colorScheme.error)
                                    fun saveExternal(m:String=externalMethod,a:String=externalAmountText,r:String=externalReference,b:String=externalAccount) {
                                        try {requireNotNull(draftStore).save(currentDraft().copy(externalMethod=m,externalAmountText=a,externalReference=r,externalAccount=b));externalMethod=m;externalAmountText=a;externalReference=r;externalAccount=b;error=null} catch(e:Exception) {error="Payment details not saved: ${e.message}"}
                                    }
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(externalMethod=="transfer",{saveExternal(m="transfer")},label={Text("Bank transfer")});FilterChip(externalMethod=="card",{saveExternal(m="card")},label={Text("Card terminal")})}
                                    OutlinedTextField(externalAmountText,{saveExternal(a=it)},label={Text("External amount recorded (₦)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                    OutlinedTextField(externalReference,{saveExternal(r=it)},label={Text("Payment reference")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                    OutlinedTextField(externalAccount,{saveExternal(b=it)},label={Text("Receiving account / terminal")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                                    if(allowCredit) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(method=="split_credit",{persistPayment(paymentMethod=if(it) "split_credit" else "split")});Text("Customer will pay the remaining balance later",Modifier.weight(1f))}
                                    Text(externalResult.exceptionOrNull()?.message ?: "${externalPayments.first().label}: ${money(externalAmount)}",style=MaterialTheme.typography.bodySmall)
                                }
                                OutlinedTextField(cashText, { persistPayment(cash=it) }, label = { Text(if (method == "cash") "Cash given by customer" else "Cash paid now (enter 0 if none)") }, prefix = { Text("₦ ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                TextButton({ persistPayment(cash=BigDecimal(cashNeeded).movePointLeft(2).toPlainString()) }) { Text("Exact amount") }
                                if (method in setOf("credit","split_credit")) {
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
                                if(method in setOf("credit","split_credit")) {
                                    OutlinedTextField(dueDateText,{persistPayment(due=it)},label={Text("Repayment due date (optional)")},placeholder={Text("YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                    Text("Leave blank if no date was agreed. Dates use West Africa Time. A due date does not send a reminder.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (cashGiven != null) {
                                    HorizontalDivider()
                                    Text(if (cashGiven!! >= cashNeeded) "Change to give ${money(cashGiven!! - cashNeeded)}" else "Still to pay ${money(cashNeeded - cashGiven!!)}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (forbiddenDraft) Text("This saved sale needs a manager or owner because it contains credit or a discount. Sign in with that account, or remove discounted items and choose Cash.",color=MaterialTheme.colorScheme.error)
                            if(staleBasket) Text("This basket changed after its discount was reviewed. Review or remove the basket discount before payment.",color=MaterialTheme.colorScheme.error)
                            if (staleDiscounts) Text("A discounted item's catalogue price changed. Review its discount before payment, or remove the item.", color = MaterialTheme.colorScheme.error)
                            if (unavailableDraftItems) {
                                Text("Some saved items are archived or unavailable. Restore them under Stock or remove them before payment.",color=MaterialTheme.colorScheme.error)
                                TextButton({
                                    try {
                                        val available = quantities.filterKeys { it in options }
                                        persistBasket(available, discounts.filterKeys { it in available })
                                    }catch(e:Exception) {error="The sale could not be changed: ${e.message}"}
                                }) {Text("Remove unavailable items from this sale")}
                            }
                            totalResult.exceptionOrNull()?.let { Text("Sale total exceeds the supported amount. Reduce a quantity before payment.", color = MaterialTheme.colorScheme.error) }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                            HorizontalDivider()
                            Spacer(Modifier.height(16.dp))
                            if(stage=="items" && allowDiscounts && lines.isNotEmpty()) TextButton({editingBasket=true},enabled=quantityErrors.none {it.key in quantities && it.value}) {Text(if(basketRule==null) "Basket discount" else "Review basket discount")}
                            Text("Total", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if ((discounts.isNotEmpty() || basketRule!=null) && totalResult.isSuccess) {
                                Row(Modifier.fillMaxWidth()) { Text("Before discount", Modifier.weight(1f)); Text(money(cartSubtotal(lines))) }
                                Row(Modifier.fillMaxWidth()) { Text("Discount", Modifier.weight(1f)); Text("−${money(cartDiscountTotal(lines))}") }
                            }
                            Text(money(total), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
                            Button(shape = MaterialTheme.shapes.small, onClick = {
                                if (stage == "items") { stage = "payment"; return@Button }
                                val cash = cashGiven
                                when {
                                    isSplit && externalResult.isFailure -> error=externalResult.exceptionOrNull()?.message
                                    externalAmount>total -> error="External amount exceeds this sale. Record advances separately; nothing was saved."
                                    method=="split" && (cashGiven ?: 0)<cashNeeded -> error="Still to pay ${money(cashNeeded-(cashGiven ?: 0))}. Choose customer credit explicitly or complete the recorded portions."
                                    cash == null -> error = "Enter a valid cash amount, with no more than two decimal places."
                                    method == "cash" && cash < total -> error = "Still to pay ${money(total - cash)}. Choose Pay later / part cash only if you are granting customer credit."
                                    method in setOf("credit","split_credit") && credit > 0 && customer == null -> error = "Choose the customer who will pay the balance."
                                    method in setOf("credit","split_credit") && credit>0 && dueDateText.isNotBlank() && runCatching { val date=java.time.LocalDate.parse(dueDateText.trim()); require(date.toString()==dueDateText.trim() && !date.isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")))) }.isFailure -> error="Use YYYY-MM-DD for a due date today or later."
                                    else -> {
                                        committing = true
                                        val result = try { onCheckout(lines, appliedCash, customer?.id, operationId, if(credit>0) dueDateText.trim().ifBlank { null } else null,basketRule,externalPayments) } catch (e: Exception) { e.message ?: "Sale not saved" }
                                        committing = false
                                        if (result == null) {
                                            savedTotal = total; savedChange = maxOf(0, cash - cashNeeded); savedCredit = credit;savedExternal=externalAmount; stage = "saved"
                                            try { draftStore?.clear(operationId) } catch(e: Exception) { error = "Sale saved, but the old draft could not be removed. Its operation reference prevents a duplicate checkout." }
                                        } else error = result
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = quantityErrors.none {it.key in quantities && it.value} && lines.isNotEmpty() && !committing && totalResult.isSuccess && !unavailableDraftItems && !staleDiscounts && !staleBasket && (!forbiddenDraft || stage=="items") && draftStore != null && restoredDraft.isSuccess) {
                                Text(if (committing) "Saving…" else if (stage == "items") "Take payment" else if (method in setOf("credit","split_credit") && credit > 0) "Save with ${money(credit)} owed" else if(isSplit) "Save without bank confirmation" else "Finish sale")
                            }
                            if (stage == "items" && lines.isEmpty()) Text("Add an item to take payment", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            if (stage == "items" && lines.isNotEmpty()) Text("Draft only · finish payment to record this sale", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if(editingBasket) {
        var kind by remember {mutableStateOf(basketRule?.kind ?: "fixed")}
        var valueText by remember {mutableStateOf(basketRule?.let {if(it.kind=="fixed") BigDecimal.valueOf(it.value,2).toPlainString() else BigDecimal.valueOf(it.value,2).toPlainString()} ?: "")}
        var reasonText by remember {mutableStateOf(basketRule?.reason.orEmpty())}
        var basketError by remember {mutableStateOf<String?>(null)}
        val preview=runCatching {
            val value=if(kind=="fixed") requireNotNull(parseNaira(valueText)) {"Enter a valid naira amount."} else requireNotNull(parseDiscountPercentage(valueText)) {"Enter a percentage from 0 to 100."}.basisPoints.toLong()
            val rule=BasketDiscount(kind,value,reasonText.trim())
            val allocated=allocateBasketDiscount(rawLines,rule)
            rule to allocated
        }
        AlertDialog(onDismissRequest={editingBasket=false},title={Text("Discount this whole sale")},text={
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Applied after item discounts. The saved reduction is shared across these lines; returns refund each line's actual paid amount.")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind=="fixed",{kind="fixed";valueText=""},label={Text("Amount off")})
                    FilterChip(kind=="percentage",{kind="percentage";valueText=""},label={Text("Percentage off")})
                }
                OutlinedTextField(valueText,{valueText=it;basketError=null},label={Text(if(kind=="fixed") "Amount off (₦)" else "Percentage off (0–100%)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(reasonText,{reasonText=it;basketError=null},label={Text("Reason for basket discount")},modifier=Modifier.fillMaxWidth())
                preview.getOrNull()?.let {(_,allocated) ->
                    Text("Basket saves ${money(allocated.sumOf {it.basketDiscountMinor})} · Customer pays ${money(cartTotal(allocated))}",fontWeight=FontWeight.Medium)
                    allocated.forEach {line -> Text("${line.product.name}: ${money(line.basketDiscountMinor)} off",style=MaterialTheme.typography.bodySmall)}
                }
                Text("Changing items, quantities, prices or item discounts requires another review. Holding reserves no stock and freezes no catalogue price.",style=MaterialTheme.typography.bodySmall)
                basketError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
        },confirmButton={TextButton({try {persistBasketRule(preview.getOrThrow().first);editingBasket=false} catch(e:Exception) {basketError=e.message ?: "Discount not saved."}}) {Text("Apply basket discount")}},dismissButton={Row {
            if(basketRule!=null) TextButton({try {persistBasketRule(null);editingBasket=false} catch(e:Exception) {basketError=e.message}}) {Text("Remove basket discount")}
            TextButton({editingBasket=false}) {Text("Cancel")}
        }})
    }
    changingPrice?.let { key ->
        val line = lines.find { it.key == key }
        if (line != null) AlertDialog(
            onDismissRequest = { changingPrice = null },
            title = { Text("Change price for this sale") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${line.product.name} · ${line.sellingUnit}", fontWeight = FontWeight.Medium)
                Text("Catalogue price: ${money(line.unitPriceMinor)} per ${line.sellingUnit}. This change applies to every ${line.sellingUnit} on this line.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!percentageMode, { percentageMode = false; priceError = null }, label = { Text("Agreed price") })
                    FilterChip(percentageMode, { percentageMode = true; priceError = null }, label = { Text("Percentage off") })
                }
                if (percentageMode) {
                    OutlinedTextField(percentageText, { percentageText = it; priceError = null }, label = { Text("Percentage off (0–100%)") }, supportingText = { Text("Up to two decimal places, for example 12.5") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val percentage=parseDiscountPercentage(percentageText)
                    if (percentage != null) {
                        val reduction=percentage.amountMinor(line.unitPriceMinor)
                        Text("Save ${money(reduction)} per ${line.sellingUnit} · Customer pays ${money(line.unitPriceMinor-reduction)} per ${line.sellingUnit}", fontWeight = FontWeight.Medium)
                    }
                    Text("The discount is rounded to the nearest kobo for each selling unit; half a kobo rounds up. It replaces any discount already on this line.", style = MaterialTheme.typography.bodySmall)
                } else OutlinedTextField(agreedPrice, { agreedPrice = it; priceError = null }, label = { Text("Customer pays per ${line.sellingUnit} (₦)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(discountReason, { discountReason = it; priceError = null }, label = { Text("Reason for discount") }, placeholder = { Text("For example, agreed market price") }, modifier = Modifier.fillMaxWidth())
                Text("The catalogue price stays unchanged. The saved receipt keeps the discount and reason. Returns refund the price actually paid.", style = MaterialTheme.typography.bodySmall)
                priceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton({
                try {
                    val next = discounts.toMutableMap()
                    if (percentageMode) {
                        val percentage=requireNotNull(parseDiscountPercentage(percentageText)) { "Enter a percentage from 0 to 100 with at most two decimal places." }
                        if (percentage.basisPoints == 0) next.remove(key)
                        else next[key] = percentage.unitDiscount(line.unitPriceMinor,discountReason.trim())
                    } else {
                        val price = requireNotNull(parseNaira(agreedPrice)) { "Enter a valid price in naira with at most two decimal places." }
                        require(price in 0..line.unitPriceMinor) { "The agreed price must be between zero and the catalogue price." }
                        if (price == line.unitPriceMinor) next.remove(key)
                        else next[key] = UnitDiscount(line.unitPriceMinor, line.unitPriceMinor-price, discountReason.trim())
                    }
                    persistBasket(quantities, next)
                    changingPrice = null
                } catch(e: Exception) { priceError = e.message ?: "Price change could not be saved." }
            }) { Text("Save price") } },
            dismissButton = { Row {
                if (discounts.containsKey(key)) TextButton({
                    try { persistBasket(quantities, discounts-key); changingPrice=null }
                    catch(e: Exception) { priceError=e.message }
                }) { Text("Remove discount") }
                TextButton({ changingPrice=null }) { Text("Cancel") }
            } },
        )
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
                            basketKind=resumed.basketDiscount?.kind.orEmpty();basketValue=resumed.basketDiscount?.value ?: 0;basketReason=resumed.basketDiscount?.reason.orEmpty();basketBasis=resumed.basketBasis.orEmpty();encodedCart=resumed.cart;encodedDiscounts=DraftDiscountCodec.encode(resumed.discounts);operationId=resumed.operationId;cashText=resumed.cashText;selectedCustomer=resumed.customerId;method=resumed.method;dueDateText=resumed.dueDateText;externalMethod=resumed.externalMethod;externalAmountText=resumed.externalAmountText;externalReference=resumed.externalReference;externalAccount=resumed.externalAccount;stage="items";error=null;heldSales=draftStore.heldSales();showHeld=false;showSaleOnly=true
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
    if(selectingPriceCustomer) AlertDialog(onDismissRequest={selectingPriceCustomer=false},title={Text("Customer for this sale")},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
        TextButton({persistPayment(customerId=null);selectingPriceCustomer=false}) {Text("Walk-in customer")}
        customers.forEach {value ->TextButton({persistPayment(customerId=value.id);selectingPriceCustomer=false}) {Text(value.name)}}
    }},confirmButton={TextButton({selectingPriceCustomer=false}) {Text("Close")}})
    if(selectingPrices && repository!=null) PriceListSelectionDialog(repository,tenantId,rawLines,selectedCustomer,onApply={quotes ->
        try {val next=discounts.toMutableMap();quotes.forEach {quote ->val discount=quote.discount();if(discount==null) next.remove(quote.lineKey) else next[quote.lineKey]=discount};persistBasket(quantities,next);selectingPrices=false;reviewNotice="Price list applied. Review quantities and total before payment."} catch(e:Exception) {error="Prices not applied: ${e.message}";selectingPrices=false}
    },onDismiss={selectingPrices=false})
    discard?.let { held -> AlertDialog(onDismissRequest={discard=null},title={Text("Remove ${held.name}?")},text={Text("This removes the unfinished basket only. No sale, stock movement or payment will be recorded.")},confirmButton={TextButton({
        try {requireNotNull(draftStore).discardHeld(held.id);heldSales=draftStore.heldSales();discard=null;heldError=null} catch(e:Exception) {heldError=e.message;discard=null}
    }) {Text("Remove saved sale")}},dismissButton={TextButton({discard=null}) {Text("Keep sale")}}) }
}

private fun formatStock(stock: Double): String = if (stock % 1.0 == 0.0) stock.toLong().toString() else stock.toString()
