package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** A contextual Stock tool following the established light back-office reference lock. */
@Composable
fun InventoryDispositionsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember(tenantId) {mutableIntStateOf(0)}
    var recording by remember(tenantId) {mutableStateOf(false)}
    var search by remember(tenantId) {mutableStateOf("")}
    var message by remember(tenantId) {mutableStateOf<String?>(null)}
    val result=remember(tenantId,revision) {runCatching {repo.inventoryDispositions(tenantId) to repo.products(tenantId)}}
    val entries=result.getOrNull()?.first.orEmpty()
    val products=result.getOrNull()?.second.orEmpty()
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            SectionHeader("Stock removed", "Damage, loss, samples and other goods taken out of stock.",Modifier.weight(1f))
            if(!recording) PosButton("Record stock removed",{recording=true;message=null},enabled=products.isNotEmpty())
            else NeutralButton("Back to removal history",{recording=false})
        }
        result.exceptionOrNull()?.let {InlineNotice("Could not load stock removals",it.message ?: "Refresh to try again.",NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        if(!recording) {
            if(products.isEmpty() && result.isSuccess) InlineNotice("No active goods", "Add or restore an item under Stock before recording goods removed.",NoticeTone.Info)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Bottom) {
                PosTextField(search,{search=it},"Search stock-removal history",Modifier.weight(1f),hint="Item, category or reason")
                NeutralButton("Refresh",{revision++;message=null})
            }
            val visible=entries.filter {search.isBlank() || listOf(it.productName,it.category.label,it.reason,it.notes,it.id).any {text ->text.contains(search,true)}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(visible.isEmpty()) item {EmptyState(if(entries.isEmpty()) "No stock removals recorded" else "No matching stock removals", "Completed removals stay in history. They do not record sales, expenses or cash payments.")}
                items(visible,key={it.id}) {entry ->
                    Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(entry.productName,style=MaterialTheme.typography.titleLarge)
                        Text("${entry.category.label} · ${receiptDate(entry.createdAt)}",style=MaterialTheme.typography.bodySmall)
                        LabelValueRow("Quantity removed","${entry.quantityLabel} ${entry.unit}",emphasized=true)
                        LabelValueRow("Stock before","${entry.beforeLabel} ${entry.unit}")
                        LabelValueRow("Stock after","${entry.afterLabel} ${entry.unit}")
                        Text("Reason: ${entry.reason}")
                        if(entry.notes.isNotEmpty()) Text(entry.notes,style=MaterialTheme.typography.bodySmall)
                        Text("Saved record ${entry.id}",style=MaterialTheme.typography.bodySmall)
                    }}
                }
            }
        } else InventoryRemovalForm(repo,tenantId,products,{recording=false;revision++;message="Stock removal saved on this computer. Its history has been kept.";onChanged()})
    }
}

