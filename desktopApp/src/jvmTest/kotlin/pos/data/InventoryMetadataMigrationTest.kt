package pos.data

import kotlin.test.*
import java.nio.file.Files
import java.sql.DriverManager

class InventoryMetadataMigrationTest {
    @Test fun openingAndReorderHistorySurviveBackupWithoutChangingStock() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val product=repo.addProduct(shop.id,"Soap","piece",10000,12.0)
            assertEquals(12.0,product.openingStock)
            assertNotNull(product.createdAt)
            repo.setReorderLevel(shop.id,product.id,10,"first")
            repo.setReorderLevel(shop.id,product.id,20,"second")
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id),"Restored")
            val recovered=repo.products(restored.id).single()
            assertEquals(12.0,recovered.stock);assertEquals(12.0,recovered.openingStock)
            assertEquals(product.createdAt,recovered.createdAt);assertEquals(20,recovered.reorderLevel)
            assertEquals(listOf(null,10),repo.stockSettingsChanges(restored.id).map {it.previousReorderLevel})
            assertEquals(listOf(10,20),repo.stockSettingsChanges(restored.id).map {it.reorderLevel})
            assertEquals(1,repo.inventoryMovements(restored.id,product.id).size)
            assertEquals(listOf(product.id),repo.lowStockProducts(restored.id).map {it.id})
        }
    }
    @Test fun actualOldDatabaseRetainsCurrentStockAndUnknownOpeningAfterMigration() {
        val file=Files.createTempFile("old-inventory-",".db")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use { s ->
                s.execute("CREATE TABLE tenants(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
                s.execute("CREATE TABLE products(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL,stock REAL NOT NULL,PRIMARY KEY(tenant_id,id))")
                s.execute("INSERT INTO tenants VALUES('shop','Old shop')")
                s.execute("INSERT INTO products VALUES('soap','shop','Soap','piece',10000,17)")
            } }
            PosRepository(file.toString()).use { repo ->
                val p=repo.products("shop").single()
                assertEquals(17.0,p.stock);assertNull(p.openingStock);assertNull(p.createdAt)
                assertNull(p.reorderLevel);assertTrue(repo.inventoryMovements("shop",p.id).isEmpty())
                repo.setReorderLevel("shop",p.id,20,"reminder")
            }
            PosRepository(file.toString()).use { repo ->
                assertEquals(20,repo.products("shop").single().reorderLevel)
                assertNull(repo.products("shop").single().openingStock)
                assertEquals(1,repo.stockSettingsChanges("shop").size)
            }
        }finally {Files.deleteIfExists(file)}
    }
}
