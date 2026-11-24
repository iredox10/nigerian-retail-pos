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

@Composable
fun AndroidQuotationsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit,onReceipt:((String)->Unit)?=null,canManage:Boolean=true)=key(tenantId) {
    var receiptId by remember {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)};var creating by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<Quotation?>(null)};var action by remember {mutableStateOf<Pair<Quotation,String>?>(null)}
    var history by remember {mutableStateOf<Quotation?>(null)};var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision){synchronized(repo){repo.quotations(tenantId) to repo.quotationOrderLinks(tenantId)}}
    fun changed(message:String){revision++;notice=message;onChanged()}
    fun openReceipt(id:String){if(onReceipt==null)receiptId=id else onReceipt(id)}
    BackHandler(receiptId!=null){receiptId=null}
    if(creating || editing!=null) AndroidQuotationEditor(repo,tenantId,editing,{creating=false;editing=null}){creating=false;editing=null;changed("Quotation terms saved. No goods or money moved.")}
    action?.let {(quote,kind) ->AndroidQuotationAction(repo,tenantId,quote,kind,{action=null}){sale ->action=null;changed("Quotation action saved on this device");sale?.let(::openReceipt)}}
    history?.let {quote ->
        val events=operationsLoad(repo,tenantId,quote.id to revision){repo.quotationEvents(tenantId,quote.id)}
        AlertDialog(onDismissRequest={history=null},title={Text("Quotation history")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)){
            item {OperationsLoadState(events,"quotation history",null)}
            items(events?.getOrNull().orEmpty(),key={it.id}){event ->OperationsCard {
                Text("Revision ${event.snapshot.version} · ${event.kind}",style=MaterialTheme.typography.titleSmall)
                Text(event.reason);LabelValueRow("Agreed total",money(event.snapshot.totalMinor));Text("Expires ${event.snapshot.expiresOn}")
                event.snapshot.lines.forEach {line ->Text("${line.productName} · ${line.quantityLabel} ${line.sellingUnit} · ${money(line.agreedUnitPriceMinor)} each")}
            }}
        }},confirmButton={TextButton({history=null}){Text("Close")}})
    }
    if(receiptId!=null) {AndroidReceiptScreen(repo,tenantId,requireNotNull(receiptId),{receiptId=null})} else {
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Quotations","Keep agreed prices separate from sales and money received")}
        if(canManage)item {PosButton("New quotation",{creating=true},Modifier.fillMaxWidth())}
        notice?.let {item {InlineNotice(it,tone=NoticeTone.Success)}}
        item {OperationsLoadState(loaded,"quotations",{revision++})}
        if(loaded?.getOrNull()?.first?.isEmpty()==true)item {EmptyState("No quotations yet","Create reviewed terms without making a sale or receiving payment.")}
        items(loaded?.getOrNull()?.first.orEmpty(),key={it.id}){quote ->OperationsCard {
            Text(quote.customer?.name ?: "Quotation ${quote.id.takeLast(8)}",style=MaterialTheme.typography.titleMedium)
            Text("${quote.status.name.lowercase().replaceFirstChar {it.uppercase()}} · Revision ${quote.version} · Expires ${quote.expiresOn} WAT")
            LabelValueRow("Agreed total",money(quote.totalMinor),emphasized=true)
            quote.lines.forEach {line ->Text("${line.productName} · ${line.quantityLabel} ${line.sellingUnit} · ${money(line.agreedUnitPriceMinor)} per unit")}
            if(quote.notes.isNotBlank())Text(quote.notes)
            val order=loaded?.getOrNull()?.second?.singleOrNull {it.quotationId==quote.id}
            if(order!=null)Text("Created order ${order.orderId.takeLast(8)}. Record payments and collection from Orders.")
            if(canManage && order==null && quote.status in setOf(QuotationStatus.ISSUED,QuotationStatus.ACCEPTED)) {
                if(quote.status==QuotationStatus.ISSUED){OutlinedButton({editing=quote},Modifier.fillMaxWidth()){Text("Revise issued quotation")};PosButton("Record customer acceptance",{action=quote to "accept"},Modifier.fillMaxWidth())}
                else {PosButton("Create customer order",{action=quote to "order"},Modifier.fillMaxWidth());OutlinedButton({action=quote to "cash"},Modifier.fillMaxWidth()){Text("Convert to full cash sale")}}
                TextButton({action=quote to "cancel"}){Text("Cancel quotation")}
            }
            quote.saleId?.let {sale ->OutlinedButton({openReceipt(sale)},Modifier.fillMaxWidth()){Text("Open converted sale receipt")}}
            TextButton({history=quote}){Text("Saved revision history")}
        }}
    }
    }
}

