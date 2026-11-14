package pos.output

import kotlin.test.*
import pos.domain.*
import java.time.LocalDate

class PurchaseOrderCsvTest {
    private val line=PurchaseOrderLine("line","item","+Rice","kg","kg",1375,3,3,1,30000)
    private val order=PurchaseOrder("order","shop","supplier","=Supplier",1,PurchaseOrderStatus.APPROVED,2,"2026-11-13T23:00:00Z","2026-11-14T01:00:00Z",null,null,"@Order note",listOf(line))
    private val receipt=StockReceipt("receipt","shop","item","+Rice","kg",375,30000,11250,5000,"Renamed supplier","2026-11-14T02:00:00Z",quantityPrecision=3,basePrecision=3)
    private val delivery=PurchaseOrderDelivery("delivery","shop","order",2,"2026-11-14T02:00:00Z",listOf(PurchaseOrderDeliveryLine("line","receipt",375,5000,0)))
    @Test fun exportUsesMeasuredQuantitiesActualReceiptCostsAndSpreadsheetSafeNames() {
        val csv=PurchaseOrderCsv.order(order,listOf(delivery),listOf(receipt))
        assertTrue(csv.contains("1.375"));assertTrue(csv.contains("0.375"));assertTrue(csv.contains("300.00"));assertTrue(csv.contains("112.50"));assertTrue(csv.contains("50.00"))
        assertTrue(csv.contains("'=Supplier"));assertTrue(csv.contains("'+Rice"));assertTrue(csv.contains("'@Order note"))
        assertTrue(csv.contains("not payment status"))
    }
    @Test fun foreignDuplicateOrMismatchedReceiptsCannotFabricateOrderReceiptTotals() {
        assertFails {PurchaseOrderCsv.order(order,listOf(delivery.copy(tenantId="other")),listOf(receipt))}
        assertFails {PurchaseOrderCsv.order(order,listOf(delivery,delivery),listOf(receipt))}
        assertFails {PurchaseOrderCsv.order(order,listOf(delivery),emptyList())}
        assertFails {PurchaseOrderCsv.order(order,listOf(delivery),listOf(receipt.copy(unitCostMinor=1,totalCostMinor=0,paidCashMinor=0)))}
    }
    @Test fun createdDateFilteringUsesInclusiveWatDaysAndRejectsForeignOrDuplicateOrders() {
        val from=LocalDate.parse("2026-11-14");val through=from
        val before=order.copy(id="before",createdAt="2026-11-13T22:59:59Z")
        val last=order.copy(id="last",createdAt="2026-11-14T22:59:59Z")
        val after=order.copy(id="after",createdAt="2026-11-14T23:00:00Z")
        assertEquals(listOf("order","last"),PurchaseOrderCsv.createdInRange("shop",listOf(before,order,last,after),from,through).map {it.id})
        assertFails {PurchaseOrderCsv.createdInRange("shop",listOf(order,order),from,through)}
        assertFails {PurchaseOrderCsv.createdInRange("other",listOf(order),from,through)}
    }
}
