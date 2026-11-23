package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.PosRepository
import pos.data.localDiagnostics
import pos.diagnostics.LocalDiagnosticReport
import pos.domain.StaffPermission
import pos.ui.components.*
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun DiagnosticsScreen(repo: PosRepository, tenantId: String) = key(tenantId) {
    var report by remember { mutableStateOf<LocalDiagnosticReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var json by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeader("Local app checks", "Review a private diagnostic export before saving it") }
        item { InlineNotice("You control this export", "Checks stay on this computer. The preview contains no shop records, names, payment details, secrets or private paths. Nothing is sent or uploaded.", NoticeTone.Info) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PosButton(if (busy) "Checking…" else "Run local checks", {
                    busy = true; error = null; notice = null; report = null
                    scope.launch {
                        try { report = withContext(Dispatchers.IO) { repo.localDiagnostics(tenantId) } }
                        catch (_: Exception) { error = "Checks could not run. Check your access and try again. No report was generated." }
                        finally { busy = false }
                    }
                }, enabled = !busy)
                NeutralButton(if (json) "Show text preview" else "Show JSON preview", { json = !json })
                NeutralButton("Save reviewed export", {
                    runCatching {
                        // Recheck the current session before displaying or saving the reviewed snapshot.
                        repo.featureRead(tenantId, StaffPermission.BACKUP) { Unit }
                        val value = requireNotNull(report)
                        val extension = if (json) "json" else "txt"
                        val chooser = JFileChooser().apply {
                            dialogTitle = "Save reviewed local diagnostic export"
                            selectedFile = java.io.File("retail-pos-diagnostics.$extension")
                            fileFilter = FileNameExtensionFilter("Diagnostic export (.$extension)", extension)
                        }
                        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) false
                        else {
                            val target = chooser.selectedFile.toPath()
                            if (Files.exists(target) && JOptionPane.showConfirmDialog(null, "Replace the selected file?", "Save diagnostic export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) false
                            else {
                                repo.featureRead(tenantId, StaffPermission.BACKUP) {
                                    Files.writeString(target, if (json) value.json() else value.text())
                                }
                                true
                            }
                        }
                    }.onSuccess { saved -> notice = if (saved) "Reviewed diagnostic export saved" else "Export cancelled" }
                        .onFailure { error = "Export could not be saved. Check your access and chosen folder, then try again." }
                }, enabled = report != null && !busy)
            }
        }
        error?.let { item { InlineNotice("Checks or export unavailable", it, NoticeTone.Danger) } }
        notice?.let { item { InlineNotice(it, tone = NoticeTone.Info) } }
        item {
            InlineNotice("What these checks mean", "SQLite quick_check tests local database structure, not financial correctness. The storage test writes and removes a tiny temporary file beside the actual database. Available space is a point-in-time reading. A local print service does not prove that a printer is connected or can print. Backup, network, sync and update health are not checked.", NoticeTone.Info)
        }
        report?.let { value ->
            item {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("Exact export preview", "Only the fields below will be saved")
                        SelectionContainer { Text(if (json) value.json() else value.text(), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        } ?: item { EmptyState("No diagnostic report yet", "Run local checks, review the results, then choose whether to save them.") }
    }
}
