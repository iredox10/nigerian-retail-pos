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
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.QuotationOutputService
import pos.ui.components.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable
fun QuotationsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit,canManage:Boolean=true) {
    var revision by remember {mutableIntStateOf(0)}
    var selectedId by rememberSaveable(tenantId) {mutableStateOf<String?>(null)}
    var search by rememberSaveable(tenantId) {mutableStateOf("")}
    var editing by remember {mutableStateOf(false)}
    var creating by remember {mutableStateOf(false)}
    var cancelling by remember {mutableStateOf(false)}
    var converting by remember {mutableStateOf(false)}
    var convertingOrder by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {repo.quotations(tenantId)}}
    val orderLinks = remember(tenantId, revision) { runCatching { repo.quotationOrderLinks(tenantId) } }
    val quotes=loaded.getOrDefault(emptyList())
    fun displayedStatus(quote: Quotation) = if (orderLinks.getOrDefault(emptyList()).any { it.quotationId == quote.id }) "Customer order created" else quoteStatus(quote)
    val selected=quotes.find {it.id==selectedId}
    fun refresh(text:String) {revision++;message=text;error=null;onChanged()}
    fun export(quote:Quotation,pdf:Boolean) {
        try {
            val tenant=repo.tenants().single {it.id==tenantId}
            val file=if(pdf) QuotationOutputService.exportPdf(tenant,quote) else QuotationOutputService.exportText(tenant,quote)
            message=if(file==null) "Export cancelled." else "Quotation revision ${quote.version} saved to ${file.name}.";error=null
        } catch(e:Exception) {error=e.message ?: "Quotation could not be exported."}
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeader("Quotations","Agreed terms · No stock is reserved and no payment is recorded on issue",Modifier.weight(1f))
            if(selected==null && canManage) PosButton("New quotation",{creating=true})
            if(selected!=null) NeutralButton("Back to quotations",{selectedId=null})
        }
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        error?.let {InlineNotice("Quotation not changed",it,NoticeTone.Danger)}
        loaded.exceptionOrNull()?.let {InlineNotice("Quotations unavailable",it.message ?: "Try again.",NoticeTone.Danger);NeutralButton("Try again",{revision++})}
        if(selected==null && loaded.isSuccess) {
            PosTextField(search,{search=it},"Find a quotation",hint="Customer, item, status or quotation ID")
            val visible=quotes.filter {q ->listOf(q.id,q.customer?.name.orEmpty(),q.status.name,q.lines.joinToString {it.productName}).any {it.contains(search,true)}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visible.isEmpty()) item {EmptyState("No matching quotations","Create a quotation with goods, agreed prices and an expiry date.")}
                items(visible,key={it.id}) {quote ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Quotation ${quote.id.takeLast(8)} · Revision ${quote.version}",style=MaterialTheme.typography.titleMedium)
                    Text(quote.customer?.name ?: "No customer/contact recorded")
                    Text("${displayedStatus(quote)} · Valid through ${quote.expiresOn} WAT")
                    LabelValueRow("Agreed total",money(quote.totalMinor),emphasized=true)
                    NeutralButton("Open quotation",{selectedId=quote.id;message=null;error=null})
                }}}
            }
        }
        selected?.let {quote ->
            val orderLink = orderLinks.getOrDefault(emptyList()).singleOrNull { it.quotationId == quote.id }
            val history=remember(quote.id,revision) {runCatching {repo.quotationEvents(tenantId,quote.id)}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                SectionHeader("Quotation ${quote.id.takeLast(8)}","Revision ${quote.version} · ${displayedStatus(quote)}")
                LabelValueRow("Valid through",quote.expiresOn+" · whole day WAT")
                Text("Customer/contact snapshot: ${quote.customer?.name ?: "Not recorded"}")
                quote.customer?.phone?.let {Text(it)};quote.customer?.address?.let {Text(it)}
                quote.lines.forEach {line ->
                    Text(line.productName,style=MaterialTheme.typography.titleSmall)
                    LabelValueRow("${line.quantityLabel} ${line.sellingUnit} × ${money(line.agreedUnitPriceMinor)}",money(line.totalMinor))
                    if(line.sellingUnitId!=null) Text("1 ${line.sellingUnit} = ${line.baseUnitsPerUnit} ${line.baseUnit}",style=MaterialTheme.typography.bodySmall)
                }
                LabelValueRow("Agreed quotation total",money(quote.totalMinor),emphasized=true)
                if(quote.notes.isNotEmpty()) Text("Agreed notes: ${quote.notes}")
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    NeutralButton("Save quotation PDF",{export(quote,true)});NeutralButton("Save quotation text",{export(quote,false)})
                    if(canManage && quote.status==QuotationStatus.ISSUED) {
                        NeutralButton("Revise terms",{editing=true})
                        PosButton("Record acceptance",{try {repo.acceptQuotation(tenantId,quote.id,quote.version);refresh("Acceptance recorded. No sale, stock movement or payment was created.")} catch(e:Exception) {error=e.message}},enabled=!quotationExpired(quote.expiresOn))
                    }
                    if(canManage && quote.status==QuotationStatus.ACCEPTED && orderLinks.isSuccess && orderLink == null) {
                        PosButton("Create customer order", { convertingOrder = true }, enabled = !quotationExpired(quote.expiresOn))
                        NeutralButton("Review cash sale",{converting=true},enabled=!quotationExpired(quote.expiresOn))
                    }
                    if(canManage && quote.status in setOf(QuotationStatus.ISSUED,QuotationStatus.ACCEPTED) && orderLinks.isSuccess && orderLink == null) NeutralButton("Cancel quotation",{cancelling=true})
                }
                orderLinks.exceptionOrNull()?.let { InlineNotice("Order conversion history unavailable", it.message.orEmpty(), NoticeTone.Warning) }
                orderLink?.let { link ->
                    InlineNotice("Customer order created", "Order ${link.orderId}. Open Customer orders to record payments, stock holds and collection.", NoticeTone.Info)
                }
                if(quote.status==QuotationStatus.ACCEPTED && orderLink == null) Text("Create a customer order using these agreed terms, with an optional cash deposit. Full cash conversion requires enough stock. Both routes preserve the quoted prices and selling units.",style=MaterialTheme.typography.bodySmall)
                quote.saleId?.let {InlineNotice("Separate sale recorded","Sale ID: $it. This quotation cannot create another sale.",NoticeTone.Info)}
                HorizontalDivider();SectionHeader("Revision history","Earlier agreed terms stay saved")
                history.exceptionOrNull()?.let {InlineNotice("History unavailable",it.message ?: "Refresh to try again.",NoticeTone.Warning)}
                history.getOrDefault(emptyList()).forEach {event ->
                    Text("Revision ${event.snapshot.version} · ${event.kind} · ${event.createdAt}",style=MaterialTheme.typography.titleSmall)
                    Text(event.reason,style=MaterialTheme.typography.bodySmall)
                    Text("${event.snapshot.lines.size} goods/units · ${money(event.snapshot.totalMinor)} · valid through ${event.snapshot.expiresOn}")
                    NeutralButton("Save revision ${event.snapshot.version} PDF",{export(event.snapshot,true)})
                }
            }
        }
    }
    if(creating || editing) QuotationEditor(repo,tenantId,if(editing) selected else null,{creating=false;editing=false}) {value ->selectedId=value.id;creating=false;editing=false;refresh("Quotation revision ${value.version} saved. No goods or money changed.")}
    if(cancelling && selected!=null) CancelQuotationDialog(repo,selected,{cancelling=false}) {cancelling=false;refresh("Quotation cancelled. Earlier terms remain in history.")}
    if (convertingOrder && selected != null) QuotationOrderDialog(repo, selected, { convertingOrder = false }) { order ->
        convertingOrder = false
        refresh("Customer order ${order.id.takeLast(8)} created. Open Customer orders for payments and collection.")
    }
    if(converting && selected!=null) QuotationCashDialog(repo,selected,{converting=false}) {sale ->converting=false;refresh("Cash sale ${sale.id.takeLast(8)} saved. Stock and cash were recorded once.")}
}
private fun quoteStatus(quote:Quotation):String = if(quote.status in setOf(QuotationStatus.ISSUED,QuotationStatus.ACCEPTED) && quotationExpired(quote.expiresOn)) "Expired · ${quote.status.name.lowercase()}" else quote.status.name.lowercase().replaceFirstChar {it.uppercase()}

