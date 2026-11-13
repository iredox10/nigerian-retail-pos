package pos.output

import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange
import java.time.LocalDate

class ProductPerformanceServiceTest {
    private val item=Product("item","shop","=Malt","bottle",100,100.0)
    private val line=CartLine(item,2,"carton","carton",2200,24,200,"Agreed price")
    private val sale=Sale("sale","shop",listOf(line),4000,4000,0,null,"2026-11-01T09:00:00Z")
    private val refund=SaleReturn("refund","shop",sale.id,listOf(ReturnLine(item.id,item.name,"carton",1,2000,"carton",24,"bottle")),2000,true,"Unopened","2026-11-03T09:00:00Z")
    private fun range(from:String="2026-11-01",through:String="2026-11-03")=ReportDateRange(LocalDate.parse(from),LocalDate.parse(through))

    @Test fun packsAndDiscountsReconcileUsingHistoricalPricesAndBaseUnits() {
        val row=ProductPerformanceService.build("shop",listOf(sale),listOf(refund),range()).single()
        assertEquals(48L,row.soldBaseQuantity)
        assertEquals(24L,row.returnedBaseQuantity)
        assertEquals(24L,row.netBaseQuantity)
        assertEquals(4000L,row.salesMinor)
        assertEquals(2000L,row.refundsMinor)
        assertEquals(2000L,row.netSalesMinor)
    }

    @Test fun returnsUseTheirOwnDateAndCanMakeThePeriodNegative() {
        val row=ProductPerformanceService.build("shop",listOf(sale),listOf(refund),range("2026-11-03","2026-11-03")).single()
        assertEquals(0L,row.soldBaseQuantity)
        assertEquals(-24L,row.netBaseQuantity)
        assertEquals(-2000L,row.netSalesMinor)
        val csv=ProductPerformanceService.csv("shop",listOf(sale),listOf(refund),range())
        assertContains(csv,"\"'=Malt\"")
        assertContains(csv,"\"40.00\",\"20.00\",\"20.00\"")
    }

    @Test fun completeHistoryIsRequiredAndForeignDuplicateOrExcessReturnsReject() {
        assertFails {ProductPerformanceService.build("other",listOf(sale),listOf(refund),range())}
        assertFails {ProductPerformanceService.build("shop",emptyList(),listOf(refund),range())}
        assertFails {ProductPerformanceService.build("shop",listOf(sale,sale),listOf(refund),range())}
        assertFails {ProductPerformanceService.build("shop",listOf(sale),listOf(refund,refund),range())}
        assertFails {ProductPerformanceService.build("shop",listOf(sale),listOf(refund,refund.copy(id="second"),refund.copy(id="third")),range())}
        val wrongLine=refund.lines.single().copy(unitPriceMinor=2200)
        assertFails {ProductPerformanceService.build("shop",listOf(sale),listOf(refund.copy(lines=listOf(wrongLine),refundMinor=2200)),range())}
    }

    @Test fun creditSalesCountAsSalesAndNotAsCashOrProfit() {
        val credit=sale.copy(cashMinor=0,creditMinor=4000,customerId="customer")
        assertEquals(4000L,ProductPerformanceService.build("shop",listOf(credit),emptyList(),range()).single().salesMinor)
        assertFails {ProductPerformanceService.build("shop",listOf(credit),listOf(refund),range())}
        val before=sale.copy(createdAt="2026-10-31T22:59:59Z")
        assertTrue(ProductPerformanceService.build("shop",listOf(before),emptyList(),range()).isEmpty())
        val start=sale.copy(createdAt="2026-10-31T23:00:00Z")
        assertEquals(1,ProductPerformanceService.build("shop",listOf(start),emptyList(),range()).size)
    }
}
