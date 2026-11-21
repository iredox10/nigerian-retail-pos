package pos.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pos.domain.StaffPermission
import pos.domain.SupplierDetails
import pos.output.PurchaseCostCsv

class PurchaseCostHistoryTest {
    @Test fun comparesLatestLinkedReceiptsUsingExactSavedConversions() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val rice = repo.addProduct(shop.id, "Rice", "kg", 300, 0.0, quantityPrecision = 1)
            val bag = repo.addPurchaseUnit(shop.id, rice.id, "bag", 3)
            val first = repo.addSupplierProfile(shop.id, SupplierDetails("Depot"), "first-account")
            val second = repo.addSupplierProfile(shop.id, SupplierDetails("Depot"), "second-account")
            repo.receiveStockForSupplier(shop.id, rice.id, 1, 2, 0, first.id, "z-old", bag.id)
            repo.receiveStockForSupplier(shop.id, rice.id, 1, 100, 0, first.id, "a-current", bag.id)
            repo.receiveStockForSupplier(shop.id, rice.id, 5, 33, 0, second.id, "b-loose")
            repo.receiveStock(shop.id, rice.id, 10, 1, 0, "Depot", "name-only")
            repo.archivePurchaseUnit(shop.id, bag.id, true)
            repo.updateSupplierProfile(shop.id, first.id, SupplierDetails("Main depot"), true, 1, "Supplier closed")

            val history = repo.purchaseCostHistory(shop.id)
            assertEquals(4, history.size)
            assertNull(history.single { it.receipt.id == "name-only" }.supplier)
            val comparison = history.latestSupplierPrices(shop.id, rice.id, "kg")
            assertEquals(listOf("b-loose", "a-current"), comparison.map { it.receipt.id })
            assertEquals(listOf(33L, 33L), comparison.map { it.roundedBaseCostMinor })
            assertFalse(comparison.first().baseCostIsRounded)
            val last = comparison.last()
            assertTrue(last.baseCostIsRounded)
            assertEquals("bag", last.receipt.unit)
            assertEquals(3, last.receipt.baseUnitsPerUnit)
            assertEquals("Depot", last.receipt.supplierName)
            assertEquals("Main depot", last.supplier?.details?.name)
            assertEquals(true, last.supplier?.archived)
        }
    }

    @Test fun requiresShopPermissionsAndExportsRecordedPricesSafely() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Other")
            val item = repo.addProduct(shop.id, "=1+1", "piece", 5, 0.0)
            repo.receiveStock(shop.id, item.id, 2, 123, 0, "+Depot", "recorded-receipt")
            val history = repo.purchaseCostHistory(shop.id)
            assertTrue(repo.purchaseCostHistory(other.id).isEmpty())
            val csv = PurchaseCostCsv.encode(shop.id, history)
            assertTrue(csv.contains("\"'=1+1\""))
            assertTrue(csv.contains("\"'+Depot\""))
            assertTrue(csv.contains("\"1.23\""))
            assertTrue(csv.contains("\"2.46\""))
            assertFails { PurchaseCostCsv.encode(other.id, history) }
            listOf(StaffPermission.MANAGE_STOCK, StaffPermission.MANAGE_SUPPLIERS).forEach { denied ->
                repo.setPermissionCheck { _, permission -> require(permission != denied) }
                assertFails { repo.purchaseCostHistory(shop.id) }
            }
        }
    }
}
