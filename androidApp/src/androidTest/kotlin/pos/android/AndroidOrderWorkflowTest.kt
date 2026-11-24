package pos.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.platform.AndroidPrivateFiles
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Executes the source operations used by the Android reviews on actual native SQLite. */
@RunWith(AndroidJUnit4::class)
class AndroidOrderWorkflowTest {
    private fun fixture(block:(PosRepository)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=java.io.File(context.cacheDir,"orders-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open);AndroidPrivateFiles.install()
        try {PosRepository(directory.resolve("pos.db").path).use(block)} finally {directory.deleteRecursively()}
    }
    private fun todayPlus(days:Long)=LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(days).toString()

    @Test fun quotationOrderDepositPartialCollectionAndDeliveryPreserveCashStockAndHistory()=fixture {repo ->
        val shop=repo.createTenant("Android order shop").id
        val customer=repo.addCustomer(shop,"Amina")
        val soap=repo.addProduct(shop,"Soap","piece",50000,12.0)
        val shift=repo.openCashShift(shop,1000000)
        val issued=repo.issueQuotation(shop,listOf(QuotationLineInput(soap.id,null,4,45000)),todayPlus(7),QuotationCustomer(customer.id,customer.name),"Agreed delivery terms","quote")
        assertEquals(12L,repo.products(shop).single().exactStockScaled)
        assertEquals(1000000L,repo.expectedCash(shop,shift.id))
        val quote=repo.acceptQuotation(shop,issued.id,issued.version,"accept")
        val conversion=repo.reviewQuotationOrder(shop,quote.id,quote.version,customer.id,todayPlus(2),"Collect in two batches")
        val order=repo.convertQuotationToOrder(shop,conversion,70000,"quote-order")
        assertEquals(order,repo.convertQuotationToOrder(shop,conversion,70000,"quote-order"))
        assertEquals(1070000L,repo.expectedCash(shop,shift.id))
        var state=repo.customerOrders(shop).single()
        repo.holdCustomerOrderStock(shop,order.id,todayPlus(3),state.history.size,0,"hold")
        assertEquals(4L,repo.orderReservedQuantities(shop).getValue(soap.id))
        val first=repo.reviewCustomerOrderCollection(shop,order.id,mapOf(soap.id to 2))
        assertEquals(70000L,first.depositMinor);assertEquals(20000L,first.cashMinor)
        val collected=repo.collectCustomerOrder(shop,first,"collection-one")
        assertEquals(collected,repo.collectCustomerOrder(shop,first,"collection-one"))
        assertEquals(1090000L,repo.expectedCash(shop,shift.id));assertEquals(10L,repo.products(shop).single().exactStockScaled)
        assertEquals(2L,repo.orderReservedQuantities(shop).getValue(soap.id))
        val beforeDeliveryCash=repo.cashEntries(shop);val beforeDeliveryStock=repo.products(shop)
        val dispatchReview=repo.reviewOrderDispatch(shop,order.id,requireNotNull(collected.saleId))
        val dispatch=repo.createOrderDispatch(shop,dispatchReview,mapOf(soap.id to 2),OrderDispatchDetails("Amina","Shop 12 Market","Local rider","LOCAL-2","Two pieces handed over"),"dispatch")
        repo.recordOrderDeliveryStatus(shop,dispatch.id,dispatch.revision,"delivered","Amina","Recipient signed for two pieces","proof")
        assertEquals(beforeDeliveryCash,repo.cashEntries(shop));assertEquals(beforeDeliveryStock,repo.products(shop))
        val second=repo.reviewCustomerOrderCollection(shop,order.id,mapOf(soap.id to 2))
        assertEquals(0L,second.depositMinor);assertEquals(90000L,second.cashMinor)
        repo.collectCustomerOrder(shop,second,"collection-two")
        state=repo.customerOrders(shop).single();assertTrue(state.completed)
        assertEquals(1180000L,repo.expectedCash(shop,shift.id));assertEquals(8L,repo.products(shop).single().exactStockScaled)
        val snapshot=repo.exportTenantSnapshot(shop)
        val restored=repo.restoreTenantSnapshot(snapshot,"Recovered orders")
        assertEquals(repo.quotationOrderLinks(shop),repo.quotationOrderLinks(restored.id))
        assertEquals(repo.orderDispatches(shop),repo.orderDispatches(restored.id))
        assertEquals(1180000L,repo.expectedCash(restored.id,shift.id))
    }

    @Test fun cancellationRefundAndStaleReviewsKeepOriginalFinancialEvents()=fixture {repo ->
        val shop=repo.createTenant("Android order shop").id
        val customer=repo.addCustomer(shop,"Amina")
        val rice=repo.addProduct(shop,"Rice","kg",100000,10.0,quantityPrecision=3)
        val shift=repo.openCashShift(shop,1000000)
        val order=repo.createCustomerOrder(shop,customer.id,"In shop",null,"Measured order",listOf(QuotationLineInput(rice.id,null,1250,100000)),"order")
        assertEquals(10000L,repo.products(shop).single().exactStockScaled)
        val state=repo.customerOrders(shop).single()
        repo.recordCustomerOrderDeposit(shop,order.id,50000,state.history.size,"deposit")
        val unchanged=repo.exportTenantSnapshot(shop)
        assertThrows(IllegalArgumentException::class.java){repo.recordCustomerOrderDeposit(shop,order.id,1000,state.history.size,"stale-deposit")}
        assertThrows(IllegalArgumentException::class.java){repo.cancelCustomerOrder(shop,order.id,state.history.size,0,"Stale cancellation","stale-cancel")}
        assertEquals(unchanged,repo.exportTenantSnapshot(shop))
        val current=repo.customerOrders(shop).single()
        repo.cancelCustomerOrder(shop,order.id,current.history.size,50000,"Customer cancelled remaining goods","cancel")
        assertEquals(1000000L,repo.expectedCash(shop,shift.id));assertEquals(10000L,repo.products(shop).single().exactStockScaled)
        assertTrue(repo.customerOrders(shop).single().cancelled)
        assertEquals(listOf("deposit","cancelled"),repo.customerOrders(shop).single().history.map {it.kind})
        assertTrue(repo.sales(shop).isEmpty())
        val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop),"Recovered cancelled order")
        assertTrue(repo.customerOrders(restored.id).single().cancelled)
    }

    @Test fun quotationRevisionsAndCashConversionKeepImmutableAgreedPrices()=fixture {repo ->
        val shop=repo.createTenant("Android quotation shop").id
        val item=repo.addProduct(shop,"Soap","piece",50000,5.0)
        val shift=repo.openCashShift(shop,0)
        val issued=repo.issueQuotation(shop,listOf(QuotationLineInput(item.id,null,2,49000)),todayPlus(2),notes="First offered terms",operationId="quote")
        val revised=repo.reviseQuotation(shop,issued.id,listOf(QuotationLineInput(item.id,null,2,45000)),todayPlus(3),null,"Revised agreed terms","Customer negotiated price",issued.version,"revise")
        val accepted=repo.acceptQuotation(shop,revised.id,revised.version,"accept")
        val review=repo.quotationConversionReview(shop,accepted.id,accepted.version)
        assertThrows(IllegalArgumentException::class.java){repo.convertQuotation(shop,accepted.id,accepted.version,review.token,80000,"short")}
        val sale=repo.convertQuotation(shop,accepted.id,accepted.version,review.token,90000,"convert")
        assertEquals(90000L,sale.totalMinor);assertEquals(90000L,repo.expectedCash(shop,shift.id))
        assertEquals(3L,repo.products(shop).single().exactStockScaled)
        val events=repo.quotationEvents(shop,issued.id)
        assertEquals(49000L,events.first().snapshot.lines.single().agreedUnitPriceMinor)
        assertEquals(QuotationStatus.CONVERTED,repo.quotations(shop).single().status)
        assertEquals(sale.id,repo.convertQuotation(shop,accepted.id,accepted.version,review.token,90000,"convert").id)
    }
}
