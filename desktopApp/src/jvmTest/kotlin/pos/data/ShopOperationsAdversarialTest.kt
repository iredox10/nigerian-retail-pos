package pos.data

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*
import pos.domain.CartLine

class ShopOperationsAdversarialTest {
    @Test fun checkoutReplayRejectsForeignTenantOrChangedUnit() {
        PosRepository(":memory:").use { db ->
            val a = db.createTenant("Shop A")
            val b = db.createTenant("Shop B")
            val p = db.addProduct(a.id, "Malt", "bottle", 50_000, 10.0)
            db.checkout(a.id, listOf(CartLine(p, 1)), 50_000, operationId = "sale-once")
            assertFailsWith<IllegalArgumentException> {
                db.checkout(a.id, listOf(CartLine(p.copy(tenantId = b.id), 1)), 50_000, operationId = "sale-once")
            }
            assertFailsWith<IllegalArgumentException> {
                db.checkout(a.id, listOf(CartLine(p.copy(unit = "carton"), 1)), 50_000, operationId = "sale-once")
            }
            assertEquals(9.0, db.products(a.id).single().stock)
            assertEquals(1, db.sales(a.id).size)
            assertTrue(db.sales(b.id).isEmpty())
        }
    }

    @Test fun repeatedGroupedCheckoutRequestIsStillTheSameOperation() {
        PosRepository(":memory:").use { db ->
            val t = db.createTenant("Shop")
            val p = db.addProduct(t.id, "Malt", "bottle", 50_000, 2.0)
            val lines = listOf(CartLine(p, 1), CartLine(p, 1))
            val first = db.checkout(t.id, lines, 100_000, operationId = "grouped-sale")
            val replay = db.checkout(t.id, lines, 100_000, operationId = "grouped-sale")
            assertEquals(first.id, replay.id)
            assertEquals(first.totalMinor, replay.totalMinor)
            assertEquals(first.lines.map { it.product.id to it.quantity }, replay.lines.map { it.product.id to it.quantity })
            assertEquals(0.0, db.products(t.id).single().stock)
            assertEquals(1, db.sales(t.id).size)
        }
    }

    @Test fun everyCashSourceContributesOnceAndClosedExpectedCashIsFrozen() {
        PosRepository(":memory:").use { db ->
            val t = db.createTenant("Shop")
            val p = db.addProduct(t.id, "Malt", "bottle", 50_000, 10.0)
            val c = db.addCustomer(t.id, "Grace")
            val shift = db.openCashShift(t.id, 10_000)
            db.checkout(t.id, listOf(CartLine(p, 2)), 60_000, c.id, "cash-sale")
            db.recordRepayment(t.id, c.id, 15_000)
            db.receiveStock(t.id, p.id, 5, 5_000, 10_000, "Supplier", "stock-once")
            db.receiveStock(t.id, p.id, 5, 5_000, 10_000, "Supplier", "stock-once")
            db.recordExpense(t.id, "Transport", "Delivery", 5_000, "expense-once")
            db.recordExpense(t.id, "Transport", "Delivery", 5_000, "expense-once")
            db.recordCashMovement(t.id, 2_000, "Owner added cash", "cash-in-once")
            db.recordCashMovement(t.id, 2_000, "Owner added cash", "cash-in-once")
            db.recordCashMovement(t.id, -1_000, "Owner withdrawal", "cash-out-once")
            assertEquals(71_000L, db.expectedCash(t.id, shift.id))
            assertEquals(6, db.cashEntries(t.id, shift.id).size)
            assertEquals(25_000L, db.customers(t.id).single().balanceMinor)
            val closed = db.closeCashShift(t.id, shift.id, 70_000)
            assertEquals(-1_000L, closed.differenceMinor)
            assertEquals(closed, db.closeCashShift(t.id, shift.id, 70_000))
            assertFailsWith<IllegalArgumentException> { db.closeCashShift(t.id, shift.id, 71_000) }
            val next = db.openCashShift(t.id, 70_000)
            db.recordCashMovement(t.id, 5_000, "More owner cash")
            assertEquals(75_000L, db.expectedCash(t.id, next.id))
            assertEquals(71_000L, db.expectedCash(t.id, shift.id))
            assertEquals(6, db.cashEntries(t.id, shift.id).size)
        }
    }

