package pos.output

import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange
import java.time.LocalDate

class StaffPerformanceServiceTest {
    private val item=Product("item","shop","Soap","piece",100,10.0)
    private val sale=Sale("sale","shop",listOf(CartLine(item,2)),200,200,0,null,"2026-11-01T09:00:00Z")
    private val refund=SaleReturn("refund","shop",sale.id,listOf(ReturnLine(item.id,item.name,item.unit,1,100)),100,true,"Unopened","2026-11-02T09:00:00Z")
    private val range=ReportDateRange(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-02"))
    private val cashier=AuditActor("shop","cashier","=Amina",StaffRole.CASHIER)
    private val manager=AuditActor("shop","manager","Manager",StaffRole.MANAGER)
    private val events=listOf(AuditEvent("a","shop","sale.completed",sale.id,sale.createdAt,cashier),AuditEvent("b","shop","sale.returned",refund.id,refund.createdAt,manager))

    @Test fun refundRecorderIsSeparateFromOriginalSalespersonAndRowsReconcile() {
        val rows=StaffPerformanceService.build("shop",listOf(sale),listOf(refund),events,range)
        assertEquals(200L,rows.sumOf {it.salesMinor})
        assertEquals(100L,rows.sumOf {it.refundsMinor})
        assertEquals(1L,rows.single {it.actor==cashier}.salesCount)
        assertEquals(0L,rows.single {it.actor==cashier}.refundsCount)
        assertEquals(1L,rows.single {it.actor==manager}.refundsCount)
    }

    @Test fun legacyActionsRemainUnknownAndExportsRetainSnapshotsAndFormulaProtection() {
        assertNull(StaffPerformanceService.build("shop",listOf(sale),emptyList(),emptyList(),range).single().actor)
        val csv=StaffPerformanceService.csv("shop",listOf(sale),listOf(refund),events,range)
        assertContains(csv,"\"'=Amina\"")
        assertContains(csv,"\"CASHIER\"")
        assertContains(csv,"Sales after discounts NGN")
    }

    @Test fun repeatedForeignOrMissingSourcesRejectRatherThanDoubleCount() {
        assertFails {StaffPerformanceService.build("other",listOf(sale),listOf(refund),events,range)}
        assertFails {StaffPerformanceService.build("shop",listOf(sale),listOf(refund),events+events.first(),range)}
        assertFails {StaffPerformanceService.build("shop",listOf(sale),listOf(refund),events+events.first().copy(id="duplicate-source"),range)}
        assertFails {StaffPerformanceService.build("shop",listOf(sale),emptyList(),events,range)}
    }
}
