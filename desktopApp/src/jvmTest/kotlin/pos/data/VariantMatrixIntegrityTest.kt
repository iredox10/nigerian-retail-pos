package pos.data

import kotlin.test.*
import pos.domain.*

class VariantMatrixIntegrityTest {
    private val definition = VariantMatrixDefinition("Cotton shirt", "piece", 0,
        VariantOptionAxis("Size", listOf("S", "M")), VariantOptionAxis("Colour", listOf("Blue", "White")))
    private fun rows() = variantMatrixCombinations(definition).mapIndexed { index, pair ->
        VariantMatrixRow(pair.first, pair.second, "SHIRT-${index + 1}", "VM-00${index + 1}", 250000, index.toLong())
    }

    @Test fun matrixCreatesIndependentlyStockedOptionsAndReplaysWithoutDuplicatingGoods() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val existing = repo.addProduct(shop, "Old shirts", "piece", 100000, 50.0)
            val review = repo.reviewVariantMatrix(shop, definition, rows())
            val batch = repo.createVariantMatrix(shop, review, "Counted each option", "matrix")
            assertEquals(batch, repo.createVariantMatrix(shop, review, "Counted each option", "matrix"))
            assertEquals(5, repo.products(shop).size)
            assertEquals(50L, repo.products(shop).single { it.id == existing.id }.exactStockScaled)
            batch.rows.forEach { row ->
                val item = repo.products(shop).single { it.id == row.productId }
                assertEquals(row.values.openingScaled, item.exactStockScaled)
                assertEquals(row.values.sku, item.sku)
                assertEquals(row.values.barcode, item.barcode)
                assertEquals(ProductVariant("Cotton shirt", variantMatrixLabel(definition, row.values)), repo.productVariants(shop)[item.id])
            }
            val item = repo.products(shop).single { it.id == batch.rows.last().productId }
            repo.checkout(shop, listOf(CartLine(item, 1)), item.priceMinor, operationId = "sale")
            repo.updateProduct(shop, item.id, "Renamed shirt", item.unit, 260000, item.barcode, "rename")
            val restored = repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop), "Restored")
            assertEquals(batch, repo.variantMatrixBatches(restored.id).single())
            assertEquals(2L, repo.products(restored.id).single { it.id == item.id }.exactStockScaled)
        }
    }

    @Test fun duplicateIdentifiersStaleReviewAndUnsupportedOptionsCannotPartlyCreateItems() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val review = repo.reviewVariantMatrix(shop, definition, rows())
            repo.addProduct(shop, "New item", "piece", 100, 1.0)
            val before = repo.exportTenantSnapshot(shop)
            assertFails { repo.createVariantMatrix(shop, review, "Stale catalogue", "stale") }
            assertEquals(before, repo.exportTenantSnapshot(shop))
            assertFails { repo.reviewVariantMatrix(shop, definition, rows().map { it.copy(sku = "SAME") }) }
            val conflict = rows().mapIndexed { index, row -> if (index == 3) row.copy(barcode = rows().first().barcode) else row }
            assertFails { repo.reviewVariantMatrix(shop, definition, conflict) }
            assertFails { variantMatrixCombinations(definition.copy(second = VariantOptionAxis("SIZE", listOf("One")))) }
            assertFails { variantMatrixCombinations(definition.copy(first = VariantOptionAxis("Size", listOf("S", "s")))) }
            assertEquals(before, repo.exportTenantSnapshot(shop))
        }
    }

    @Test fun measuredOptionsRestoreWithExactCountsAndRequireCompleteSourceHistory() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val d = VariantMatrixDefinition("Fabric", "metre", 2, VariantOptionAxis("Colour", listOf("Blue")))
            val row = VariantMatrixRow("Blue", null, "FABRIC-BLUE", null, 125000, 1375)
            val review = repo.reviewVariantMatrix(shop, d, listOf(row))
            val batch = repo.createVariantMatrix(shop, review, "Counted fabric length", "fabric")
            assertEquals(1375L, repo.products(shop).single().exactStockScaled)
            val snapshot = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
            assertEquals(batch, repo.variantMatrixBatches(restored.id).single())
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "variant_matrix_rows"), "Missing rows") }
            val modified = snapshot.tables.getValue("variant_matrix_rows").map { it + ("first_value" to "Red") }
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("variant_matrix_rows" to modified)), "Changed options") }
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.SELL) }
            assertFails { repo.reviewVariantMatrix(shop, d, listOf(row.copy(sku = "NEW"))) }
        }
    }
}
