package pos.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class AndroidTradeCatalogue(val customers:List<Customer>,val products:List<Product>,val units:List<SellingUnit>)
internal fun readAndroidTradeCatalogue(repo:PosRepository,tenantId:String)=synchronized(repo) {AndroidTradeCatalogue(repo.customers(tenantId),repo.products(tenantId),repo.sellingUnits(tenantId))}
internal fun requireAndroidTradeCatalogue(repo:PosRepository,tenantId:String,expected:AndroidTradeCatalogue) {
    require(readAndroidTradeCatalogue(repo,tenantId)==expected) {"Customer or catalogue details changed. Reopen and review the goods."}
}

@Composable
internal fun AndroidTradeCustomerPicker(customers:List<Customer>,selected:String?,change:(String)->Unit) {
    var menu by remember {mutableStateOf(false)}
    Box {
        OutlinedButton({menu=true},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(customers.singleOrNull {it.id==selected}?.name ?: "Choose customer")}
        DropdownMenu(menu,{menu=false}) {customers.forEach {customer ->DropdownMenuItem(text={Text(customer.name)},onClick={change(customer.id);menu=false})}}
    }
}

/** Explicit base/pack quantities; no stock is changed by editing these terms. */
@Composable
internal fun AndroidTradeGoodsEditor(catalogue:AndroidTradeCatalogue,lines:List<QuotationLineInput>,agreedPrices:Boolean,change:(List<QuotationLineInput>)->Unit) {
    var productId by remember {mutableStateOf<String?>(null)}
    var unitId by remember(productId) {mutableStateOf<String?>(null)}
    var quantity by remember(productId,unitId) {mutableStateOf("1")}
    var price by remember(productId,unitId) {mutableStateOf("")}
    var productMenu by remember {mutableStateOf(false)};var unitMenu by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val product=catalogue.products.singleOrNull {it.id==productId}
    val unit=catalogue.units.singleOrNull {it.id==unitId}
    val normal=unit?.priceMinor ?: product?.priceMinor
    lines.forEach {line ->
        val item=catalogue.products.singleOrNull {it.id==line.productId}
        val pack=catalogue.units.singleOrNull {it.id==line.sellingUnitId}
        Text("${item?.name ?: "Unavailable item"} · ${quantityLabel(line.quantity.toLong(),if(pack==null)item?.quantityPrecision ?: 0 else 0)} ${pack?.name ?: item?.unit.orEmpty()} · ${money(line.agreedUnitPriceMinor)} per unit")
        TextButton({change(lines.filterNot {it.key==line.key})}) {Text("Remove this item")}
    }
    Box {
        OutlinedButton({productMenu=true},Modifier.fillMaxWidth()){Text(product?.name ?: "Choose goods")}
        DropdownMenu(productMenu,{productMenu=false}) {catalogue.products.forEach {item ->DropdownMenuItem(text={Text(item.name)},onClick={productId=item.id;productMenu=false;error=null})}}
    }
    if(product!=null) {
        Box {
            OutlinedButton({unitMenu=true},Modifier.fillMaxWidth()){Text(unit?.name ?: product.unit)}
            DropdownMenu(unitMenu,{unitMenu=false}) {
                DropdownMenuItem(text={Text(product.unit)},onClick={unitId=null;unitMenu=false})
                catalogue.units.filter {it.productId==product.id}.forEach {pack ->DropdownMenuItem(text={Text(pack.name)},onClick={unitId=pack.id;unitMenu=false})}
            }
        }
        PosTextField(quantity,{quantity=it;error=null},"Quantity in ${unit?.name ?: product.unit}")
        if(agreedPrices) PosTextField(price,{price=it;error=null},"Agreed price per ${unit?.name ?: product.unit} (₦)",hint="Blank uses ${money(requireNotNull(normal))}")
        else LabelValueRow("Current price per unit",money(requireNotNull(normal)))
        OutlinedButton({runCatching {
            val parsed=requireNotNull(parseQuantity(quantity,if(unit==null)product.quantityPrecision else 0)) {"Enter the quantity in the displayed selling unit."}
            val count=Math.toIntExact(parsed.scaledUnits);require(count>0)
            val amount=if(agreedPrices && price.isNotBlank()) requireNotNull(parseNaira(price)) {"Enter an exact agreed price."} else requireNotNull(normal)
            val line=QuotationLineInput(product.id,unit?.id,count,amount)
            require(lines.none {it.key==line.key}) {"Remove the existing row before changing its quantity."}
            change(lines+line);productId=null;error=null
        }.onFailure {error=it.message ?: "Review this item."}},Modifier.fillMaxWidth()){Text("Add these goods")}
    }
    error?.let {InlineNotice("Goods not added",it,NoticeTone.Danger)}
}

