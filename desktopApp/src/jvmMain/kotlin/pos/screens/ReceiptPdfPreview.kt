package pos.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import pos.ui.components.*

private data class RenderedReceipt(val image: ByteArray, val width: Int, val height: Int, val pages: Int)

/** Shows a rasterized page from the same PDF bytes used by receipt export. */
@Composable
fun ReceiptPdfPreview(bytes: ByteArray, modifier: Modifier = Modifier) {
    var page by remember(bytes) { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val rendered by produceState<Result<RenderedReceipt>?>(null, bytes, page) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                Loader.loadPDF(bytes).use { document ->
                    val image = PDFRenderer(document).renderImageWithDPI(page, 160f)
                    val png = ByteArrayOutputStream().use { out ->
                        check(ImageIO.write(image, "png", out)) { "The preview image could not be rendered." }
                        out.toByteArray()
                    }
                    RenderedReceipt(png, image.width, image.height, document.numberOfPages)
                }
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val result = rendered
        when {
            result == null -> Text("Rendering receipt preview…")
            result.isFailure -> InlineNotice("Preview could not be rendered", result.exceptionOrNull()?.message.orEmpty(), NoticeTone.Danger)
            else -> {
                val receipt = result.getOrThrow()
                val bitmap = remember(receipt.image) { SkiaImage.makeFromEncoded(receipt.image).toComposeImageBitmap() }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Page ${page + 1} of ${receipt.pages}", modifier = Modifier.weight(1f))
                    if (receipt.pages > 1) {
                        TextButton({ page-- }, enabled = page > 0) { Text("Previous page") }
                        TextButton({ page++ }, enabled = page + 1 < receipt.pages) { Text("Next page") }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f to "Fit", 1.5f to "150%", 2f to "200%").forEach { (value, label) ->
                        FilterChip(zoom == value, { zoom = value }, label = { Text(label) })
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val previewWidth = maxWidth * zoom
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Surface(Modifier.width(previewWidth), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Image(bitmap, "Receipt PDF preview, page ${page + 1}",
                                Modifier.fillMaxWidth().aspectRatio(receipt.width.toFloat() / receipt.height), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
        }
    }
}
