package pos.android.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidReceiptDesignTest {
    private fun png(colour: Int): ByteArray {
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        return try {bitmap.eraseColor(colour);ByteArrayOutputStream().also {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()} finally {bitmap.recycle()}
    }
    @Test fun samplePreviewStaysOutOfBooksCenteredHeadingsRenderAndStaleArtworkRollsBack() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Receipt design QA").id
            val details=ShopDetails("AAA",appearance=ReceiptAppearance(style="classic",accentHex="#0000FF",headerAlignment="center"))
            val preview=androidReceiptDesignSource(details,png(Color.BLUE),png(Color.RED))
            assertTrue(preview.designPreview)
            assertTrue(androidReceiptText(preview).contains("DESIGN PREVIEW"))
            assertTrue(androidReceiptText(preview).contains("Not a recorded sale"))
            assertTrue(r.sales(t).isEmpty());assertTrue(r.products(t).isEmpty());assertNull(r.shopProfile(t))
            for(paper in listOf("A4","58mm","80mm")) {
                val file=writeAndroidReceiptPdf(context,preview.copy(details=details.copy(receiptPaper=paper),logo=null,artwork=null,designPreview=false))
                try {ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd ->PdfRenderer(fd).use {renderer ->renderer.openPage(0).use {page ->
                    val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val xs=mutableListOf<Int>()
                        for(y in 0 until minOf(90,page.height))for(x in 0 until page.width) {
                            val pixel=bitmap.getPixel(x,y)
                            if(Color.blue(pixel)>180 && Color.red(pixel)<100 && Color.green(pixel)<100)xs+=x
                        }
                        assertTrue("Heading ink missing on $paper",xs.isNotEmpty())
                        val centre=(xs.minOrNull()!!+xs.maxOrNull()!!)/2f
                        assertTrue("Heading is not centred on $paper",kotlin.math.abs(centre-page.width/2f)<8f)
                    } finally {bitmap.recycle()}
                }}}} finally {file.delete()}
            }
            val upload=ImageUpload.fromBytes(png(Color.BLUE))
            val first=r.saveReviewedAndroidReceiptDesign(t,details,upload,0,"Initial design","save-design")
            assertArrayEquals(upload.bytes,r.imageBytes(t,first.details.appearance.artworkAssetId!!))
            assertEquals(first,r.saveReviewedAndroidReceiptDesign(t,details,upload,0,"Initial design","save-design"))
            assertEquals(1,r.shopProfileHistory(t).size)
            r.saveShopProfile(t,first.details.copy(footer="Updated elsewhere"),1,"New footer","new-footer")
            val rejected=ImageUpload.fromBytes(png(Color.RED))
            assertThrows(IllegalArgumentException::class.java) {r.saveReviewedAndroidReceiptDesign(t,details.copy(footer="Stale footer"),rejected,1,"Old review","stale-design")}
            assertThrows(IllegalStateException::class.java) {r.imageBytes(t,rejected.assetId)}
            assertEquals(2,r.shopProfileHistory(t).size)
            assertTrue(r.sales(t).isEmpty());assertTrue(r.stockReceipts(t).isEmpty());assertTrue(r.products(t).isEmpty())
        }
    }
}
