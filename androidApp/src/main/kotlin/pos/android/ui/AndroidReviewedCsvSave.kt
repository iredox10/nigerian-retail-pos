package pos.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pos.data.PosRepository
import pos.domain.StaffPermission
import pos.ui.components.*

/** Explicit local export with a reviewed snapshot and a fresh permission check at document write. */
@Composable
internal fun AndroidReviewedCsvSave(repo:PosRepository,tenantId:String,permission:StaffPermission,label:String,filename:String,generate:()->String) {
    val resolver=LocalContext.current.contentResolver;val scope=rememberCoroutineScope()
    var preview by remember(tenantId,filename) {mutableStateOf<String?>(null)}
    var pending by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri ->
        val snapshot=pending;pending=null
        if(uri==null) {busy=false;notice="Export cancelled. No records changed."}
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.featureRead(tenantId,permission) {Unit}
                    requireNotNull(resolver.openOutputStream(uri,"wt")) {"Destination cannot be written."}.use {output ->output.write(requireNotNull(snapshot) {"Generate the preview again."}.toByteArray(Charsets.UTF_8));output.flush()}
                };notice="Reviewed CSV saved to the selected document."
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=e.message ?: "CSV was not saved. The selected document may be incomplete."}
            finally {busy=false}
        }
    }
    OutlinedButton(enabled=!busy,onClick={busy=true;error=null;notice=null;preview=null;scope.launch {
        try {preview=withContext(Dispatchers.IO) {repo.featureRead(tenantId,permission) {generate()}.also {require(it.toByteArray(Charsets.UTF_8).size<=10*1024*1024) {"Export exceeds 10 MB. Choose a smaller period or record set."}}}}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {error=e.message ?: "CSV could not be generated."}
        finally {busy=false}
    }},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(busy)"Working…" else label)}
    error?.let {InlineNotice("Export needs checking",it,NoticeTone.Danger)}
    notice?.let {Text(it)}
    preview?.let {text ->
        Text("Exact CSV preview · first ${minOf(text.length,4000)} characters. Saving includes the full reviewed snapshot.",style=MaterialTheme.typography.bodySmall)
        SelectionContainer {Text(text.take(4000),style=MaterialTheme.typography.bodySmall)}
        OutlinedButton(enabled=!busy,onClick={
            runCatching {repo.featureRead(tenantId,permission) {Unit};pending=text;busy=true;save.launch(filename)}.onFailure {pending=null;busy=false;error=it.message}
        }) {Text("Choose document and save CSV")}
        TextButton(enabled=!busy,onClick={preview=null}) {Text("Close CSV preview")}
    }
}
