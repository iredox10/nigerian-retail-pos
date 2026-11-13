package pos.output

import kotlin.test.*
import pos.domain.*
import java.time.LocalDate

class SupplierAccountStatementServiceTest {
    private val profile=SupplierProfile("supplier-id","shop",SupplierDetails("=Current renamed supplier"),false,2,"2026-11-01T00:00:00Z")
    private val from=LocalDate.parse("2026-11-02")
    private val through=LocalDate.parse("2026-11-03")
    private fun receipt(id:String,name:String,date:String,cost:Long=1000,paid:Long=0)=StockReceipt(id,"shop","item","Rice","bag",1,cost,cost,paid,name,date)
    private fun link(receipt:String,supplier:String=profile.id)=SupplierReceiptLink("shop",receipt,supplier,"link-$receipt","2026-11-04T00:00:00Z")

    @Test fun renamedSupplierAndSimilarUnlinkedNamesDoNotChangeHistoricalAccountTotals() {
        val old=receipt("old","Old supplier name","2026-11-01T09:00:00Z",1000,200)
        val later=receipt("later","Renamed supplier","2026-11-02T09:00:00Z",2000,500)
        val unlinked=receipt("unlinked","Old supplier name","2026-11-02T09:00:00Z",9999)
        val other=receipt("other","Old supplier name","2026-11-02T09:00:00Z",8888)
        val payment=SupplierPayment("paid","shop",old.id,old.supplierName,300,"2026-11-03T09:00:00Z")
        val result=SupplierAccountStatementService.build(profile,listOf(link(old.id),link(later.id),link(other.id,"different-profile")),listOf(old,later,unlinked,other),listOf(payment),from,through)
        assertEquals(800L,result.statement.openingMinor)
        assertEquals(2000L,result.statement.purchasesMinor)
        assertEquals(2000L,result.statement.closingMinor)
        assertEquals(listOf(old,later),result.receipts)
        assertEquals("Old supplier name",result.receipts.first().supplierName)
        val renamed=SupplierAccountStatementService.build(profile.copy(details=profile.details.copy(name="Another current name")),result.links,result.receipts,result.payments,from,through)
        assertEquals(result.statement.closingMinor,renamed.statement.closingMinor)
    }
    @Test fun exportsStableAccountIdCurrentNameAndUnchangedHistoricalReceiptNamesSafely() {
        val receipt=receipt("purchase","+Original name","2026-11-02T09:00:00Z")
        val result=SupplierAccountStatementService.build(profile,listOf(link(receipt.id)),listOf(receipt),emptyList(),from,through)
        val csv=SupplierAccountStatementService.csv(result)
        assertTrue(csv.contains("Supplier account ID"))
        assertTrue(csv.contains("supplier-id"))
        assertTrue(csv.contains("'=Current renamed supplier"))
        assertTrue(csv.contains("'+Original name"))
        assertFails {SupplierAccountStatementService.csv(result.copy(statement=result.statement.copy(closingMinor=1)))}
    }
    @Test fun rejectsForeignDuplicateMissingAndMismatchedHistoricalSources() {
        val receipt=receipt("purchase","Original","2026-11-02T09:00:00Z")
        val link=link(receipt.id)
        val payment=SupplierPayment("paid","shop",receipt.id,"Wrong name",100,"2026-11-03T09:00:00Z")
        assertFails {SupplierAccountStatementService.build(profile,listOf(link.copy(tenantId="foreign")),listOf(receipt),emptyList(),from,through)}
        assertFails {SupplierAccountStatementService.build(profile,listOf(link,link),listOf(receipt),emptyList(),from,through)}
        assertFails {SupplierAccountStatementService.build(profile,listOf(link),emptyList(),emptyList(),from,through)}
        assertFails {SupplierAccountStatementService.build(profile,listOf(link),listOf(receipt),listOf(payment),from,through)}
    }
    @Test fun watCutoffAndFullyPaidReceiptsRemainVisibleOnLinkedAccounts() {
        val boundary=receipt("start","Historic","2026-11-01T23:00:00Z",1000,1000)
        val after=receipt("after","Historic","2026-11-03T23:00:00Z",500,0)
        val result=SupplierAccountStatementService.build(profile,listOf(link(boundary.id),link(after.id)),listOf(boundary,after),emptyList(),from,through)
        assertEquals(1000L,result.statement.purchasesMinor)
        assertEquals(0L,result.statement.closingMinor)
        assertEquals(500L,result.statement.currentOutstandingMinor)
        assertEquals(listOf(boundary.id),result.statement.events.map {it.receiptId})
    }
}