@Composable
fun AndroidOrdersScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit,onReceipt:((String)->Unit)?=null)=key(tenantId) {
    var revision by remember {mutableIntStateOf(0)};var creating by remember {mutableStateOf(false)}
    var action by remember {mutableStateOf<Pair<CustomerOrderState,String>?>(null)}
    var receiptId by remember {mutableStateOf<String?>(null)}
    var dispatchOrder by remember {mutableStateOf<CustomerOrderState?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision){repo.customerOrders(tenantId)}
    fun changed(message:String){revision++;notice=message;onChanged()}
    fun openReceipt(id:String){if(onReceipt==null)receiptId=id else onReceipt(id)}
    BackHandler(receiptId!=null || dispatchOrder!=null){if(receiptId!=null)receiptId=null else dispatchOrder=null}
    if(creating) AndroidCreateOrder(repo,tenantId,{creating=false}){creating=false;changed("Customer order saved. No goods or cash moved.")}
    action?.let {(state,kind) ->AndroidOrderAction(repo,tenantId,state,kind,{action=null}){receipt ->action=null;changed("Order action saved on this device");receipt?.let(::openReceipt)}}
    if(receiptId!=null) {
        AndroidReceiptScreen(repo,tenantId,requireNotNull(receiptId),{receiptId=null})
    } else if(dispatchOrder!=null) {
        AndroidDispatchScreen(repo,tenantId,requireNotNull(dispatchOrder).order.id,{dispatchOrder=null}){changed("Delivery history saved")}
    } else {
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Customer orders","Agreed goods, cash deposits and explicit collection")}
        item {PosButton("New customer order",{creating=true},Modifier.fillMaxWidth())}
        notice?.let {item {InlineNotice(it,tone=NoticeTone.Success)}}
        item {OperationsLoadState(loaded,"orders",{revision++})}
        if(loaded?.getOrNull()?.isEmpty()==true)item {EmptyState("No customer orders","Create an order without recording a sale or changing stock.")}
        items(loaded?.getOrNull().orEmpty(),key={it.order.id}){state ->OperationsCard {
            Text(state.order.customerName,style=MaterialTheme.typography.titleMedium)
            Text("Order ${state.order.id.takeLast(8)} · ${if(state.cancelled)"Cancelled" else if(state.completed)"Collected" else "Open"}")
            LabelValueRow("Agreed order total",money(state.order.totalMinor))
            LabelValueRow("Deposit still held",money(state.depositLeftMinor))
            LabelValueRow("Remaining goods value",money(state.remainingValueMinor))
            LabelValueRow("Still to pay on remaining goods",money(state.balanceMinor),emphasized=true)
            state.order.collectOn?.let {Text("Planned collection $it · WAT")}
            state.order.lines.forEach {line ->Text("${line.productName} · ${quantityLabel(state.remaining.getValue(line.key).toLong(),line.quantityPrecision)} ${line.sellingUnit} remaining")}
            if(state.open) {
                OutlinedButton({action=state to "deposit"},Modifier.fillMaxWidth(),enabled=state.balanceMinor>0){Text("Record cash deposit")}
                PosButton("Collect goods",{action=state to "collect"},Modifier.fillMaxWidth())
                OutlinedButton({action=state to "hold"},Modifier.fillMaxWidth()){Text("Review stock hold")}
                TextButton({action=state to "cancel"}){Text("Cancel remaining order")}
            }
            if(state.history.any {it.kind=="collected"})OutlinedButton({dispatchOrder=state},Modifier.fillMaxWidth()){Text("Dispatch & delivery proof")}
            state.history.forEach {event ->
                val label=when(event.kind){"deposit"->"Cash deposit received";"collected"->"Deposit applied at collection";else->"Deposit refunded on cancellation"}
                Text("$label · ${money(event.amountMinor)} · ${event.createdAt.take(19).replace('T',' ')} UTC",style=MaterialTheme.typography.bodySmall)
                event.saleId?.let {id ->TextButton({openReceipt(id)}){Text("Open collection receipt")}}
            }
        }}
    }
    }
}

