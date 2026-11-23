package pos.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import pos.domain.StaffPermission
import pos.domain.StockReceipt

class LandedCostTest {
    @Test
    fun paidExpenseSplitsExactlyAndKeepsRevisionsWithoutPostingCashOrStockAgain() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Landed cost shop").id
            val rice = repo.addProduct(shop, "Rice", "bag", 500, 0.0)
            val beans = repo.addProduct(shop, "Beans", "bag", 600, 0.0)
            val first = repo.receiveStock(shop, rice.id, 1, 100, 0, "Test supplier", "receipt-a")
            val second = repo.receiveStock(shop, beans.id, 1, 300, 0, "Test supplier", "receipt-b")
            repo.openCashShift(shop, 2_000)
            val expense = repo.recordExpense(shop, "Freight", "Shared delivery", 101)
            val cash = repo.cashEntries(shop)

            val ids = setOf(second.id, first.id)
            val review = repo.reviewLandedCost(shop, expense.id, 0, LandedCostMethod.PURCHASE_VALUE, ids)
            assertEquals(mapOf(first.id to 25L, second.id to 76L), review.shares.associate { it.receiptId to it.amountMinor })
            val firstSave = repo.saveLandedCost(shop, review, "One delivery for both receipts", "allocate")
            assertEquals(firstSave, repo.saveLandedCost(shop, review, "One delivery for both receipts", "allocate"))
            assertFails { repo.saveLandedCost(shop, review, "Different retry reason", "allocate") }
            assertFails { repo.saveLandedCost(shop, review, "Stale version", "old-plan") }
            assertFails { repo.reviewLandedCost(shop, expense.id, 1, LandedCostMethod.PURCHASE_VALUE, ids) }

            val revised = repo.reviewLandedCost(shop, expense.id, 1, LandedCostMethod.EQUAL, ids)
            assertEquals(mapOf(first.id to 51L, second.id to 50L), revised.shares.associate { it.receiptId to it.amountMinor })
            repo.saveLandedCost(shop, revised, "Split evenly as agreed", "revise")
            assertEquals(mapOf(first.id to 51L, second.id to 50L), repo.landedCostTotalsForPurchases(shop))

            val removal = repo.reviewLandedCost(shop, expense.id, 2, null, emptySet())
            repo.saveLandedCost(shop, removal, "Delivery was for a different order", "remove")
            assertTrue(repo.landedCostTotalsForPurchases(shop).isEmpty())
            val history = repo.landedCostBook(shop).history
            assertEquals(listOf("allocate", "revise", "remove"), history.map { it.id })
            assertEquals(listOf(1L, 2L, 3L), history.map { it.version })
            assertEquals(cash, repo.cashEntries(shop))
            assertEquals(setOf(first, second), repo.stockReceipts(shop).toSet())
            assertEquals(100L, repo.supplierOutstanding(shop, first.id))
            assertEquals(300L, repo.supplierOutstanding(shop, second.id))
            assertEquals(1.0, repo.products(shop).single { it.id == rice.id }.stock)
        }
    }

    @Test
    fun allocationIsTenantScopedAndBothPermissionsAndAuditAreRequired() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val other = repo.createTenant("Other").id
            val item = repo.addProduct(shop, "Rice", "bag", 500, 0.0)
            val foreignItem = repo.addProduct(other, "Rice", "bag", 500, 0.0)
            val receipt = repo.receiveStock(shop, item.id, 1, 100, 0, "Test supplier", "our-receipt")
            val foreign = repo.receiveStock(other, foreignItem.id, 1, 100, 0, "Other supplier", "foreign-receipt")
            repo.openCashShift(shop, 1_000)
            val expense = repo.recordExpense(shop, "Delivery", "Vendor delivery", 45)
            assertFails { repo.reviewLandedCost(shop, expense.id, 0, LandedCostMethod.EQUAL, setOf(foreign.id)) }
            assertFails { repo.reviewLandedCost(other, expense.id, 0, LandedCostMethod.EQUAL, setOf(foreign.id)) }
            val review = repo.reviewLandedCost(shop, expense.id, 0, LandedCostMethod.EQUAL, setOf(receipt.id))
            for (forbidden in listOf(StaffPermission.MANAGE_STOCK, StaffPermission.MANAGE_MONEY)) {
                repo.setPermissionCheck { _, permission -> check(permission != forbidden) }
                assertFails { repo.saveLandedCost(shop, review, "Delivery of rice bags", "allocate") }
            }
            repo.setPermissionCheck(null)
            repo.setActorProvider { error("Session ended during posting") }
            assertFails { repo.saveLandedCost(shop, review, "Delivery of rice bags", "allocate") }
            repo.setActorProvider(null)
            assertTrue(repo.landedCostBook(shop).history.isEmpty())
            assertTrue(repo.landedCostBook(other).history.isEmpty())
            repo.saveLandedCost(shop, review, "Delivery of rice bags", "allocate")
            assertEquals(mapOf(receipt.id to 45L), repo.landedCostBook(shop).allocatedByReceipt)
        }
    }

    @Test
    fun backupRecomputesAllocationAndRejectsMissingOrAlteredHistory() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Rice", "bag", 500, 0.0)
            val receipt = repo.receiveStock(shop, item.id, 1, 100, 0, "Test supplier")
            repo.openCashShift(shop, 1_000)
            val expense = repo.recordExpense(shop, "Delivery", "Freight", 45)
            val review = repo.reviewLandedCost(shop, expense.id, 0, LandedCostMethod.EQUAL, setOf(receipt.id))
            val entry = repo.saveLandedCost(shop, review, "Receipt from same delivery", "allocate")
            val snapshot = repo.exportTenantSnapshot(shop)
            val recovered = repo.restoreTenantSnapshot(snapshot, "Restored landed costs").id
            assertEquals(listOf(entry), repo.landedCostBook(recovered).history)
            assertEquals(mapOf(receipt.id to 45L), repo.landedCostTotalsForPurchases(recovered))
            val shares = snapshot.tables.getValue("landed_cost_shares")
            assertFails {
                repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables +
                    ("landed_cost_shares" to shares.map { it + ("amount_minor" to "44") })), "Corrupt")
            }
            assertFails {
                repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables
                    .filterKeys { it !in landedCostSnapshotTables }), "Missing")
            }
        }
    }

    @Test
    fun exactSplitHandlesOverflowSizedWeightsAndZeroValueReceipts() {
        fun receipt(id: String, minor: Long) =
            StockReceipt(id, "shop", "item", "Rice", "bag", 1, minor, minor, 0, "Supplier", "2026-11-23T00:00:00Z")
        val highA = receipt("a", Long.MAX_VALUE)
        val highB = receipt("b", Long.MAX_VALUE)
        assertEquals(listOf(LandedCostShare("a", Long.MAX_VALUE / 2 + 1), LandedCostShare("b", Long.MAX_VALUE / 2)),
            splitLandedCost(Long.MAX_VALUE, LandedCostMethod.PURCHASE_VALUE, listOf(highB, highA)))
        val zero = receipt("free", 0)
        assertFails { splitLandedCost(10, LandedCostMethod.PURCHASE_VALUE, listOf(zero)) }
        assertEquals(listOf(LandedCostShare("free", 10)), splitLandedCost(10, LandedCostMethod.EQUAL, listOf(zero)))
    }
}
