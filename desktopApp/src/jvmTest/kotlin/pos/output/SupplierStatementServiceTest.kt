package pos.output

import kotlin.test.*
import pos.domain.StockReceipt
import pos.domain.SupplierPayment
import java.time.LocalDate

class SupplierStatementServiceTest {
    private val name="=Amina supplies"
    private fun receipt(id:String,date:String,cost:Long=1000,paid:Long=200,supplier:String=name,tenant:String="shop")=
        StockReceipt(id,tenant,"item","Rice","bag",1,cost,cost,paid,supplier,date)
    private fun payment(id:String,receipt:String,date:String,amount:Long=300,supplier:String=name,tenant:String="shop")=
        SupplierPayment(id,tenant,receipt,supplier,amount,date)
    private val from=LocalDate.parse("2026-11-02")
    private val through=LocalDate.parse("2026-11-03")
    private fun build(receipts:List<StockReceipt>,payments:List<SupplierPayment> = emptyList(),supplier:String=name)=
        SupplierStatementService.build("shop",supplier,receipts,payments,from,through)

    @Test fun reconcilesOpeningPurchasesInitialCashLaterPaymentsClosingAndCurrent() {
        val receipts=listOf(receipt("opening","2026-11-01T09:00:00Z"),receipt("in-range","2026-11-02T10:00:00Z",2000,500),receipt("future","2026-11-05T09:00:00Z",700,0))
        val payments=listOf(payment("old-payment","opening","2026-11-01T10:00:00Z",100),payment("range-payment","opening","2026-11-03T09:00:00Z",300),payment("future-payment","in-range","2026-11-06T09:00:00Z",200))
        val statement=build(receipts,payments)
        assertEquals(700L,statement.openingMinor)
        assertEquals(2000L,statement.purchasesMinor)
        assertEquals(500L,statement.paidAtReceiptMinor)
        assertEquals(300L,statement.laterPaymentsMinor)
        assertEquals(1900L,statement.closingMinor)
        assertEquals(2400L,statement.currentOutstandingMinor)
        assertEquals(listOf("in-range","range-payment"),statement.events.map {it.id})
        assertEquals(listOf(2200L,1900L),statement.events.map {it.balanceAfterMinor})
    }

    @Test fun datesUseInclusiveLagosCalendarDaysAndNotUtcMidnight() {
        val receipts=listOf(receipt("before","2026-11-01T22:59:59.999999999Z",100,0),receipt("start","2026-11-01T23:00:00Z",200,0),receipt("last","2026-11-03T22:59:59.999999999Z",300,0),receipt("after","2026-11-03T23:00:00Z",400,0))
        val statement=build(receipts)
        assertEquals(100L,statement.openingMinor)
        assertEquals(listOf("start","last"),statement.events.map {it.id})
        assertEquals(600L,statement.closingMinor)
        assertEquals(1000L,statement.currentOutstandingMinor)
    }

    @Test fun exactRecordedNamesStaySeparateAndFullyPaidReceiptsRemainInStatements() {
        val mine=receipt("mine","2026-11-02T09:00:00Z",1000,1000)
        val similar=receipt("similar","2026-11-02T09:00:00Z",900,0,name.lowercase())
        val other=receipt("other","2026-11-02T09:00:00Z",800,0,"Other supplier")
        val statement=build(listOf(mine,similar,other))
        assertEquals(listOf("mine"),statement.events.map {it.id})
        assertEquals(1000L,statement.purchasesMinor)
        assertEquals(1000L,statement.paidAtReceiptMinor)
        assertEquals(0L,statement.currentOutstandingMinor)
        assertTrue(build(listOf(mine),supplier="Missing supplier").events.isEmpty())
    }

    @Test fun rejectsForeignDuplicateMissingMismatchedOrOverpaidHistory() {
        val receipt=receipt("receipt","2026-11-02T09:00:00Z")
        val payment=payment("payment",receipt.id,"2026-11-03T09:00:00Z")
        assertFails {build(listOf(receipt.copy(tenantId="other")))}
        assertFails {build(listOf(receipt),listOf(payment.copy(tenantId="other")))}
        assertFails {build(listOf(receipt,receipt))}
        assertFails {build(listOf(receipt),listOf(payment,payment))}
        assertFails {build(listOf(receipt),listOf(payment.copy(receiptId="missing")))}
        assertFails {build(listOf(receipt),listOf(payment.copy(supplierName="Different")))}
        assertFails {build(listOf(receipt),listOf(payment.copy(amountMinor=801)))}
        assertFails {build(listOf(receipt),listOf(payment.copy(createdAt="2026-11-01T09:00:00Z")))}
        assertFails {build(listOf(receipt.copy(createdAt="invalid")))}
        assertFails {SupplierStatementService.build("shop",name,listOf(receipt),emptyList(),through,from)}
    }

    @Test fun sameInstantSortsReceiptBeforePaymentRegardlessOfInputOrder() {
        val instant="2026-11-02T09:00:00Z"
        val statement=build(listOf(receipt("z-receipt",instant)),listOf(payment("a-payment","z-receipt",instant,800)))
        assertEquals(listOf("z-receipt","a-payment"),statement.events.map {it.id})
        assertEquals(listOf(800L,0L),statement.events.map {it.balanceAfterMinor})
    }

    @Test fun csvPreservesNumericAmountsProtectsTextAndRejectsTamperedTotals() {
        val statement=build(listOf(receipt("=receipt","2026-11-02T09:00:00Z",1001,201)))
        val csv=SupplierStatementService.csv(statement)
        assertContains(csv,"\"'=Amina supplies\"")
        assertContains(csv,"\"'=receipt\"")
        assertContains(csv,"\"10.01\",\"2.01\",\"0.00\",\"8.00\"")
        assertContains(csv,"Current outstanding — all recorded dates")
        assertFails {SupplierStatementService.csv(statement.copy(closingMinor=1))}
        assertFails {SupplierStatementService.csv(statement.copy(events=emptyList()))}
    }

    @Test fun emptyPeriodCarriesItsOpeningBalanceWithoutInventingTransactions() {
        val statement=build(listOf(receipt("old","2026-11-01T09:00:00Z")))
        assertTrue(statement.events.isEmpty())
        assertEquals(800L,statement.openingMinor)
        assertEquals(800L,statement.closingMinor)
        assertEquals(0L,statement.purchasesMinor)
    }
}