@Composable
private fun AndroidCreateOrder(repo:PosRepository,tenantId:String,dismiss:()->Unit,saved:()->Unit) {
    val loaded=operationsLoad(repo,tenantId,Unit){readAndroidTradeCatalogue(repo,tenantId)}
    var customerId by remember {mutableStateOf<String?>(null)};var date by remember {mutableStateOf("")};var note by remember {mutableStateOf("")}
    var source by remember {mutableStateOf("In shop")};var lines by remember {mutableStateOf(emptyList<QuotationLineInput>())}
    var reviewed by remember {mutableStateOf(false)};var confirmed by remember {mutableStateOf(false)}
    val operation=remember {UUID.randomUUID().toString()}
    OperationsDialog("New customer order",dismiss,if(reviewed)"Save customer order" else "Review customer order",action={
        val catalogue=requireNotNull(loaded).getOrThrow();val customer=requireNotNull(customerId){"Choose an existing customer."}
        require(lines.isNotEmpty()){ "Choose the goods for this order." }
        require(date.isBlank() || isValidCreditDueDate(date.trim())){"Use YYYY-MM-DD for the collection date."}
        if(!reviewed)reviewed=true else {
            require(confirmed){"Confirm the agreed order terms."}
            withContext(Dispatchers.IO){synchronized(repo){requireAndroidTradeCatalogue(repo,tenantId,catalogue);repo.createCustomerOrder(tenantId,customer,source,date.trim().ifEmpty {null},note.trim(),lines,operation)}}
            saved()
        }
    }) {
        Text("Creating an order does not collect cash, hold stock or record a sale. Record each action separately after saving.")
        OperationsLoadState(loaded,"catalogue",null)
        loaded?.getOrNull()?.let {catalogue ->
            AndroidTradeCustomerPicker(catalogue.customers,customerId){customerId=it;reviewed=false;confirmed=false}
            listOf("In shop","Phone","WhatsApp","Other").forEach {option ->FilterChip(source==option,{source=option;reviewed=false;confirmed=false},label={Text(option)})}
            PosTextField(date,{date=it;reviewed=false;confirmed=false},"Planned collection date (optional)",hint="YYYY-MM-DD · WAT")
            PosTextField(note,{note=it;reviewed=false;confirmed=false},"Order note (optional)")
            AndroidTradeGoodsEditor(catalogue,lines,false){lines=it;reviewed=false;confirmed=false}
            if(reviewed) {
                LabelValueRow("Agreed goods total",money(androidTradeTotal(lines,catalogue)),emphasized=true)
                OperationsCheck(confirmed,{confirmed=it},"I checked the customer, selling units, quantities and agreed prices.")
            }
        }
    }
}

internal fun androidTradeTotal(lines:List<QuotationLineInput>,catalogue:AndroidTradeCatalogue):Long=lines.fold(0L){sum,line ->
    val precision=if(line.sellingUnitId==null)catalogue.products.single {it.id==line.productId}.quantityPrecision else 0
    Math.addExact(sum,Quantity(line.quantity.toLong(),precision).lineTotalMinor(line.agreedUnitPriceMinor))
}

