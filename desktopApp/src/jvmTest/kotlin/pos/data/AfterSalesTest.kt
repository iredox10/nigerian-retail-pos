package pos.data

import kotlin.test.*
import pos.domain.CartLine
import java.nio.file.Files

class AfterSalesTest {
    @Test fun partialAndDamagedReturnsPreserveSaleHistoryAndNeverOverReturn() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val shift=db.openCashShift(t.id,0)
            val sale=db.checkout(t.id,listOf(CartLine(p,3)),150000)
            val returned=db.returnItems(t.id,sale.id,mapOf(p.id to 1),true,"Customer changed mind","return-1")
            assertEquals(50000L,returned.refundMinor)
            assertEquals(8.0,db.products(t.id).single().stock)
            assertEquals(2,db.returnableQuantities(t.id,sale.id)[p.id])
            assertEquals(returned,db.returnItems(t.id,sale.id,mapOf(p.id to 1),true,"Customer changed mind","return-1"))
            db.returnItems(t.id,sale.id,mapOf(p.id to 2),false,"Damaged packaging")
            assertEquals(8.0,db.products(t.id).single().stock)
            assertEquals(0L,db.expectedCash(t.id,shift.id))
            assertEquals(150000L,db.sales(t.id).single().totalMinor)
            val pending=db.pendingSyncCount(t.id)
            assertFailsWith<IllegalArgumentException> { db.returnItems(t.id,sale.id,mapOf(p.id to 1),true,"Too many") }
            assertEquals(pending,db.pendingSyncCount(t.id))
            assertEquals(2,db.saleReturns(t.id).size)
        }
    }
    @Test fun failedReturnAndDeniedCreditReturnLeaveAllLedgersUnchanged() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val c=db.addCustomer(t.id,"Grace")
            val credit=db.checkout(t.id,listOf(CartLine(p,1)),0,c.id)
            val cash=db.checkout(t.id,listOf(CartLine(p,1)),50000)
            val before=db.products(t.id).single().stock
            assertFailsWith<IllegalArgumentException> { db.returnItems(t.id,cash.id,mapOf(p.id to 1),true,"Return") }
            val shift=db.openCashShift(t.id,0)
            val pending=db.pendingSyncCount(t.id)
            db.setPermissionCheck {_,permission -> require(permission!=pos.domain.StaffPermission.MANAGE_CUSTOMERS) {"Denied customer debt corrections"} }
            assertFailsWith<IllegalArgumentException> { db.returnItems(t.id,credit.id,mapOf(p.id to 1),true,"Return") }
            db.setPermissionCheck(null)
            assertFailsWith<IllegalArgumentException> { db.returnItems(t.id,cash.id,mapOf(p.id to 1),true,"Return") }
            assertEquals(before,db.products(t.id).single().stock)
            assertEquals(0L,db.expectedCash(t.id,shift.id))
            assertEquals(pending,db.pendingSyncCount(t.id))
            assertTrue(db.saleReturns(t.id).isEmpty())
        }
    }
    @Test fun foreignReceiptAndOriginalProductCannotBeUsedForReturnOrSupplierPayment() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A"); val b=db.createTenant("B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,10.0)
            val sale=db.checkout(a.id,listOf(CartLine(p,1)),50000)
            val purchase=db.receiveStock(a.id,p.id,2,10000,0,"Supplier")
            db.openCashShift(b.id,100000)
            assertFailsWith<IllegalArgumentException> { db.returnItems(b.id,sale.id,mapOf(p.id to 1),true,"Wrong shop") }
            assertFailsWith<IllegalArgumentException> { db.returnableQuantities(b.id,sale.id) }
            assertFailsWith<IllegalArgumentException> { db.saleReturns(b.id,sale.id) }
            assertFailsWith<IllegalArgumentException> { db.paySupplierReceipt(b.id,purchase.id,10000) }
            assertFailsWith<IllegalArgumentException> { db.supplierOutstanding(b.id,purchase.id) }
            assertTrue(db.saleReturns(a.id).isEmpty())
            assertEquals(20000L,db.supplierOutstanding(a.id,purchase.id))
        }
    }
    @Test fun supplierPaymentsPreservePurchaseSnapshotAndRejectOverpayAndMismatchedRetry() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); val p=db.addProduct(t.id,"Malt","bottle",50000,0.0)
            val purchase=db.receiveStock(t.id,p.id,5,10000,0,"Supplier")
            val shift=db.openCashShift(t.id,100000)
            val paid=db.paySupplierReceipt(t.id,purchase.id,20000,"supplier-1")
            assertEquals(paid,db.paySupplierReceipt(t.id,purchase.id,20000,"supplier-1"))
            assertEquals(30000L,db.supplierOutstanding(t.id,purchase.id))
            assertEquals(50000L,db.stockReceipts(t.id).single().unpaidMinor)
            assertEquals(80000L,db.expectedCash(t.id,shift.id))
            assertFailsWith<IllegalArgumentException> { db.paySupplierReceipt(t.id,purchase.id,10000,"supplier-1") }
            assertFailsWith<IllegalArgumentException> { db.paySupplierReceipt(t.id,purchase.id,30001) }
            db.paySupplierReceipt(t.id,purchase.id,30000)
            assertEquals(0L,db.supplierOutstanding(t.id,purchase.id))
        }
    }
    @Test fun afterSalesPersistenceAndRetryWorkAfterRebootAndShiftClosure() {
        val path=Files.createTempDirectory("pos-after-sales-").resolve("shop.db").toString()
        var tenant="";var product="";var saleId="";var receipt="";var shiftId=""
        PosRepository(path).use { db ->
            val t=db.createTenant("Shop");tenant=t.id
            val p=db.addProduct(t.id,"Malt","bottle",50000,10.0); product=p.id
            shiftId=db.openCashShift(t.id,100000).id
            saleId=db.checkout(t.id,listOf(CartLine(p,1)),50000).id
            receipt=db.receiveStock(t.id,p.id,2,10000,0,"Supplier").id
            db.returnItems(t.id,saleId,mapOf(p.id to 1),true,"Return","stable-return")
            db.paySupplierReceipt(t.id,receipt,10000,"stable-supplier")
            db.closeCashShift(t.id,shiftId,90000)
        }
        PosRepository(path).use { db ->
            val pending=db.pendingSyncCount(tenant)
            db.returnItems(tenant,saleId,mapOf(product to 1),true,"Return","stable-return")
            db.paySupplierReceipt(tenant,receipt,10000,"stable-supplier")
            assertEquals(pending,db.pendingSyncCount(tenant))
            assertEquals(90000L,db.expectedCash(tenant,shiftId))
            assertEquals(10000L,db.supplierOutstanding(tenant,receipt))
            assertEquals(0,db.returnableQuantities(tenant,saleId)[product])
            assertFailsWith<IllegalArgumentException> { db.returnItems(tenant,saleId,mapOf(product to 1),false,"Return","stable-return") }
        }
    }
    @Test fun insufficientSupplierCashDoesNotRecordPaymentOrOutbox() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); val p=db.addProduct(t.id,"Malt","bottle",50000,0.0)
            val purchase=db.receiveStock(t.id,p.id,1,50000,0,"Supplier")
            val shift=db.openCashShift(t.id,10000)
            val pending=db.pendingSyncCount(t.id)
            assertFailsWith<IllegalArgumentException> { db.paySupplierReceipt(t.id,purchase.id,20000) }
            assertEquals(50000L,db.supplierOutstanding(t.id,purchase.id))
            assertEquals(10000L,db.expectedCash(t.id,shift.id))
            assertTrue(db.supplierPayments(t.id).isEmpty())
            assertEquals(pending,db.pendingSyncCount(t.id))
        }
    }
}
