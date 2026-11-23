package pos.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pos.domain.ReceiptAppearance
import pos.ui.components.*

/** Editable colour and text remain local until validation at preview or save. */
internal data class ReceiptAppearanceDraft(
    val style: String = "classic", val frame: String = "none", val accentHex: String = "#087F73",
    val headerAlignment: String = "left", val showLogo: Boolean = true,
    val artworkAssetId: String? = null, val tagline: String = "", val policy: String = "",
) {
    constructor(value: ReceiptAppearance) : this(value.style, value.frame, value.accentHex,
        value.headerAlignment, value.showLogo, value.artworkAssetId, value.tagline.orEmpty(), value.policy.orEmpty())

    fun appearance() = ReceiptAppearance(style, frame, accentHex.trim(), headerAlignment,
        showLogo, artworkAssetId, tagline.trim().ifEmpty { null }, policy.trim().ifEmpty { null })
}

@Composable
internal fun ReceiptAppearanceEditor(
    draft: ReceiptAppearanceDraft,
    changed: (ReceiptAppearanceDraft) -> Unit,
    artwork: ByteArray?,
    artworkName: String?,
    chooseArtwork: () -> Unit,
    removeArtwork: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Choose a look", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("classic" to "Classic", "modern" to "Modern", "compact" to "Compact").forEach { (value, label) ->
                val selected = draft.style == value
                Surface(onClick = { changed(draft.copy(style = value)) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.width(132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PosIcon(if (selected) PosIcons.CircleCheck else PosIcons.ReceiptText,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(when (value) {
                            "modern" -> "A bold heading and colour accents"
                            "compact" -> "A tighter, simple receipt"
                            else -> "Familiar and easy to read"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Frame", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("none" to "No frame", "line" to "Single line", "double" to "Double line").forEach { (value, label) ->
                FilterChip(draft.frame == value, { changed(draft.copy(frame = value)) }, label = { Text(label) })
            }
        }
        Text("Accent colour", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("#087F73" to "Teal", "#182230" to "Ink", "#175CD3" to "Blue", "#8A3A12" to "Copper").forEach { (hex, label) ->
                FilterChip(draft.accentHex.equals(hex, true), { changed(draft.copy(accentHex = hex)) },
                    label = { Text(label) }, leadingIcon = {
                        Box(Modifier.size(14.dp).background(Color(0xFF000000 or hex.drop(1).toLong(16)), RoundedCornerShape(4.dp)))
                    })
            }
        }
        PosTextField(draft.accentHex, { changed(draft.copy(accentHex = it)) }, "Custom colour", hint = "#087F73", modifier = Modifier.fillMaxWidth())
        Text("Heading alignment", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("left" to "Left", "center" to "Centre").forEach { (value, label) ->
                FilterChip(draft.headerAlignment == value, { changed(draft.copy(headerAlignment = value)) }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Show shop logo", style = MaterialTheme.typography.titleSmall)
                Text("Uses the logo saved in Shop details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(draft.showLogo, { changed(draft.copy(showLogo = it)) })
        }
        PosTextField(draft.tagline, { changed(draft.copy(tagline = it)) }, "Tagline below the heading (optional)", modifier = Modifier.fillMaxWidth())
        PosTextField(draft.policy, { changed(draft.copy(policy = it)) }, "Returns policy or receipt note (optional)",
            modifier = Modifier.fillMaxWidth(), hint = "Your shop's agreed returns terms")
        HorizontalDivider()
        Text("Additional receipt image", style = MaterialTheme.typography.titleMedium)
        Text("Add a banner, stamp or other artwork. Keep important receipt details in text so they remain readable.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (artwork != null) {
            LocalImage(artwork, "Receipt artwork preview", Modifier.fillMaxWidth().height(110.dp))
            artworkName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralButton(if (artwork == null) "Choose receipt image" else "Replace receipt image", chooseArtwork)
            if (artwork != null) NeutralButton("Remove receipt image", removeArtwork)
        }
        Text("PNG or JPEG · Saved with the receipt design when you save below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