@Composable
private fun AndroidQuotationEditor(repo:PosRepository,tenantId:String,previous:Quotation?,dismiss:()->Unit,saved:()->Unit) {
    val loaded=operationsLoad(repo,tenantId,Unit){readAndroidTradeCatalogue(repo,tenantId)}
    var customerId by remember {mutableStateOf(previous?.customer?.customerId)}
    var date by remember {mutableStateOf(previous?.expiresOn ?: LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(7).toString())}
    var note by remember {mutableStateOf(previous?.notes.orEmpty())};var reason by remember {mutableStateOf("")}
    var lines by remember {mutableStateOf(previous?.lines?.map {it.input()}.orEmpty())}
    var reviewed by remember {mutableStateOf(false)};var confirmed by remember {mutableStateOf(false)}
    val operation=remember {UUID.randomUUID().toString()}
    OperationsDialog(if(previous==null)"New quotation" else "Revise issued quotation",dismiss,if(reviewed)"Save quotation terms" else "Review quotation",action={
        val catalogue=requireNotNull(loaded).getOrThrow();require(lines.isNotEmpty()){ "Choose the goods for this quotation." }
        require(isValidCreditDueDate(date.trim())){"Use YYYY-MM-DD for the expiry date."}
        if(previous!=null)require(reason.trim().length>=3){"Explain this revision."}
        if(!reviewed)reviewed=true else {
            require(confirmed){"Confirm the goods, units, prices and expiry."}
            withContext(Dispatchers.IO){synchronized(repo){
                requireAndroidTradeCatalogue(repo,tenantId,catalogue)
                val customer=catalogue.customers.singleOrNull {it.id==customerId}?.let {QuotationCustomer(it.id,it.name,it.phone,it.address)}
                if(previous==null)repo.issueQuotation(tenantId,lines,date.trim(),customer,note.trim(),operation)
                else repo.reviseQuotation(tenantId,previous.id,lines,date.trim(),customer,note.trim(),reason.trim(),previous.version,operation)
            }};saved()
        }
    }) {
        Text("These terms do not reserve stock, record a sale or receive money.")
        OperationsLoadState(loaded,"catalogue",null)
        loaded?.getOrNull()?.let {catalogue ->
            Text("Existing customer (optional)",style=MaterialTheme.typography.titleSmall)
            AndroidTradeCustomerPicker(catalogue.customers,customerId){customerId=it;reviewed=false;confirmed=false}
            if(customerId!=null)TextButton({customerId=null;reviewed=false;confirmed=false}){Text("Leave customer unlinked")}
            PosTextField(date,{date=it;reviewed=false;confirmed=false},"Quotation expires on",hint="YYYY-MM-DD · WAT")
            PosTextField(note,{note=it;reviewed=false;confirmed=false},"Quotation notes (optional)")
            if(previous!=null)PosTextField(reason,{reason=it;reviewed=false;confirmed=false},"Reason for revised terms")
            AndroidTradeGoodsEditor(catalogue,lines,true){lines=it;reviewed=false;confirmed=false}
            if(reviewed){LabelValueRow("Agreed quotation total",money(androidTradeTotal(lines,catalogue)),emphasized=true);OperationsCheck(confirmed,{confirmed=it},"I checked these goods, selling units, quantities, prices and expiry.")}
        }
    }
}

