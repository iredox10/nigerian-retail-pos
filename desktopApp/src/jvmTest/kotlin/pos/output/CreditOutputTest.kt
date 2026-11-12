package pos.output

import kotlin.test.*
import pos.domain.*

class CreditOutputTest {
    @Test fun receiptAndExportsPreserveAgreedDueDateWithoutCallingItCurrentDebt() {
        val shop=Tenant("s","Sample shop")
        val p=Product("p","s","Malt","bottle",10000,10.0)
        val sale=Sale("sale","s",listOf(CartLine(p,2)),20000,5000,15000,"c","2026-11-12T12:00:00Z","2026-12-01")
        val customer=Customer("c","s","Buyer",15000,"+2348012345678","Market",50000)
        val receipt=ReceiptService.format(shop,sale)
        assertContains(receipt,"Agreed repayment due date: 2026-12-01 (WAT)")
        assertContains(receipt,"This is not the customer's current debt.")
        assertContains(CsvService.sales("s",listOf(sale)),"2026-12-01")
        assertContains(CustomerStatementService.csv(CustomerStatementService.build("s",customer,listOf(sale),emptyList())),"2026-12-01")
        val csv=CsvService.customers("s",listOf(customer))
        assertContains(csv,"\"500.00\"")
        assertContains(csv,"\"'+2348012345678\"")
    }
    @Test fun draftDueTextSurvivesHoldResumeIncludingIncompleteInput() {
        val dir=java.nio.file.Files.createTempDirectory("credit-draft-")
        try {
            val store=pos.data.DraftStore("s",dir)
            val draft=pos.data.SaleDraft("p:1","operation","0","c","credit","2026-12-")
            store.save(draft);val held=store.hold("Buyer",draft)
            assertEquals("2026-12-",pos.data.DraftStore("s",dir).resume(held.id).dueDateText)
        }finally {dir.toFile().deleteRecursively()}
    }
}
