package pos.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import pos.output.SupplierInvoiceCsv
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AndroidSupplierInvoiceParityTest {
    @Test fun invoiceCorrectionsRetainOriginalEvidenceAndReleaseLinksWithoutFinancialPostings() {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use { r ->
            val t=r.createTenant("Invoice parity").id
            val supplier=r.addSupplierProfile(t,SupplierDetails("Invoice supplier"),"supplier")
            val product=r.addProduct(t,"Soap","piece",300,0.0)
            r.openCashShift(t,1000)
            val receipt=r.receiveStockForSupplier(t,product.id,2,100,100,supplier.id,"delivery")
            val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            val bytes=try {ByteArrayOutputStream().also {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()} finally {bitmap.recycle()}
            val upload=SupplierInvoiceAttachmentUpload.fromBytes("supplier-proof.png",bytes)
            val details=SupplierInvoiceDetails("INV-1",LocalDate.now(ZoneId.of("Africa/Lagos")).toString())
            val first=r.recordSupplierInvoice(t,supplier.id,details,listOf(receipt.id),upload,"invoice")
            assertEquals(first,r.recordSupplierInvoice(t,supplier.id,details,listOf(receipt.id),upload,"invoice"))
            assertArrayEquals(bytes,r.supplierInvoiceAttachmentBytes(t,first.id,1))
            assertThrows(IllegalArgumentException::class.java) {
                r.recordSupplierInvoice(t,supplier.id,details,listOf(receipt.id),upload,"duplicate")
            }
            val correction=r.correctSupplierInvoice(t,first.id,details.copy(notes="Reviewed original document"),listOf(receipt.id),SupplierInvoiceStatus.ACTIVE,null,reason="Removed evidence from current revision",expectedVersion=1,operationId="correction")
            assertNull(correction.attachment)
            assertArrayEquals(bytes,r.supplierInvoiceAttachmentBytes(t,first.id,1))
            assertThrows(IllegalArgumentException::class.java) {r.supplierInvoiceAttachmentBytes(t,first.id)}
            val voided=r.correctSupplierInvoice(t,first.id,correction.details,emptyList(),SupplierInvoiceStatus.VOIDED,null,reason="Invoice metadata voided",expectedVersion=2,operationId="void")
            assertEquals(3L,voided.version)
            val replacement=r.recordSupplierInvoice(t,supplier.id,details.copy(number="INV-2"),listOf(receipt.id),operationId="replacement")
            assertEquals(listOf(receipt.id),replacement.receiptIds)
            val history=r.supplierInvoiceRevisions(t,first.id)
            assertEquals(3,history.size)
            assertTrue(SupplierInvoiceCsv.history(t,history,r.stockReceipts(t)).contains("supplier-proof.png"))
            assertEquals(2L,r.products(t).single().exactStockScaled)
            assertEquals(900L,r.expectedCash(t,r.activeCashShift(t)!!.id))
            assertEquals(100L,r.supplierOutstanding(t,receipt.id))
            r.setPermissionCheck {_,permission ->require(permission!=StaffPermission.MANAGE_SUPPLIERS) {"Supplier authority revoked"}}
            assertThrows(IllegalArgumentException::class.java) {r.supplierInvoiceAttachmentBytes(t,first.id,1)}
            r.setPermissionCheck {_,_->}
            assertEquals(2,r.supplierInvoices(t,supplier.id).size)
        }
    }
}
