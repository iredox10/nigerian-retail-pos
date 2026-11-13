package pos.output

import kotlin.test.*
import pos.domain.*
import java.time.LocalDate

class HistoricalCreditServiceTest {
    private val item=Product("item","shop","Rice","bag",1000,10.0)
    private fun sale(time:String="2026-11-01T09:00:00Z")=Sale("sale","shop",listOf(CartLine(item,1)),1000,0,1000,"customer",time,"2026-11-02")
    private val payment=CustomerRepayment("payment","shop","customer",400,"2026-11-04T09:00:00Z")
    private val assignment=RepaymentAllocation("assignment","shop","customer","payment","sale",400,"2026-11-05T09:00:00Z")
    private val correction=AllocationReversal("correction","shop","customer","assignment","Wrong receipt","2026-11-06T09:00:00Z")
    private fun review(day:String,corrections:List<AllocationReversal> = emptyList())=HistoricalCreditService.build("shop",listOf(Customer("customer","shop","=Amina",600)),listOf(sale()),listOf(payment),listOf(assignment),corrections,LocalDate.parse(day))

    @Test fun laterRepaymentsAndAssignmentsAreExcludedAtTheirActualRecordedDates() {
        val beforePayment=review("2026-11-03").rows.single()
        assertEquals(1000L,beforePayment.currentDebtMinor)
        assertEquals(1000L,beforePayment.preciseOverdueMinor)
        val unassigned=review("2026-11-04").rows.single()
        assertEquals(600L,unassigned.currentDebtMinor)
        assertEquals(400L,unassigned.unallocatedRepaymentsMinor)
        assertNull(unassigned.ageing)
        val assigned=review("2026-11-05").rows.single()
        assertEquals(600L,assigned.preciseOverdueMinor)
        assertEquals(0L,assigned.unallocatedRepaymentsMinor)
        assertTrue(review("2026-10-31").rows.isEmpty())
    }

    @Test fun correctionDoesNotRewriteHowReceiptWasCoveredBeforeItWasRecorded() {
        assertEquals(600L,review("2026-11-05",listOf(correction)).rows.single().preciseOverdueMinor)
        val undone=review("2026-11-06",listOf(correction)).rows.single()
        assertEquals(600L,undone.currentDebtMinor)
        assertEquals(400L,undone.unallocatedRepaymentsMinor)
        assertNull(undone.preciseOverdueMinor)
    }

    @Test fun endOfDayBoundaryUsesLagosRatherThanUtc() {
        fun at(time:String)=HistoricalCreditService.build("shop",listOf(Customer("customer","shop","Amina",1000)),listOf(sale(time)),emptyList(),emptyList(),emptyList(),LocalDate.parse("2026-11-01"))
        assertEquals(1000L,at("2026-11-01T22:59:59.999999999Z").rows.single().currentDebtMinor)
        assertTrue(at("2026-11-01T23:00:00Z").rows.isEmpty())
    }

    @Test fun settledCustomersCanStillHaveHistoricalDebt() {
        val full=payment.copy(amountMinor=1000)
        val result=HistoricalCreditService.build("shop",listOf(Customer("customer","shop","Amina",0)),listOf(sale()),listOf(full),emptyList(),emptyList(),LocalDate.parse("2026-11-03"))
        assertEquals(1000L,result.rows.single().currentDebtMinor)
    }

    @Test fun invalidCurrentHistoryForeignCorrectionsAndImpossibleDatesAreRejected() {
        val customer=Customer("customer","shop","Amina",600)
        fun build(customers:List<Customer> = listOf(customer), assignments:List<RepaymentAllocation> = listOf(assignment), corrections:List<AllocationReversal> = emptyList())=
            HistoricalCreditService.build("shop",customers,listOf(sale()),listOf(payment),assignments,corrections,LocalDate.parse("2026-11-03"))
        assertFails {build(customers=listOf(customer.copy(balanceMinor=601)))}
        assertFails {build(assignments=listOf(assignment.copy(createdAt="2026-11-03T00:00:00Z")))}
        assertFails {build(corrections=listOf(correction.copy(tenantId="other")))}
        assertFails {build(corrections=listOf(correction.copy(createdAt="2026-11-01T00:00:00Z")))}
        assertFails {build(corrections=listOf(correction,correction))}
        assertFails {build(corrections=listOf(correction.copy(allocationId="missing")))}
    }

    @Test fun exportsStateTheHistoricalBasisAndKeepSpreadsheetProtection() {
        val csv=HistoricalCreditService.csv(review("2026-11-03"))
        assertContains(csv,"Debt at selected date NGN")
        assertContains(csv,"Recorded history through 2026-11-03 WAT; customer profile details are current")
        assertContains(csv,"\"'=Amina\"")
        assertFalse(csv.contains("not a historical balance"))
    }
}
