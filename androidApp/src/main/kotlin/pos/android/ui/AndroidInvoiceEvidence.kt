package pos.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import pos.data.*
import pos.domain.*
import pos.ui.components.*

/** Exports the integrity-checked bytes of this exact saved invoice revision. */
@Composable
internal fun AndroidInvoiceEvidenceSave(repo: PosRepository, tenantId: String, invoice: SupplierInvoice) {
    val reference = invoice.attachment ?: return
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(reference.mediaType)) { uri ->
        val snapshot = pending; pending = null
        if (uri == null) { busy = false; message = "Document save cancelled. Invoice history is retained." }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) { Unit }
                    val bytes = requireNotNull(snapshot) { "Choose Save evidence again to reload the original document." }
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "This document destination cannot be written." }.use { it.write(bytes); it.flush() }
                }
                message = "Original evidence for revision ${invoice.version} saved to the chosen document."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "${e.message ?: "Evidence was not saved."} The chosen document may be incomplete." }
            finally { busy = false }
        }
    }
    Text("Evidence: ${reference.fileName} · ${reference.mediaType} · ${reference.byteSize} bytes")
    OutlinedButton(enabled = !busy, onClick = {
        busy = true; error = null; message = null
        scope.launch {
            try {
                pending = withContext(Dispatchers.IO) { repo.supplierInvoiceAttachmentBytes(tenantId, invoice.id, invoice.version) }
                save.launch(reference.fileName)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pending = null; busy = false; error = e.message ?: "Original evidence is unavailable." }
        }
    }) { Text(if (busy) "Working…" else "Save original evidence") }
    message?.let { Text(it) }
    error?.let { InlineNotice("Evidence needs checking", it, NoticeTone.Danger) }
}
