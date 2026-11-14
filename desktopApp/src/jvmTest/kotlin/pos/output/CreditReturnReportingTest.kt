package pos.output

import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange
import java.time.LocalDate

class CreditReturnReportingTest {
    private val customer=Customer("customer","shop","Amina",300)
    private val item=Product("soap","shop","Soap","piece",100,10.0)
    private val sale=Sale("sale","shop",listOf(CartLine(item,10)),1000,200,800,customer.id,"2026-11-01T09:00:00Z","2026-11-02")
    private val payment=CustomerRepayment("payment","shop",customer.id,200,"2026-11-03T09:00:00Z")
    private val assignment=RepaymentAllocation("assignment","shop",customer.id,payment.id,sale.id,200,"2026-11-03T10:00:00Z")
    private val reduction=CreditReturnReduction("return","shop",sale.id,customer.id,300,"2026-11-04T09:00:00Z")
    private fun statement()=CustomerStatementService.build("shop",customer,listOf(sale),listOf(payment),listOf(reduction))

    @Test fun statementsKeepReturnedCreditSeparateFromRepaymentAndCashRefund() {
        val value=statement()
        assertEquals(800L,value.creditGrantedMinor);assertEquals(200L,value.repaidMinor);assertEquals(300L,value.creditReturnedMinor)
        assertEquals(listOf("sale","payment","return"),value.events.map {it.id})
        val dated=DatedCustomerStatementService.build(value,ReportDateRange(LocalDate.parse("2026-11-03"),LocalDate.parse("2026-11-04")))
        assertEquals(800L,dated.openingMinor);assertEquals(300L,dated.closingMinor)
        assertEquals(listOf(600L,300L),dated.events.map {it.second})
        assertContains(CustomerStatementService.csv(value),"Debt reduced on return NGN")
        assertContains(DatedCustomerStatementService.csv(dated),"Credit-sale return")
    }

    @Test fun ageingAndHistoricalBalancesRespectReturnRecordedDates() {
        val current=DebtAgeingService.build("shop",listOf(customer),listOf(sale),listOf(payment),LocalDate.parse("2026-11-05"),listOf(assignment),listOf(reduction)).single()
        assertEquals(300L,current.currentDebtMinor);assertEquals(300L,current.invoiceBalances.single().returnedCreditMinor)
        fun historical(day:String)=HistoricalCreditService.build("shop",listOf(customer),listOf(sale),listOf(payment),listOf(assignment),emptyList(),LocalDate.parse(day),listOf(reduction)).rows.single()
        assertEquals(600L,historical("2026-11-03").currentDebtMinor)
        assertEquals(300L,historical("2026-11-04").currentDebtMinor)
        assertFails {DebtAgeingService.build("shop",listOf(customer),listOf(sale),listOf(payment),LocalDate.parse("2026-11-05"),listOf(assignment),listOf(reduction,reduction.copy(returnId="unknown",customerId="missing",amountMinor=0)))}
        assertFails {CustomerStatementService.build("shop",customer,listOf(sale),listOf(payment),listOf(reduction.copy(createdAt="2026-10-31T00:00:00Z")))}
    }

    @Test fun salesPerformanceSubtractsGoodsValueIncludingDebtRatherThanOnlyRefundedCash() {
        val returned=SaleReturn("return","shop",sale.id,listOf(ReturnLine(item.id,item.name,item.unit,3,100)),0,true,"Returned goods",reduction.createdAt,300)
        val range=ReportDateRange(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-05"))
        val value=ProductPerformanceService.build("shop",listOf(sale),listOf(returned),range).single()
        assertEquals(700L,value.netSalesMinor);assertEquals(300L,value.refundsMinor)
        val receipt=ReceiptService.format(Tenant("shop","Sample shop"),sale,listOf(returned))
        assertContains(receipt,"Total customer debt reduced:")
        assertContains(receipt,"Sale value after returns:")
    }
}
