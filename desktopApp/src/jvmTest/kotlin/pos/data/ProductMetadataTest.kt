package pos.data

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.sql.DriverManager

class ProductMetadataTest {
    @Test fun metadataIsSearchableAndDoesNotChangeMoneyOrStockAndRetriesCannotOverwriteLaterChanges() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,24.0)
            val first=ProductMetadata("MALT-01","Drinks","Example",listOf("Malta","Malt | bottle"),true)
            val saved=repo.updateProductMetadata(shop.id,item.id,first,"first",item.metadata())
            assertEquals(first,saved.metadata())
            listOf("malt-01","drinks","EXAMPLE","malta","malt | bottle").forEach {assertTrue(saved.matchesCatalogueSearch(it))}
            assertEquals(item.stock,saved.stock);assertEquals(item.priceMinor,saved.priceMinor)
            val next=first.copy(favourite=false,category="Beverages")
            repo.updateProductMetadata(shop.id,item.id,next,"next",first)
            repo.updateProductMetadata(shop.id,item.id,first,"first",item.metadata())
            assertEquals(next,repo.products(shop.id).single().metadata())
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.updateProductMetadata(shop.id,item.id,first,"stale",first)}
            assertFails {repo.updateProductMetadata(shop.id,item.id,next,"first")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val history=repo.productMetadataChanges(shop.id,item.id)
            assertEquals(listOf(1L,2L),history.map {it.sequence})
            assertEquals(item.metadata(),history.first().previous)
            assertEquals(first,history.last().previous)
            assertEquals(2,repo.auditEvents(shop.id).count {it.kind=="product.metadata_changed"})
        }
    }

    @Test fun skuUniquenessIncludesArchivedGoodsButAllowsSeparateShopsAndPermissionsAreEnforced() {
        PosRepository(":memory:").use { repo ->
            val a=repo.createTenant("A"); val b=repo.createTenant("B")
            val first=repo.addProduct(a.id,"First","piece",1,0.0)
            val second=repo.addProduct(a.id,"Second","piece",1,0.0)
            val foreign=repo.addProduct(b.id,"Other","piece",1,0.0)
            repo.updateProductMetadata(a.id,first.id,ProductMetadata(sku="ABC"),"sku")
            repo.archiveProduct(a.id,first.id,true)
            assertFails {repo.updateProductMetadata(a.id,second.id,ProductMetadata(sku="abc"),"duplicate")}
            repo.updateProductMetadata(b.id,foreign.id,ProductMetadata(sku="abc"),"sku")
            assertFails {repo.updateProductMetadata(a.id,foreign.id,ProductMetadata(),"foreign")}
            assertFails {repo.productMetadataChanges(a.id,foreign.id)}
            repo.setPermissionCheck {_,permission -> require(permission!=StaffPermission.MANAGE_STOCK)}
            assertFails {repo.updateProductMetadata(a.id,second.id,ProductMetadata(brand="Brand"),"denied")}
        }
    }

    @Test fun backupPreservesMetadataAndRejectsInvalidHistoryOrAliasesWithoutCreatingShop() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val metadata=ProductMetadata("S-1","Cleaning","Brand",listOf("Soap | large","Sabulu"),true)
            repo.updateProductMetadata(shop.id,item.id,metadata,"metadata")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val restored=repo.restoreTenantSnapshot(snapshot,"Restored")
            assertEquals(metadata,repo.products(restored.id).single().metadata())
            assertEquals(metadata,repo.productMetadataChanges(restored.id,item.id).single().current)
            val before=repo.tenants()
            listOf("aliases" to "bad\n\nname", "sku" to "forged", "sequence" to "2").forEach { change ->
                val forged=snapshot.copy(tables=snapshot.tables+("product_metadata_changes" to snapshot.tables.getValue("product_metadata_changes").map {it+change}))
                assertFails {repo.restoreTenantSnapshot(forged,"Invalid")}
                assertEquals(before,repo.tenants())
            }
            val legacy=snapshot.copy(tables=(snapshot.tables-"product_metadata_changes").mapValues { (table,rows) -> if(table=="products") rows.map {it.filterKeys {k -> k !in setOf("sku","category","brand","aliases","favourite")}} else rows })
            val old=repo.restoreTenantSnapshot(legacy,"Legacy")
            assertEquals(ProductMetadata(),repo.products(old.id).single().metadata())
        }
    }

    @Test fun upgradeReopenAndImmutableHistoryAreSupported() {
        val dir=Files.createTempDirectory("metadata")
        try {
            val file=dir.resolve("pos.db").toString()
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:$file").use {db -> db.createStatement().use {
                it.execute("CREATE TABLE tenants(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
                it.execute("CREATE TABLE products(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL,stock REAL NOT NULL,PRIMARY KEY(tenant_id,id))")
                it.execute("INSERT INTO tenants VALUES('shop','Shop')")
                it.execute("INSERT INTO products VALUES('item','shop','Soap','piece',100,5)")
            }}
            PosRepository(file).use {repo ->
                assertEquals(ProductMetadata(),repo.products("shop").single().metadata())
                repo.updateProductMetadata("shop","item",ProductMetadata(aliases=listOf("Local name")),"change")
            }
            PosRepository(file).use {repo ->
                assertEquals(listOf("Local name"),repo.products("shop").single().aliases)
                assertEquals(1,repo.productMetadataChanges("shop","item").size)
                DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use {
                    assertFails {it.executeUpdate("UPDATE product_metadata_changes SET brand='changed'")}
                    assertFails {it.executeUpdate("DELETE FROM product_metadata_changes")}
                }}
            }
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun namesAreBoundedAndAliasesCannotBreakTheirEncoding() {
        assertFails {ProductMetadata(sku=" padded ")}
        assertFails {ProductMetadata(aliases=listOf("one\ntwo"))}
        assertFails {ProductMetadata(aliases=listOf("Name","name"))}
        assertFails {ProductMetadata(aliases=List(21) {"Alias $it"})}
        val names=listOf("Name | with separator","Other name")
        assertEquals(names,decodeProductAliases(encodeProductAliases(names)))
    }
}
