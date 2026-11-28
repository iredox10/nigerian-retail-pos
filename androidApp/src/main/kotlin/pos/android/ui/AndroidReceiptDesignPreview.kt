package pos.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pos.android.documents.*
import pos.domain.ShopDetails
import pos.ui.components.*

private data class AndroidRenderedDesign(val image: Bitmap, val pages: Int, val source: AndroidReceiptSource, val page: Int)

/** The displayed bitmap is rendered from the actual native PDF, not a separate imitation. */
@Composable
internal fun AndroidReceiptDesignPreview(details: ShopDetails, logo: ByteArray?, artwork: ByteArray?, onReady: (ShopDetails, ByteArray?, ByteArray?, Boolean) -> Unit) {
    val context = LocalContext.current
    val source = remember(details, logo, artwork) { androidReceiptDesignSource(details, logo, artwork) }
    var pageIndex by remember(details, logo, artwork) { mutableIntStateOf(0) }
    val preview by produceState<Result<AndroidRenderedDesign>?>(null, source, pageIndex) {
        value = null
        delay(180)
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = writeAndroidReceiptPdf(context, source)
                try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            val selected = pageIndex.coerceIn(0, renderer.pageCount - 1)
                            renderer.openPage(selected).use { page ->
                                val scale = minOf(640f / page.width, 2000f / page.height)
                                val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    AndroidRenderedDesign(bitmap, renderer.pageCount, source, selected)
                                } catch (error: Throwable) { bitmap.recycle(); throw error }
                            }
                        }
                    }
                } finally { file.delete() }
            }
        }
    }
    val exact = preview?.getOrNull()?.takeIf { it.source == source && it.page == pageIndex }
    LaunchedEffect(source, preview, pageIndex) { onReady(details, logo, artwork, exact != null) }
    Text("Design preview", style = MaterialTheme.typography.titleMedium)
    Text("Sample items and amounts for layout only. The preview is not a sale.", style = MaterialTheme.typography.bodySmall)
    when {
        preview == null || (preview?.isSuccess == true && exact == null) -> LinearProgressIndicator(Modifier.fillMaxWidth())
        preview?.isFailure == true -> InlineNotice("Preview unavailable", preview?.exceptionOrNull()?.message ?: "Review the receipt details.", NoticeTone.Warning)
        else -> exact?.let { rendered ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = pageIndex > 0, onClick = { pageIndex-- }) { Text("Previous page") }
                Text("Page ${pageIndex + 1} of ${rendered.pages}")
                TextButton(enabled = pageIndex + 1 < rendered.pages, onClick = { pageIndex++ }) { Text("Next page") }
            }
            Image(rendered.image.asImageBitmap(), "Rendered receipt design preview, page ${pageIndex + 1}", Modifier.fillMaxWidth().aspectRatio(rendered.image.width.toFloat() / rendered.image.height))
        }
    }
}