@Composable
private fun AndroidQuotationAction(repo:PosRepository,tenantId:String,quote:Quotation,kind:String,dismiss:()->Unit,saved:(String?)->Unit) {
    val customers=operationsLoad(repo,tenantId,quote.id){repo.customers(tenantId)}
    var customerId by remember {mutableStateOf(quote.customer?.customerId)}
    var deposit by remember {mutableStateOf("0")};var date by remember {mutableStateOf("")};var note by remember {mutableStateOf("")};var reason by remember {mutableStateOf("")}
    var confirmed by remember {mutableStateOf(false)};var reviewed by remember {mutableStateOf(false)}
    var orderReview by remember {mutableStateOf<QuotationOrderReview?>(null)};var cashReview by remember {mutableStateOf<QuotationConversionReview?>(null)}
    val operation=remember {UUID.randomUUID().toString()}
    val title=when(kind){"order"->"Create order from quotation";"cash"->"Convert quotation to cash sale";"accept"->"Record customer acceptance";else->"Cancel quotation"}
    OperationsDialog(title,dismiss,if(reviewed)"Save reviewed action" else "Review quotation action",action={
        if(!reviewed) {
            when(kind){
                "order"->{val amount=requireNotNull(parseNaira(deposit)){"Enter exact cash deposit or zero."};require(amount in 0..quote.totalMinor);orderReview=withContext(Dispatchers.IO){repo.reviewQuotationOrder(tenantId,quote.id,quote.version,requireNotNull(customerId){"Choose an existing customer."},date.trim().ifEmpty {null},note.trim())}}
                "cash"->cashReview=withContext(Dispatchers.IO){repo.quotationConversionReview(tenantId,quote.id,quote.version)}
                "cancel"->require(reason.trim().length>=3){"Enter a cancellation reason."}
            }
            reviewed=true
        } else {
            require(confirmed){"Confirm this action and any cash shown."}
            val receipt=withContext(Dispatchers.IO){when(kind){
                "order"->{repo.convertQuotationToOrder(tenantId,requireNotNull(orderReview),requireNotNull(parseNaira(deposit)),operation);null}
                "cash"->repo.convertQuotation(tenantId,quote.id,quote.version,requireNotNull(cashReview).token,quote.totalMinor,operation).id
                "accept"->{repo.acceptQuotation(tenantId,quote.id,quote.version,operation);null}
                else->{repo.cancelQuotation(tenantId,quote.id,reason.trim(),quote.version,operation);null}
            }};saved(receipt)
        }
    }) {
        Text("Quotation ${quote.id.takeLast(8)} · Revision ${quote.version}")
        LabelValueRow("Agreed total",money(quote.totalMinor),emphasized=true)
        if(kind=="order") {
            Text("Copies accepted prices into an order. Deposits are actual cash; collection and stock holds remain separate order actions.")
            OperationsLoadState(customers,"customers",null)
            if(quote.customer?.customerId==null)AndroidTradeCustomerPicker(customers?.getOrNull().orEmpty(),customerId){customerId=it;reviewed=false;confirmed=false}
            else Text("Customer · ${quote.customer?.name.orEmpty()}")
            PosTextField(deposit,{deposit=it;reviewed=false;confirmed=false;orderReview=null},"Cash deposit received (₦)",hint="0 when no cash was received")
            PosTextField(date,{date=it;reviewed=false;confirmed=false;orderReview=null},"Planned collection date (optional)",hint="YYYY-MM-DD · WAT")
            PosTextField(note,{note=it;reviewed=false;confirmed=false;orderReview=null},"Order note (optional)")
        }
        if(kind=="cash")Text("Receives exactly the agreed total in cash and creates one sale. Short payment does not become debt. Give any tender change before confirming the exact amount retained.")
        if(kind=="cancel")PosTextField(reason,{reason=it;reviewed=false;confirmed=false},"Reason for cancelling this quotation")
        if(kind=="accept")Text("Record only confirmed customer agreement. Acceptance does not sell goods, hold stock or receive money.")
        if(reviewed) {
            if(kind=="order"){val amount=requireNotNull(parseNaira(deposit));LabelValueRow("Cash entering drawer",money(amount));LabelValueRow("Remaining order balance",money(quote.totalMinor-amount))}
            if(kind=="cash")LabelValueRow("Exact cash received",money(quote.totalMinor),emphasized=true)
            OperationsCheck(confirmed,{confirmed=it},when(kind){"order"->"I checked the order terms and counted any cash deposit shown.";"cash"->"I checked the goods and received the exact cash shown.";"accept"->"The customer accepted this exact saved revision.";else->"I confirm cancellation; original revisions remain in history."})
        }
    }
}