@Composable
private fun AndroidOrderAction(repo:PosRepository,tenantId:String,state:CustomerOrderState,kind:String,dismiss:()->Unit,saved:(String?)->Unit) {
    var amount by remember {mutableStateOf("")};var reason by remember {mutableStateOf("")};var holdDate by remember {mutableStateOf(LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(7).toString())}
    var reviewed by remember {mutableStateOf(false)};var confirmed by remember {mutableStateOf(false)}
    var collection by remember {mutableStateOf<OrderCollectionReview?>(null)}
    val quantities=remember {mutableStateMapOf<String,String>().apply {state.order.lines.forEach {put(it.key,quantityLabel(state.remaining.getValue(it.key).toLong(),it.quantityPrecision))}}}
    val holds=operationsLoad(repo,tenantId,state.order.id){repo.customerOrderReservationHistory(tenantId,state.order.id)}
    val operation=remember {UUID.randomUUID().toString()}
    fun chosen()=state.order.lines.mapNotNull {line ->
        val n=requireNotNull(parseQuantity(quantities[line.key].orEmpty(),line.quantityPrecision)){"Enter ${line.productName} in ${line.sellingUnit}."}.scaledUnits
        require(n in 0..state.remaining.getValue(line.key).toLong()){ "Quantity exceeds the goods still waiting for collection." }
        if(n==0L)null else line.key to Math.toIntExact(n)
    }.toMap().also {require(it.isNotEmpty()){ "Choose at least one quantity to collect." }}
    val title=when(kind){"deposit"->"Record cash deposit";"collect"->"Collect order goods";"cancel"->"Cancel remaining order";else->"Review stock hold"}
    OperationsDialog(title,dismiss,if(reviewed)"Save order action" else "Review order action",action={
        if(!reviewed) {
            if(kind=="deposit")require(requireNotNull(parseNaira(amount)){"Enter actual cash received."} in 1..state.balanceMinor)
            if(kind=="collect")collection=withContext(Dispatchers.IO){repo.reviewCustomerOrderCollection(tenantId,state.order.id,chosen())}
            if(kind=="cancel")require(reason.trim().length>=3){"Enter why the remaining order is cancelled."}
            if(kind=="hold")require(isValidCreditDueDate(holdDate.trim())){"Enter a valid hold-until date."}
            reviewed=true
        } else {
            require(confirmed){"Confirm this reviewed action and any actual cash."}
            val receipt=withContext(Dispatchers.IO){when(kind){
                "deposit"->{repo.recordCustomerOrderDeposit(tenantId,state.order.id,requireNotNull(parseNaira(amount)),state.history.size,operation);null}
                "collect"->repo.collectCustomerOrder(tenantId,requireNotNull(collection),operation).saleId
                "cancel"->{repo.cancelCustomerOrder(tenantId,state.order.id,state.history.size,state.depositLeftMinor,reason.trim(),operation);null}
                else->{val history=requireNotNull(holds).getOrThrow();val latest=history.lastOrNull();val today=LocalDate.now(ZoneId.of("Africa/Lagos")).toString()
                    if(latest?.kind=="held" && latest.expiresOn.orEmpty()>=today) repo.releaseCustomerOrderStock(tenantId,state.order.id,reason.trim(),state.history.size,latest.revision,operation)
                    else repo.holdCustomerOrderStock(tenantId,state.order.id,holdDate.trim(),state.history.size,latest?.revision ?: 0,operation);null}
            }}
            saved(receipt)
        }
    }) {
        Text("Order ${state.order.id.takeLast(8)} · ${state.order.customerName}")
        if(kind=="deposit")PosTextField(amount,{amount=it;reviewed=false;confirmed=false},"Cash deposit counted (₦)",hint="Up to ${money(state.balanceMinor)}")
        if(kind=="collect")state.order.lines.forEach {line ->PosTextField(quantities[line.key].orEmpty(),{quantities[line.key]=it;reviewed=false;confirmed=false;collection=null},"${line.productName} · ${line.sellingUnit}",hint="Remaining ${quantityLabel(state.remaining.getValue(line.key).toLong(),line.quantityPrecision)}")}
        if(kind=="cancel"){LabelValueRow("Cash deposit to refund",money(state.depositLeftMinor),emphasized=true);PosTextField(reason,{reason=it;reviewed=false;confirmed=false},"Reason for cancellation")}
        if(kind=="hold") {
            OperationsLoadState(holds,"stock holds",null)
            val latest=holds?.getOrNull()?.lastOrNull();val active=latest?.kind=="held" && latest.expiresOn.orEmpty()>=LocalDate.now(ZoneId.of("Africa/Lagos")).toString()
            if(active){Text("Goods held through ${latest?.expiresOn}. Saving this action releases that hold.");PosTextField(reason,{reason=it;reviewed=false;confirmed=false},"Reason to release the stock hold")}
            else PosTextField(holdDate,{holdDate=it;reviewed=false;confirmed=false},"Hold remaining stock through",hint="YYYY-MM-DD · WAT")
            Text("A stock hold reduces availability for other sales. It does not move physical stock or receive money.")
        }
        if(reviewed) {
            collection?.let {review ->LabelValueRow("Goods being collected",money(cartTotal(review.lines)));LabelValueRow("Deposit applied once",money(review.depositMinor));LabelValueRow("Exact additional cash to receive",money(review.cashMinor),emphasized=true)}
            if(kind=="deposit")LabelValueRow("Cash entering drawer",money(requireNotNull(parseNaira(amount))),emphasized=true)
            if(kind=="cancel")Text("Uncollected goods are cancelled. Any held deposit shown above is refunded in cash. Completed collections remain in history.")
            OperationsCheck(confirmed,{confirmed=it},when(kind){"deposit"->"I counted and received this cash deposit.";"collect"->"I checked these goods and received the exact additional cash shown.";"cancel"->"I checked the cancellation and paid the cash refund shown, if any.";else->"I checked the remaining goods and this stock hold action."})
        }
    }
}
