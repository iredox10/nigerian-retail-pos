package pos.screens

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
import pos.data.*
import pos.domain.*
import pos.output.CsvService
import pos.output.PurchaseOrderCsv
import pos.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Supplier subsection; approval never fabricates a receipt, stock movement or payment. */
@Composable
fun PurchaseOrdersScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var search by rememberSaveable {mutableStateOf("")}
    var creating by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf(false)}
    var receiving by remember {mutableStateOf(false)}
    var cancelling by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {repo.purchaseOrders(tenantId)}}
    val orders=loaded.getOrDefault(emptyList())
    val selected=orders.find {it.id==selectedId}
    fun refresh(text:String) {revision++;message=text;error=null;onChanged()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeader("Purchase orders","Requests to suppliers · Stock and money change only when goods are received",Modifier.weight(1f))
            if(selected==null) PosButton("New purchase order",{creating=true}) else NeutralButton("Back to orders",{selectedId=null})
        }
        loaded.exceptionOrNull()?.let {InlineNotice("Orders unavailable",it.message ?: "Try again.",NoticeTone.Danger);NeutralButton("Try again",{revision++})}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        error?.let {InlineNotice("Order not changed",it,NoticeTone.Danger)}
        if(selected==null && loaded.isSuccess) {
            PosTextField(search,{search=it},"Find a purchase order",hint="Supplier, item, state or order ID")
            val visible=orders.filter {order ->listOf(order.id,order.supplierName,order.status.name,order.lines.joinToString {it.productName}).any {it.contains(search,true)}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visible.isEmpty()) item {EmptyState(if(orders.isEmpty()) "No purchase orders yet" else "No matching orders","Create a draft with a supplier and the goods you plan to buy. Approval does not add stock or create debt.")}
                items(visible,key={it.id}) {order ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Text("Order ${order.id.takeLast(8)} · ${order.supplierName}",Modifier.weight(1f),fontWeight=FontWeight.Medium);StatusPill(orderStatusLabel(order.status),NoticeTone.Neutral);NeutralButton("Open order",{selectedId=order.id})}
                    Text("${order.lines.size} line(s) · Created ${order.createdAt}",style=MaterialTheme.typography.bodySmall)
                    LabelValueRow("Estimated buying cost",money(order.estimatedTotalMinor))
                }}}
            }
        } else if(selected!=null) {
            val history=remember(selected,revision) {runCatching {Triple(repo.purchaseOrderDeliveries(tenantId,selected.id),repo.purchaseOrderRemaining(tenantId,selected.id),repo.purchaseOrderEvents(tenantId,selected.id))}}
            history.exceptionOrNull()?.let {InlineNotice("Order history unavailable",it.message ?: "Refresh and try again.",NoticeTone.Danger)}
            if(history.isSuccess) {
                val (deliveries,remaining,events)=history.getOrThrow()
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item {Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        StatusPill(orderStatusLabel(selected.status),NoticeTone.Neutral)
                        LabelValueRow("Order ID",selected.id)
                        LabelValueRow("Supplier saved on order",selected.supplierName)
                        LabelValueRow("Supplier account",selected.supplierId)
                        LabelValueRow("Estimated order buying cost",money(selected.estimatedTotalMinor),emphasized=true)
                        if(selected.notes.isNotEmpty()) Text(selected.notes)
                        Text("Approval is an agreement to order, not payment or stock receipt. Each actual delivery records its own cash and supplier balance; partial deliveries round their own costs to the nearest kobo.",style=MaterialTheme.typography.bodySmall)
                        if(selected.status==PurchaseOrderStatus.CANCELLED) InlineNotice("Unreceived remainder cancelled",selected.cancellationReason.orEmpty()+" Earlier deliveries, cash and supplier debt remain in their original history.",NoticeTone.Info)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(selected.status==PurchaseOrderStatus.DRAFT) {
                                NeutralButton("Edit draft",{editing=true})
                                PosButton("Approve order",{try {repo.approvePurchaseOrder(tenantId,selected.id,selected.version,"approve:"+UUID.randomUUID());refresh("Order approved. No stock, cash or supplier debt was recorded.")} catch(e:Exception) {error=e.message ?: "Order was not approved."}})
                            }
                            if(selected.status==PurchaseOrderStatus.APPROVED) PosButton("Receive delivery",{receiving=true},enabled=remaining.values.any {it>0})
                            if(selected.status!=PurchaseOrderStatus.CANCELLED) NeutralButton("Cancel unreceived order",{cancelling=true})
                            NeutralButton("Save order CSV",{try {
                                val csv=PurchaseOrderCsv.order(selected,deliveries,repo.stockReceipts(tenantId))
                                val file=CsvService.save(csv,"purchase-order-${selected.id.takeLast(8)}.csv")
                                message=if(file==null) "Export cancelled." else "Order CSV saved to ${file.name}.";error=null
                            } catch(e:Exception) {error=e.message ?: "CSV could not be saved."}})
                        }
                    }}}
                    item {SectionHeader("Ordered goods","Quantities use the buying unit and conversion saved on the order.")}
                    items(selected.lines,key={it.id}) {line ->Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(line.productName,fontWeight=FontWeight.Medium)
                        LabelValueRow("Ordered", "${line.quantityLabel} ${line.buyingUnit}")
                        LabelValueRow("Received", "${quantityLabel((line.quantity-remaining.getValue(line.id)).toLong(),line.quantityPrecision)} ${line.buyingUnit}")
                        LabelValueRow(if(selected.status==PurchaseOrderStatus.CANCELLED) "Unreceived, cancelled" else "Still to receive","${quantityLabel(remaining.getValue(line.id).toLong(),line.quantityPrecision)} ${line.buyingUnit}")
                        LabelValueRow("Agreed buying cost", "${money(line.unitCostMinor)} / ${line.buyingUnit}")
                        Text("1 ${line.buyingUnit} = ${line.baseUnitsPerUnit} ${line.baseUnit}",style=MaterialTheme.typography.bodySmall)
                    }}}
                    item {SectionHeader("Deliveries","Each saved receipt can be reviewed under Stock and paid under Suppliers → Receipts & payments.")}
                    if(deliveries.isEmpty()) item {Text("No deliveries received. This order has not changed stock or money.")}
                    items(deliveries,key={it.id}) {delivery ->Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Delivery ${delivery.id.takeLast(8)} · ${delivery.createdAt}",fontWeight=FontWeight.Medium)
                        delivery.lines.forEach {received ->val line=selected.lines.single {it.id==received.lineId};Text("${quantityLabel(received.quantity.toLong(),line.quantityPrecision)} ${line.buyingUnit} ${line.productName} · Receipt ${received.receiptId}",style=MaterialTheme.typography.bodySmall)}
                    }}}
                    item {SectionHeader("Order history","Draft revisions and approvals remain recorded.")}
                    items(events.reversed(),key={it.id}) {event ->Text("Revision ${event.version} · ${event.createdAt} · ${event.reason}",style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
    }
    if(creating || (editing && selected!=null)) PurchaseOrderEditor(repo,tenantId,if(creating)null else selected,{creating=false;editing=false}) {supplier,lines,notes,reason,operation ->
        val saved=if(creating) repo.createPurchaseOrder(tenantId,supplier,lines,notes,operation) else repo.editPurchaseOrder(tenantId,selected!!.id,supplier,lines,notes,reason,selected.version,operation)
        creating=false;editing=false;selectedId=saved.id;refresh("Purchase order draft saved. Stock and money are unchanged.")
    }
    if(receiving && selected!=null) PurchaseDeliveryDialog(repo,selected,{receiving=false}) {receiving=false;refresh("Delivery received. Stock, cash applied and supplier balances were saved together.")}
    if(cancelling && selected!=null) {
        var reason by remember {mutableStateOf("")};var localError by remember {mutableStateOf<String?>(null)}
        val operation=remember(selected.id,reason) {UUID.randomUUID().toString()}
        AlertDialog(onDismissRequest={cancelling=false},title={Text("Cancel unreceived order")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("This stops further receiving on this order. It does not undo goods already received, cash paid or supplier debt.")
            PosTextField(reason,{reason=it},"Why is the remaining order cancelled?")
            localError?.let {InlineNotice("Order not cancelled",it,NoticeTone.Danger)}
        }},confirmButton={PosButton("Save cancellation",{try {repo.cancelPurchaseOrder(tenantId,selected.id,reason.trim(),selected.version,operation);cancelling=false;refresh("Unreceived order cancelled. Earlier deliveries are unchanged.")} catch(e:Exception) {localError=e.message ?: "Cancellation could not be saved."}})},dismissButton={TextButton({cancelling=false}) {Text("Keep order")}})
    }
}

