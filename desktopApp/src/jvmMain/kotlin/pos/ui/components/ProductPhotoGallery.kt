package pos.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.ImageUpload
import pos.data.PRODUCT_GALLERY_LIMIT
import pos.data.PosRepository
import pos.data.ProductGalleryChange
import pos.data.imageBytes
import pos.data.productGalleryHistory
import pos.data.saveGalleryPhoto
import pos.output.receiptDate
import java.util.UUID

private data class GalleryEditor(val photo: ProductGalleryChange? = null, val removing: Boolean = false)

@Composable
internal fun ProductPhotoGallery(repo: PosRepository, tenantId: String, productId: String, enabled: Boolean) {
    var revision by remember { mutableIntStateOf(0) }
    var showHistory by remember { mutableStateOf(false) }
    var historyCount by remember { mutableIntStateOf(10) }
    var editing by remember { mutableStateOf<GalleryEditor?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = remember(revision) { runCatching { repo.productGalleryHistory(tenantId, productId) } }
    val history = loaded.getOrDefault(emptyList())
    val active = history.associateBy { it.photoId }.values.filter { it.assetId != null }
    SectionHeader("Additional photos", "Keep up to $PRODUCT_GALLERY_LIMIT views of this item. Stock and Sell use the main product photo above.")
    loaded.exceptionOrNull()?.let { InlineNotice("Photo gallery unavailable", it.message.orEmpty(), NoticeTone.Danger) }
    notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
    if (loaded.isSuccess && active.isEmpty()) Text("No additional photos saved.", style = MaterialTheme.typography.bodySmall)
    for ((index, photo) in active.withIndex()) key(photo.photoId) {
        val photoBytes = remember(photo.assetId) { runCatching { repo.imageBytes(tenantId, requireNotNull(photo.assetId)) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LocalImage(photoBytes.getOrNull(), "Additional product photo ${index + 1}", Modifier.size(88.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Photo ${index + 1}", style = MaterialTheme.typography.titleSmall)
                if (photoBytes.isFailure) Text("Saved photo unavailable", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeutralButton("View or edit", { editing = GalleryEditor(photo); notice = null })
                    if (enabled) NeutralButton("Remove photo", { editing = GalleryEditor(photo, true); notice = null })
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (enabled) NeutralButton("Add photo", { editing = GalleryEditor(); notice = null },
            enabled = loaded.isSuccess && active.size < PRODUCT_GALLERY_LIMIT)
        NeutralButton("Reload gallery", { revision++; notice = null })
        if (history.isNotEmpty()) NeutralButton(if (showHistory) "Hide photo history" else "View photo history", { showHistory = !showHistory })
    }
    if (showHistory) {
        for (event in history.asReversed().take(historyCount)) {
            HorizontalDivider()
            val action = when {
                event.assetId == null -> "Photo removed"
                event.version == 1L -> "Photo added"
                else -> "Photo changed"
            }
            Text("$action · ${receiptDate(event.recordedAt.toString())}", style = MaterialTheme.typography.titleSmall)
            Text(event.reason, style = MaterialTheme.typography.bodySmall)
        }
        if (historyCount < history.size) TextButton({ historyCount += 10 }) { Text("Show older changes") }
    }
    editing?.let { editor ->
        GalleryPhotoDialog(repo, tenantId, productId, editor, enabled, { editing = null }) {
            notice = if (editor.removing) "Photo removed from gallery. Its earlier history is kept." else "Photo saved on this computer."
            revision++
            editing = null
        }
    }
}

@Composable
private fun GalleryPhotoDialog(
    repo: PosRepository,
    tenantId: String,
    productId: String,
    editor: GalleryEditor,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val photo = editor.photo
    val original = remember(photo) {
        runCatching { photo?.assetId?.let { ImageUpload.fromStoredBytes(repo.imageBytes(tenantId, it)) } }
    }
    var pending by remember { mutableStateOf<ImageUpload?>(null) }
    var adjusting by remember { mutableStateOf<ImageUpload?>(null) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val operationId = remember(pending, reason) { UUID.randomUUID().toString() }
    val selected = pending ?: original.getOrNull()
    val bytes = remember(selected) { selected?.bytes }
    val canSave = enabled && reason.trim().length in 3..250 &&
        (editor.removing || (pending != null && pending?.assetId != photo?.assetId))
    adjusting?.let { source ->
        PhotoAdjustDialog(source, { adjusting = null }) { pending = it; adjusting = null }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.removing) "Remove additional photo" else if (photo == null) "Add a product photo" else "Additional product photo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LocalImage(bytes, "Additional product photo preview", Modifier.fillMaxWidth().height(220.dp))
                original.exceptionOrNull()?.let { InlineNotice("Saved photo unavailable", it.message.orEmpty(), NoticeTone.Danger) }
                if (enabled && !editor.removing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeutralButton(if (selected == null) "Choose image" else "Choose another image", {
                            runCatching { choosePhotoFile("Choose additional product photo") }
                                .onSuccess { chosen -> chosen?.let { pending = it.second; error = null } }
                                .onFailure { error = it.message ?: "The image could not be opened." }
                        })
                        if (selected != null) NeutralButton("Crop or rotate", { adjusting = selected; error = null })
                    }
                }
                if (enabled) PosTextField(reason, { reason = it; error = null }, "Why is this image changing?",
                    hint = "A reason is kept in photo history")
                error?.let { InlineNotice("Photo not saved", it, NoticeTone.Danger) }
            }
        },
        confirmButton = {
            if (enabled) PosButton(if (editor.removing) "Remove photo" else "Save photo", {
                runCatching {
                    repo.saveGalleryPhoto(tenantId, productId, photo?.photoId, if (editor.removing) null else pending,
                        photo?.version ?: 0, reason.trim(), operationId)
                }.onSuccess { onSaved() }.onFailure { error = it.message ?: "Your changes are still here. Try again." }
            }, enabled = canSave)
        },
        dismissButton = { TextButton(onDismiss) { Text(if (enabled) "Cancel" else "Close") } },
    )
}
