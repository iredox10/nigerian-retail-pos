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

/** Contextual Stock tool. The existing back-office reference lock owns spacing and controls. */
@Composable
fun StockCountSessionsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember(tenantId) {mutableIntStateOf(0)}
    var selectedId by remember(tenantId) {mutableStateOf<String?>(null)}
    var creating by remember(tenantId) {mutableStateOf(false)}
    var error by remember(tenantId) {mutableStateOf<String?>(null)}
    var message by remember(tenantId) {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {repo.stockCountSessions(tenantId) to repo.products(tenantId)}}
    val sessions=loaded.getOrNull()?.first.orEmpty()
    val products=loaded.getOrNull()?.second.orEmpty()
    val selected=sessions.singleOrNull {it.id==selectedId}
    fun refreshed(text:String) {revision++;message=text;error=null;onChanged()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            SectionHeader("Count several items", "Save a physical count, review differences, then post together.",Modifier.weight(1f))
            if(selectedId==null) PosButton("Start a count",{creating=true;error=null},enabled=products.isNotEmpty())
            else NeutralButton("Back to count history",{selectedId=null;error=null})
        }
        InlineNotice("Counts do not reserve stock", "If an item moves or changes after the count starts, posting stops for the entire session. Cancel it and start a fresh recount. No partial correction is saved.",NoticeTone.Info)
        loaded.exceptionOrNull()?.let {InlineNotice("Could not load stock counts",it.message ?: "Refresh to try again.",NoticeTone.Danger)}
        error?.let {InlineNotice("Count not saved",it,NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        if(products.isEmpty() && loaded.isSuccess) InlineNotice("No active items to count", "Add or restore goods under Stock before starting a count.",NoticeTone.Info)
        NeutralButton("Refresh",{revision++;error=null;message=null})
        if(selected!=null) CountSessionDetails(selected,repo,tenantId,{refreshed(it)},{error=it})
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(sessions.isEmpty()) item {EmptyState("No stock-count sessions", "Start a count and choose the goods you will count. Saved drafts can be resumed after reopening the app.")}
            items(sessions,key={it.id}) {session ->
                Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(session.name,style=MaterialTheme.typography.titleLarge)
                    Text("${countStatus(session.status)} · ${session.lines.size} items · Started ${receiptDate(session.createdAt)}",style=MaterialTheme.typography.bodySmall)
                    session.completedAt?.let {Text("Completed ${receiptDate(it)}",style=MaterialTheme.typography.bodySmall)}
                    NeutralButton(if(session.status==StockCountStatus.DRAFT) "Resume count" else "View saved count",{selectedId=session.id;message=null;error=null})
                }}
            }
        }
    }
    if(creating) {
        var name by remember {mutableStateOf("")}
        var search by remember {mutableStateOf("")}
        val chosen=remember {mutableStateMapOf<String,Boolean>()}
        var createError by remember {mutableStateOf<String?>(null)}
        val operationId=remember {UUID.randomUUID().toString()}
        AlertDialog(onDismissRequest={creating=false},title={Text("Start a stock count")},text={
            Column(Modifier.widthIn(max=540.dp).heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                PosTextField(name,{name=it},"Name this count",hint="For example, Monday shelf count")
                PosTextField(search,{search=it},"Find goods to count")
                Text("${chosen.count {it.value}} chosen · Maximum 500",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f,false)) {
                    val visible=products.filter {it.matchesCatalogueSearch(search)}
                    if(visible.isEmpty()) item {Text("No matching items")}
                    items(visible,key={it.id}) {item -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(chosen[item.id]==true,{checked ->chosen[item.id]=checked})
                        Column {Text(item.name);Text("${item.stockLabel} ${item.unit} recorded",style=MaterialTheme.typography.bodySmall)}
                    }}
                }
                createError?.let {InlineNotice("Count not started",it,NoticeTone.Danger)}
            }
        },confirmButton={PosButton("Save & start count",{
            try {
                val ids=products.filter {chosen[it.id]==true}.map {it.id}
                val saved=repo.createStockCountSession(tenantId,name,ids,operationId)
                creating=false;selectedId=saved.id;refreshed("Count started on this computer")
            } catch(e:Exception) {createError=e.message ?: "The count could not be started."}
        },enabled=name.isNotBlank() && chosen.count {it.value} in 1..500)},dismissButton={TextButton({creating=false}) {Text("Cancel")}})
    }
}

