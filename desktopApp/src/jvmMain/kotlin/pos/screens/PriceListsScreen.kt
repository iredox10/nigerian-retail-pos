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
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID

/** Stock subsection using the existing Carbon-derived table/detail reference lock. */
@Composable
fun PriceListsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember(tenantId) {mutableIntStateOf(0)}
    var editing by remember(tenantId) {mutableStateOf(false)}
    var selected by remember(tenantId) {mutableStateOf<PriceList?>(null)}
    var history by remember(tenantId) {mutableStateOf<PriceList?>(null)}
    var search by remember(tenantId) {mutableStateOf("")}
    var message by remember(tenantId) {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {repo.priceLists(tenantId)}}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeader("Price lists","Retail, wholesale and customer quantity prices · Apply explicitly when selling",Modifier.weight(1f))
            PosButton("New price list",{selected=null;editing=true})
        }
        InlineNotice("Normal prices stay unchanged","List prices can match or reduce normal prices. To charge more, raise the normal selling price first. No price list is applied automatically.",NoticeTone.Info)
        loaded.exceptionOrNull()?.let {InlineNotice("Price lists unavailable",it.message ?: "Try again.",NoticeTone.Danger);NeutralButton("Try again",{revision++})}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        PosTextField(search,{search=it},"Find a price list",hint="Name or retail, wholesale, customer")
        val lists=loaded.getOrDefault(emptyList()).filter {it.details.name.contains(search,true) || it.details.kind.label.contains(search,true)}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(lists.isEmpty() && loaded.isSuccess) item {EmptyState("No matching price lists","Create a list, choose goods and add the quantity needed for each price.")}
            items(lists,key={it.id}) {list ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(list.details.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    StatusPill(if(list.details.archived) "Archived" else list.details.kind.label,NoticeTone.Neutral)
                    NeutralButton("Edit list",{selected=list;editing=true})
                    NeutralButton("History",{history=list})
                }
                Text("${list.details.tiers.size} quantity price(s) · Revision ${list.version} · ${receiptDate(list.updatedAt)}",style=MaterialTheme.typography.bodySmall)
                list.details.customerId?.let {Text("Customer account: $it",style=MaterialTheme.typography.bodySmall)}
            }}}
        }
    }
    if(editing) PriceListEditor(repo,tenantId,selected,{editing=false}) {saved ->message="${saved.details.name} saved. Existing sales retain their original prices.";editing=false;revision++;onChanged()}
    history?.let {list ->
        val values=remember(list.id,revision) {runCatching {repo.priceListHistory(tenantId,list.id)}}
        AlertDialog(onDismissRequest={history=null},title={Text("${list.details.name} history")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            values.exceptionOrNull()?.let {InlineNotice("History unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
            values.getOrDefault(emptyList()).asReversed().forEach {event ->
                Text("Revision ${event.version} · ${receiptDate(event.createdAt)}",style=MaterialTheme.typography.titleMedium)
                Text(event.reason);Text("${event.current.name} · ${event.current.kind.label} · ${event.current.tiers.size} price(s)${if(event.current.archived) " · Archived" else ""}",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }},confirmButton={TextButton({history=null}) {Text("Close")}})
    }
}

@Composable
private fun PriceListEditor(repo:PosRepository,tenantId:String,existing:PriceList?,onDismiss:()->Unit,onSaved:(PriceList)->Unit) {
    val loaded=remember(tenantId) {runCatching {Triple(repo.products(tenantId),repo.sellingUnits(tenantId),repo.customers(tenantId))}}
    val goods=loaded.getOrNull()?.first.orEmpty()
    val units=loaded.getOrNull()?.second.orEmpty()
    val customers=loaded.getOrNull()?.third.orEmpty()
    var name by remember {mutableStateOf(existing?.details?.name.orEmpty())}
    var kind by remember {mutableStateOf(existing?.details?.kind ?: PriceListKind.WHOLESALE)}
    var customerId by remember {mutableStateOf(existing?.details?.customerId)}
    var archived by remember {mutableStateOf(existing?.details?.archived ?: false)}
    val tiers=remember {mutableStateListOf<PriceListTier>().also {it.addAll(existing?.details?.tiers.orEmpty())}}
    var productId by remember {mutableStateOf<String?>(null)}
    var unitId by remember {mutableStateOf<String?>(null)}
    var itemMenu by remember {mutableStateOf(false)}
    var unitMenu by remember {mutableStateOf(false)}
    var customerMenu by remember {mutableStateOf(false)}
    var quantity by remember(productId,unitId) {mutableStateOf("")}
    var price by remember(productId,unitId) {mutableStateOf("")}
    var itemSearch by remember {mutableStateOf("")}
    var reason by remember {mutableStateOf(if(existing==null) "Initial price-list setup" else "")}
    var error by remember {mutableStateOf<String?>(null)}
    val operation=remember(name,kind,customerId,archived,tiers.toList(),reason) {UUID.randomUUID().toString()}
    val listId=remember {existing?.id ?: UUID.randomUUID().toString()}
    val product=goods.find {it.id==productId}
    val unit=units.find {it.id==unitId && it.productId==productId}
    val precision=if(unit==null) product?.quantityPrecision ?: 0 else 0
    fun tierLabel(tier:PriceListTier):String {
        val item=goods.find {it.id==tier.productId};val pack=units.find {it.id==tier.sellingUnitId}
        return "${item?.name ?: tier.productId} · from ${tier.minimumLabel} ${pack?.name ?: item?.unit ?: "saved unit"} · ${money(tier.unitPriceMinor)} each"
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(existing==null) "New price list" else "Edit price list")},text={Column(Modifier.heightIn(max=580.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        loaded.exceptionOrNull()?.let {InlineNotice("Goods unavailable",it.message ?: "Reopen this form.",NoticeTone.Danger)}
        Text("List prices reduce or match normal prices. Larger quantity breaks must have the same or a lower unit price. Completed sales keep their saved list revision.",style=MaterialTheme.typography.bodySmall)
        PosTextField(name,{name=it},"List name")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {PriceListKind.entries.forEach {option ->FilterChip(kind==option,{kind=option;if(option!=PriceListKind.CUSTOMER) customerId=null},label={Text(option.label)})}}
        if(kind==PriceListKind.CUSTOMER) Box {
            NeutralButton(customers.find {it.id==customerId}?.name ?: "Choose customer",{customerMenu=true})
            DropdownMenu(customerMenu,{customerMenu=false},modifier=Modifier.heightIn(max=280.dp)) {customers.forEach {customer ->DropdownMenuItem(text={Text(customer.name)},onClick={customerId=customer.id;customerMenu=false})}}
        }
        if(existing!=null) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(archived,{archived=it});Text("Archived — unavailable for new sales")}
        HorizontalDivider();Text("Add a quantity price",style=MaterialTheme.typography.titleMedium)
        PosTextField(itemSearch,{itemSearch=it},"Find goods",hint="Name or barcode")
        Box {NeutralButton(product?.name ?: "Choose item",{itemMenu=true});DropdownMenu(itemMenu,{itemMenu=false},modifier=Modifier.heightIn(max=250.dp)) {goods.filter {it.matchesCatalogueSearch(itemSearch)}.forEach {item ->DropdownMenuItem(text={Text(item.name)},onClick={productId=item.id;unitId=null;itemMenu=false})}}}
        product?.let {item ->
            Box {NeutralButton("Selling unit: ${unit?.name ?: item.unit}",{unitMenu=true});DropdownMenu(unitMenu,{unitMenu=false}) {
                DropdownMenuItem(text={Text(item.unit)},onClick={unitId=null;unitMenu=false})
                units.filter {it.productId==item.id && !it.archived}.forEach {pack ->DropdownMenuItem(text={Text(pack.name)},onClick={unitId=pack.id;unitMenu=false})}
            }}
            Text("Normal price: ${money(unit?.priceMinor ?: item.priceMinor)} per ${unit?.name ?: item.unit}",style=MaterialTheme.typography.bodySmall)
            PosTextField(quantity,{quantity=it},"Minimum quantity in ${unit?.name ?: item.unit}",hint="More than zero · up to $precision decimal places")
            PosTextField(price,{price=it},"List price per ${unit?.name ?: item.unit} (₦)")
            NeutralButton("Add quantity price",{
                try {
                    val qty=requireNotNull(parseQuantity(quantity,precision)) {"Enter a valid positive minimum quantity."}.scaledUnits
                    val amount=requireNotNull(parseNaira(price)) {"Enter a non-negative naira price."}
                    require(amount<=(unit?.priceMinor ?: item.priceMinor)) {"List price exceeds the normal price. Raise the normal price first if needed."}
                    val tier=PriceListTier(item.id,unit?.id,Math.toIntExact(qty),precision,amount)
                    require(tiers.none {it.productId==tier.productId && it.sellingUnitId==tier.sellingUnitId && it.minimumQuantity==tier.minimumQuantity}) {"Remove the existing matching quantity threshold before adding its replacement."}
                    PriceListDetails(name.trim().ifBlank {"Draft"},if(kind==PriceListKind.CUSTOMER && customerId==null) PriceListKind.WHOLESALE else kind,customerId,tier.let {tiers.toList()+it},archived)
                    tiers+=tier;quantity="";price="";error=null
                } catch(e:Exception) {error=e.message}
            })
        }
        Text("${tiers.size} quantity price(s)",style=MaterialTheme.typography.titleMedium)
        tiers.toList().forEach {tier ->Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(tierLabel(tier));TextButton({tiers.remove(tier)}) {Text("Remove this quantity price")}}}
        PosTextField(reason,{reason=it},"Reason for this revision")
        error?.let {InlineNotice("Price list not saved",it,NoticeTone.Danger)}
    }},confirmButton={TextButton({try {
        val details=PriceListDetails(name.trim(),kind,if(kind==PriceListKind.CUSTOMER) customerId else null,tiers.toList(),archived)
        onSaved(repo.savePriceList(tenantId,listId,details,existing?.version ?: 0,reason.trim(),operation))
    } catch(e:Exception) {error=e.message}},enabled=loaded.isSuccess) {Text("Save price list")}},dismissButton={TextButton(onDismiss) {Text("Cancel")}})
}