@Composable
private fun PurchaseOrderEditor(repo:PosRepository,tenantId:String,order:PurchaseOrder?,dismiss:()->Unit,save:(String,List<PurchaseOrderLineInput>,String,String,String)->Unit) {
    val loaded=remember {runCatching {repo.supplierProfiles(tenantId) to repo.products(tenantId)}}
    val suppliers=loaded.getOrNull()?.first.orEmpty();val products=loaded.getOrNull()?.second.orEmpty()
    var supplierId by remember {mutableStateOf(order?.supplierId)}
    var supplierMenu by remember {mutableStateOf(false)}
    var itemId by remember {mutableStateOf<String?>(null)}
    var itemSearch by remember {mutableStateOf("")}
    var unitId by remember {mutableStateOf<String?>(null)}
    var quantity by remember {mutableStateOf("")};var cost by remember {mutableStateOf("")}
    var notes by remember {mutableStateOf(order?.notes.orEmpty())};var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val lines=remember {mutableStateListOf<PurchaseOrderLineInput>().also {it.addAll(order?.lines?.map {line ->line.input()}.orEmpty())}}
    val supplier=suppliers.find {it.id==supplierId};val item=products.find {it.id==itemId}
    val units=remember(itemId) {runCatching {if(itemId==null) emptyList() else repo.purchaseUnits(tenantId,itemId!!)}}
    val unit=units.getOrDefault(emptyList()).find {it.id==unitId}
    val precision=if(unit==null) item?.quantityPrecision ?: 0 else 0
    val operation=remember(supplierId,lines.toList(),notes,reason) {UUID.randomUUID().toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(order==null) "New purchase order" else "Edit purchase order draft")},text={Column(Modifier.widthIn(max=600.dp).heightIn(max=600.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Choose a supplier and add each item with its agreed buying price. Saving or approving this draft does not add stock, pay cash or create supplier debt.",style=MaterialTheme.typography.bodySmall)
        loaded.exceptionOrNull()?.let {InlineNotice("Items or suppliers unavailable",it.message ?: "Close and try again.",NoticeTone.Danger)}
        Box {NeutralButton(supplier?.details?.name ?: "Choose supplier account",{supplierMenu=true});DropdownMenu(supplierMenu,{supplierMenu=false}) {suppliers.forEach {p ->DropdownMenuItem(text={Text("${p.details.name} · ${p.id.takeLast(8)}")},onClick={supplierId=p.id;supplierMenu=false})}}}
        if(suppliers.isEmpty() && loaded.isSuccess) Text("Add an active supplier profile under Suppliers before creating this order.")
        PosTextField(itemSearch,{itemSearch=it},"Find goods to order",hint="Search item names")
        Column(Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {products.filter {it.name.contains(itemSearch,true)}.forEach {p ->TextButton({itemId=p.id;unitId=null;quantity="";cost=""}) {Text("${if(itemId==p.id) "Selected: " else ""}${p.name} · ${p.unit}")}}}
        item?.let {product ->
            Text(product.name,fontWeight=FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(unitId==null,{unitId=null;quantity=""},label={Text(product.unit)})
                units.getOrDefault(emptyList()).forEach {u ->FilterChip(unitId==u.id,{unitId=u.id;quantity=""},label={Text("${u.name} · ${u.baseUnitsPerUnit} ${product.unit}")})}
            }
            PosTextField(quantity,{quantity=it},"Order quantity (${unit?.name ?: product.unit})",hint="Greater than 0; up to $precision decimal places")
            PosTextField(cost,{cost=it},"Agreed buying price per ${unit?.name ?: product.unit} (₦)")
            NeutralButton("Add order line",{try {
                val q=requireNotNull(quantityCount(quantity,precision)) {"Enter a positive quantity using this unit's decimal places."}
                val c=requireNotNull(parseNaira(cost)) {"Enter a non-negative buying cost in naira."}
                require(lines.none {it.productId==product.id && it.purchaseUnitId==unitId}) {"That item and buying unit are already listed. Remove its line to replace the quantity or price."}
                require(lines.size<200) {"An order supports up to 200 lines."}
                lines+=PurchaseOrderLineInput(UUID.randomUUID().toString(),product.id,q,c,unitId);quantity="";cost="";error=null
            } catch(e:Exception) {error=e.message ?: "Order line could not be added."}})
        }
        units.exceptionOrNull()?.let {InlineNotice("Buying units unavailable",it.message ?: "Choose another item or reopen the draft.",NoticeTone.Danger)}
        Text("${lines.size} order line(s)",fontWeight=FontWeight.Medium)
        lines.toList().forEach {line ->
            val p=products.find {it.id==line.productId};val original=order?.lines?.find {it.id==line.id}
            Row(verticalAlignment=Alignment.CenterVertically) {Text("${p?.name ?: original?.productName ?: "Unavailable item"} · ${if(original!=null) original.quantityLabel else quantityLabel(line.quantity.toLong(),if(line.purchaseUnitId==null)p?.quantityPrecision ?: 0 else 0)} · ${money(line.unitCostMinor)} per buying unit",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton({lines.remove(line)}) {Text("Remove")}}
        }
        PosTextField(notes,{notes=it},"Order notes (optional)")
        if(order!=null) PosTextField(reason,{reason=it},"Why is the draft changing?")
        error?.let {InlineNotice("Draft not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Save draft",{try {require(supplier!=null) {"Choose an active supplier account."};require(lines.isNotEmpty()) {"Add at least one order line."};save(supplier.id,lines.toList(),notes.trim(),reason.trim(),operation)} catch(e:Exception) {error=e.message ?: "Draft could not be saved."}},enabled=loaded.isSuccess)},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}

@Composable
private fun PurchaseDeliveryDialog(repo:PosRepository,order:PurchaseOrder,dismiss:()->Unit,saved:()->Unit) {
    val loaded=remember {runCatching {repo.purchaseOrderRemaining(order.tenantId,order.id) to repo.products(order.tenantId,true).associateBy {it.id}}}
    val quantities=remember {mutableStateMapOf<String,String>()};val paid=remember {mutableStateMapOf<String,String>()}
    var error by remember {mutableStateOf<String?>(null)}
    val operation=remember(quantities.toMap(),paid.toMap()) {UUID.randomUUID().toString()}
    val preview=runCatching {order.lines.fold(0L to 0L) {sum,line ->
        val input=quantities[line.id].orEmpty().trim();if(input.isEmpty()) return@fold sum
        val q=requireNotNull(quantityCount(input,line.quantityPrecision)) {"Check the received quantities."}
        val p=requireNotNull(parseNaira(paid[line.id].orEmpty())) {"Enter cash paid for each received line; enter 0 explicitly for supplier credit."}
        val total=Quantity(q.toLong(),line.quantityPrecision).lineTotalMinor(line.unitCostMinor);require(p<=total) {"Cash paid exceeds a line's buying cost."}
        Math.addExact(sum.first,total) to Math.addExact(sum.second,p)
    }}
    AlertDialog(onDismissRequest=dismiss,title={Text("Receive purchase order delivery")},text={Column(Modifier.widthIn(max=600.dp).heightIn(max=600.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Record only accepted goods entering sellable stock. Damaged/quarantined deliveries are not supported here; leave unaccepted quantities unreceived. Blank quantities leave a line unreceived. Enter cash paid on every received line; enter 0 explicitly when the supplier is still owed.")
        loaded.exceptionOrNull()?.let {InlineNotice("Receiving unavailable",it.message ?: "Reopen the order.",NoticeTone.Danger)}
        order.lines.filter {loaded.getOrNull()?.first?.get(it.id)?.let {remaining ->remaining>0}==true}.forEach {line ->
            val remaining=loaded.getOrThrow().first.getValue(line.id)
            Text("${line.productName} · ${line.buyingUnit}",fontWeight=FontWeight.Medium)
            Text("Still ordered: ${quantityLabel(remaining.toLong(),line.quantityPrecision)} ${line.buyingUnit} · ${money(line.unitCostMinor)} each",style=MaterialTheme.typography.bodySmall)
            PosTextField(quantities[line.id].orEmpty(),{quantities[line.id]=it;error=null},"Quantity received (${line.buyingUnit})")
            PosTextField(paid[line.id].orEmpty(),{paid[line.id]=it;error=null},"Cash paid for this line (₦)",hint="Enter 0 for supplier credit")
            HorizontalDivider()
        }
        preview.getOrNull()?.let {(cost,cash) ->LabelValueRow("This delivery buying cost",money(cost));LabelValueRow("Cash paid now",money(cash));LabelValueRow("Supplier still owed on this delivery",money(cost-cash),emphasized=true)}
        Text("Each delivery rounds its buying cost separately to the nearest kobo. Cash requires an open drawer with sufficient recorded money. If stock changed since this preview, close and reopen the form.",style=MaterialTheme.typography.bodySmall)
        error?.let {InlineNotice("Delivery not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Save delivery & add stock",{try {
        val current=loaded.getOrThrow()
        val inputs=order.lines.mapNotNull {line ->val value=quantities[line.id].orEmpty().trim();if(value.isEmpty()) null else {
            val quantity=requireNotNull(quantityCount(value,line.quantityPrecision)) {"Check the received quantity for ${line.productName}."}
            require(quantity<=current.first.getValue(line.id)) {"Received quantity exceeds what is still ordered."}
            val cash=requireNotNull(parseNaira(paid[line.id].orEmpty())) {"Enter cash paid for ${line.productName}; use 0 explicitly for supplier credit."}
            PurchaseDeliveryInput(line.id,quantity,cash,requireNotNull(current.second[line.productId]) {"An item is no longer available."}.exactStockScaled)
        }}
        require(inputs.isNotEmpty()) {"Enter at least one received quantity."}
        repo.receivePurchaseOrder(order.tenantId,order.id,inputs,order.version,operation);saved()
    } catch(e:Exception) {error=e.message ?: "Delivery could not be saved. Your entries remain here."}},enabled=loaded.isSuccess)},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}

private fun orderStatusLabel(status:PurchaseOrderStatus)=when(status) {PurchaseOrderStatus.DRAFT -> "Draft";PurchaseOrderStatus.APPROVED -> "Approved to order";PurchaseOrderStatus.CANCELLED -> "Unreceived remainder cancelled"}
