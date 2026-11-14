package pos.screens

import androidx.compose.foundation.horizontalScroll
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
import java.math.BigDecimal
import java.util.UUID

/** Contextual owner/manager tool; the five everyday destinations stay unchanged. */
@Composable
fun MinimumPricesScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember(tenantId) {mutableIntStateOf(0)}
    var search by remember(tenantId) {mutableStateOf("")}
    var editing by remember(tenantId) {mutableStateOf<CartLine?>(null)}
    var historyFor by remember(tenantId) {mutableStateOf<MinimumPricePolicy?>(null)}
    var message by remember(tenantId) {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {Triple(repo.minimumPricePolicies(tenantId),repo.products(tenantId,true),repo.sellingUnits(tenantId,includeArchived=true))}}
    val policies=loaded.getOrNull()?.first.orEmpty()
    val goods=loaded.getOrNull()?.second.orEmpty()
    val units=loaded.getOrNull()?.third.orEmpty()
    val choices=goods.filter {!it.archived}.flatMap {product ->
        listOf(CartLine(product,quantityScaleFactor(product.quantityPrecision).toInt()))+units.filter {it.productId==product.id && !it.archived}.map {unit ->CartLine(product,1,unit.id,unit.name,unit.priceMinor,unit.baseUnitsPerUnit)}
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Minimum selling prices","Owner or manager settings · Checked after all item and basket discounts")
        InlineNotice("A base-unit minimum also applies to packs","A pack must meet both its own minimum, if set, and the minimum for the base goods inside it. Measured totals round upward to a whole kobo. Below-minimum sales are blocked until the sale or this policy is explicitly changed.",NoticeTone.Info)
        Text("These limits do not estimate costs or profit. There is no separate-identity approval override yet. Historical sales keep their recorded minimum-price checks.",style=MaterialTheme.typography.bodySmall)
        loaded.exceptionOrNull()?.let {InlineNotice("Minimum prices unavailable",it.message ?: "Try again.",NoticeTone.Danger);NeutralButton("Reload",{revision++})}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        PosTextField(search,{search=it},"Find goods or selling packs",hint="Item name or unit")
        val visible=choices.filter {it.product.name.contains(search,true) || it.sellingUnit.contains(search,true)}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(visible.isEmpty() && loaded.isSuccess) item {EmptyState("No matching active goods","Add or restore goods under Stock, or clear the search.")}
            items(visible,key={it.key}) {line ->
                val policy=policies.singleOrNull {it.terms.key==line.key}
                Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("${line.product.name} · ${line.sellingUnit}",style=MaterialTheme.typography.titleMedium)
                    if(line.sellingUnitId!=null) Text("1 ${line.sellingUnit} = ${line.baseUnitsPerUnit} ${line.product.unit}",style=MaterialTheme.typography.bodySmall)
                    LabelValueRow("Normal price per ${line.sellingUnit}",money(line.unitPriceMinor))
                    LabelValueRow("Minimum per ${line.sellingUnit}",policy?.terms?.minimumUnitPriceMinor?.let(::money) ?: "Not set",emphasized=true)
                    if(line.sellingUnitId!=null) policies.singleOrNull {it.terms.key==line.product.id}?.terms?.minimumUnitPriceMinor?.let {base ->Text("Base minimum also applies: ${money(base)} / ${line.product.unit}",style=MaterialTheme.typography.bodySmall)}
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        NeutralButton("Set minimum",{editing=line;message=null})
                        if(policy!=null) NeutralButton("History",{historyFor=policy})
                    }
                }}
            }
            val inactive=policies.filter {policy ->choices.none {it.key==policy.terms.key}}
            if(inactive.isNotEmpty()) item {Text("Archived goods or packs · History retained",style=MaterialTheme.typography.titleMedium)}
            items(inactive,key={"archived:${it.terms.key}"}) {policy ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${policy.terms.productName} · ${policy.terms.unitName}")
                Text(policy.terms.minimumUnitPriceMinor?.let {"Saved minimum ${money(it)} / ${policy.terms.unitName}"} ?: "Minimum removed")
                Text("Restore the goods/pack under Stock before changing this policy.",style=MaterialTheme.typography.bodySmall)
                NeutralButton("History",{historyFor=policy})
            }}}
        }
    }
    editing?.let {line ->
        val policy=policies.singleOrNull {it.terms.key==line.key}
        MinimumPriceEditor(repo,tenantId,line,policy,{editing=null}) {
            editing=null;revision++;message="Minimum-price policy saved. Completed sales remain unchanged.";onChanged()
        }
    }
    historyFor?.let {policy ->
        val history=remember(tenantId,policy.terms.key,revision) {runCatching {repo.minimumPriceHistory(tenantId,policy.terms.productId,policy.terms.sellingUnitId)}}
        AlertDialog(onDismissRequest={historyFor=null},title={Text("Minimum-price history")},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${policy.terms.productName} · ${policy.terms.unitName}")
            history.exceptionOrNull()?.let {InlineNotice("History unavailable",it.message ?: "Close and reload.",NoticeTone.Danger)}
            history.getOrDefault(emptyList()).asReversed().forEach {event ->
                Text("Revision ${event.version} · ${receiptDate(event.createdAt)}",style=MaterialTheme.typography.titleSmall)
                Text(event.current.minimumUnitPriceMinor?.let {"Minimum ${money(it)} / ${event.current.unitName}"} ?: "Minimum removed")
                event.previous?.let {old ->Text(old.minimumUnitPriceMinor?.let {"Previously ${money(it)} / ${old.unitName}"} ?: "Previously not set",style=MaterialTheme.typography.bodySmall)}
                Text(event.reason);HorizontalDivider()
            }
        }},confirmButton={TextButton({historyFor=null}) {Text("Close")}})
    }
}

