package pos.data

import kotlin.test.*
import pos.domain.*

class BundleIntegrityTest {
    @Test fun cashBundleAllocatesExactKoboTracksComponentStockAndRetriesOnce() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val soap = repo.addProduct(shop, "Soap", "piece", 45000, 30.0)
            val malt = repo.addProduct(shop, "Malt", "bottle", 50000, 30.0)
            repo.openCashShift(shop, 0)
            val inputs = listOf(BundleComponentInput(soap.id, null, 2), BundleComponentInput(malt.id, null, 1))
            val definition = repo.saveBundle(shop, "starter", "Starter pack", true, 125001, inputs, 0, "Initial offer", "definition")
            assertEquals(definition, repo.saveBundle(shop, "starter", "Starter pack", true, 125001, inputs, 0, "Initial offer", "definition"))
            val review = repo.reviewBundleSale(shop, definition.id, 2)
            assertEquals(280000L, review.normalTotalMinor)
            assertEquals(250002L, cartTotal(review.lines))
            assertEquals(29998L, review.lines.sumOf { it.basketDiscountMinor })
            val sale = repo.completeBundleCashSale(shop, review, 300000, "bundle-sale")
            assertEquals(49998L, sale.changeMinor)
            assertEquals(sale.totalMinor, sale.lines.sumOf { it.cumulativeRefundMinor(it.quantity) })
            assertEquals(sale, repo.completeBundleCashSale(shop, review, 300000, "bundle-sale"))
            assertEquals(1, repo.sales(shop).size)
            assertEquals(26L, repo.products(shop).single { it.id == soap.id }.exactStockScaled)
            assertEquals(28L, repo.products(shop).single { it.id == malt.id }.exactStockScaled)
            assertEquals("Bundle Starter pack · revision 1 · 2 bundle(s)", sale.note)
            assertEquals("bundle-sale", repo.bundleSales(shop).single().saleId)
            val before = repo.exportTenantSnapshot(shop)
            assertFails { repo.completeBundleCashSale(shop, review, 300001, "bundle-sale") }
            assertFails { repo.featureExecute("UPDATE bundle_sales SET bundle_count=3") }
            assertFails { repo.featureExecute("DELETE FROM bundle_versions") }
            assertEquals(before, repo.exportTenantSnapshot(shop))
        }
    }

    @Test fun changedStockPricesAndTermsRequireNewReviewWithoutPartialSale() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val soap = repo.addProduct(shop, "Soap", "piece", 10000, 10.0)
            repo.openCashShift(shop, 0)
            val inputs = listOf(BundleComponentInput(soap.id, null, 2))
            repo.saveBundle(shop, "pair", "Soap pair", true, 19000, inputs, 0, "Initial offer")
            val stockReview = repo.reviewBundleSale(shop, "pair", 1)
            repo.checkout(shop, listOf(CartLine(soap, 1)), 10000, operationId = "ordinary")
            var before = repo.exportTenantSnapshot(shop)
            assertFails { repo.completeBundleCashSale(shop, stockReview, 19000, "stale-stock") }
            assertEquals(before, repo.exportTenantSnapshot(shop))
            val priceReview = repo.reviewBundleSale(shop, "pair", 1)
            repo.updateProduct(shop, soap.id, soap.name, soap.unit, 11000, null, "new-price")
            before = repo.exportTenantSnapshot(shop)
            assertFails { repo.completeBundleCashSale(shop, priceReview, 19000, "stale-price") }
            assertEquals(before, repo.exportTenantSnapshot(shop))
            val versionReview = repo.reviewBundleSale(shop, "pair", 1)
            repo.saveBundle(shop, "pair", "Soap pair", false, 19000, inputs, 1, "Pause offer")
            before = repo.exportTenantSnapshot(shop)
            assertFails { repo.completeBundleCashSale(shop, versionReview, 19000, "stale-version") }
            assertFails { repo.reviewBundleSale(shop, "pair", 1) }
            assertEquals(before, repo.exportTenantSnapshot(shop))
        }
    }

    @Test fun measuredGoodsAndPacksUseExactStockAndEnforceFloorsAndDiscountPermission() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val rice = repo.addProduct(shop, "Rice", "kg", 100000, 10.0, quantityPrecision = 3)
            val malt = repo.addProduct(shop, "Malt", "bottle", 50000, 30.0)
            val carton = repo.addSellingUnit(shop, malt.id, "carton", 12, 550000)
            repo.openCashShift(shop, 0)
            val inputs = listOf(BundleComponentInput(rice.id, null, 1375), BundleComponentInput(malt.id, carton.id, 1))
            repo.saveBundle(shop, "food", "Food bundle", true, 650001, inputs, 0, "Initial offer")
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.DISCOUNT) { "Discount denied" } }
            assertFails { repo.reviewBundleSale(shop, "food", 1) }
            repo.setPermissionCheck(null)
            repo.saveMinimumPrice(shop, rice.id, null, 100000, 0, "Keep the rice floor")
            val before = repo.exportTenantSnapshot(shop)
            assertFails { repo.reviewBundleSale(shop, "food", 1) }
            assertEquals(before, repo.exportTenantSnapshot(shop))
            repo.saveMinimumPrice(shop, rice.id, null, null, 1, "Remove the rice floor")
            val review = repo.reviewBundleSale(shop, "food", 2)
            val sale = repo.completeBundleCashSale(shop, review, review.totalMinor, "food-sale")
            assertEquals(1300002L, sale.totalMinor)
            assertEquals(7250L, repo.products(shop).single { it.id == rice.id }.exactStockScaled)
            assertEquals(6L, repo.products(shop).single { it.id == malt.id }.exactStockScaled)
            assertFails { repo.reviewBundleSale(shop, "food", 1) }
        }
    }

    @Test fun immutableBundleSourceRestoresAndMissingOrChangedLinksAreRejected() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val soap = repo.addProduct(shop, "Soap", "piece", 10000, 10.0)
            repo.openCashShift(shop, 0)
            val inputs = listOf(BundleComponentInput(soap.id, null, 2))
            repo.saveBundle(shop, "pair", "Soap pair", true, 19001, inputs, 0, "Initial offer")
            val review = repo.reviewBundleSale(shop, "pair", 1)
            repo.completeBundleCashSale(shop, review, 20000, "sale")
            repo.saveBundle(shop, "pair", "Weekend pair", false, 18000, inputs, 1, "Close old offer")
            repo.updateProduct(shop, soap.id, "Renamed soap", soap.unit, 12000, null, "rename")
            val snapshot = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
            assertEquals(repo.bundleHistory(shop, "pair"), repo.bundleHistory(restored.id, "pair"))
            assertEquals(repo.bundleSales(shop), repo.bundleSales(restored.id))
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "bundle_sales"), "Missing source") }
            val changed = snapshot.tables.getValue("bundle_sales").map { it + ("bundle_count" to "2") }
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("bundle_sales" to changed)), "Changed count") }
            val before = repo.exportTenantSnapshot(shop)
            assertFails { repo.saveBundle(shop, "pair", "Weekend pair", true, 18000, inputs, 1, "Stale edit") }
            assertEquals(before, repo.exportTenantSnapshot(shop))
        }
    }
}
