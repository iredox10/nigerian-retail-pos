package pos.data

import kotlin.test.*
import pos.domain.AuditActor
import pos.domain.CartLine
import pos.domain.StaffPermission
import pos.domain.StaffRole

class PurchaseReturnIntegrityTest {
    @Test fun returnsUsingSavedCartonConversionBeforeDrawerOpensAndCapsLaterSupplierPayments() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val item = repo.addProduct(tenant.id, "Malt", "bottle", 1_200, 0.0)
            val pack = repo.addPurchaseUnit(tenant.id, item.id, "carton", 24)
            val receipt = repo.receiveStock(tenant.id, item.id, 3, 20_000, 0, "Malt supplier", purchaseUnitId = pack.id)
            repo.archivePurchaseUnit(tenant.id, pack.id, true)
            val review = repo.reviewPurchaseReturn(tenant.id, receipt.id, 1)
            assertEquals(72L, review.stockBefore)
            assertEquals(48L, review.stockAfter)
            assertEquals(60_000L, review.owedBeforeMinor)
            assertEquals(20_000L, review.creditMinor)
            assertNull(repo.activeCashShift(tenant.id))
            repo.setActorProvider { AuditActor(it, "manager", "Manager", StaffRole.MANAGER) }
            val returned = repo.savePurchaseReturn(tenant.id, review, "Supplier accepted the unopened carton", "return")
            assertEquals(40_000L, repo.supplierOutstanding(tenant.id, receipt.id))
            assertEquals(48L, repo.products(tenant.id).single().exactStockScaled)
            assertEquals(listOf(receipt), repo.stockReceipts(tenant.id))
            assertTrue(repo.cashEntries(tenant.id).isEmpty())
            assertTrue(repo.supplierPayments(tenant.id).isEmpty())
            val movement = repo.inventoryMovements(tenant.id, item.id).single { it.kind == "purchase_return" }
            assertEquals(-24.0, movement.quantityDelta)
            assertEquals(returned.id, movement.sourceId)
            assertEquals("Manager", repo.auditEvents(tenant.id).single { it.kind == "supplier.purchase_returned" }.actor?.name)
            assertEquals(returned, repo.savePurchaseReturn(tenant.id, review, returned.reason, returned.id))
            assertFails { repo.savePurchaseReturn(tenant.id, review, "Changed reason", returned.id) }

            repo.openCashShift(tenant.id, 100_000)
            assertFails { repo.paySupplierReceipt(tenant.id, receipt.id, 40_001) }
            repo.paySupplierReceipt(tenant.id, receipt.id, 35_000)
            assertEquals(5_000L, repo.supplierOutstanding(tenant.id, receipt.id))
            assertFails { repo.reviewPurchaseReturn(tenant.id, receipt.id, 1) }
            assertEquals(listOf(returned), repo.purchaseReturns(tenant.id))
            assertEquals(listOf(-35_000L), repo.cashEntries(tenant.id).map { it.amountMinor })
            repo.validatePurchaseReturns(tenant.id)
        }
    }

    @Test fun creditsForMeasuredGoodsReverseExactlyTheOriginalRoundedReceipt() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val item = repo.addProduct(tenant.id, "Rice", "kg", 400, 0.0, quantityPrecision = 1)
            val receipt = repo.receiveStock(tenant.id, item.id, 3, 105, 0, "Rice supplier")
            val credits = (1..3).map {
                val review = repo.reviewPurchaseReturn(tenant.id, receipt.id, 1)
                repo.savePurchaseReturn(tenant.id, review, "Returned measured rice").creditMinor
            }
            assertEquals(listOf(11L, 10L, 11L), credits)
            assertEquals(receipt.totalCostMinor, credits.sum())
            assertEquals(0L, repo.supplierOutstanding(tenant.id, receipt.id))
            assertEquals(0L, repo.products(tenant.id).single().exactStockScaled)
            assertFails { repo.reviewPurchaseReturn(tenant.id, receipt.id, 1) }
            repo.validatePurchaseReturns(tenant.id)
        }
    }

    @Test fun staleReviewAndLateActorFailureLeaveBalancesUnchanged() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val item = repo.addProduct(tenant.id, "Rice", "bag", 2_000, 0.0)
            val receipt = repo.receiveStock(tenant.id, item.id, 3, 1_000, 0, "Rice supplier")
            val review = repo.reviewPurchaseReturn(tenant.id, receipt.id, 1)
            repo.openCashShift(tenant.id, 0)
            val sale = repo.checkout(tenant.id, listOf(CartLine(item, 1)), item.priceMinor)
            assertFails { repo.reviewPurchaseReturn(tenant.id, receipt.id, 3) }
            repo.returnItems(tenant.id, sale.id, mapOf(item.id to 1), true, "Returned unopened bag")
            assertEquals(3L, repo.products(tenant.id).single().exactStockScaled)
            val before = repo.exportTenantSnapshot(tenant.id)
            assertFails { repo.savePurchaseReturn(tenant.id, review, "Using stale review", "op") }
            val fresh = repo.reviewPurchaseReturn(tenant.id, receipt.id, 1)
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.MANAGE_STOCK) }
            assertFails { repo.savePurchaseReturn(tenant.id, fresh, "No stock permission", "op") }
            repo.setPermissionCheck(null)
            repo.setActorProvider { error("Actor revoked") }
            assertFails { repo.savePurchaseReturn(tenant.id, fresh, "Actor lost permission", "op") }
            repo.setActorProvider(null)
            assertEquals(before, repo.exportTenantSnapshot(tenant.id))
        }
    }

    @Test fun restoredReturnBalancesAndStockMatchAndTamperedRecordsRollBack() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Shop")
            val item = repo.addProduct(tenant.id, "Rice", "bag", 300, 0.0)
            val receipt = repo.receiveStock(tenant.id, item.id, 4, 200, 0, "Rice supplier")
            val returnEvent = repo.savePurchaseReturn(tenant.id, repo.reviewPurchaseReturn(tenant.id, receipt.id, 2), "Supplier accepted two bags")
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
            assertEquals(listOf(returnEvent.copy(tenantId = restored.id)), repo.purchaseReturns(restored.id))
            assertEquals(400L, repo.supplierOutstanding(restored.id, receipt.id))
            assertEquals(2L, repo.products(restored.id).single().exactStockScaled)
            assertEquals(-2.0, repo.inventoryMovements(restored.id, item.id).single { it.kind == "purchase_return" }.quantityDelta)
            val shops = repo.tenants()
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "purchase_returns"), "Missing return") }
            val missingAudit = snapshot.tables.getValue("activity_audit").filterNot { it["entity_id"] == returnEvent.id }
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("activity_audit" to missingAudit)), "Missing audit") }
            val original = snapshot.tables.getValue("purchase_returns").single()
            listOf("credit_minor" to "401", "stock_before" to "5", "owed_before_minor" to "900").forEach { change ->
                val tampered = snapshot.copy(tables = snapshot.tables + ("purchase_returns" to listOf(original + change)))
                assertFails { repo.restoreTenantSnapshot(tampered, "Tampered return") }
            }
            assertEquals(shops, repo.tenants())
            assertFails { repo.featureExecute("UPDATE purchase_returns SET reason='Rewrite' WHERE tenant_id=?", tenant.id) }
            assertFails { repo.featureExecute("DELETE FROM purchase_returns WHERE tenant_id=?", tenant.id) }
        }
    }
}
