package pos.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.domain.*
import pos.output.InventoryCostingCsv

class InventoryCostingIntegrityTest {
    @Test fun declaredOpeningCostAndPurchasesConsumeInExactFifoOrderAndRetainCorrections() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 500, 2.0)
            assertNull(repo.inventoryCosting(shop).items.single().stockValueMinor)
            val opening = repo.saveOpeningInventoryCost(shop, item.id, 201, 0, "Opening invoice counted by owner", "opening")
            assertEquals(opening, repo.saveOpeningInventoryCost(shop, item.id, 201, 0, "Opening invoice counted by owner", "opening"))
            repo.receiveStock(shop, item.id, 3, 200, 0, "Wholesaler", "purchase")
            repo.openCashShift(shop, 0)
            val sale = repo.checkout(shop, listOf(CartLine(item, 3)), 1500, operationId = "sale")
            val report = repo.inventoryCosting(shop)
            assertEquals(2L, report.items.single().quantityScaled)
            assertEquals(400L, report.stockValueMinor)
            assertEquals(401L, report.originalSalesCogsMinor)
            assertEquals(401L, report.items.single().ledger.single { it.sourceId == sale.id && it.kind == "sale" }.costMinor)
            val cash = repo.cashEntries(shop)
            assertFails { repo.saveOpeningInventoryCost(shop, item.id, 203, 0, "Stale owner review", "stale") }
            repo.saveOpeningInventoryCost(shop, item.id, 203, 1, "Corrected original invoice amount", "corrected")
            assertEquals(403L, repo.inventoryCosting(shop).originalSalesCogsMinor)
            assertEquals(400L, repo.inventoryCosting(shop).stockValueMinor)
            assertEquals(cash, repo.cashEntries(shop))
            assertEquals(2, repo.openingInventoryCostHistory(shop, item.id).size)
            val csv = InventoryCostingCsv.review(report)
            assertContains(csv, "4.01")
            assertContains(csv, "FIFO purchase basis")
            val snapshot = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
            assertEquals(403L, repo.inventoryCosting(restored.id).originalSalesCogsMinor)
            assertEquals(400L, repo.inventoryCosting(restored.id).stockValueMinor)
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "opening_inventory_costs"), "Missing opening basis") }
        }
    }

    @Test fun cumulativeRoundingConservesEveryKoboAcrossPartialLotConsumption() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 200, 3.0)
            repo.saveOpeningInventoryCost(shop, item.id, 100, 0, "Original lot invoice total", "opening")
            repo.openCashShift(shop, 0)
            repeat(3) { repo.checkout(shop, listOf(CartLine(item, 1)), 200, operationId = "sale-$it") }
            val report = repo.inventoryCosting(shop)
            assertEquals(listOf(33L, 33L, 34L), report.items.single().ledger.filter { it.kind == "sale" }.map { it.costMinor })
            assertEquals(100L, report.originalSalesCogsMinor)
            assertEquals(0L, report.stockValueMinor)
        }
    }

    @Test fun returnsRestoreCostsAndCountAdditionsRemainUnavailableWhileDeliveryExpenseStaysSeparate() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 200, 0.0)
            val receipt = repo.receiveStock(shop, item.id, 3, 100, 0, "Wholesaler", "purchase")
            repo.openCashShift(shop, 1000)
            val expense = repo.recordExpense(shop, "Delivery", "Transport for soap", 30, "delivery")
            repo.saveLandedCost(shop, repo.reviewLandedCost(shop, expense.id, 0, LandedCostMethod.EQUAL, setOf(receipt.id)),
                "Allocate actual delivery expense", "allocate")
            val before = repo.inventoryCosting(shop)
            assertEquals(300L, before.stockValueMinor)
            assertEquals(30L, before.landedCostsExcludedMinor)
            val sale = repo.checkout(shop, listOf(CartLine(item, 1)), 200, operationId = "sale")
            assertEquals(100L, repo.inventoryCosting(shop).originalSalesCogsMinor)
            repo.returnItems(shop, sale.id, mapOf(item.id to 1), true, "Sellable returned soap", "return")
            val returned = repo.inventoryCosting(shop).items.single()
            assertEquals(300L, returned.stockValueMinor)
            assertEquals(100L, returned.originalSalesCogsMinor)
            assertEquals(100L, returned.restoredReturnCostMinor)
            assertEquals(0L, returned.netSalesCogsMinor)
            repo.adjustStock(shop, item.id, 5, "Count found additional goods", "count", expectedStock = 3.0)
            val counted = repo.inventoryCosting(shop).items.single()
            assertNull(counted.stockValueMinor)
            assertTrue(counted.gaps.any { it.contains("count", true) })
        }
    }

    @Test fun declarationsRespectOwnerPermissionTenantBoundariesAndHistoricalCutoffs() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val other = repo.createTenant("Other").id
            val item = repo.addProduct(shop, "Soap", "piece", 200, 1.0)
            assertFails { repo.saveOpeningInventoryCost(other, item.id, 100, 0, "Another shop", "foreign") }
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.VIEW_REPORTS) }
            assertFails { repo.saveOpeningInventoryCost(shop, item.id, 100, 0, "Needs owner", "denied") }
            assertFails { repo.inventoryCosting(shop) }
            repo.setPermissionCheck(null)
            repo.saveOpeningInventoryCost(shop, item.id, 0, 0, "Owner confirms donated opening stock", "zero")
            assertEquals(0L, repo.inventoryCosting(shop).stockValueMinor)
            val yesterday = LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(1)
            assertTrue(repo.inventoryCosting(shop, yesterday).items.isEmpty())
            assertFails { repo.inventoryCosting(shop, yesterday.plusDays(2)) }
            val snapshot = repo.exportTenantSnapshot(shop)
            val original = snapshot.tables.getValue("opening_inventory_costs").single()
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables +
                ("opening_inventory_costs" to listOf(original + ("quantity_scaled" to "2")))), "Wrong opening quantity") }
        }
    }
    @Test fun partialReturnsRestoreOriginalConsumedSlicesAndSeparateNonRestockedLoss() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 500, 2.0)
            repo.saveOpeningInventoryCost(shop, item.id, 201, 0, "Opening invoice total", "opening")
            repo.receiveStock(shop, item.id, 1, 200, 0, "Supplier", "purchase")
            repo.openCashShift(shop, 0)
            val sale = repo.checkout(shop, listOf(CartLine(item, 3)), 1500, operationId = "sale")
            repo.returnItems(shop, sale.id, mapOf(item.id to 1), true, "First sellable piece returned", "return-one")
            assertEquals(100L, repo.inventoryCosting(shop).stockValueMinor)
            repo.returnItems(shop, sale.id, mapOf(item.id to 1), true, "Second sellable piece returned", "return-two")
            assertEquals(201L, repo.inventoryCosting(shop).stockValueMinor)
            repo.returnItems(shop, sale.id, mapOf(item.id to 1), false, "Third piece returned damaged", "return-loss")
            val report = repo.inventoryCosting(shop)
            val row = report.items.single()
            assertEquals(401L, row.originalSalesCogsMinor)
            assertEquals(201L, row.restoredReturnCostMinor)
            assertEquals(200L, row.nonRestockedReturnCostMinor)
            assertEquals(0L, report.netSalesCogsMinor)
            assertEquals(201L, report.stockValueMinor)
            assertEquals(listOf(100L, 101L, 200L), row.ledger.filter { it.kind.startsWith("return") }.map { it.costMinor })
        }
    }

    @Test fun purchaseReturnDepletesFifoCostIndependentlyOfSupplierCredit() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 500, 0.0)
            repo.receiveStock(shop, item.id, 2, 100, 0, "Supplier", "cheap")
            val later = repo.receiveStock(shop, item.id, 2, 300, 0, "Supplier", "later")
            repo.savePurchaseReturn(shop, repo.reviewPurchaseReturn(shop, later.id, 1), "Supplier accepted one piece", "return")
            val report = repo.inventoryCosting(shop)
            assertEquals(700L, report.stockValueMinor)
            assertEquals(100L, report.items.single().ledger.single { it.kind == "purchase_return" }.costMinor)
            assertEquals(300L, repo.purchaseReturns(shop).single().creditMinor)
            assertEquals(300L, repo.supplierOutstanding(shop, later.id))
        }
    }

    @Test fun repackingTransfersMaterialCostAcrossAChronologicalRecipeCycle() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val input = repo.addProduct(shop, "Bulk goods", "bag", 500, 5.0)
            val output = repo.addProduct(shop, "Small pack", "pack", 300, 0.0)
            repo.saveOpeningInventoryCost(shop, input.id, 1001, 0, "Original material invoice", "opening")
            val first = RepackingRecipeDetails("Pack goods", input.id, input.name, input.unit, 0, 2,
                output.id, output.name, output.unit, 0, 3)
            repo.saveRepackingRecipe(shop, "pack", first, 0, "Measured recipe", "recipe-one")
            repo.executeRepacking(shop, "pack", 1, "Make three packs", repo.repackingReview(shop, "pack", 1), "run-one")
            val firstReport = repo.inventoryCosting(shop)
            assertEquals(1001L, firstReport.stockValueMinor)
            assertEquals(601L, firstReport.items.single { it.product.id == input.id }.stockValueMinor)
            assertEquals(400L, firstReport.items.single { it.product.id == output.id }.stockValueMinor)
            val second = RepackingRecipeDetails("Recombine packs", output.id, output.name, output.unit, 0, 3,
                input.id, input.name, input.unit, 0, 1)
            repo.saveRepackingRecipe(shop, "combine", second, 0, "Measured recombination", "recipe-two")
            repo.executeRepacking(shop, "combine", 1, "Recombine three packs", repo.repackingReview(shop, "combine", 1), "run-two")
            val report = repo.inventoryCosting(shop)
            assertEquals(1001L, report.stockValueMinor)
            assertEquals(1001L, report.items.single { it.product.id == input.id }.stockValueMinor)
            assertEquals(0L, report.items.single { it.product.id == output.id }.stockValueMinor)
            assertEquals(400L, report.items.single { it.product.id == input.id }.ledger.single { it.kind == "repacking_in" }.costMinor)
        }
    }

}
