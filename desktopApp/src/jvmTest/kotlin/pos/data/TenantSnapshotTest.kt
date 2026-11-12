package pos.data

import kotlin.test.*
import pos.domain.CartLine

class TenantSnapshotTest {
    @Test fun exportIncludesOnlySelectedTenantAndRestoreUsesNewIdentity() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A"); val b=db.createTenant("B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,10.0)
            db.addProduct(b.id,"Private goods","piece",10000,3.0)
            val c=db.addCustomer(a.id,"Grace")
            val shift=db.openCashShift(a.id,100000)
            val sale=db.checkout(a.id,listOf(CartLine(p,2)),50000,c.id)
            db.recordRepayment(a.id,c.id,10000,"repay")
            val purchase=db.receiveStock(a.id,p.id,2,10000,0,"Supplier")
            db.paySupplierReceipt(a.id,purchase.id,10000,"pay")
            val cashSale=db.checkout(a.id,listOf(CartLine(p,1)),50000)
            db.returnItems(a.id,cashSale.id,mapOf(p.id to 1),true,"Return","return")
            val snapshot=db.exportTenantSnapshot(a.id)
            assertTrue(snapshot.tables.values.flatten().all { it["tenant_id"]==a.id })
            assertFalse(snapshot.tables.values.flatten().any { it.values.contains("Private goods") })
            var provisioned=""
            val restored=db.restoreTenantSnapshot(snapshot,"A restored") { provisioned=it.id }
            assertNotEquals(a.id,restored.id)
            assertEquals(restored.id,provisioned)
            assertEquals("A restored",restored.name)
            assertEquals(db.products(a.id).single().stock,db.products(restored.id).single().stock)
            assertTrue(db.sales(restored.id).any { it.id==sale.id })
            assertEquals(1,db.saleReturns(restored.id).size)
            assertEquals(40000L,db.customers(restored.id).single().balanceMinor)
            assertEquals(10000L,db.supplierOutstanding(restored.id,purchase.id))
            assertEquals(db.expectedCash(a.id,shift.id),db.expectedCash(restored.id,shift.id))
            assertEquals(db.pendingSyncCount(a.id),db.pendingSyncCount(restored.id))
            assertEquals(1,db.products(b.id).size)
        }
    }
    @Test fun foreignRowsExtraTablesColumnsAndMalformedNumbersAreRejectedWithoutCreatingTenant() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val original=db.exportTenantSnapshot(t.id)
            val row=original.tables.getValue("products").single()
            val invalid=listOf(
                original.copy(tables=original.tables+("products" to listOf(row+("tenant_id" to "foreign")))) ,
                original.copy(tables=original.tables+("credentials" to emptyList())),
                original.copy(tables=original.tables+("products" to listOf(row+("unknown" to "data")))) ,
                original.copy(tables=original.tables+("products" to listOf(row+("price_minor" to "bad"))))
            )
            invalid.forEach { snapshot ->
                assertFailsWith<IllegalArgumentException> { db.restoreTenantSnapshot(snapshot,"Invalid restore") }
                assertEquals(1,db.tenants().size)
            }
        }
    }
    @Test fun foreignKeyFailureAndOwnerProvisionFailureRollbackAllImportedRecords() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); val p=db.addProduct(t.id,"Malt","bottle",50000,10.0)
            db.checkout(t.id,listOf(CartLine(p,1)),50000)
            val original=db.exportTenantSnapshot(t.id)
            val broken=original.copy(tables=original.tables+("sale_lines" to original.tables.getValue("sale_lines").map { it+("sale_id" to "missing-sale") }))
            assertFails { db.restoreTenantSnapshot(broken,"Broken") }
            assertFailsWith<IllegalStateException> { db.restoreTenantSnapshot(original,"Provision fail") { error("Cannot provision owner") } }
            assertEquals(1,db.tenants().size)
            assertEquals(1,db.sales(t.id).size)
        }
    }
    @Test fun optionalNewTablesMayBeMissingFromOlderBackup() {
        PosRepository(":memory:").use { db ->
            val t=db.createTenant("Shop"); db.addProduct(t.id,"Malt","bottle",50000,10.0)
            val snapshot=db.exportTenantSnapshot(t.id)
            val older=snapshot.copy(tables=snapshot.tables.filterKeys { it=="products" })
            val restored=db.restoreTenantSnapshot(older,"Older copy")
            assertEquals(1,db.products(restored.id).size)
            assertTrue(db.saleReturns(restored.id).isEmpty())
        }
    }
}
