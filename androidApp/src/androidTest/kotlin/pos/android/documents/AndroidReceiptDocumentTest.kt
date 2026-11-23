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
}
