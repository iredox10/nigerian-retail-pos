package pos.android.ui

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
import java.util.UUID

@Composable
fun AndroidDispatchScreen(repo:PosRepository,tenantId:String,orderId:String,onBack:()->Unit,onChanged:()->Unit)=key(tenantId,orderId) {
    var revision by remember {mutableIntStateOf(0)};var creating by remember {mutableStateOf(false)}
    var action by remember {mutableStateOf<Pair<OrderDispatch,String>?>(null)};var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision){synchronized(repo){repo.customerOrders(tenantId).single {it.order.id==orderId} to repo.orderDispatches(tenantId,orderId)}}
    val state=loaded?.getOrNull()?.first
    fun changed(){revision++;notice="Delivery history saved on this device";onChanged()}
    if(creating && state!=null)AndroidCreateDispatch(repo,tenantId,state,{creating=false}){creating=false;changed()}
    action?.let {(shipment,kind) ->AndroidDeliveryStatus(repo,tenantId,shipment,kind,{action=null}){action=null;changed()}}
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {TextButton(onBack){Text("Back to orders")};SectionHeader("Dispatch & delivery proof","Merchant-recorded handover of already sold order goods")}
        item {InlineNotice("Separate from stock and payment","Collection already sold these goods. Dispatch records custody only; delivery proof does not confirm an external courier service.",NoticeTone.Info)}
        item {OperationsLoadState(loaded,"delivery history",{revision++})}
        notice?.let {item {InlineNotice(it,tone=NoticeTone.Success)}}
        if(state!=null)item {PosButton("Record goods leaving for delivery",{creating=true},Modifier.fillMaxWidth(),enabled=state.history.any {it.kind=="collected"})}
        if(loaded?.getOrNull()?.second?.isEmpty()==true)item {EmptyState("No dispatches recorded","Only goods from an order collection receipt can be dispatched.")}
        items(loaded?.getOrNull()?.second.orEmpty(),key={it.id}){shipment ->OperationsCard {
            Text(shipment.details.recipient,style=MaterialTheme.typography.titleMedium)
            Text(shipment.details.address);Text("Courier · ${shipment.details.courier}")
            if(shipment.details.tracking.isNotBlank())Text("Tracking · ${shipment.details.tracking}")
            Text("${shipment.status.replaceFirstChar {it.uppercase()}} · Revision ${shipment.revision}")
            state?.order?.lines?.filter {it.key in shipment.quantities}?.forEach {line ->Text("${line.productName} · ${quantityLabel(shipment.quantities.getValue(line.key).toLong(),line.quantityPrecision)} ${line.sellingUnit}")}
            if(shipment.status=="dispatched") {
                PosButton("Record delivered",{action=shipment to "delivered"},Modifier.fillMaxWidth())
                OutlinedButton({action=shipment to "failed"},Modifier.fillMaxWidth()){Text("Record failed delivery")}
                OutlinedButton({action=shipment to "returned"},Modifier.fillMaxWidth()){Text("Record goods returned by courier")}
            }
            if(shipment.status=="failed") {
                OutlinedButton({action=shipment to "dispatched"},Modifier.fillMaxWidth()){Text("Retry delivery")}
                OutlinedButton({action=shipment to "returned"},Modifier.fillMaxWidth()){Text("Record goods returned by courier")}
            }
            shipment.history.forEach {event ->
                Text("${event.kind.replaceFirstChar {it.uppercase()}} · ${event.createdAt.take(19).replace('T',' ')} UTC",style=MaterialTheme.typography.titleSmall)
                event.proofName?.let {Text("Received by $it")};Text(event.note,style=MaterialTheme.typography.bodySmall)
            }
        }}
    }
}

