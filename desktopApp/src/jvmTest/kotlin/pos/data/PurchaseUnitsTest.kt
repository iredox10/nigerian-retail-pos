package pos.data

import kotlin.test.*
import java.nio.file.Files
import java.sql.DriverManager

class PurchaseUnitsTest {
    @Test fun packageCreationRetriesAndNamesAreScopedToProductAndTenant() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A");val b=db.createTenant("B")
            val malt=db.addProduct(a.id,"Malt","bottle",50000,0.0)
            val oil=db.addProduct(a.id,"Oil","bottle",80000,0.0)
            val other=db.addProduct(b.id,"Malt","bottle",50000,0.0)
            val carton=db.addPurchaseUnit(a.id,malt.id," Carton ",24,"carton")
            assertEquals("Carton",carton.name);assertEquals("bottle",carton.baseUnit)
            assertEquals(carton,db.addPurchaseUnit(a.id,malt.id,"Carton",24,"carton"))
            assertFails {db.addPurchaseUnit(a.id,malt.id,"Carton",12,"carton")}
            assertFails {db.addPurchaseUnit(a.id,malt.id,"carton",12)}
            db.addPurchaseUnit(a.id,oil.id,"Carton",12)
            db.addPurchaseUnit(b.id,other.id,"Carton",24)
            assertEquals(2,db.purchaseUnits(a.id).size)
            assertFails {db.addPurchaseUnit(b.id,malt.id,"Pack",6)}
            assertFails {db.purchaseUnits(b.id,malt.id)}
            assertFails {db.archivePurchaseUnit(b.id,carton.id,true)}
            assertFails {db.addPurchaseUnit(a.id,malt.id,"bottle",1)}
            assertFails {db.addPurchaseUnit(a.id,malt.id,"Zero",0)}
        }
    }
    @Test fun archivedPackageStopsNewReceiptsButSavedReceiptRetryRemainsSafe() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop");val item=db.addProduct(t.id,"Malt","bottle",50000,0.0)
            val unit=db.addPurchaseUnit(t.id,item.id,"carton",24,"unit")
            val receipt=db.receiveStock(t.id,item.id,2,1000000,0,"Supplier","receipt",unit.id,24)
            db.archivePurchaseUnit(t.id,unit.id,true)
            val count=db.pendingSyncCount(t.id);db.archivePurchaseUnit(t.id,unit.id,true)
            assertEquals(count,db.pendingSyncCount(t.id))
            assertTrue(db.purchaseUnits(t.id,item.id).isEmpty())
            assertTrue(db.purchaseUnits(t.id,item.id,true).single().archived)
            assertEquals(receipt,db.receiveStock(t.id,item.id,2,1000000,0,"Supplier","receipt",unit.id,24))
            assertFails {db.receiveStock(t.id,item.id,1,1000000,0,"Supplier","new",unit.id,24)}
            assertEquals(48.0,db.products(t.id).single().stock)
            assertEquals(1,db.stockReceipts(t.id).size)
            assertFails {db.addPurchaseUnit(t.id,item.id,"CARTON",12)}
            db.archivePurchaseUnit(t.id,unit.id,false)
            db.receiveStock(t.id,item.id,1,1000000,0,"Supplier","new",unit.id,24)
            assertEquals(72.0,db.products(t.id).single().stock)
        }
    }
    @Test fun receivingRejectsForeignWrongProductAndUnreviewedConversionsBeforeWrites() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A");val b=db.createTenant("B")
            val item=db.addProduct(a.id,"Malt","bottle",50000,0.0)
            val different=db.addProduct(a.id,"Oil","bottle",80000,0.0)
            val other=db.addProduct(b.id,"Other","bottle",50000,0.0)
            val unit=db.addPurchaseUnit(a.id,item.id,"carton",24)
            val pending=db.pendingSyncCount(a.id)
            assertFails {db.receiveStock(b.id,other.id,1,1,0,"Supplier",purchaseUnitId=unit.id)}
            assertFails {db.receiveStock(a.id,different.id,1,1,0,"Supplier",purchaseUnitId=unit.id)}
            assertFails {db.receiveStock(a.id,item.id,1,1,0,"Supplier",purchaseUnitId=unit.id,expectedBaseUnitsPerUnit=12)}
            assertFails {db.receiveStock(a.id,item.id,1,1,0,"Supplier",expectedBaseUnitsPerUnit=24)}
            assertEquals(pending,db.pendingSyncCount(a.id));assertTrue(db.stockReceipts(a.id).isEmpty())
            assertEquals(0.0,db.products(a.id).first {it.id==item.id}.stock)
        }
    }
    @Test fun snapshotsRoundTripPackageAndSupplierHistoryAndAcceptLegacyReceipts() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop");val item=db.addProduct(t.id,"Malt","bottle",50000,0.0)
            val unit=db.addPurchaseUnit(t.id,item.id,"carton",24)
            val receipt=db.receiveStock(t.id,item.id,2,1000000,0,"Supplier",purchaseUnitId=unit.id)
            db.archivePurchaseUnit(t.id,unit.id,true)
            val restored=db.restoreTenantSnapshot(db.exportTenantSnapshot(t.id),"Restored")
            val restoredReceipt=db.stockReceipts(restored.id).single()
            assertEquals(receipt.copy(tenantId=restored.id),restoredReceipt)
            assertEquals(48L,restoredReceipt.baseQuantity)
            assertEquals(2000000L,db.supplierOutstanding(restored.id,receipt.id))
            assertTrue(db.purchaseUnits(restored.id,item.id,true).single().archived)
            val base=db.receiveStock(t.id,item.id,3,100,0,"Supplier")
            val snapshot=db.exportTenantSnapshot(t.id)
            val legacy=snapshot.copy(tables=snapshot.tables.filterKeys {it!="purchase_units"}+("stock_receipts" to snapshot.tables.getValue("stock_receipts").filter {it["id"]==base.id}.map {row->row.filterKeys {it !in setOf("base_unit","base_units_per_unit","purchase_unit_id")}}))
            val old=db.restoreTenantSnapshot(legacy,"Legacy")
            assertEquals("bottle",db.stockReceipts(old.id).single().baseUnit)
            assertEquals(3L,db.stockReceipts(old.id).single().baseQuantity)
        }
    }
    @Test fun oldDatabaseReceiptMigrationPreservesPurchaseAmounts() {
        val file=Files.createTempFile("pos-old-receipt-",".db")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:$file").use { db ->db.createStatement().use { s ->
                s.execute("CREATE TABLE tenants(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
                s.execute("CREATE TABLE products(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,unit TEXT NOT NULL,price_minor INTEGER NOT NULL,stock REAL NOT NULL,PRIMARY KEY(tenant_id,id))")
                s.execute("CREATE TABLE stock_receipts(id TEXT NOT NULL,tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,unit TEXT NOT NULL,quantity INTEGER NOT NULL,unit_cost_minor INTEGER NOT NULL,total_cost_minor INTEGER NOT NULL,paid_cash_minor INTEGER NOT NULL,supplier_name TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
                s.execute("INSERT INTO tenants VALUES('shop','Shop')")
                s.execute("INSERT INTO products VALUES('item','shop','Malt','bottle',50000,10)")
                s.execute("INSERT INTO stock_receipts VALUES('receipt','shop','item','Malt','bottle',10,40000,400000,0,'Supplier','2026-11-12T00:00:00Z')")
            } }
            PosRepository(file.toString()).use { db ->
                val old=db.stockReceipts("shop").single()
                assertEquals(10L,old.baseQuantity);assertEquals(400000L,old.totalCostMinor);assertNull(old.purchaseUnitId)
                val pack=db.addPurchaseUnit("shop","item","pack",6)
                db.receiveStock("shop","item",2,200000,0,"Supplier",purchaseUnitId=pack.id)
                assertEquals(22.0,db.products("shop").single().stock)
            }
            PosRepository(file.toString()).use { db ->assertEquals(2,db.stockReceipts("shop").size);assertEquals(22.0,db.products("shop").single().stock) }
        } finally {Files.deleteIfExists(file)}
    }
}
