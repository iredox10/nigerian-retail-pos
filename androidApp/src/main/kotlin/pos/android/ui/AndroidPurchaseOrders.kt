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
import pos.ui.components.*
import java.util.UUID

private data class BuyingOrders(val orders:List<PurchaseOrder>,val remaining:Map<String,Map<String,Int>>)
@Composable
fun AndroidPurchaseOrdersScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var creating by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<PurchaseOrder?>(null)}
    var approving by remember {mutableStateOf<PurchaseOrder?>(null)}
    var cancelling by remember {mutableStateOf<PurchaseOrder?>(null)}
    var receiving by remember {mutableStateOf<PurchaseOrder?>(null)}
    var history by remember {mutableStateOf<PurchaseOrder?>(null)}
    var discrepancies by remember {mutableStateOf<PurchaseOrder?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision) {val orders=repo.purchaseOrders(tenantId);BuyingOrders(orders,orders.associate {it.id to repo.purchaseOrderRemaining(tenantId,it.id)})}
    fun changed(message:String) {creating=false;editing=null;approving=null;cancelling=null;receiving=null;revision++;notice=message;onChanged()}
    if(creating || editing!=null)AndroidPurchaseDraft(repo,tenantId,editing,{creating=false;editing=null}) {changed("Purchase draft saved. No stock or cash changed.")}
    approving?.let {order ->
        val operation=remember(order.id) {UUID.randomUUID().toString()}
        OperationsDialog("Approve purchase order",{approving=null},"Approve this order",action={
            withContext(Dispatchers.IO) {repo.approvePurchaseOrder(tenantId,order.id,order.version,operation)}
            changed("Order approved. Record goods only when they arrive.")
        }) {Text(order.supplierName);order.lines.forEach {Text("${it.productName} · ${it.quantityLabel} ${it.buyingUnit} · ${money(it.estimatedTotalMinor)}")};LabelValueRow("Estimated buying total",money(order.estimatedTotalMinor));Text("Approval records agreed terms. It does not add stock, record a supplier debt or pay cash.")}
    }
    cancelling?.let {order ->
        var reason by rememberSaveable(order.id) {mutableStateOf("")};val operation=remember(order.id) {UUID.randomUUID().toString()}
        OperationsDialog("Cancel remaining purchase order",{cancelling=null},"Save cancellation",action={withContext(Dispatchers.IO) {repo.cancelPurchaseOrder(tenantId,order.id,reason.trim(),order.version,operation)};changed("Remaining order cancelled; received goods and history retained.")}) {
            Text(order.supplierName);Text("Goods already received, cash paid and supplier debt remain recorded. This cancels only the remaining order.");PosTextField(reason,{reason=it},"Reason for cancellation")
        }
    }
    receiving?.let {order ->AndroidPurchaseReceiving(repo,tenantId,order,{receiving=null}) {changed("Delivery saved with its stock, cash and supplier balances.")}}
    discrepancies?.let {order ->AndroidPurchaseDiscrepancies(repo,tenantId,order,{discrepancies=null}) {revision++;onChanged()}}
    history?.let {order ->
        val events=operationsLoad(repo,tenantId,order.id) {repo.purchaseOrderEvents(tenantId,order.id) to repo.purchaseOrderDeliveries(tenantId,order.id)}
        OperationsDialog("Purchase order history",{history=null},"Close",action={history=null}) {
            Text(order.supplierName);OperationsLoadState(events,"purchase order history",{})
            events?.getOrNull()?.let {(changes,deliveries) ->
                changes.forEach {Text("${it.createdAt.take(19)} UTC · ${it.kind} · Revision ${it.version}");Text(it.reason)}
                deliveries.forEach {delivery ->HorizontalDivider();Text("Delivery ${delivery.createdAt.take(19)} UTC");delivery.lines.forEach {line ->Text("Receipt ${line.receiptId} · ${money(line.paidCashMinor)} cash paid")}}
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Purchase orders","Agree what to buy, then record the deliveries that actually arrive")}
        item {PosButton("New purchase order",{creating=true},modifier=Modifier.fillMaxWidth())}
        notice?.let {item {InlineNotice(it,tone=NoticeTone.Success)}}
        item {OperationsLoadState(loaded,"purchase orders",{revision++})}
        if(loaded?.isSuccess==true && loaded.getOrThrow().orders.isEmpty())item {EmptyState("No purchase orders","Add a supplier and stock items, then create an order.")}
        items(loaded?.getOrNull()?.orders.orEmpty(),key={it.id}) {order ->OperationsCard {
            Text(order.supplierName,style=MaterialTheme.typography.titleMedium)
            Text("${order.status.name.lowercase().replaceFirstChar {it.uppercase()}} · Revision ${order.version}")
            LabelValueRow("Estimated order total",money(order.estimatedTotalMinor))
            order.lines.forEach {line ->Text("${line.productName} · ${line.quantityLabel} ${line.buyingUnit} ordered · ${quantityLabel(loaded?.getOrNull()?.remaining?.get(order.id)?.get(line.id)?.toLong() ?: 0,line.quantityPrecision)} remaining")}
            if(order.status==PurchaseOrderStatus.DRAFT) {
                OutlinedButton(onClick={editing=order}) {Text("Edit draft")}
                PosButton("Review approval",{approving=order})
            }
            if(order.status==PurchaseOrderStatus.APPROVED && loaded?.getOrNull()?.remaining?.get(order.id)?.values?.any {it>0}==true)PosButton("Record goods received",{receiving=order})
            if(order.status!=PurchaseOrderStatus.CANCELLED)TextButton(onClick={cancelling=order}) {Text("Cancel remaining order")}
            TextButton(onClick={history=order}) {Text("View order and delivery history")}
            TextButton(onClick={discrepancies=order}) {Text("Review discrepancies")}
        }}
    }
}

private data class PurchaseChoices(val products:List<Product>,val suppliers:List<SupplierProfile>,val units:List<PurchaseUnit>)
@Composable private fun AndroidPurchaseDraft(repo:PosRepository,tenantId:String,original:PurchaseOrder?,dismiss:()->Unit,saved:()->Unit) {
    val loaded=operationsLoad(repo,tenantId,original?.id) {PurchaseChoices(repo.products(tenantId),repo.supplierProfiles(tenantId),repo.purchaseUnits(tenantId))}
    var supplierId by rememberSaveable {mutableStateOf(original?.supplierId)}
    var productId by rememberSaveable {mutableStateOf<String?>(null)}
    var unitId by rememberSaveable {mutableStateOf<String?>(null)}
    var search by rememberSaveable {mutableStateOf("")}
    var quantity by rememberSaveable {mutableStateOf("")}
    var cost by rememberSaveable {mutableStateOf("")}
    var notes by rememberSaveable {mutableStateOf(original?.notes.orEmpty())}
    var reason by rememberSaveable {mutableStateOf("")}
    var lines by remember {mutableStateOf(original?.lines?.map {it.input()}.orEmpty())}
    var reviewed by remember {mutableStateOf(false)}
    var lineError by remember {mutableStateOf<String?>(null)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    val choices=loaded?.getOrNull();val product=choices?.products?.singleOrNull {it.id==productId};val unit=choices?.units?.singleOrNull {it.id==unitId}
    fun edit() {reviewed=false}
    OperationsDialog(if(original==null)"New purchase order" else "Edit purchase draft",dismiss,if(reviewed)"Save purchase draft" else "Review purchase draft",action={
        val current=requireNotNull(choices) {"Wait for this shop’s suppliers and items to load."}
        val supplier=requireNotNull(current.suppliers.singleOrNull {it.id==supplierId}) {"Choose an active supplier."}
        require(lines.isNotEmpty() && lines.size<=200) {"Add 1–200 different item and buying-unit combinations."}
        require(productId==null || quantity.isBlank() && cost.isBlank()) {"Add or clear the unfinished line before reviewing."}
        if(!reviewed)reviewed=true else {
            val requested=lines.toList();val text=notes.trim();val why=reason.trim()
            withContext(Dispatchers.IO) {
                if(original==null)repo.createPurchaseOrder(tenantId,supplier.id,requested,text,operation)
                else repo.editPurchaseOrder(tenantId,original.id,supplier.id,requested,text,why,original.version,operation)
            };saved()
        }
    }) {
        OperationsLoadState(loaded,"suppliers and stock",{})
        Text("Supplier",style=MaterialTheme.typography.titleMedium)
        choices?.suppliers.orEmpty().forEach {supplier ->FilterChip(supplierId==supplier.id,{supplierId=supplier.id;edit()},label={Text(supplier.details.name)})}
        PosTextField(search,{search=it},"Find an item")
        choices?.products.orEmpty().filter {it.matchesCatalogueSearch(search)}.take(30).forEach {p ->FilterChip(productId==p.id,{productId=p.id;unitId=null;quantity="";cost="";edit()},label={Text(p.name)})}
        if((choices?.products?.count {it.matchesCatalogueSearch(search)} ?: 0)>30)Text("Showing the first 30 matching items. Refine the search to find another item.")
        product?.let {p ->
            FilterChip(unitId==null,{unitId=null;quantity="";cost="";edit()},label={Text(p.unit)})
            choices?.units.orEmpty().filter {it.productId==p.id}.forEach {pack ->FilterChip(unitId==pack.id,{unitId=pack.id;quantity="";cost="";edit()},label={Text("${pack.name} · ${pack.baseUnitsPerUnit} ${pack.baseUnit}")})}
            PosTextField(quantity,{quantity=it;edit()},"Quantity in ${unit?.name ?: p.unit}")
            PosTextField(cost,{cost=it;edit()},"Buying price per ${unit?.name ?: p.unit} (₦)")
            OutlinedButton(onClick={runCatching {
                val places=if(unit==null)p.quantityPrecision else 0
                val scaled=requireNotNull(parseQuantity(quantity,places)) {"Enter the quantity in the selected buying unit."}.scaledUnits
                require(scaled in 1..Int.MAX_VALUE.toLong()) {"Enter a positive order quantity."}
                val price=requireNotNull(parseNaira(cost)) {"Enter an exact buying price."}
                require(lines.none {it.productId==p.id && it.purchaseUnitId==unitId}) {"Remove the existing line before adding this item/buying unit again."}
                lines=lines+PurchaseOrderLineInput(UUID.randomUUID().toString(),p.id,scaled.toInt(),price,unitId)
                productId=null;unitId=null;quantity="";cost="";lineError=null;edit()
            }.onFailure {lineError=it.message}}) {Text("Add order line")}
            TextButton(onClick={productId=null;unitId=null;quantity="";cost="";lineError=null;edit()}) {Text("Clear unfinished line")}
        }
        lineError?.let {InlineNotice("Check this line",it,NoticeTone.Danger)}
        HorizontalDivider()
        Text("${lines.size} order lines",style=MaterialTheme.typography.titleMedium)
        lines.forEach {line ->
            val p=choices?.products?.singleOrNull {it.id==line.productId};val u=choices?.units?.singleOrNull {it.id==line.purchaseUnitId}
            val places=if(line.purchaseUnitId==null)p?.quantityPrecision ?: original?.lines?.singleOrNull {it.id==line.id}?.quantityPrecision ?: 0 else 0
            Text("${p?.name ?: original?.lines?.singleOrNull {it.id==line.id}?.productName ?: line.productId} · ${quantityLabel(line.quantity.toLong(),places)} ${u?.name ?: p?.unit.orEmpty()} at ${money(line.unitCostMinor)} each")
            TextButton(onClick={lines=lines.filterNot {it.id==line.id};edit()}) {Text("Remove this line")}
        }
        PosTextField(notes,{notes=it;edit()},"Order notes (optional)")
        if(original!=null)PosTextField(reason,{reason=it;edit()},"Why is this draft changing?")
        Text("Saving a draft does not change stock, pay cash or create supplier debt. Approve it separately after reviewing the agreed terms.")
        if(reviewed)Text("Review all ${lines.size} lines and the supplier above, then save.",style=MaterialTheme.typography.titleMedium)
    }
}

private data class ReceivingState(val remaining:Map<String,Int>,val products:Map<String,Product>)
@Composable private fun AndroidPurchaseReceiving(repo:PosRepository,tenantId:String,order:PurchaseOrder,dismiss:()->Unit,saved:()->Unit) {
    val loaded=operationsLoad(repo,tenantId,order.id) {ReceivingState(repo.purchaseOrderRemaining(tenantId,order.id),repo.products(tenantId,true).associateBy {it.id})}
    var quantities by remember {mutableStateOf<Map<String,String>>(emptyMap())}
    var paid by remember {mutableStateOf<Map<String,String>>(emptyMap())}
    var reviewed by remember {mutableStateOf<List<PurchaseDeliveryInput>?>(null)}
    var confirmed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    OperationsDialog("Receive purchase order",dismiss,if(reviewed==null)"Review delivery" else "Save actual delivery",action={
        val state=requireNotNull(loaded?.getOrThrow()) {"Wait for the current stock and remaining order quantities."}
        val delivery=reviewed
        if(delivery==null) {
            val inputs=order.lines.mapNotNull {line ->
                val text=quantities[line.id].orEmpty().trim()
                if(text.isBlank())null else {
                    val q=requireNotNull(parseQuantity(text,line.quantityPrecision)) {"Enter the actual quantity of ${line.productName} in ${line.buyingUnit}."}.scaledUnits
                    require(q in 1..(state.remaining[line.id] ?: 0).toLong() && q<=Int.MAX_VALUE) {"Received quantity must fit the remaining order for ${line.productName}."}
                    val cash=requireNotNull(parseNaira(paid[line.id].orEmpty().ifBlank {"0"})) {"Enter actual cash paid for ${line.productName}, or zero."}
                    require(cash<=Quantity(q,line.quantityPrecision).lineTotalMinor(line.unitCostMinor)) {"Cash paid exceeds this delivery line’s cost."}
                    PurchaseDeliveryInput(line.id,q.toInt(),cash,state.products.getValue(line.productId).exactStockScaled)
                }
            }
            require(inputs.isNotEmpty()) {"Enter the goods that actually arrived; leave other lines blank."};reviewed=inputs
        } else {
            require(confirmed) {"Confirm actual goods and cash paid."}
            withContext(Dispatchers.IO) {repo.receivePurchaseOrder(tenantId,order.id,delivery,order.version,operation)};saved()
        }
    }) {
        Text(order.supplierName);OperationsLoadState(loaded,"current stock and remaining quantities",{})
        order.lines.filter {(loaded?.getOrNull()?.remaining?.get(it.id) ?: 0)>0}.forEach {line ->
            Text(line.productName,style=MaterialTheme.typography.titleMedium)
            Text("Remaining ${quantityLabel(loaded?.getOrNull()?.remaining?.get(line.id)?.toLong() ?: 0,line.quantityPrecision)} ${line.buyingUnit} · ${money(line.unitCostMinor)} per ${line.buyingUnit}")
            PosTextField(quantities[line.id].orEmpty(),{quantities=quantities+(line.id to it);reviewed=null;confirmed=false},"Received in ${line.buyingUnit} (blank if none)")
            PosTextField(paid[line.id].orEmpty(),{paid=paid+(line.id to it);reviewed=null;confirmed=false},"Actual cash paid for this line (₦)")
        }
        reviewed?.let {delivery ->
            val cash=delivery.fold(0L) {sum,line ->Math.addExact(sum,line.paidCashMinor)}
            val total=delivery.fold(0L) {sum,input ->val line=order.lines.single {it.id==input.lineId};Math.addExact(sum,Quantity(input.quantity.toLong(),line.quantityPrecision).lineTotalMinor(line.unitCostMinor))}
            LabelValueRow("Delivery buying total",money(total));LabelValueRow("Cash paid",money(cash));LabelValueRow("Added supplier debt",money(total-cash))
            OperationsCheck(confirmed,{confirmed=it},"I received these goods and recorded only cash actually paid.")
        }
    }
}