/** Explicit preview/apply; callers persist returned discounts with their existing basket transaction. */
@Composable
fun PriceListSelectionDialog(repo:PosRepository,tenantId:String,lines:List<CartLine>,customerId:String?,onApply:(List<PriceListQuote>)->Unit,onDismiss:()->Unit) {
    var listId by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,customerId) {runCatching {repo.checkoutPriceLists(tenantId,customerId)}}
    val quote=remember(tenantId,listId,lines,customerId) {runCatching {listId?.let {repo.quotePriceList(tenantId,it,lines,customerId)}.orEmpty()}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Review price-list prices")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Only shown qualifying lines will change. Existing negotiated discounts on those lines are replaced. Basket discounts require another review afterwards.")
        if(customerId==null) Text("Customer-specific lists appear after choosing that customer for this sale.",style=MaterialTheme.typography.bodySmall)
        loaded.exceptionOrNull()?.let {InlineNotice("Price lists unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
        loaded.getOrDefault(emptyList()).forEach {list ->FilterChip(listId==list.id,{listId=list.id;error=null},label={Text("${list.details.name} · ${list.details.kind.label} · v${list.version}")})}
        if(loaded.isSuccess && loaded.getOrThrow().isEmpty()) Text("No active matching lists. Add price lists under Stock.")
        quote.exceptionOrNull()?.let {InlineNotice("Prices unavailable",it.message ?: "Review the basket.",NoticeTone.Danger)}
        quote.getOrDefault(emptyList()).forEach {value ->
            val line=lines.single {it.key==value.lineKey}
            Text(line.product.name,style=MaterialTheme.typography.titleMedium)
            LabelValueRow("${line.quantityLabel} ${line.sellingUnit} · from ${value.tier.minimumLabel}","${money(value.catalogueUnitPriceMinor)} → ${money(value.unitPriceMinor)}")
        }
        if(listId!=null && quote.isSuccess && quote.getOrThrow().isEmpty()) Text("No basket lines qualify for this list's quantity breaks.")
        error?.let {InlineNotice("Prices not applied",it,NoticeTone.Danger)}
    }},confirmButton={TextButton({try {
        val fresh=repo.quotePriceList(tenantId,requireNotNull(listId),lines,customerId)
        require(fresh==quote.getOrThrow()) {"Price list changed while reviewing. Close and review it again."}
        onApply(fresh)
    } catch(e:Exception) {error=e.message}},enabled=listId!=null && quote.isSuccess && quote.getOrThrow().isNotEmpty()) {Text("Apply reviewed prices")}},dismissButton={TextButton(onDismiss) {Text("Cancel")}})
}
