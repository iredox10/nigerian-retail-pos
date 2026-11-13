package pos.output

import kotlin.test.*
import pos.domain.*
import java.time.LocalDate

class DebtAgeingBandsTest {
    private val today=LocalDate.parse("2026-11-13")
    private fun sale(id:String,daysLate:Long?,amount:Long=10_000)=Sale(
        id,"shop",listOf(CartLine(Product(id,"shop","Rice","bag",amount,100.0),1)),
        amount,0,amount,"customer","2026-01-01T09:00:00Z",
        daysLate?.let { today.minusDays(it).toString() },
    )
    private fun build(sales:List<Sale>,payments:List<CustomerRepayment> = emptyList(),allocations:List<RepaymentAllocation> = emptyList()):CreditReviewRow {
        val balance=sales.sumOf { it.creditMinor }-payments.sumOf { it.amountMinor }
        return DebtAgeingService.build("shop",listOf(Customer("customer","shop","=Amina",balance)),sales,payments,today,allocations).single()
    }

    @Test fun boundariesUseCalendarDaysAndDueTodayIsNotOverdue() {
        val cases=mapOf(-2L to DebtAgeBand.NOT_LATE,0L to DebtAgeBand.NOT_LATE,1L to DebtAgeBand.LATE_1_7,7L to DebtAgeBand.LATE_1_7,8L to DebtAgeBand.LATE_8_30,30L to DebtAgeBand.LATE_8_30,31L to DebtAgeBand.LATE_31_60,60L to DebtAgeBand.LATE_31_60,61L to DebtAgeBand.LATE_61_90,90L to DebtAgeBand.LATE_61_90,91L to DebtAgeBand.LATE_OVER_90,366L to DebtAgeBand.LATE_OVER_90)
        cases.forEach { (days,band) -> assertEquals(band,DebtAgeBand.classify(today.minusDays(days).toString(),today)) }
        assertEquals(DebtAgeBand.UNDATED,DebtAgeBand.classify(null,today))
        assertFails { DebtAgeBand.classify("bad-date",today) }
    }

    @Test fun allBandsReconcileToDebtAndOnlyDatedLateAmountsEnterOverdue() {
        val row=build(listOf(sale("future",-1),sale("today",0),sale("one",1),sale("eight",8),sale("thirtyone",31),sale("sixtyone",61),sale("old",91),sale("unknown",null)))
        val amounts=requireNotNull(row.ageing)
        assertEquals(80_000L,amounts.totalMinor)
        assertEquals(row.currentDebtMinor,amounts.totalMinor)
        assertEquals(20_000L,amounts.amounts[DebtAgeBand.NOT_LATE])
        assertEquals(10_000L,amounts.amounts[DebtAgeBand.UNDATED])
        assertEquals(50_000L,amounts.overdueMinor)
        assertEquals(row.preciseOverdueMinor,amounts.overdueMinor)
    }

    @Test fun partialAssignedPaymentReducesOnlyItsReceiptBandAndSettledReceiptLeavesZero() {
        val sales=listOf(sale("old",100),sale("recent",3),sale("future",-5))
        val payment=CustomerRepayment("payment","shop","customer",15_000,"2026-11-12T09:00:00Z")
        val allocations=listOf(
            RepaymentAllocation("a","shop","customer","payment","old",10_000,"2026-11-12T09:00:00Z"),
            RepaymentAllocation("b","shop","customer","payment","recent",5_000,"2026-11-12T09:00:00Z"),
        )
        val row=build(sales,listOf(payment),allocations)
        assertEquals(0L,row.ageing!!.amounts[DebtAgeBand.LATE_OVER_90])
        assertEquals(5_000L,row.ageing!!.amounts[DebtAgeBand.LATE_1_7])
        assertEquals(10_000L,row.ageing!!.amounts[DebtAgeBand.NOT_LATE])
        assertEquals(15_000L,row.ageing!!.totalMinor)
        assertEquals(5_000L,row.preciseOverdueMinor)
    }

    @Test fun unassignedPaymentSuppressesEveryExactBandAndCsvLeavesAmountsBlank() {
        val payment=CustomerRepayment("payment","shop","customer",5_000,"2026-11-12T09:00:00Z")
        val row=build(listOf(sale("old",100)),listOf(payment))
        assertNull(row.ageing)
        assertNull(row.preciseOverdueMinor)
        assertEquals(5_000L,row.currentDebtMinor)
        val csv=DebtAgeingService.csv("shop",listOf(row),today)
        assertContains(csv,"\"false\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"")
        assertContains(csv,"\"'=Amina\"")
        assertContains(csv,"Current saved balances; not a historical balance")
    }

    @Test fun exportUsesAllCustomersAndRejectsForeignTenantDuplicateRowsAndRelabeledDates() {
        val row=build(listOf(sale("old",7)))
        val csv=DebtAgeingService.csv("shop",listOf(row),today)
        assertContains(csv,"Over 90 days late NGN")
        assertContains(csv,"\"true\",\"0.00\",\"100.00\"")
        assertFails { DebtAgeingService.csv("other-shop",listOf(row),today) }
        assertFails { DebtAgeingService.csv("shop",listOf(row,row),today) }
        assertFails { DebtAgeingService.csv("shop",listOf(row),today.plusDays(1)) }
    }

    @Test fun reviewDateOnlyReclassifiesCurrentBalancesAcrossLeapDay() {
        val due="2024-02-29"
        assertEquals(DebtAgeBand.LATE_1_7,DebtAgeBand.classify(due,LocalDate.parse("2024-03-07")))
        assertEquals(DebtAgeBand.LATE_8_30,DebtAgeBand.classify(due,LocalDate.parse("2024-03-08")))
        val sales=listOf(sale("receipt",1))
        val row=DebtAgeingService.build("shop",listOf(Customer("customer","shop","Amina",10_000)),sales,emptyList(),today.minusDays(2)).single()
        assertEquals(10_000L,row.ageing!!.amounts[DebtAgeBand.NOT_LATE])
        assertEquals(10_000L,row.currentDebtMinor)
    }
}
