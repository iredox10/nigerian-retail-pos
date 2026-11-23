package pos.data

import kotlin.test.*
import pos.domain.*

class ReplenishmentPolicyIntegrityTest {
    @Test fun leadBufferAndBuyingMultipleUseExactQuantitiesAndPreserveHistory() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 100, 30.0)
            repo.checkout(shop, listOf(CartLine(item, 30)), 3000, operationId = "sale")
            val first = repo.saveReplenishmentPolicy(shop, item.id, 14, 5, 12, 0, "Supplier sells cartons", "rules")
            assertEquals(first, repo.saveReplenishmentPolicy(shop, item.id, 14, 5, 12, 0, "Supplier sells cartons", "rules"))
            val row = repo.reorderSuggestions(shop, 7).rows.single()
            assertEquals(26L, row.target)
            assertEquals(36L, row.shortage)
            assertEquals(0L, repo.products(shop).single().exactStockScaled)
            assertFails { repo.saveReplenishmentPolicy(shop, item.id, 1, 0, 1, 0, "Stale rules", "stale") }
            repo.saveReplenishmentPolicy(shop, item.id, 2, 0, 6, 1, "Supplier delivers faster", "updated")
            assertEquals(2, repo.replenishmentPolicyHistory(shop).size)
            val restored = repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop), "Restored")
            assertEquals(2, repo.replenishmentPolicies(restored.id).getValue(item.id).leadDays)
            assertEquals(12L, repo.reorderSuggestions(restored.id, 7).rows.single().shortage)
        }
    }

    @Test fun reservedGoodsReduceAvailabilityAndUnauthorisedChangesRollBack() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Rice", "kg", 100, 10.0, quantityPrecision = 2)
            val customer = repo.addCustomer(shop, "Amina")
            val order = repo.createCustomerOrder(shop, customer.id, "In shop", null, "",
                listOf(QuotationLineInput(item.id, null, 900, 100)), "order")
            repo.holdCustomerOrderStock(shop, order.id, orderReservationDate(java.time.Instant.now()), 0, 0, "hold")
            repo.saveReplenishmentPolicy(shop, item.id, 0, 250, 100, 0, "Keep a buffer", "buffer")
            val row = repo.reorderSuggestions(shop, 7).rows.single()
            assertEquals(900L, row.reserved)
            assertEquals(200L, row.shortage)
            val snapshot = repo.exportTenantSnapshot(shop)
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.BACKUP) }
            assertFails { repo.saveReplenishmentPolicy(shop, item.id, 0, 0, 1, 1, "Denied rules", "denied") }
            assertEquals(snapshot, repo.exportTenantSnapshot(shop))
            repo.setPermissionCheck(null)
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "replenishment_policy_events"), "Missing") }
        }
    }
}
