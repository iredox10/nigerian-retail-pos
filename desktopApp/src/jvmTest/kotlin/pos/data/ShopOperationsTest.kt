package pos.data

import kotlin.test.*
import pos.domain.CartLine
import java.nio.file.Files
import java.sql.DriverManager

class ShopOperationsTest {
    @Test fun repaymentRetryDoesNotDeductDebtOrCashTwiceEvenAfterClosing() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop")
            val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val c=db.addCustomer(t.id,"Grace")
            val shift=db.openCashShift(t.id,0)
            db.checkout(t.id,listOf(CartLine(p,1)),0,c.id)
            db.recordRepayment(t.id,c.id,10000,"repayment-1")
            db.closeCashShift(t.id,shift.id,10000)
            val pending=db.pendingSyncCount(t.id)
            db.recordRepayment(t.id,c.id,10000,"repayment-1")
            assertEquals(40000L,db.customers(t.id).single().balanceMinor)
            assertEquals(1,db.cashEntries(t.id,shift.id).size)
            assertEquals(10000L,db.expectedCash(t.id,shift.id))
            assertEquals(pending,db.pendingSyncCount(t.id))
            assertFailsWith<IllegalArgumentException> { db.recordRepayment(t.id,c.id,9000,"repayment-1") }
        }
    }
    @Test fun repaymentOperationIdsAreScopedToEachTenant() {
        PosRepository(":memory:").use { db ->
            listOf(db.createTenant("A"),db.createTenant("B")).forEach { tenant ->
                val p=db.addProduct(tenant.id,"Malt","bottle",50000,10.0)
                val c=db.addCustomer(tenant.id,"Grace")
                db.checkout(tenant.id,listOf(CartLine(p,1)),0,c.id)
                db.recordRepayment(tenant.id,c.id,10000,"same-operation")
                db.recordRepayment(tenant.id,c.id,10000,"same-operation")
                assertEquals(40000L,db.customers(tenant.id).single().balanceMinor)
                assertEquals(1,db.cashEntries(tenant.id).size)
            }
        }
    }
    @Test fun openingStockBeyondExactDoubleIntegerRangeIsRejected() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop")
            assertFailsWith<IllegalArgumentException> { db.addProduct(t.id,"Malt","bottle",1,9_007_199_254_740_992.0) }
            assertTrue(db.products(t.id).isEmpty())
            assertEquals(0,db.pendingSyncCount(t.id))
        }
    }

    @Test fun receivingStockUpdatesQuantityAndTracksUnpaidPurchaseExactlyOnce() {
        PosRepository(":memory:").use { db ->
            val tenant=db.createTenant("Shop")
            val p=db.addProduct(tenant.id,"Malt","bottle",50000,10.0)
            val shift=db.openCashShift(tenant.id,100000)
            val receipt=db.receiveStock(tenant.id,p.id,5,10000,20000,"Supplier", "purchase-1")
            val repeated=db.receiveStock(tenant.id,p.id,5,10000,20000,"Supplier", "purchase-1")
            assertEquals(receipt,repeated)
            assertEquals(15.0,db.products(tenant.id).single().stock)
            assertEquals(30000L,receipt.unpaidMinor)
            assertEquals(80000L,db.expectedCash(tenant.id,shift.id))
            assertEquals(1,db.stockReceipts(tenant.id).size)
            assertEquals(1,db.cashEntries(tenant.id,shift.id).size)
            assertFailsWith<IllegalArgumentException> { db.receiveStock(tenant.id,p.id,5,10000,10000,"Supplier","purchase-1") }
        }
    }
    @Test fun payoutsRequireActiveShiftAndSufficientCashWithoutPartialEffects() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop")
            val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            assertFailsWith<IllegalArgumentException> { db.recordExpense(t.id,"Fuel","Generator fuel",1000) }
            assertFailsWith<IllegalArgumentException> { db.receiveStock(t.id,p.id,1,10000,10000,"") }
            assertEquals(10.0,db.products(t.id).single().stock)
            assertTrue(db.stockReceipts(t.id).isEmpty())
            val shift=db.openCashShift(t.id,5000)
            val pending=db.pendingSyncCount(t.id)
            assertFailsWith<IllegalArgumentException> { db.recordExpense(t.id,"Fuel","Generator fuel",6000) }
            assertFailsWith<IllegalArgumentException> { db.recordCashMovement(t.id,-6000,"Owner withdrawal") }
            assertEquals(5000L,db.expectedCash(t.id,shift.id))
            assertTrue(db.expenses(t.id).isEmpty())
            assertTrue(db.cashEntries(t.id,shift.id).isEmpty())
            assertEquals(pending,db.pendingSyncCount(t.id))
        }
    }
    @Test fun receiptOnSupplierCreditDoesNotRequireCashShift() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop")
            val p=db.addProduct(t.id,"Malt","bottle",50000,0.0)
            assertFailsWith<IllegalArgumentException> { db.receiveStock(t.id,p.id,1,20000,0,"") }
            val receipt=db.receiveStock(t.id,p.id,1,20000,0,"Supplier")
            assertEquals(20000L,receipt.unpaidMinor)
            assertEquals(1.0,db.products(t.id).single().stock)
            assertTrue(db.cashEntries(t.id).isEmpty())
        }
    }
    @Test fun closingSnapshotIncludesSaleRepaymentExpenseAndManualMovementAndSurvivesRestart() {
        val path=Files.createTempDirectory("pos-cash-shift-").resolve("shop.db").toString()
        var tenantId=""; var shiftId=""
        PosRepository(path).use { db ->
            val t=db.createTenant("Shop"); tenantId=t.id
            val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val c=db.addCustomer(t.id,"Grace")
            shiftId=db.openCashShift(t.id,100000).id
            db.checkout(t.id,listOf(CartLine(p,2)),60000,c.id,"sale-1")
            db.recordRepayment(t.id,c.id,10000)
            db.recordExpense(t.id,"Fuel","Generator fuel",20000,"expense-1")
            db.recordExpense(t.id,"Fuel","Generator fuel",20000,"expense-1")
            db.recordCashMovement(t.id,5000,"Owner contribution","cash-1")
            db.recordCashMovement(t.id,5000,"Owner contribution","cash-1")
            assertEquals(155000L,db.expectedCash(t.id,shiftId))
            val closed=db.closeCashShift(t.id,shiftId,153000)
            assertEquals(-2000L,closed.differenceMinor)
            assertEquals(closed,db.closeCashShift(t.id,shiftId,153000))
            assertFailsWith<IllegalArgumentException> { db.closeCashShift(t.id,shiftId,155000) }
            assertNull(db.activeCashShift(t.id))
        }
        PosRepository(path).use { db ->
            assertEquals(155000L,db.expectedCash(tenantId,shiftId))
            assertEquals(153000L,db.cashShifts(tenantId).single().countedMinor)
            assertEquals(4,db.cashEntries(tenantId,shiftId).size)
            val next=db.openCashShift(tenantId,153000)
            assertEquals(153000L,db.expectedCash(tenantId,next.id))
        }
    }
    @Test fun cashAndStockOperationsRejectAnotherTenantsIdentifiers() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A"); val b=db.createTenant("B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,10.0)
            val shift=db.openCashShift(a.id,100000)
            assertFailsWith<IllegalArgumentException> { db.receiveStock(b.id,p.id,1,10000,0,"Supplier") }
            assertFailsWith<IllegalArgumentException> { db.expectedCash(b.id,shift.id) }
            assertFailsWith<IllegalArgumentException> { db.cashEntries(b.id,shift.id) }
            assertFailsWith<IllegalArgumentException> { db.closeCashShift(b.id,shift.id,0) }
            assertTrue(db.cashShifts(b.id).isEmpty())
            assertTrue(db.stockReceipts(b.id).isEmpty())
            assertEquals(10.0,db.products(a.id).single().stock)
        }
    }
    @Test fun overflowRollsBackSaleStockAndOutboxTogether() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop")
            val p=db.addProduct(t.id,"Malt","bottle",1,10.0)
            val shift=db.openCashShift(t.id,Long.MAX_VALUE)
            val pending=db.pendingSyncCount(t.id)
            assertFailsWith<ArithmeticException> { db.checkout(t.id,listOf(CartLine(p,1)),1) }
            assertFailsWith<ArithmeticException> { db.receiveStock(t.id,p.id,2,Long.MAX_VALUE,0,"Supplier") }
            assertEquals(10.0,db.products(t.id).single().stock)
            assertTrue(db.sales(t.id).isEmpty())
            assertTrue(db.stockReceipts(t.id).isEmpty())
            assertEquals(Long.MAX_VALUE,db.expectedCash(t.id,shift.id))
            assertEquals(pending,db.pendingSyncCount(t.id))
        }
    }
    @Test fun onlyOneActiveShiftPerTenant() {
        val path=Files.createTempDirectory("pos-active-shift-").resolve("shop.db").toString()
        PosRepository(path).use { first ->
            val t=first.createTenant("Shop")
            val shift=first.openCashShift(t.id,1000)
            PosRepository(path).use { second ->
                assertFailsWith<IllegalArgumentException> { second.openCashShift(t.id,2000) }
                assertEquals(shift,second.activeCashShift(t.id))
            }
        }
    }
    @Test fun migrationBackfillsLegacyCashOnceWithoutAssigningItToNewShift() {
        val path=Files.createTempDirectory("pos-legacy-cash-").resolve("shop.db").toString()
        var tenantId=""
        PosRepository(path).use { db ->
            val t=db.createTenant("Shop"); tenantId=t.id
            val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val c=db.addCustomer(t.id,"Grace")
            db.checkout(t.id,listOf(CartLine(p,2)),60000,c.id)
            db.recordRepayment(t.id,c.id,10000)
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use { it.execute("DELETE FROM cash_entries") } }
        PosRepository(path).use { db ->
            assertEquals(2,db.cashEntries(tenantId).size)
            assertTrue(db.cashEntries(tenantId).all { it.shiftId==null })
            val shift=db.openCashShift(tenantId,70000)
            assertEquals(70000L,db.expectedCash(tenantId,shift.id))
            assertTrue(db.cashEntries(tenantId,shift.id).isEmpty())
        }
        PosRepository(path).use { db -> assertEquals(2,db.cashEntries(tenantId).size) }
    }
}
