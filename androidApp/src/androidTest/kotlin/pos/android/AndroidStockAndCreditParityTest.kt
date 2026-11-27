package pos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*

@RunWith(AndroidJUnit4::class)
class AndroidStockAndCreditParityTest {
    private fun fixture(work:(PosRepository,String)->Unit) {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use {repo ->work(repo,repo.createTenant("Native stock and supplier credit").id)}
    }
    @Test fun countDraftsAndStalePostingNeverEraseSalesAndPostedRetryChangesStockOnce()=fixture {r,t ->
        val a=r.addProduct(t,"Soap","piece",100,10.0);val b=r.addProduct(t,"Rice","bag",200,5.0)
        r.openCashShift(t,0)
        val draft=r.createStockCountSession(t,"Closing count",listOf(a.id,b.id),"first-count")
        val partial=r.saveStockCountDraft(t,draft.id,mapOf(a.id to 8,b.id to null),"Counted shelf",draft.version)
        assertThrows(IllegalArgumentException::class.java) {r.postStockCountSession(t,draft.id,partial.version)}
        val complete=r.saveStockCountDraft(t,draft.id,mapOf(a.id to 8,b.id to 4),"Counted shelf",partial.version)
        r.checkout(t,listOf(CartLine(a,1)),100,operationId="sale-during-count")
        assertThrows(IllegalArgumentException::class.java) {r.postStockCountSession(t,draft.id,complete.version)}
        assertEquals(9L,r.products(t).single {it.id==a.id}.exactStockScaled)
        assertEquals(5L,r.products(t).single {it.id==b.id}.exactStockScaled)
        r.cancelStockCountSession(t,draft.id,"Stock moved; recount required",complete.version)
        val recount=r.createStockCountSession(t,"Fresh recount",listOf(a.id,b.id),"fresh-count")
        val saved=r.saveStockCountDraft(t,recount.id,mapOf(a.id to 8,b.id to 4),"Physical shelf recount",recount.version)
        val posted=r.postStockCountSession(t,recount.id,saved.version)
        assertEquals(posted,r.postStockCountSession(t,recount.id,saved.version))
        assertEquals(8L,r.products(t).single {it.id==a.id}.exactStockScaled)
        assertEquals(4L,r.products(t).single {it.id==b.id}.exactStockScaled)
        assertEquals(1,r.sales(t).size);assertEquals(100L,r.expectedCash(t,r.activeCashShift(t)!!.id))
    }
    @Test fun supplierCreditAllocationReversalAndVoidKeepCashStockAndOriginalHistory()=fixture {r,t ->
        val product=r.addProduct(t,"Soap","piece",300,0.0)
        val supplier=r.addSupplierProfile(t,SupplierDetails("Credit supplier"),"supplier")
        r.openCashShift(t,10000)
        val receipt=r.receiveStockForSupplier(t,product.id,10,100,200,supplier.id,"receipt")
        val note=r.recordSupplierCredit(t,supplier.id,"CN-1",java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")).toString(),500,"Supplier agreed credit","credit")
        val review=r.reviewSupplierCredit(t,note.id,receipt.id,300)
        val allocation=r.applySupplierCredit(t,review,"Offset buying receipt","allocation")
        assertEquals(allocation,r.applySupplierCredit(t,review,"Offset buying receipt","allocation"))
        assertEquals(500L,r.supplierOutstanding(t,receipt.id));assertEquals(200L,r.supplierCreditBalances(t).single().availableMinor)
        assertEquals(9800L,r.expectedCash(t,r.activeCashShift(t)!!.id));assertEquals(10L,r.products(t).single().exactStockScaled)
        val reversal=r.reverseSupplierCreditApplication(t,allocation.id,"Application corrected","reversal")
        assertEquals(-300L,reversal.signedAmountMinor);assertEquals(800L,r.supplierOutstanding(t,receipt.id))
        r.voidSupplierCredit(t,note.id,"Supplier cancelled unused note","void")
        assertTrue(r.supplierCreditBalances(t).single().voided);assertEquals(2,r.supplierCreditAllocations(t).size)
        assertEquals(9800L,r.expectedCash(t,r.activeCashShift(t)!!.id));assertEquals(10L,r.products(t).single().exactStockScaled)
        assertThrows(IllegalArgumentException::class.java) {r.reviewSupplierCredit(t,note.id,receipt.id,100)}
    }
}