@Composable
private fun ColumnScope.InventoryRemovalForm(repo:PosRepository,tenantId:String,products:List<Product>,onSaved:()->Unit) {
    var productId by remember {mutableStateOf<String?>(null)}
    var itemSearch by remember {mutableStateOf("")}
    var productMenu by remember {mutableStateOf(false)}
    var baselineRevision by remember {mutableIntStateOf(0)}
    var quantity by remember(productId) {mutableStateOf("")}
    var category by remember(productId) {mutableStateOf(InventoryDispositionCategory.DAMAGE)}
    var categoryMenu by remember {mutableStateOf(false)}
    var reason by remember(productId) {mutableStateOf("")}
    var notes by remember(productId) {mutableStateOf("")}
    var error by remember(productId) {mutableStateOf<String?>(null)}
    var reviewing by remember {mutableStateOf(false)}
    val operationId=remember(productId) {UUID.randomUUID().toString()}
    val item=products.singleOrNull {it.id==productId}
    val baseline=remember(productId,baselineRevision) {runCatching {productId?.let {id ->
        val current=repo.products(tenantId,true).singleOrNull {it.id==id}
        require(current!=null && item!=null && current.name==item.name && current.unit==item.unit && current.quantityPrecision==item.quantityPrecision) {"Item details changed. Return to removal history, refresh, and choose the item again."}
        repo.inventoryDispositionBaseline(tenantId,id)
    }}}
    val savedBaseline=baseline.getOrNull()
    val parsed=item?.let {parseQuantity(quantity,it.quantityPrecision)?.scaledUnits}
    val valid=item!=null && savedBaseline!=null && parsed!=null && parsed in 1..savedBaseline.stockScaled && reason.trim().length in 3..500 && notes.trim().length<=1000
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {InlineNotice("This changes stock only", "Use this for goods damaged, lost, given away or used outside a sale. No sale, cash payment, expense or profit is recorded.",NoticeTone.Info)}
        item {PosTextField(itemSearch,{itemSearch=it},"Find the item",hint="Name, SKU, category or barcode")}
        item {Box {
            NeutralButton(item?.let {"${it.name} · ${it.unit}"} ?: "Choose the item",{productMenu=true})
            DropdownMenu(expanded=productMenu,onDismissRequest={productMenu=false},modifier=Modifier.heightIn(max=320.dp)) {
                val visible=products.filter {it.matchesCatalogueSearch(itemSearch)}
                if(visible.isEmpty()) DropdownMenuItem(text={Text("No matching goods")},onClick={},enabled=false)
                visible.forEach {product ->DropdownMenuItem(text={Text("${product.name} · ${product.unit}")},onClick={productId=product.id;productMenu=false})}
            }
        }}
        if(item!=null) {
            item {baseline.exceptionOrNull()?.let {InlineNotice("Item not ready",it.message ?: "Reopen the item and try again.",NoticeTone.Danger)}}
            item {savedBaseline?.let {LabelValueRow("Stock recorded","${quantityLabel(it.stockScaled,item.quantityPrecision)} ${item.unit}",emphasized=true)}}
            item {PosTextField(quantity,{quantity=it;error=null},"Quantity removed in ${item.unit}",hint="More than 0, with up to ${item.quantityPrecision} decimal places")}
            item {Box {
                NeutralButton("Category: ${category.label}",{categoryMenu=true})
                DropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false}) {
                    InventoryDispositionCategory.entries.forEach {value ->DropdownMenuItem(text={Text(value.label)},onClick={category=value;categoryMenu=false;error=null})}
                }
            }}
            item {PosTextField(reason,{reason=it;error=null},"Why are these goods being removed?",hint="3–500 characters, for example damaged during unloading")}
            item {PosTextField(notes,{notes=it;error=null},"Notes (optional)",hint="Up to 1,000 characters")}
            item {if(parsed!=null && savedBaseline!=null && parsed in 1..savedBaseline.stockScaled) LabelValueRow("Stock remaining","${quantityLabel(savedBaseline.stockScaled-parsed,item.quantityPrecision)} ${item.unit}")
                else if(quantity.isNotBlank()) Text("Enter an exact positive quantity no greater than the stock recorded.",color=MaterialTheme.colorScheme.error)}
        }
        item {error?.let {InlineNotice("Stock not removed",it,NoticeTone.Danger)}}
        item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PosButton("Review stock removal",{reviewing=true},enabled=valid)
            if(item!=null) NeutralButton("Reload stock to review",{baselineRevision++;error=null})
        }}
        item {Text("If stock changes before saving, this operation stops. Reload and review the quantity again. Completed removals remain in history.",style=MaterialTheme.typography.bodySmall)}
    }
    if(reviewing && valid) AlertDialog(onDismissRequest={reviewing=false},title={Text("Remove these goods from stock?")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(requireNotNull(item).name,style=MaterialTheme.typography.titleLarge)
        Text(category.label)
        LabelValueRow("Stock before","${quantityLabel(requireNotNull(savedBaseline).stockScaled,item.quantityPrecision)} ${item.unit}")
        LabelValueRow("Quantity removed","${quantityLabel(requireNotNull(parsed),item.quantityPrecision)} ${item.unit}")
        LabelValueRow("Stock remaining","${quantityLabel(savedBaseline.stockScaled-parsed,item.quantityPrecision)} ${item.unit}")
        Text("Reason: ${reason.trim()}")
        Text("This saves one permanent stock-removal record. It does not collect or pay cash.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={PosButton("Save stock removal",{
        try {
            repo.recordInventoryDisposition(tenantId,requireNotNull(productId),requireNotNull(parsed),category,reason,notes,requireNotNull(savedBaseline),operationId)
            reviewing=false;onSaved()
        } catch(e:Exception) {reviewing=false;error=e.message ?: "No stock was removed. Review the entries and try again."}
    })},dismissButton={TextButton({reviewing=false}) {Text("Back to review")}})
}
