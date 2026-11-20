package pos.data

import java.time.LocalDate
import java.time.ZoneId
import pos.domain.CartLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import pos.domain.StaffPermission

class FrequentItemsTest {
    @Test fun countsDistinctRetainedReceiptsAcrossPacksAndKeepsShopsSeparate() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Other")
            val chips = repo.addProduct(shop.id, "Chips", "bag", 100, 50.0)
            val nuts = repo.addProduct(shop.id, "Nuts", "bag", 100, 50.0)
            val pack = repo.addSellingUnit(shop.id, chips.id, "carton", 10, 900)
            repo.openCashShift(shop.id, 10_000)
            val mixed = repo.checkout(shop.id, listOf(
                CartLine(chips, 1), CartLine(chips, 1, pack.id, pack.name, pack.priceMinor, pack.baseUnitsPerUnit)
            ), 1000)
            repo.checkout(shop.id, listOf(CartLine(nuts, 1)), 100)
            repo.checkout(shop.id, listOf(CartLine(nuts, 1)), 100)
            assertEquals(listOf(nuts.id, chips.id), repo.frequentItemIds(shop.id))
            repo.returnItems(shop.id, mixed.id, mapOf(chips.id to 1), true, "Unopened")
            assertEquals(listOf(nuts.id, chips.id), repo.frequentItemIds(shop.id))
            repo.returnItems(shop.id, mixed.id, mapOf("${chips.id}~${pack.id}" to 1), true, "Unopened")
            assertEquals(listOf(nuts.id), repo.frequentItemIds(shop.id))
            repo.archiveProduct(shop.id, nuts.id, true)
            assertEquals(emptyList(), repo.frequentItemIds(shop.id))
            assertEquals(emptyList(), repo.frequentItemIds(other.id))
        }
    }

    @Test fun honoursShopDateWindowAndSellPermission() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Soap", "bar", 500, 5.0)
            repo.checkout(shop.id, listOf(CartLine(item, 1)), 500)
            val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
            assertEquals(emptyList(), repo.frequentItemIds(shop.id, today.minusDays(1)))
            assertEquals(emptyList(), repo.frequentItemIds(shop.id, today.plusDays(30)))
            assertEquals(listOf(item.id), repo.frequentItemIds(shop.id))
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.SELL) }
            assertFails { repo.frequentItemIds(shop.id) }
        }
    }
}