@Composable
private fun AndroidCreateDispatch(repo:PosRepository,tenantId:String,state:CustomerOrderState,dismiss:()->Unit,saved:()->Unit) {
    val sources=state.history.filter {it.kind=="collected"}.mapNotNull {it.saleId}.distinct()
    var saleId by remember {mutableStateOf(sources.firstOrNull())};var menu by remember {mutableStateOf(false)}
    val loaded=operationsLoad(repo,tenantId,saleId){repo.reviewOrderDispatch(tenantId,state.order.id,requireNotNull(saleId){"Collect order goods before dispatch."})}
    val review=loaded?.getOrNull();val quantities=remember(saleId,review?.token){mutableStateMapOf<String,String>().apply {review?.lines?.forEach {line ->put(line.key,quantityLabel(review.available.getValue(line.key).toLong(),line.quantityPrecision))}}}
    var recipient by remember {mutableStateOf(state.order.customerName)};var address by remember {mutableStateOf("")};var courier by remember {mutableStateOf("")};var tracking by remember {mutableStateOf("")};var note by remember {mutableStateOf("")}
    var reviewed by remember {mutableStateOf(false)};var confirmed by remember {mutableStateOf(false)}
    val operation=remember {UUID.randomUUID().toString()}
    fun chosen()=requireNotNull(review).lines.mapNotNull {line ->
        val n=requireNotNull(parseQuantity(quantities[line.key].orEmpty(),line.quantityPrecision)){"Enter dispatch quantities in the displayed units."}.scaledUnits
        require(n in 0..review.available.getValue(line.key).toLong()){ "Quantity exceeds sold goods available for this dispatch." }
        if(n==0L)null else line.key to Math.toIntExact(n)
    }.toMap().also {require(it.isNotEmpty()){ "Choose goods leaving for delivery." }}
    OperationsDialog("Record dispatch",dismiss,if(reviewed)"Save goods dispatched" else "Review dispatch",action={
        chosen();val expected=requireNotNull(loaded).getOrThrow()
        require(recipient.trim().isNotEmpty() && address.trim().length>=3 && courier.trim().isNotEmpty() && note.trim().length>=3){"Enter recipient, address, courier and dispatch note."}
        if(!reviewed)reviewed=true else {
            require(confirmed){"Confirm the actual quantities handed to the courier."}
            withContext(Dispatchers.IO){repo.createOrderDispatch(tenantId,expected,chosen(),OrderDispatchDetails(recipient.trim(),address.trim(),courier.trim(),tracking.trim(),note.trim()),operation)};saved()
        }
    }) {
        Box {
            OutlinedButton({menu=true},Modifier.fillMaxWidth()){Text("Collection receipt ${saleId?.takeLast(8).orEmpty()}")}
            DropdownMenu(menu,{menu=false}){sources.forEach {id ->DropdownMenuItem(text={Text("Receipt ${id.takeLast(8)}")},onClick={saleId=id;menu=false;reviewed=false;confirmed=false})}}
        }
        OperationsLoadState(loaded,"goods available for dispatch",null)
        review?.lines?.forEach {line ->PosTextField(quantities[line.key].orEmpty(),{quantities[line.key]=it;reviewed=false;confirmed=false},"${line.product.name} · ${line.sellingUnit}",hint="Available ${quantityLabel(review.available.getValue(line.key).toLong(),line.quantityPrecision)}")}
        PosTextField(recipient,{recipient=it;reviewed=false;confirmed=false},"Recipient")
        PosTextField(address,{address=it;reviewed=false;confirmed=false},"Delivery address and landmark")
        PosTextField(courier,{courier=it;reviewed=false;confirmed=false},"Courier or person delivering")
        PosTextField(tracking,{tracking=it;reviewed=false;confirmed=false},"Tracking reference (optional)")
        PosTextField(note,{note=it;reviewed=false;confirmed=false},"Dispatch note")
        if(reviewed){Text("These quantities were already sold. Saving this dispatch does not deduct stock again or collect cash.");OperationsCheck(confirmed,{confirmed=it},"I checked the quantities and handed these goods to this delivery person.")}
    }
}

@Composable
private fun AndroidDeliveryStatus(repo:PosRepository,tenantId:String,shipment:OrderDispatch,kind:String,dismiss:()->Unit,saved:()->Unit) {
    var name by remember {mutableStateOf("")};var note by remember {mutableStateOf("")};var reviewed by remember {mutableStateOf(false)};var confirmed by remember {mutableStateOf(false)}
    val operation=remember {UUID.randomUUID().toString()}
    OperationsDialog("Record ${if(kind=="dispatched")"delivery retry" else kind}",dismiss,if(reviewed)"Save delivery history" else "Review delivery update",action={
        require(note.trim().length>=3){"Enter the actual delivery update or proof note."};if(kind=="delivered")require(name.trim().isNotBlank()){ "Enter the person who received the goods." }
        if(!reviewed)reviewed=true else {
            require(confirmed){"Confirm this merchant-recorded delivery update."}
            withContext(Dispatchers.IO){repo.recordOrderDeliveryStatus(tenantId,shipment.id,shipment.revision,kind,if(kind=="delivered")name.trim() else null,note.trim(),operation)};saved()
        }
    }) {
        Text("Dispatch ${shipment.id.takeLast(8)} · ${shipment.details.courier}")
        if(kind=="delivered")PosTextField(name,{name=it;reviewed=false;confirmed=false},"Recipient proof name")
        PosTextField(note,{note=it;reviewed=false;confirmed=false},if(kind=="delivered")"Delivery proof or handover note" else "What happened?")
        Text("This records your observed outcome. It does not contact or verify a courier service, issue a customer refund, or change physical stock. Returned goods still need an explicit sale return if appropriate.")
        if(reviewed)OperationsCheck(confirmed,{confirmed=it},"I checked and confirm this delivery outcome and note.")
    }
}
