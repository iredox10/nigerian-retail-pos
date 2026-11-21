package pos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.ImageCrop
import pos.data.ImageUpload

@Composable
internal fun PhotoAdjustDialog(
    source: ImageUpload,
    onDismiss: () -> Unit,
    onAdjusted: (ImageUpload) -> Unit,
) {
    var turns by remember(source) { mutableIntStateOf(0) }
    var left by remember(source) { mutableStateOf("0") }
    var top by remember(source) { mutableStateOf("0") }
    var right by remember(source) { mutableStateOf("0") }
    var bottom by remember(source) { mutableStateOf("0") }
    fun resetCrop() {
        left = "0"
        top = "0"
        right = "0"
        bottom = "0"
    }
    val preview = remember(source, turns, left, top, right, bottom) {
        runCatching {
            fun percent(text: String) = requireNotNull(text.ifBlank { "0" }.toIntOrNull()) {
                "Enter whole percentages from 0 to 99."
            }
            source.adjust(turns, ImageCrop(percent(left), percent(top), percent(right), percent(bottom)))
        }
    }
    val edited = preview.getOrNull()
    val previewBytes = remember(source, edited) { (edited ?: source).bytes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust image") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LocalImage(previewBytes, "Crop and rotation preview", Modifier.fillMaxWidth().height(180.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeutralButton("Rotate left", { turns = (turns + 3) % 4; resetCrop() })
                    NeutralButton("Rotate right", { turns = (turns + 1) % 4; resetCrop() })
                }
                Text("Crop by entering how much to remove from each edge of the rotated image.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PosTextField(left, { left = it }, "Left (%)", modifier = Modifier.weight(1f))
                    PosTextField(right, { right = it }, "Right (%)", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PosTextField(top, { top = it }, "Top (%)", modifier = Modifier.weight(1f))
                    PosTextField(bottom, { bottom = it }, "Bottom (%)", modifier = Modifier.weight(1f))
                }
                TextButton({ turns = 0; resetCrop() }) { Text("Reset adjustments") }
                preview.exceptionOrNull()?.let { InlineNotice("Check crop amounts", it.message.orEmpty(), NoticeTone.Danger) }
            }
        },
        confirmButton = {
            PosButton("Use adjusted image", { edited?.let(onAdjusted) }, enabled = edited != null && edited.assetId != source.assetId)
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
