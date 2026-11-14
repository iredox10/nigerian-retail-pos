package pos.domain

import kotlin.test.*

class PurchaseOrderTest {
    private fun line()=PurchaseOrderLine("line","rice","Rice","kg","kg",1375,3,3,1,30000)
    @Test fun exactMeasuredAndWholePackSnapshotsKeepTheirUnitsAndEstimatedCosts() {
        val rice=line()
        assertEquals("1.375",rice.quantityLabel)
        assertEquals(1375L,rice.baseQuantity)
        assertEquals(41250L,rice.estimatedTotalMinor)
        val carton=PurchaseOrderLine("carton","malt","Malt","bottle","carton",2,0,0,24,1000000,"unit")
        assertEquals(48L,carton.baseQuantity)
        assertFails {carton.copy(quantityPrecision=3)}
        assertFails {rice.copy(basePrecision=2)}
        assertFails {rice.copy(quantity=0)}
    }
    @Test fun orderStatesAndCancelledHistoryCannotPretendToBePayments() {
        val draft=PurchaseOrder("order","shop","supplier","Supplier",1,PurchaseOrderStatus.DRAFT,1,"2026-11-14T00:00:00Z",null,null,null,"",listOf(line()))
        assertEquals(41250L,draft.estimatedTotalMinor)
        assertFails {draft.copy(status=PurchaseOrderStatus.APPROVED)}
        assertFails {draft.copy(status=PurchaseOrderStatus.CANCELLED,cancelledAt="2026-11-14T01:00:00Z",cancellationReason="")}
        assertFails {draft.copy(lines=listOf(line(),line()))}
        assertFails {draft.copy(notes="Bad\ncontrol")}
    }
    @Test fun estimatedCostOverflowAndMalformedDeliveryRequestsAreRejected() {
        assertFails {line().copy(unitCostMinor=Long.MAX_VALUE)}
        assertFails {PurchaseDeliveryInput("line",0,0,0)}
        assertFails {PurchaseDeliveryInput("line",1,-1,0)}
        assertFails {PurchaseDeliveryInput("line",1,0,-1)}
    }
}