@Composable
private fun MinimumPriceEditor(repo:PosRepository,tenantId:String,line:CartLine,policy:MinimumPricePolicy?,dismiss:()->Unit,saved:()->Unit) {
    var amount by remember(line.key,policy?.version) {mutableStateOf(policy?.terms?.minimumUnitPriceMinor?.let {BigDecimal.valueOf(it,2).toPlainString()}.orEmpty())}
    var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val operation=remember(amount,reason) {UUID.randomUUID().toString()}
    val parsed=if(amount.isBlank()) null else parseNaira(amount)
    AlertDialog(onDismissRequest=dismiss,title={Text("Minimum selling price")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("${line.product.name} · ${line.sellingUnit}",style=MaterialTheme.typography.titleMedium)
        Text("Normal price ${money(line.unitPriceMinor)} / ${line.sellingUnit}")
        if(line.sellingUnitId==null) Text("This minimum also applies to the base quantity inside each selling pack.",style=MaterialTheme.typography.bodySmall)
        else Text("The minimum for ${line.product.unit} also applies. This pack rule cannot lower the base-goods minimum.",style=MaterialTheme.typography.bodySmall)
        PosTextField(amount,{amount=it;error=null},"Minimum per ${line.sellingUnit} (₦)",hint="Leave blank to remove this minimum")
        if(parsed!=null && parsed>line.unitPriceMinor) InlineNotice("Higher than the normal price","The normal price will also fail this limit. Change the selling price under Stock before selling, or use a lower minimum.",NoticeTone.Warning)
        PosTextField(reason,{reason=it;error=null},"Why is the minimum changing?",hint="Required reason, 3–250 characters")
        Text("Changes apply to new sales. A price-list or quotation reduction cannot bypass this limit.",style=MaterialTheme.typography.bodySmall)
        error?.let {InlineNotice("Minimum not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton(if(amount.isBlank()) "Remove minimum" else "Save minimum",{
        try {
            require(amount.isBlank() || parsed!=null) {"Enter a valid minimum in naira, with at most two decimal places."}
            repo.saveMinimumPrice(tenantId,line.product.id,line.sellingUnitId,parsed,policy?.version ?: 0,reason.trim(),operation)
            saved()
        } catch(e:Exception) {error=e.message ?: "Your entries remain here. Try again."}
    },enabled=reason.trim().length in 3..250 && (amount.isBlank() || parsed!=null) && (policy!=null || parsed!=null))},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}