@Composable
private fun ColumnScope.CountSessionDetails(session:StockCountSession,repo:PosRepository,tenantId:String,onSaved:(String)->Unit,onError:(String)->Unit) {
    var reason by remember(session.id,session.version) {mutableStateOf(session.reason)}
    val inputs=remember(session.id,session.version) {mutableStateMapOf<String,String>().apply {session.lines.forEach {put(it.productId,it.countedLabel.orEmpty())}}}
    var confirming by remember(session.id) {mutableStateOf(false)}
    var cancelling by remember(session.id) {mutableStateOf(false)}
    val draft=session.status==StockCountStatus.DRAFT
    val parsed=session.lines.associate {line ->line.productId to inputs[line.productId].orEmpty().let {text ->if(text.isBlank()) null else quantityCount(text,line.precision)}}
    val invalid=session.lines.any {inputs[it.productId].orEmpty().isNotBlank() && parsed[it.productId]==null}
    val changed=reason.trim()!=session.reason || session.lines.any {inputs[it.productId].orEmpty()!=it.countedLabel.orEmpty()}
    Text(session.name,style=MaterialTheme.typography.titleLarge)
    Text("${countStatus(session.status)} · Snapshot ${receiptDate(session.createdAt)}",style=MaterialTheme.typography.bodySmall)
    if(draft) PosTextField(reason,{reason=it},"Reason for this stock count",hint="Required before posting")
    else Text("Reason: ${session.reason}")
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        items(session.lines,key={it.productId}) {line ->
            Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(line.productName,style=MaterialTheme.typography.titleMedium)
                Text("Recorded at start: ${line.baselineLabel} ${line.unit}",style=MaterialTheme.typography.bodySmall)
                if(draft) {
                    PosTextField(inputs[line.productId].orEmpty(),{inputs[line.productId]=it},"Physical count in ${line.unit}",hint="Up to ${line.precision} decimal places; 0 means none")
                    val count=parsed[line.productId]
                    if(inputs[line.productId].orEmpty().isNotBlank() && count==null) Text("Enter zero or more with up to ${line.precision} decimal places.",color=MaterialTheme.colorScheme.error)
                    count?.let {Text("Difference: ${line.copy(countedScaled=it).differenceLabel} ${line.unit}")}
                } else {
                    LabelValueRow("Physical count",line.countedLabel?.let {"$it ${line.unit}"} ?: "Not entered")
                    line.differenceLabel?.let {LabelValueRow("Difference from start","$it ${line.unit}")}
                }
            }}
        }
    }
    if(draft) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        NeutralButton("Save unfinished count",{
            try {repo.saveStockCountDraft(tenantId,session.id,parsed,reason,session.version);onSaved("Unfinished count saved on this computer")}
            catch(e:Exception) {onError(e.message ?: "The draft could not be saved.")}
        },enabled=!invalid && changed)
        PosButton("Review & post counts",{confirming=true},enabled=!changed && session.reason.isNotBlank() && session.lines.all {it.countedScaled!=null})
        TextButton({cancelling=true}) {Text("Cancel this count")}
    }
    if(draft && changed) Text("Save your entries before reviewing and posting. Blank counts remain unfinished; enter 0 when no goods are present.",style=MaterialTheme.typography.bodySmall)
    if(confirming) AlertDialog(onDismissRequest={confirming=false},title={Text("Post these stock counts?")},text={Text("${session.lines.size} physical counts will replace their unchanged starting quantities together. Review each difference above first. This changes stock only; it does not record a purchase, sale or payment. Completed corrections remain in history.")},confirmButton={PosButton("Post ${session.lines.size} counts",{
        try {repo.postStockCountSession(tenantId,session.id,session.version);confirming=false;onSaved("Stock counts posted. Corrections remain in stock history.")}
        catch(e:Exception) {confirming=false;onError(e.message ?: "No stock counts were posted.")}
    })},dismissButton={TextButton({confirming=false}) {Text("Back to review")}})
    if(cancelling) {
        var cancellationReason by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={cancelling=false},title={Text("Cancel this unfinished count")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Stock stays unchanged. This count remains in history as cancelled.")
            PosTextField(cancellationReason,{cancellationReason=it},"Why is this count being cancelled?")
        }},confirmButton={PosButton("Save cancellation",{
            try {repo.cancelStockCountSession(tenantId,session.id,cancellationReason,session.version);cancelling=false;onSaved("Count cancelled. Stock was not changed.")}
            catch(e:Exception) {cancelling=false;onError(e.message ?: "Count was not cancelled.")}
        },enabled=cancellationReason.isNotBlank())},dismissButton={TextButton({cancelling=false}) {Text("Keep counting")}})
    }
}

private fun countStatus(status:StockCountStatus)=when(status) {StockCountStatus.DRAFT->"Unfinished";StockCountStatus.POSTED->"Posted";StockCountStatus.CANCELLED->"Cancelled"}
