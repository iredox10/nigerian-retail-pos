package pos.data

import kotlin.test.*
import pos.domain.CartLine
import java.nio.file.Files
import java.sql.DriverManager

class CatalogueOperationsTest {
    @Test fun editsPreserveSaleSnapshotsAndRecordPriceHistoryOnce() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop");val p=db.addProduct(t.id,"Old name","piece",15001,9.0,"00123")
            val sale=db.checkout(t.id,listOf(CartLine(p,1)),15001)
            val changed=db.updateProduct(t.id,p.id,"New name",p.unit,19999,"00124","edit-1")
            assertEquals("New name",changed.name);assertEquals(19999L,changed.priceMinor)
            db.updateProduct(t.id,p.id,"New name",p.unit,19999,"00124","edit-1")
            assertEquals(1,db.priceHistory(t.id,p.id).size)
            assertEquals(15001L,db.priceHistory(t.id,p.id).single().previousPriceMinor)
            assertEquals("Old name",db.sales(t.id).single().lines.single().product.name)
            assertEquals(sale.totalMinor,db.sales(t.id).single().totalMinor)
            assertFails { db.updateProduct(t.id,p.id,"Other",p.unit,19999,"00124","edit-1") }
            assertFails { db.updateProduct(t.id,p.id,"New name","carton",19999,"00124") }
            assertFails { db.checkout(t.id,listOf(CartLine(p,1)),15001) }
        }
    }
    @Test fun barcodesAreExactUniqueWithinTenantAndSafeForArchivedProducts() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A");val b=db.createTenant("B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,10.0," 001234 ")
            val other=db.addProduct(b.id,"Other shop malt","bottle",80000,10.0,"001234")
            assertEquals(p.id,db.productByBarcode(a.id,"001234")?.id)
            assertEquals(other.id,db.productByBarcode(b.id,"001234")?.id)
            assertNull(db.productByBarcode(a.id,"1234"))
            assertFails { db.addProduct(a.id,"Duplicate","piece",1,1.0,"001234") }
            assertEquals(1,db.products(a.id).size)
            db.archiveProduct(a.id,p.id,true,"archive")
            assertNull(db.productByBarcode(a.id,"001234"))
            assertFails { db.addProduct(a.id,"Duplicate archived","piece",1,1.0,"001234") }
            assertFails { db.addProduct(a.id,"Bad code","piece",1,1.0,"a\nb") }
            assertFails { db.addProduct(a.id,"Long code","piece",1,1.0,"a".repeat(129)) }
        }
    }
    @Test fun archivedItemsAreHiddenAndCannotBeSoldReceivedOrCountedButHistorySurvives() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop");val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val sale=db.checkout(t.id,listOf(CartLine(p,1)),50000,operationId="sale")
            db.archiveProduct(t.id,p.id,true,"archive")
            val pending=db.pendingSyncCount(t.id)
            db.archiveProduct(t.id,p.id,true,"archive")
            assertEquals(pending,db.pendingSyncCount(t.id))
            assertTrue(db.products(t.id).isEmpty());assertTrue(db.products(t.id,true).single().archived)
            assertEquals(sale.id,db.checkout(t.id,listOf(CartLine(p,1)),50000,operationId="sale").id)
            assertEquals(1,db.sales(t.id).size)
            assertFails { db.checkout(t.id,listOf(CartLine(p,1)),50000) }
            assertFails { db.receiveStock(t.id,p.id,1,100,0,"Supplier") }
            assertFails { db.adjustStock(t.id,p.id,8,"Count") }
            assertFails { db.archiveProduct(t.id,p.id,false,"archive") }
            db.archiveProduct(t.id,p.id,false,"restore")
            assertEquals(p.id,db.products(t.id).single().id)
            assertEquals(sale.id,db.sales(t.id).single().id)
        }
    }
    @Test fun countedCorrectionsPreserveBeforeAfterAndRetriesDoNotApplyTwice() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop");val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val correction=db.adjustStock(t.id,p.id,7,"Two damaged, one missing","count",expectedStock=10.0)
            assertEquals(10.0,correction.previousStock);assertEquals(7,correction.countedStock);assertEquals(-3.0,correction.difference)
            db.checkout(t.id,listOf(CartLine(p,1)),50000)
            val pending=db.pendingSyncCount(t.id)
            assertEquals(correction,db.adjustStock(t.id,p.id,7,"Two damaged, one missing","count",expectedStock=10.0))
            assertEquals(6.0,db.products(t.id).single().stock)
            assertEquals(pending,db.pendingSyncCount(t.id))
            assertFails { db.adjustStock(t.id,p.id,9,"Other","count") }
            assertFails { db.adjustStock(t.id,p.id,9,"Stale count",expectedStock=7.0) }
            assertFails { db.adjustStock(t.id,p.id,-1,"Invalid") }
            assertFails { db.adjustStock(t.id,p.id,0," ") }
            assertEquals(1,db.stockAdjustments(t.id).size)
            db.adjustStock(t.id,p.id,0,"Write off spoiled stock",expectedStock=6.0)
            assertEquals(0.0,db.products(t.id).single().stock)
        }
    }
    @Test fun catalogueAndCountOperationsRejectForeignProductReferences() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A");val b=db.createTenant("B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,10.0)
            assertFails { db.updateProduct(b.id,p.id,"Bad",p.unit,1,null) }
            assertFails { db.archiveProduct(b.id,p.id,true) }
            assertFails { db.adjustStock(b.id,p.id,1,"Bad") }
            assertFails { db.stockAdjustments(b.id,p.id) }
            assertFails { db.priceHistory(b.id,p.id) }
            assertTrue(db.stockAdjustments(b.id).isEmpty())
            assertEquals(p,db.products(a.id).single())
        }
    }
    @Test fun snapshotsPreserveNewHistoryAndAcceptOlderProductRows() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop");val p=db.addProduct(t.id,"Malt","bottle",50000,10.0,"123")
            db.updateProduct(t.id,p.id,"Malt new",p.unit,60000,"123","edit")
            db.adjustStock(t.id,p.id,8,"Damage","count")
            db.archiveProduct(t.id,p.id,true,"archive")
            val snapshot=db.exportTenantSnapshot(t.id)
            val restored=db.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(8.0,db.products(restored.id,true).single().stock)
            assertEquals("123",db.products(restored.id,true).single().barcode)
            assertTrue(db.products(restored.id).isEmpty())
            assertEquals(1,db.stockAdjustments(restored.id).size)
            assertEquals(1,db.priceHistory(restored.id).size)
            val old=snapshot.copy(tables=mapOf("products" to snapshot.tables.getValue("products").map { row -> row.filterKeys { it !in setOf("barcode","archived") } }))
            val oldRestore=db.restoreTenantSnapshot(old,"Old format")
            assertNull(db.products(oldRestore.id).single().barcode)
            val malicious=snapshot.copy(tables=snapshot.tables+("stock_adjustments" to snapshot.tables.getValue("stock_adjustments").map { it+("counted_stock" to "4294967297") }))
            val tenants=db.tenants().size
            assertFails { db.restoreTenantSnapshot(malicious,"Overflow") }
            assertEquals(tenants,db.tenants().size)
        }
    }
    @Test fun olderDatabaseMigratesCatalogueColumnsWithoutLosingRows() {
        val file=Files.createTempFile("retail-pos-old-catalogue",".db")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
                db.createStatement().use { s ->
                    s.execute("CREATE TABLE tenants(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
                    s.execute("CREATE TABLE products(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL,stock REAL NOT NULL,PRIMARY KEY(tenant_id,id))")
                    s.execute("INSERT INTO tenants VALUES('shop','Shop')")
                    s.execute("INSERT INTO products VALUES('product','shop','Old item','piece',10000,4)")
                }
            }
            PosRepository(file.toString()).use { db ->
                val p=db.products("shop").single()
                assertNull(p.barcode);assertFalse(p.archived)
                db.updateProduct("shop",p.id,p.name,p.unit,12000,"001","edit")
                db.adjustStock("shop",p.id,3,"Count","count")
            }
            PosRepository(file.toString()).use { db ->
                assertEquals(3.0,db.products("shop").single().stock)
                assertEquals("001",db.productByBarcode("shop","001")?.barcode)
                assertEquals(1,db.priceHistory("shop").size)
            }
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun repaymentsQueryKeepsCustomerAndTenantBoundaries() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A");val b=db.createTenant("B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,10.0)
            val c=db.addCustomer(a.id,"Grace");val other=db.addCustomer(a.id,"Other")
            db.checkout(a.id,listOf(CartLine(p,1)),0,c.id)
            db.recordRepayment(a.id,c.id,12345,"repayment")
            assertEquals(12345L,db.repayments(a.id,c.id).single().amountMinor)
            assertTrue(db.repayments(a.id,other.id).isEmpty());assertTrue(db.repayments(b.id).isEmpty())
            assertFails { db.repayments(b.id,c.id) }
        }
    }
}
