package pos.output

import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange
import java.time.LocalDate

class DatedCustomerStatementServiceTest {
    private fun range(from:String,through:String)=ReportDateRange(LocalDate.parse(from),LocalDate.parse(through))
    private val item=Product("item","shop","Rice","bag",1000,10.0)
    private val sale=Sale("sale","shop",listOf(CartLine(item,1)),1000,0,1000,"customer","2026-11-01T09:00:00Z")
    private val payment=CustomerRepayment("payment","shop","customer",400,"2026-11-04T09:00:00Z")
    private fun statement()=CustomerStatementService.build("shop",Customer("customer","shop","=Amina",600),listOf(sale),listOf(payment))

    @Test fun openingPeriodEventsClosingAndCurrentBalancesStayDistinct() {
        val dated=DatedCustomerStatementService.build(statement(),range("2026-11-03","2026-11-03"))
        assertEquals(1000L,dated.openingMinor)
        assertTrue(dated.events.isEmpty())
        assertEquals(1000L,dated.closingMinor)
        assertEquals(600L,dated.complete.customer.balanceMinor)
        val paid=DatedCustomerStatementService.build(statement(),range("2026-11-04","2026-11-04"))
        assertEquals(1000L,paid.openingMinor)
        assertEquals(600L,paid.events.single().second)
        assertEquals(600L,paid.closingMinor)
    }

    @Test fun watCutoffsAndFormulaSafeExportPreserveTheRecordedBasis() {
        val source=CustomerStatementService.build("shop",statement().customer,listOf(sale.copy(createdAt="2026-10-31T23:00:00Z")),listOf(payment))
        assertEquals(0L,DatedCustomerStatementService.build(source,range("2026-11-01","2026-11-01")).openingMinor)
        val csv=DatedCustomerStatementService.csv(source,range("2026-11-01","2026-11-01"))
        assertContains(csv,"\"'=Amina\"")
        assertContains(csv,"Closing debt through selected WAT date")
        assertContains(csv,"Current debt — all saved dates")
    }

    @Test fun forgedTotalsAndImpossibleRepaymentChronologyReject() {
        assertFails {DatedCustomerStatementService.build(statement().copy(creditGrantedMinor=900),range("2026-11-01","2026-11-05"))}
        val bad=CustomerStatementService.build("shop",statement().customer,listOf(sale),listOf(payment.copy(createdAt="2026-10-01T00:00:00Z")))
        assertFails {DatedCustomerStatementService.build(bad,range("2026-11-01","2026-11-05"))}
    }
}