    @Test fun reusedOperationWithChangedDetailsCannotRewriteHistory() {
        PosRepository(":memory:").use { db ->
            val t = db.createTenant("Shop")
            val p = db.addProduct(t.id, "Malt", "bottle", 50_000, 10.0)
            val shift = db.openCashShift(t.id, 100_000)
            db.receiveStock(t.id, p.id, 2, 10_000, 20_000, "Supplier", "stock-id")
            db.recordExpense(t.id, "Transport", "Delivery", 5_000, "expense-id")
            db.recordCashMovement(t.id, -1_000, "Owner withdrew", "movement-id")
            val before = db.pendingSyncCount(t.id)
            assertFailsWith<IllegalArgumentException> { db.receiveStock(t.id, p.id, 3, 10_000, 20_000, "Supplier", "stock-id") }
            assertFailsWith<IllegalArgumentException> { db.recordExpense(t.id, "Transport", "Changed delivery", 5_000, "expense-id") }
            assertFailsWith<IllegalArgumentException> { db.recordCashMovement(t.id, 1_000, "Owner withdrew", "movement-id") }
            assertEquals(12.0, db.products(t.id).single().stock)
            assertEquals(74_000L, db.expectedCash(t.id, shift.id))
            assertEquals(before, db.pendingSyncCount(t.id))
        }
    }

    @Test fun foreignProductsAndShiftsAndOverdrawsLeaveNoPartialEffects() {
        PosRepository(":memory:").use { db ->
            val a = db.createTenant("Shop A")
            val b = db.createTenant("Shop B")
            val p = db.addProduct(a.id, "Malt", "bottle", 50_000, 10.0)
            val shiftA = db.openCashShift(a.id, 10_000)
            val shiftB = db.openCashShift(b.id, 20_000)
            val pendingA = db.pendingSyncCount(a.id)
            val pendingB = db.pendingSyncCount(b.id)
            assertFailsWith<IllegalArgumentException> { db.receiveStock(b.id, p.id, 1, 100, 100, "Supplier") }
            assertFailsWith<IllegalArgumentException> { db.expectedCash(b.id, shiftA.id) }
            assertFailsWith<IllegalArgumentException> { db.cashEntries(a.id, shiftB.id) }
            assertFailsWith<IllegalArgumentException> { db.closeCashShift(b.id, shiftA.id, 10_000) }
            assertFailsWith<IllegalArgumentException> { db.recordExpense(a.id, "Fuel", "Generator", 10_001) }
            assertFailsWith<IllegalArgumentException> { db.receiveStock(a.id, p.id, 2, 6_000, 12_000, "Supplier") }
            assertFailsWith<IllegalArgumentException> { db.recordCashMovement(a.id, -10_001, "Too much") }
            assertTrue(db.expenses(a.id).isEmpty())
            assertTrue(db.stockReceipts(a.id).isEmpty())
            assertTrue(db.cashEntries(a.id).isEmpty())
            assertEquals(10.0, db.products(a.id).single().stock)
            assertEquals(10_000L, db.expectedCash(a.id, shiftA.id))
            assertEquals(pendingA, db.pendingSyncCount(a.id))
            assertEquals(pendingB, db.pendingSyncCount(b.id))
        }
    }

    @Test fun legacyCashBackfillIsUnassignedAndReopeningNeverDuplicatesCash() {
        val directory = Files.createTempDirectory("pos-legacy-cash-")
        val path = directory.resolve("shop.db").toString()
        try {
            var tenantId = ""
            PosRepository(path).use { db ->
                tenantId = db.createTenant("Old shop").id
                val p = db.addProduct(tenantId, "Malt", "bottle", 50_000, 10.0)
                val c = db.addCustomer(tenantId, "Grace")
                db.checkout(tenantId, listOf(CartLine(p, 2)), 60_000, c.id)
                db.recordRepayment(tenantId, c.id, 15_000)
            }
            // Drop only the newly introduced tables to represent the original schema.
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { sql ->
                    listOf("cash_entries", "cash_shifts", "expenses", "stock_receipts").forEach { sql.execute("DROP TABLE $it") }
                }
            }
            PosRepository(path).use { db ->
                val migrated = db.cashEntries(tenantId)
                assertEquals(2, migrated.size)
                assertEquals(75_000L, migrated.sumOf { it.amountMinor })
                assertTrue(migrated.all { it.shiftId == null })
                val shift = db.openCashShift(tenantId, 75_000)
                assertEquals(75_000L, db.expectedCash(tenantId, shift.id))
                assertTrue(db.cashEntries(tenantId, shift.id).isEmpty())
            }
            PosRepository(path).use { db ->
                assertEquals(2, db.cashEntries(tenantId).size)
                assertEquals(75_000L, db.expectedCash(tenantId, db.activeCashShift(tenantId)!!.id))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
