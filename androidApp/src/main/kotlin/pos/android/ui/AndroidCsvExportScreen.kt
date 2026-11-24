package pos.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.android.documents.*
import pos.data.PosRepository
import pos.ui.components.*

@Composable
fun AndroidCsvExportScreen(repo: PosRepository, tenantId: String) = key(tenantId) {
    var kind by remember { mutableStateOf(AndroidCsvKind.SALES) }
    var preview by remember { mutableStateOf<AndroidCsvExport?>(null) }
    var pending by remember { mutableStateOf<AndroidCsvExport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val selected = pending; pending = null
        if(uri == null) { busy = false; notice = "CSV export cancelled. No records changed." }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val export = requireNotNull(selected) { "Generate and review the export again." }
                    repo.authorizeAndroidCsvSave(tenantId, export)
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "The selected destination cannot be written." }.use { output ->
                        output.write(export.csv.toByteArray(Charsets.UTF_8)); output.flush()
                    }
                }
                notice = "Reviewed CSV saved to the selected document."
            } catch(failure: Exception) { error = failure.message ?: "CSV was not saved. The selected document may be incomplete." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Export CSV records", "Review this shop's saved records before choosing a destination")
        Text("This saves a file through Android's document picker. It does not upload, send or synchronize records. CSV import is not available here.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidCsvKind.entries.forEach { option -> FilterChip(kind==option, {kind=option;preview=null;error=null;notice=null}, enabled=!busy, label={Text(option.label)}) }
        }
        Text(kind.scopeNote, style=MaterialTheme.typography.bodyMedium)
        error?.let { InlineNotice("Export unavailable", it, NoticeTone.Danger) }
        notice?.let { InlineNotice(it, tone=NoticeTone.Info) }
        PosButton(if(busy) "Working…" else "Generate exact preview", {
            busy=true;error=null;notice=null;preview=null
            scope.launch {
                try { preview=withContext(Dispatchers.IO) {repo.prepareAndroidCsv(tenantId,kind)} }
                catch(failure:Exception) {error=failure.message ?: "The selected records could not be read. Check your permissions."}
                finally {busy=false}
            }
        },modifier=Modifier.fillMaxWidth(),enabled=!busy)
        preview?.let { export ->
            Text("${export.rowCount} rows · Snapshot generated ${export.generatedAt}",style=MaterialTheme.typography.titleSmall)
            Text("Exact header and first ${minOf(20,export.rowCount)} rows. All ${export.rowCount} rows are included when saving. Money columns are NGN decimals; text that could run as a spreadsheet formula is prefixed with an apostrophe.",style=MaterialTheme.typography.bodySmall)
            Surface(shape=MaterialTheme.shapes.medium) {
                Box(Modifier.fillMaxWidth().heightIn(max=340.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)) {
                    SelectionContainer { Text(export.previewCsv,style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),softWrap=false) }
                }
            }
            PosButton("Choose document & save CSV", {
                error=null;notice=null
                runCatching {
                    repo.authorizeAndroidCsvSave(tenantId,export)
                    pending=export;busy=true;error=null;notice=null
                    save.launch("retail-pos-${export.kind.name.lowercase()}-${export.generatedAt.take(10)}.csv")
                }.onFailure {pending=null;busy=false;error=it.message ?: "This export could not be opened. Check your access and try again."}
            },modifier=Modifier.fillMaxWidth(),enabled=!busy)
        }
    }
}
