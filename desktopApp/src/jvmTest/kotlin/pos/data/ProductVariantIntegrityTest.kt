package pos.data

import pos.domain.StaffPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ProductVariantIntegrityTest {
    @Test fun independentGoodsKeepIdentityAndVariantHistorySurvivesRecovery() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val small = repo.addProduct(shop.id, "Blue school shirt small", "piece", 500, 4.0, "SHIRT-BLUE-S")
            val large = repo.addProduct(shop.id, "Blue school shirt large", "piece", 600, 9.0, "SHIRT-BLUE-L")
            val first = ProductVariant("School shirt", "Blue / Small")
            val second = ProductVariant("School shirt", "Blue / Large")
            val saved = repo.saveProductVariant(shop.id, small.id, first, 0, "Group the small shirt", "small")
            assertEquals(saved, repo.saveProductVariant(shop.id, small.id, first, 0, "Group the small shirt", "small"))
            assertFails {
                repo.saveProductVariant(shop.id, large.id, ProductVariant("school shirt", "blue / small"), 0, "Duplicate label")
            }
            repo.saveProductVariant(shop.id, large.id, second, 0, "Group the large shirt")
            assertFails { repo.saveProductVariant(shop.id, large.id, null, 0, "Stale removal") }
            assertEquals(mapOf(small.id to first, large.id to second), repo.productVariants(shop.id))
            repo.saveProductVariant(shop.id, small.id, null, 1, "Remove the small shirt")
            assertEquals(small, repo.productByBarcode(shop.id, "SHIRT-BLUE-S"))
            assertEquals(9.0, repo.productByBarcode(shop.id, "SHIRT-BLUE-L")?.stock)
            val backup = repo.exportTenantSnapshot(shop.id)
            val recovered = repo.restoreTenantSnapshot(backup, "Recovered")
            assertEquals(mapOf(large.id to second), repo.productVariants(recovered.id))
            assertEquals(listOf(first, null), repo.productVariantHistory(recovered.id, small.id).map { it.variant })
            assertFails { repo.restoreTenantSnapshot(backup.copy(tables = backup.tables - variantSnapshotTables.toSet()), "Missing") }
        }
    }

    @Test fun archivedCrossShopAndUnauthorizedChangesCannotReshapeCatalogue() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Other")
            val item = repo.addProduct(shop.id, "Shirt", "piece", 500, 4.0)
            val variant = ProductVariant("School shirt", "Blue")
            assertFails { repo.saveProductVariant(other.id, item.id, variant, 0, "Wrong shop") }
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.MANAGE_STOCK) }
            assertFails { repo.saveProductVariant(shop.id, item.id, variant, 0, "Forbidden") }
            repo.setPermissionCheck(null)
            repo.archiveProduct(shop.id, item.id, true)
            assertFails { repo.saveProductVariant(shop.id, item.id, variant, 0, "Archived") }
            assertTrue(repo.productVariants(shop.id).isEmpty())
        }
    }
}
