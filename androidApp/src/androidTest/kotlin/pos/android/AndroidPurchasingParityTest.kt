package pos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import pos.output.SupplierAccountStatementService
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AndroidPurchasingParityTest {
    private fun fixture(work:(PosRepository,String)->Unit) {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use {repo ->work(repo,repo.createTenant("Native purchasing").id)}
    }
    @Test fun stableSupplierAndBuyingPackPreserveHistoricalMeaningThroughRenameArchiveAndRetry()=fixture {r,t ->
        val item=r.addProduct(t,"Soap","piece",300,10.0)
        val pack=r.addPurchaseUnit(t,item.id,"carton",24,"pack")
        val supplier=r.addSupplierProfile(t,SupplierDetails("Original supplier"),"supplier")
        r.openCashShift(t,1000000)
        val receipt=r.receiveStockForSupplier(t,item.id,2,110000,120000,supplier.id,"received",pack.id,24,supplier.version)
        assertEquals(58L,r.products(t).single().exactStockScaled)
        assertEquals(220000L,receipt.totalCostMinor);assertEquals(100000L,r.supplierOutstanding(t,receipt.id))
        assertEquals(supplier.id,r.supplierReceiptLinks(t).single().supplierId)
        val renamed=r.updateSupplierProfile(t,supplier.id,SupplierDetails("Renamed supplier"),false,1,"Changed trading name","rename")
        r.updateSupplierProfile(t,supplier.id,renamed.details,true,renamed.version,"No more purchases","archive")
        assertEquals(receipt,r.receiveStockForSupplier(t,item.id,2,110000,120000,supplier.id,"received",pack.id,24,1))
        assertEquals("Original supplier",r.stockReceipts(t).single().supplierName)
        assertEquals(3,r.supplierProfileChanges(t,supplier.id).size)
        val today=LocalDate.now(ZoneId.of("Africa/Lagos"))
        val account=SupplierAccountStatementService.build(r.supplierProfiles(t,true).single(),r.supplierReceiptLinks(t),r.stockReceipts(t),r.supplierPayments(t),today.minusDays(1),today)
        assertEquals(100000L,account.statement.currentOutstandingMinor)
        assertEquals(100000L,account.statement.closingMinor)
        assertTrue(SupplierAccountStatementService.csv(account).contains("Original supplier"))
        assertTrue(SupplierAccountStatementService.csv(account).contains("Renamed supplier"))
        assertThrows(IllegalArgumentException::class.java) {r.receiveStockForSupplier(t,item.id,1,110000,0,supplier.id,"new-after-archive",pack.id,24,1)}
        assertEquals(58L,r.products(t).single().exactStockScaled)
    }
    @Test fun orderApprovalPartialDeliveryAndCancellationKeepExactStockCashAndRetryHistory()=fixture {r,t ->
        val a=r.addProduct(t,"Soap","piece",300,10.0);val b=r.addProduct(t,"Rice","bag",500,5.0)
        val supplier=r.addSupplierProfile(t,SupplierDetails("Market supplier"),"supplier")
        r.openCashShift(t,10000)
        val draft=r.createPurchaseOrder(t,supplier.id,listOf(PurchaseOrderLineInput("a",a.id,3,100),PurchaseOrderLineInput("b",b.id,4,200)),"Reviewed buying terms","order")
        val approved=r.approvePurchaseOrder(t,draft.id,draft.version,"approval")
        assertTrue(r.stockReceipts(t).isEmpty())
        val lines=listOf(PurchaseDeliveryInput("a",2,100,10),PurchaseDeliveryInput("b",1,0,5))
        val delivery=r.receivePurchaseOrder(t,draft.id,lines,approved.version,"delivery")
        assertEquals(delivery,r.receivePurchaseOrder(t,draft.id,lines,approved.version,"delivery"))
        assertEquals(2,r.stockReceipts(t).size)
        assertEquals(12L,r.products(t).single {it.id==a.id}.exactStockScaled)
        assertEquals(6L,r.products(t).single {it.id==b.id}.exactStockScaled)
        assertEquals(9900L,r.expectedCash(t,r.activeCashShift(t)!!.id))
        assertEquals(300L,r.stockReceipts(t).sumOf {r.supplierOutstanding(t,it.id)})
        assertEquals(mapOf("a" to 1,"b" to 3),r.purchaseOrderRemaining(t,draft.id))
        r.cancelPurchaseOrder(t,draft.id,"Supplier cannot deliver remaining goods",approved.version,"cancel")
        assertEquals(2,r.stockReceipts(t).size);assertEquals(1,r.purchaseOrderDeliveries(t,draft.id).size)
        assertThrows(IllegalArgumentException::class.java) {r.receivePurchaseOrder(t,draft.id,lines,approved.version,"after-cancel")}
        assertEquals(2,r.stockReceipts(t).size)
    }
}
