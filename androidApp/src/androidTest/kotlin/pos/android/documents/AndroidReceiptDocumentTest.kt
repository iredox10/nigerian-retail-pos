package pos.android.documents

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

@RunWith(AndroidJUnit4::class)
class AndroidReceiptDocumentTest {
    @Test fun savedIdentityUnconfirmedMoneyAndLongReceiptsRemainTrueAndPaginate() {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use {repo->
            val shop=repo.createTenant("Original shop")
            val customer=repo.addCustomer(shop.id,"Customer")
            val details=ShopDetails("Captured shop",footer="Original footer",receiptPaper="58mm")
            repo.saveShopProfile(shop.id,details,0,"Initial captured details")
            repo.openCashShift(shop.id,0)
            val products=(1..45).map {repo.addProduct(shop.id,"Long product name $it with a preserved historical selling identity","piece",10000,3.0)}
            val sale=repo.checkout(shop.id,products.map {CartLine(it,1)},100000,customer.id,"receipt-source",externalPayments=listOf(ExternalPayment("transfer",100000,"reference","shop account")))
            val source=loadAndroidReceipt(repo,shop.id,sale.id)
            val text=androidReceiptText(source)
            assertTrue(text.contains("Captured shop"));assertTrue(text.contains("Transfer not confirmed"))
            assertTrue(text.contains("This app has not confirmed that the money arrived."));assertTrue(text.contains("Customer owes"))
            repo.saveShopProfile(shop.id,details.copy(displayName="Changed shop"),1,"Change later identity")
            assertEquals("Captured shop",loadAndroidReceipt(repo,shop.id,sale.id).details?.displayName)
            val other=repo.createTenant("Other")
            try {loadAndroidReceipt(repo,other.id,sale.id);fail("Cross-shop receipt accepted")}catch(_:IllegalArgumentException){}
            val context=ApplicationProvider.getApplicationContext<android.content.Context>()
            val pdf=writeAndroidReceiptPdf(context,source)
            try {assertTrue(pdf.length()>0);assertEquals("%PDF-",pdf.inputStream().use {String(it.readNBytes(5))})
                ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {renderer->assertTrue(renderer.pageCount>1)}}
            } finally {pdf.delete()}
        }
    }
    @Test fun maximumMoneyAndLongNamesStayInsideNativePrintableMargins() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val product=Product("product","shop","Very long original product name ".repeat(4).trim(),"piece",Long.MAX_VALUE,1.0)
        val sale=Sale("max-value-saved-sale","shop",listOf(CartLine(product,1)),Long.MAX_VALUE,Long.MAX_VALUE,0,null,"2026-11-23T12:00:00Z",cashGivenMinor=Long.MAX_VALUE)
        for(paper in listOf("A4","58mm","80mm")) {
            val source=AndroidReceiptSource(sale,ShopDetails("Long captured merchant name ".repeat(4).trim(),receiptPaper=paper,appearance=ReceiptAppearance(style="modern",policy="A long policy sentence ".repeat(15).trim())),null,null)
            val file=writeAndroidReceiptPdf(context,source)
            try {ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {renderer->
                for(index in 0 until renderer.pageCount)renderer.openPage(index).use {page->
                    val bitmap=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    for(y in 0 until bitmap.height)for(x in 0..5) {
                        assertEquals("Left margin $paper page$index at $x,$y",android.graphics.Color.WHITE,bitmap.getPixel(x,y))
                        assertEquals("Right margin $paper page$index",android.graphics.Color.WHITE,bitmap.getPixel(bitmap.width-1-x,y))
                    }
                    bitmap.recycle()
                }
            }}}finally {file.delete()}
        }
    }

}
