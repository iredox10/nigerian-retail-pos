package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.PosRepository
import pos.data.ProductPhotoImport
import pos.data.importProductPhotos
import pos.data.reviewProductPhotoFolder
import pos.ui.components.*

@Composable
fun BulkProductPhotosScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) = key(tenantId) {
    var preview by remember { mutableStateOf<ProductPhotoImport?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reason by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val reviewed = preview
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Name each PNG or JPEG file with the item's SKU or barcode. For example, SOAP-200.png matches the SKU SOAP-200.")
        Text("You can choose up to 64 images in one folder. Selected images become the main photos in Stock and Sell.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NeutralButton(if (busy) "Working…" else "Choose photo folder", {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val folder = withContext(Dispatchers.IO) { chooseProductPhotoFolder() }
                        if (folder != null) {
                            val result = withContext(Dispatchers.IO) { repo.reviewProductPhotoFolder(tenantId, folder) }
                            preview = result
                            selected = result.rows.filter { it.candidate != null }.map { it.filename }.toSet()
                            folderName = folder.fileName?.toString().orEmpty()
                            saved = null
                        }
                    } catch (failure: Exception) {
                        error = failure.message ?: "The folder could not be reviewed."
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy)
            if (folderName.isNotEmpty()) Text(folderName, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { InlineNotice("Photo import unavailable", it, NoticeTone.Danger) }
        saved?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (reviewed != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                PosTextField(reason, { reason = it }, "Reason for changing photos", Modifier.weight(1f))
                PosButton(if (selected.size == 1) "Save 1 photo" else "Save ${selected.size} photos", {
                    val choice = selected
                    val note = reason
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val count = withContext(Dispatchers.IO) { repo.importProductPhotos(tenantId, reviewed, choice, note) }
                            saved = if (count == 1) "1 photo saved on this computer." else "$count photos saved on this computer."
                            preview = null
                            selected = emptySet()
                            reason = ""
                            onChanged()
                        } catch (failure: Exception) {
                            error = failure.message ?: "None of the selected photos were saved."
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy && selected.isNotEmpty() && reason.trim().length in 3..250)
            }
            val skipped = reviewed.rows.count { it.candidate == null }
            Text("${selected.size} selected · $skipped skipped", style = MaterialTheme.typography.bodySmall)
            if (reviewed.rows.isEmpty()) EmptyState("No supported photos", "Choose a folder with PNG or JPEG files in it.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(reviewed.rows, key = { it.filename }) { row ->
                    val candidate = row.candidate
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (candidate != null) {
                                Checkbox(row.filename in selected, {
                                    selected = if (it) selected + row.filename else selected - row.filename
                                }, enabled = !busy)
                                LocalImage(remember(candidate.image) { candidate.image.bytes }, "Preview for ${candidate.product.name}", Modifier.size(48.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(candidate?.product?.name ?: row.filename, style = MaterialTheme.typography.titleSmall)
                                val status = when {
                                    candidate == null -> row.skippedReason.orEmpty()
                                    candidate.current.assetId != null -> "${row.filename} · Replace main photo"
                                    else -> "${row.filename} · Add main photo"
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun chooseProductPhotoFolder(): Path? {
    var folder: Path? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a folder of product photos"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) folder = chooser.selectedFile.toPath()
    }
    return folder
}
