package pos.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pos.android.documents.*
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

/** A document is read only; no financial/catalogue mutation occurs before explicit reviewed apply. */
@Composable
fun AndroidCsvImportScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) = key(tenantId) {
    val scope=rememberCoroutineScope();val resolver=LocalContext.current.contentResolver
    var kind by remember {mutableStateOf(AndroidCsvImportKind.NEW_ITEMS)}
    var review by remember {mutableStateOf<AndroidCsvImportReview?>(null)}
    var operation by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}
    var reason by rememberSaveable {mutableStateOf("")}
    var page by remember {mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var pickerActive by remember {mutableStateOf(false)}
    var saved by remember {mutableStateOf(false)}
    var pendingTemplate by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    fun work(action:suspend ()->Unit) {
        if(busy)return
        busy=true;error=null;notice=null
        scope.launch {
            try {action()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=e.message ?: "Could not complete the CSV action. Saved shop records are kept."}
            finally {busy=false}
        }
    }
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        pickerActive=false
        if(uri==null)notice="File selection cancelled. No records changed."
        else work {
            val selected=kind
            review=withContext(Dispatchers.IO) {
                repo.authorizeAndroidCsvImport(tenantId)
                val text=requireNotNull(resolver.openInputStream(uri)) {"This document cannot be read."}.use(AndroidCsvImport::readUtf8)
                repo.reviewAndroidCsvImport(tenantId,selected,text)
            }
            page=0;saved=false;operation=UUID.randomUUID().toString()
        }
    }
    val template=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri ->
        val text=pendingTemplate;pendingTemplate=null;pickerActive=false
        if(uri==null)notice="Template save cancelled. No records changed."
        else work {
            withContext(Dispatchers.IO) {
                repo.authorizeAndroidCsvImport(tenantId)
                requireNotNull(resolver.openOutputStream(uri,"wt")) {"This destination cannot be written."}.use {it.write(requireNotNull(text) {"Generate the template again."}.toByteArray(Charsets.UTF_8));it.flush()}
            }
            notice="CSV template saved. Edit it outside the app, then choose it here for review."
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {SectionHeader("Import CSV items","Choose a file, review every row, then apply to this shop")}
        item {Text("UTF-8 CSV only, up to 5 MB and 10,000 items. Imports stay on this device; existing cloud sharing handles saved records separately.")}
        item {Column {
            AndroidCsvImportKind.entries.forEach {option ->FilterChip(kind==option,{kind=option;review=null;saved=false;error=null;notice=null;page=0},enabled=!busy && !pickerActive,label={Text(option.label)})}
        }}
        item {Text(if(kind==AndroidCsvImportKind.NEW_ITEMS)"New items are added with their reviewed opening stock. Existing goods, including archived goods, are never overwritten. For measured goods, the template declares quantity precision explicitly." else "Export this shop’s update template first. Keep product IDs, review versions, units and quantity precision unchanged. Updates keep stock quantities and saved receipts unchanged.")}
        error?.let {item {InlineNotice("Action needs checking",it,NoticeTone.Danger)}}
        notice?.let {item {InlineNotice(it,tone=if(saved)NoticeTone.Success else NoticeTone.Info)}}
        item {OutlinedButton(enabled=!busy && !pickerActive,onClick={work {
            pendingTemplate=withContext(Dispatchers.IO) {repo.authorizeAndroidCsvImport(tenantId);if(kind==AndroidCsvImportKind.NEW_ITEMS)AndroidCsvImport.template else repo.androidCsvUpdateTemplate(tenantId)}
            pickerActive=true
            try {template.launch(if(kind==AndroidCsvImportKind.NEW_ITEMS)"new-items-template.csv" else "catalogue-update-template.csv")} catch(e:Exception) {pickerActive=false;throw e}
        }},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Save ${if(kind==AndroidCsvImportKind.NEW_ITEMS)"new item" else "current catalogue"} template")}}
        item {PosButton(if(busy || pickerActive)"Working…" else "Choose CSV file",{
            review=null;saved=false;error=null;notice=null
            runCatching {repo.authorizeAndroidCsvImport(tenantId);pickerActive=true;choose.launch(arrayOf("text/*","application/vnd.ms-excel","application/octet-stream"))}.onFailure {pickerActive=false;error=it.message}
        },modifier=Modifier.fillMaxWidth(),enabled=!busy && !pickerActive)}
        review?.let {result ->
            item {Text("${result.rowCount} items reviewed · ${result.issues.size} row errors",style=MaterialTheme.typography.titleMedium)}
            if(kind==AndroidCsvImportKind.UPDATE_ITEMS)item {Text("${result.changedCount} items have changes. Any stale review or row error blocks the entire batch.")}
            items(result.issues.take(50)) {issue ->InlineNotice("Record ${issue.record}",issue.message,NoticeTone.Danger)}
            if(result.issues.size>50)item {Text("Showing the first 50 errors. Fix the file and review it again; all errors must be resolved.")}
            val start=page*20;val end=minOf(start+20,result.rowCount)
            item {Text("Preview ${if(result.rowCount==0)0 else start+1}–$end of ${result.rowCount}. All rows are validated; use the pages to inspect the full batch.")}
            if(result.kind==AndroidCsvImportKind.NEW_ITEMS)items(result.newItems.drop(start).take(20)) {row ->OperationsCard {
                Text(row.name.take(250),style=MaterialTheme.typography.titleMedium)
                Text("${quantityLabel(row.stock.toLong(),row.quantityPrecision)} ${row.unit.take(100)} opening stock")
                LabelValueRow("Selling price",money(row.priceMinor))
                Text("Quantity decimal places: ${row.quantityPrecision}")
            }} else items(result.updates.drop(start).take(20)) {row ->OperationsCard {
                Text("Record ${row.record} · ${row.values.name.take(250)}",style=MaterialTheme.typography.titleMedium)
                LabelValueRow("New selling price",money(row.values.priceMinor))
                Text("Unit ${row.values.unit} · Decimal places ${row.values.precision}")
                Text("Barcode ${row.values.barcode.orEmpty()} · SKU ${row.values.metadata.sku.orEmpty()}")
                Text("Category ${row.values.metadata.category.orEmpty()} · Brand ${row.values.metadata.brand.orEmpty()}")
                Text("Aliases ${row.values.metadata.aliases.joinToString(", ").take(500)}")
                Text("Favourite ${row.values.metadata.favourite} · Reorder level ${row.values.reorderLevel ?: "Disabled"}")
            }}
            item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(enabled=!busy && !pickerActive && page>0,onClick={page--}) {Text("Previous 20")}
                TextButton(enabled=!busy && !pickerActive && end<result.rowCount,onClick={page++}) {Text("Next 20")}
            }}
            if(kind==AndroidCsvImportKind.UPDATE_ITEMS)item {PosTextField(reason,{reason=it},"Why are these catalogue details changing?")}
            item {PosButton(if(saved)"Items saved" else "Apply ${result.rowCount} reviewed items",{work {
                val count=withContext(Dispatchers.IO) {repo.applyAndroidCsvImport(tenantId,result,reason,operation)}
                saved=true;notice="$count items saved on this device. Existing sales and their prices are unchanged.";onChanged()
            }},modifier=Modifier.fillMaxWidth(),enabled=!busy && !pickerActive && !saved && result.canApply && (kind==AndroidCsvImportKind.NEW_ITEMS || reason.trim().length>=3))}
        }
    }
}
