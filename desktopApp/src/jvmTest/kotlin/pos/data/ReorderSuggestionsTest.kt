package pos.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import pos.domain.CartLine
import pos.domain.PurchaseDeliveryInput
import pos.domain.PurchaseOrderLineInput
import pos.domain.StaffPermission

class ReorderSuggestionsTest {
    @Test fun usesRetainedBaseQuantitiesAndOnlyUnreceivedApprovedOrders() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val rice = repo.addProduct(shop.id, "Rice", "kg", 1_000, 20.0, quantityPrecision = 1)
            repo.setReorderLevel(shop.id, rice.id, 20, "Rice target")
            val sellingPack = repo.addSellingUnit(shop.id, rice.id, "bag", 2, 2_000)
            val buyingPack = repo.addPurchaseUnit(shop.id, rice.id, "carton", 2)
            val supplier = repo.addSupplierProfile(shop.id, pos.domain.SupplierDetails("Supplier"))
            val packLine = CartLine(rice, 3, sellingPack.id, sellingPack.name, sellingPack.priceMinor, sellingPack.baseUnitsPerUnit)
            repo.openCashShift(shop.id, 0)
            val sale = repo.checkout(shop.id, listOf(packLine, CartLine(rice, 12)), 7_200)
            repo.returnItems(shop.id, sale.id, mapOf(rice.id to 5), true, "Returned half a kg")
            val draft = repo.createPurchaseOrder(shop.id, supplier.id,
                listOf(PurchaseOrderLineInput("rice", rice.id, 4, 1_000, buyingPack.id)))

            val before = repo.reorderSuggestions(shop.id, 7).rows.single()
            assertEquals(67L, before.retainedSales)
            assertEquals(133L, before.product.exactStockScaled)
            assertEquals(0L, before.approvedIncoming)
            assertEquals(68L, before.shortage)

            val approved = repo.approvePurchaseOrder(shop.id, draft.id, draft.version)
            repo.receivePurchaseOrder(shop.id, approved.id, listOf(PurchaseDeliveryInput("rice", 1, 0, 133)), approved.version)
            val after = repo.reorderSuggestions(shop.id, 7).rows.single()
            assertEquals(153L, after.product.exactStockScaled)
            assertEquals(60L, after.approvedIncoming)
            assertEquals(0L, after.shortage)

            repo.cancelPurchaseOrder(shop.id, draft.id, "Supplier cancelled the balance", approved.version)
            assertEquals(48L, repo.reorderSuggestions(shop.id, 7).rows.single().shortage)
        }
    }

    @Test fun demandOnlySuggestionsRespectShopDatesArchivedGoodsAndPermissions() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Another shop")
            val soap = repo.addProduct(shop.id, "Soap", "bar", 100, 30.0)
            repo.checkout(shop.id, listOf(CartLine(soap, 30)), 3_000)
            val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
            val review = repo.reorderSuggestions(shop.id, 7, today)
            assertEquals(today.minusDays(29), review.from)
            assertEquals(7L, review.rows.single().shortage)
            assertTrue(repo.reorderSuggestions(shop.id, 7, today.plusDays(30)).rows.isEmpty())
            assertTrue(repo.reorderSuggestions(other.id, 7).rows.isEmpty())
            repo.archiveProduct(shop.id, soap.id, true)
            assertTrue(repo.reorderSuggestions(shop.id, 7).rows.isEmpty())
            assertFails { repo.reorderSuggestions(shop.id, 0) }
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.MANAGE_STOCK) }
            assertFails { repo.reorderSuggestions(shop.id, 7) }
        }
    }
}
