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
import pos.android.documents.AndroidCsvCodec
import pos.android.documents.AndroidCsvCell
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.math.BigDecimal
import java.util.UUID

@Composable
fun AndroidStockCountsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var create by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<StockCountSession?>(null)}
    var posting by remember {mutableStateOf<StockCountSession?>(null)}
    var cancelling by remember {mutableStateOf<StockCountSession?>(null)}
    var viewing by remember {mutableStateOf<StockCountSession?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision) {repo.stockCountSessions(tenantId)}
    fun changed(message:String) {create=false;editing=null;posting=null;cancelling=null;revision++;notice=message;onChanged()}
    if(create)AndroidNewStockCount(repo,tenantId,{create=false}) {changed("Count started and saved. No stock changed.")}
    editing?.let {session ->AndroidCountDraft(repo,tenantId,session,{editing=null}) {changed("Count entries saved as a draft. Stock is unchanged.")}}
    posting?.let {session ->
        var confirmed by remember {mutableStateOf(false)}
        OperationsDialog("Review stock corrections",{posting=null},"Post these stock corrections",action={
            require(confirmed) {"Confirm the physical counts and reason."}
            withContext(Dispatchers.IO) {repo.postStockCountSession(tenantId,session.id,session.version)}
            changed("Stock corrections saved with the original count history.")
        }) {
            Text(session.name,style=MaterialTheme.typography.titleMedium)
            Text("Reason: ${session.reason}")
            session.lines.forEach {Text("${it.productName}: ${it.baselineLabel} → ${it.countedLabel} ${it.unit} · Change ${it.differenceLabel}")}
            Text("Any stock movement or item change since this session started blocks the entire posting. Cancel and recount if that happens; completed sales are never erased.")
            OperationsCheck(confirmed,{confirmed=it},"I checked these physical counts and authorize the stock corrections.")
        }
    }
    cancelling?.let {session ->
        var reason by rememberSaveable(session.id) {mutableStateOf("")}
        OperationsDialog("Cancel stock-count draft",{cancelling=null},"Save cancellation",action={
            require(reason.trim().isNotBlank()) {"Enter why the count is cancelled."}
            withContext(Dispatchers.IO) {repo.cancelStockCountSession(tenantId,session.id,reason.trim(),session.version)}
            changed("Count cancelled. Draft entries and reason remain in history; stock is unchanged.")
        }) {Text(session.name);PosTextField(reason,{reason=it},"Reason for cancelling")}
    }
    viewing?.let {session ->OperationsDialog("Saved stock count",{viewing=null},"Close",action={viewing=null}) {
        Text("${session.name} · ${session.status.name.lowercase()} · Revision ${session.version}")
        Text("Started ${session.createdAt} · Completed ${session.completedAt ?: "Not completed"}")
        Text("Reason: ${session.reason.ifBlank {"Not recorded"}}")
        session.lines.forEach {Text("${it.productName}: ${it.baselineLabel} → ${it.countedLabel ?: "Not counted"} ${it.unit} · ${it.differenceLabel ?: ""}")}
        AndroidReviewedCsvSave(repo,tenantId,StaffPermission.MANAGE_STOCK,"Preview count CSV","stock-count-${session.id}.csv") {
            val current=repo.stockCountSessions(tenantId).single {it.id==session.id}
            fun text(s:String)=AndroidCsvCell.Text(s)
            AndroidCsvCodec.encode(listOf("Count ID","Status","Revision","Product ID","Name","Unit","Baseline quantity","Counted quantity","Reason"),current.lines.map {line ->listOf(text(current.id),text(current.status.name),AndroidCsvCell.Number(current.version.toString()),text(line.productId),text(line.productName),text(line.unit),AndroidCsvCell.Number(BigDecimal.valueOf(line.baselineScaled,line.precision).toPlainString()),line.countedScaled?.let {AndroidCsvCell.Number(BigDecimal.valueOf(it.toLong(),line.precision).toPlainString())} ?: text(""),text(current.reason))})
        }
    }}
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Stock counts","Save a multi-item physical count before posting corrections")}
        item {PosButton("Start a stock count",{create=true},modifier=Modifier.fillMaxWidth())}
        notice?.let {item {InlineNotice(it,tone=NoticeTone.Success)}}
        item {OperationsLoadState(loaded,"stock count sessions",{revision++})}
        if(loaded?.isSuccess==true && loaded.getOrThrow().isEmpty())item {EmptyState("No saved count sessions","Choose the goods to count. Selling after the count starts will require a fresh recount before posting.")}
        items(loaded?.getOrNull().orEmpty(),key={it.id}) {session ->OperationsCard {
            Text(session.name,style=MaterialTheme.typography.titleMedium)
            Text("${session.status.name.lowercase()} · ${session.lines.count {it.countedScaled!=null}} of ${session.lines.size} goods counted · Revision ${session.version}")
            if(session.status==StockCountStatus.DRAFT) {
                OutlinedButton(onClick={editing=session}) {Text("Enter or continue counts")}
                if(session.reason.isNotBlank() && session.lines.all {it.countedScaled!=null})PosButton("Review stock corrections",{posting=session})
                TextButton(onClick={cancelling=session}) {Text("Cancel this count")}
            }
            TextButton(onClick={viewing=session}) {Text("View saved details and export")}
        }}
    }
}
@Composable private fun AndroidNewStockCount(repo:PosRepository,tenantId:String,dismiss:()->Unit,saved:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    val products=operationsLoad(repo,tenantId,revision) {repo.products(tenantId)}
    var name by rememberSaveable {mutableStateOf("")};var search by rememberSaveable {mutableStateOf("")}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    OperationsDialog("Start stock count",dismiss,"Save count session",action={
        val goods=requireNotNull(products?.getOrThrow()) {"Wait for stock to load."}
        val ids=goods.filter {it.id in selected}.map {it.id}
        require(ids.size==selected.size) {"Some selected goods became unavailable. Reopen the count and review your selection."}
        require(ids.size in 1..500) {"Choose from 1 to 500 different goods."}
        withContext(Dispatchers.IO) {repo.createStockCountSession(tenantId,name.trim(),ids,operation)};saved()
    }) {
        OperationsLoadState(products,"stock",{revision++})
        PosTextField(name,{name=it},"Count name")
        PosTextField(search,{search=it},"Find goods to count")
        Text("${selected.size} items selected. Choosing goods saves their current stock and movement baseline.")
        products?.getOrNull().orEmpty().filter {it.matchesCatalogueSearch(search)}.take(50).forEach {item ->OperationsCheck(item.id in selected,{checked ->selected=if(checked)selected+item.id else selected-item.id},"${item.name} · ${item.stockLabel} ${item.unit}")}
        Text("Showing up to 50 matching goods. Refine the search to select other goods; previous selections stay selected.")
    }
}
@Composable private fun AndroidCountDraft(repo:PosRepository,tenantId:String,session:StockCountSession,dismiss:()->Unit,saved:()->Unit) {
    var values by remember(session.id,session.version) {mutableStateOf(session.lines.associate {it.productId to (it.countedScaled?.let {value ->BigDecimal.valueOf(value.toLong(),it.precision).toPlainString()}.orEmpty())})}
    var reason by rememberSaveable(session.id,session.version) {mutableStateOf(session.reason)}
    OperationsDialog("Count ${session.name}",dismiss,"Save draft counts",action={
        val counts=session.lines.associate {line ->
            val text=values[line.productId].orEmpty().trim()
            line.productId to if(text.isBlank())null else requireNotNull(parseQuantity(text,line.precision)?.scaledUnits?.takeIf {it<=Int.MAX_VALUE}?.toInt()) {"Enter a non-negative count of ${line.productName} with ${line.precision} decimal places."}
        }
        withContext(Dispatchers.IO) {repo.saveStockCountDraft(tenantId,session.id,counts,reason.trim(),session.version)};saved()
    }) {
        Text("Blank means not counted; zero is an explicit physical count. Saving this draft does not change stock.")
        session.lines.forEach {line ->
            Text("${line.productName} · Baseline ${line.baselineLabel} ${line.unit}")
            PosTextField(values[line.productId].orEmpty(),{values=values+(line.productId to it)},"Counted ${line.unit}")
        }
        PosTextField(reason,{reason=it},"Reason for corrections (required before posting)")
    }
}
