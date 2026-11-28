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
import pos.android.documents.*
import pos.data.PosRepository
import pos.screens.ReportDateRange
import pos.ui.components.*

@Composable
internal fun AndroidReportPdfSave(repo: PosRepository, tenantId: String, range: ReportDateRange) = key(tenantId, range) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<AndroidReportDocument?>(null) }
    var pending by remember { mutableStateOf<AndroidReportDocument?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val reviewed = pending; pending = null
        if (uri == null) { busy = false; notice = "PDF export cancelled. No records changed." }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val snapshot = requireNotNull(reviewed) { "Review the report again before saving." }
                    repo.authorizeAndroidReportSave(tenantId, snapshot)
                    val file = writeAndroidReportPdf(context, snapshot)
                    try {
                        repo.authorizeAndroidReportSave(tenantId, snapshot)
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "The selected destination cannot be written." }.use { output ->
                            file.inputStream().use { it.copyTo(output) }; output.flush()
                        }
                    } finally { file.delete() }
                }
                notice = "Reviewed report PDF saved to the selected document."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "PDF was not saved. The selected document may be incomplete." }
            finally { busy = false }
        }
    }
    OutlinedButton(onClick = {
        busy = true; error = null; notice = null; preview = null
        scope.launch {
            try { preview = withContext(Dispatchers.IO) { repo.prepareAndroidReportDocument(tenantId, range) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "The report could not be prepared." }
            finally { busy = false }
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "Working…" else "Review report PDF") }
    error?.let { InlineNotice("PDF needs checking", it, NoticeTone.Danger) }
    notice?.let { Text(it) }
    preview?.let { report ->
        OperationsDialog("Review report PDF", { if (!busy) preview = null }, "Choose document and save PDF", action = {
            repo.authorizeAndroidReportSave(tenantId, report)
            pending = report; busy = true
            try { save.launch("retail-pos-report-${report.from}-${report.through}.pdf"); preview = null }
            catch (e: Exception) { pending = null; busy = false; throw e }
        }) {
            Text("This saves the reviewed snapshot, including all product, category and staff rows. It does not change sales, cash or stock.")
            Text("Text review · first ${minOf(report.text.length, 4000)} characters. The PDF includes the complete report.", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(report.text.take(4000), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