private data class QuoteDraftLine(val productId:String,val unitId:String?,val label:String,val precision:Int,val quantity:String,val price:String) {
    val key:String get()=unitId?.let {"$productId~$it"} ?: productId
}
private data class QuoteGoods(val product:Product,val unit:SellingUnit?) {
    val label:String get()="${product.name} · ${unit?.name ?: product.unit}"
    val key:String get()=unit?.id?.let {"${product.id}~$it"} ?: product.id
    val precision:Int get()=if(unit==null) product.quantityPrecision else 0
    val price:Long get()=unit?.priceMinor ?: product.priceMinor
}
@Composable
private fun QuotationEditor(repo:PosRepository,tenantId:String,old:Quotation?,dismiss:()->Unit,saved:(Quotation)->Unit) {
    val catalogue=remember(tenantId) {runCatching {
        val products=repo.products(tenantId);val units=repo.sellingUnits(tenantId)
        products.flatMap {product ->listOf(QuoteGoods(product,null))+units.filter {it.productId==product.id}.map {QuoteGoods(product,it)}}
    }}
    val customers=remember(tenantId) {runCatching {repo.customers(tenantId)}}
    var lines by remember {mutableStateOf(old?.lines?.map {QuoteDraftLine(it.productId,it.sellingUnitId,"${it.productName} · ${it.sellingUnit}",it.quantityPrecision,it.quantityLabel,BigDecimal.valueOf(it.agreedUnitPriceMinor,2).toPlainString())}.orEmpty())}
    var query by remember {mutableStateOf("")}
    var chooseGoods by remember {mutableStateOf(false)}
    var chooseCustomer by remember {mutableStateOf(false)}
    var customerId by remember {mutableStateOf(old?.customer?.customerId)}
    var name by remember {mutableStateOf(old?.customer?.name.orEmpty())}
    var phone by remember {mutableStateOf(old?.customer?.phone.orEmpty())}
    var address by remember {mutableStateOf(old?.customer?.address.orEmpty())}
    var expiry by remember {mutableStateOf(old?.expiresOn ?: LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(7).toString())}
    var notes by remember {mutableStateOf(old?.notes.orEmpty())}
    var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val operationId=remember(old?.id,old?.version) {UUID.randomUUID().toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(old==null) "New quotation" else "Revise quotation terms")},text={Column(Modifier.widthIn(max=660.dp).heightIn(max=620.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Prices, quantities, pack meanings and contact details are frozen when saved. A revision captures current goods details and preserves all earlier revisions. Goods are not reserved.",style=MaterialTheme.typography.bodySmall)
        catalogue.exceptionOrNull()?.let {InlineNotice("Goods unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
        PosTextField(query,{query=it},"Find goods to quote")
        Box {NeutralButton("Add goods / selling pack",{chooseGoods=true});DropdownMenu(chooseGoods,{chooseGoods=false}) {
            catalogue.getOrDefault(emptyList()).filter {it.label.contains(query,true)}.take(40).forEach {goods ->DropdownMenuItem(text={Text("${goods.label} · ${money(goods.price)}")},onClick={
                if(lines.any {it.key==goods.key}) error="These goods and unit are already listed. Edit their quantity below."
                else lines=lines+QuoteDraftLine(goods.product.id,goods.unit?.id,goods.label,goods.precision,"1",BigDecimal.valueOf(goods.price,2).toPlainString())
                chooseGoods=false
            })}
        }}
        lines.forEachIndexed {index,line ->
            Text(line.label,style=MaterialTheme.typography.titleSmall)
            PosTextField(line.quantity,{value ->lines=lines.toMutableList().also {it[index]=line.copy(quantity=value)}},"Agreed quantity (${line.precision} decimal places)")
            PosTextField(line.price,{value ->lines=lines.toMutableList().also {it[index]=line.copy(price=value)}},"Agreed price per selling unit (₦)")
            NeutralButton("Remove goods",{lines=lines.filterIndexed {i,_ ->i!=index}})
        }
        HorizontalDivider()
        Box {NeutralButton("Choose existing customer (optional)",{chooseCustomer=true});DropdownMenu(chooseCustomer,{chooseCustomer=false}) {
            DropdownMenuItem(text={Text("No linked customer")},onClick={customerId=null;chooseCustomer=false})
            customers.getOrDefault(emptyList()).take(100).forEach {customer ->DropdownMenuItem(text={Text(customer.name)},onClick={customerId=customer.id;name=customer.name;phone=customer.phone.orEmpty();address=customer.address.orEmpty();chooseCustomer=false})}
        }}
        customerId?.let {Text("Linked customer ID: $it",style=MaterialTheme.typography.bodySmall)}
        PosTextField(name,{name=it},"Customer/contact name (optional)")
        PosTextField(phone,{phone=it},"Contact phone (optional)")
        PosTextField(address,{address=it},"Contact address (optional)")
        PosTextField(expiry,{expiry=it},"Valid through (YYYY-MM-DD, WAT)")
        PosTextField(notes,{notes=it},"Agreed notes (optional)",singleLine=false)
        if(old!=null) PosTextField(reason,{reason=it},"Reason for this revision")
        error?.let {InlineNotice("Quotation not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Save quotation terms",{
        try {
            val inputs=lines.map {line ->
                val quantity=requireNotNull(parseQuantity(line.quantity,line.precision)) {"Enter a valid agreed quantity for ${line.label}."}.scaledUnits
                require(quantity in 1..Int.MAX_VALUE.toLong()) {"Agreed quantity is outside the supported range."}
                val price=requireNotNull(parseNaira(line.price)) {"Enter an exact non-negative naira price for ${line.label}."}
                QuotationLineInput(line.productId,line.unitId,quantity.toInt(),price)
            }
            val contact=if(name.isBlank() && phone.isBlank() && address.isBlank() && customerId==null) null else QuotationCustomer(customerId,name.trim(),if(phone.isBlank()) null else requireNotNull(normalizeNigerianPhone(phone)) {"Enter a valid Nigerian phone number."},address.trim().ifEmpty {null})
            val value=if(old==null) repo.issueQuotation(tenantId,inputs,expiry,contact,notes,operationId) else repo.reviseQuotation(tenantId,old.id,inputs,expiry,contact,notes,reason.trim(),old.version,operationId)
            saved(value)
        } catch(e:Exception) {error=e.message ?: "Review the quotation terms."}
    },enabled=catalogue.isSuccess)},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}

@Composable
private fun CancelQuotationDialog(repo:PosRepository,quote:Quotation,dismiss:()->Unit,saved:()->Unit) {
    var reason by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    val operationId=remember {UUID.randomUUID().toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text("Cancel quotation")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Earlier agreed terms stay in history. No stock or money will change.")
        PosTextField(reason,{reason=it},"Reason for cancellation")
        error?.let {InlineNotice("Quotation not cancelled",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Cancel quotation",{try {repo.cancelQuotation(quote.tenantId,quote.id,reason.trim(),quote.version,operationId);saved()} catch(e:Exception) {error=e.message}})},dismissButton={TextButton(dismiss) {Text("Keep quotation")}})
}

@Composable
private fun QuotationCashDialog(repo:PosRepository,quote:Quotation,dismiss:()->Unit,saved:(Sale)->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    val review=remember(quote.id,quote.version,revision) {runCatching {repo.quotationConversionReview(quote.tenantId,quote.id,quote.version)}}
    var cash by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    val operationId=remember(review.getOrNull()?.token) {UUID.randomUUID().toString()}
    val tender=parseNaira(cash)
    AlertDialog(onDismissRequest=dismiss,title={Text("Review quotation as a cash sale")},text={Column(Modifier.widthIn(max=640.dp).heightIn(max=600.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Quotation ${quote.id.takeLast(8)} · accepted revision ${quote.version}")
        Text("This records a real sale and reduces stock. Count the full cash payment before recording. Transfer, card, credit and changed goods/pack conversions are unavailable here.",style=MaterialTheme.typography.bodySmall)
        review.exceptionOrNull()?.let {InlineNotice("Conversion unavailable",it.message ?: "Review the quotation and goods.",NoticeTone.Warning)}
        review.getOrNull()?.lines?.forEach {line ->
            Text(line.product.name)
            LabelValueRow("${line.quantityLabel} ${line.sellingUnit} × ${money(line.netUnitPriceMinor)}",money(line.totalMinor))
            if(line.discountPerUnitMinor>0) Text("Current normal price ${money(line.unitPriceMinor)}; agreed reduction ${money(line.discountPerUnitMinor)} per ${line.sellingUnit}.",style=MaterialTheme.typography.bodySmall)
        }
        NeutralButton("Refresh conversion review",{revision++;error=null})
        LabelValueRow("Agreed total",money(quote.totalMinor),emphasized=true)
        PosTextField(cash,{cash=it},"Cash received (₦)")
        if(tender!=null && tender>=quote.totalMinor) LabelValueRow("Change to give",money(tender-quote.totalMinor))
        error?.let {InlineNotice("No sale saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Record cash sale",{
        try {
            require(tender!=null && tender>=quote.totalMinor) {"Receive the full quotation total in cash. Short payments are not recorded as debt."}
            saved(repo.convertQuotation(quote.tenantId,quote.id,quote.version,review.getOrThrow().token,quote.totalMinor,operationId))
        } catch(e:Exception) {error=e.message ?: "The cash sale could not be recorded."}
    },enabled=review.isSuccess && tender!=null && tender>=quote.totalMinor)},dismissButton={TextButton(dismiss) {Text("Back to quotation")}})
}
