package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.nio.file.Files
import java.sql.DriverManager

class InventoryDispositionIntegrityTest {
    private fun PosRepository.fixture():Pair<Tenant,Product> {
        initializeInventoryDispositions()
        val shop=createTenant("Stock removals")
        return shop to addProduct(shop.id,"Loose rice","kg",100000,10.125,quantityPrecision=3)
    }
    @Test fun classifiedMeasuredRemovalHasOneMovementAndNoSaleCashExpenseOrCount() {
        PosRepository(":memory:").use {repo ->
            val(shop,item)=repo.fixture()
            val baseline=repo.inventoryDispositionBaseline(shop.id,item.id)
            repo.setActorProvider {tenant ->AuditActor(tenant,"manager","Sample manager",StaffRole.MANAGER)}
            val removal=repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.DAMAGE,"Bag torn","During unloading",baseline,"rice-damage")
            assertEquals("0.125",removal.quantityLabel)
            assertEquals("10",repo.products(shop.id).single().stockLabel)
            val movements=repo.inventoryMovements(shop.id,item.id).filter {it.kind=="disposition"}
            assertEquals(1,movements.size)
            assertEquals(removal.id,movements.single().sourceId)
            assertEquals(-0.125,movements.single().quantityDelta)
            assertTrue(repo.stockAdjustments(shop.id).isEmpty())
            assertTrue(repo.sales(shop.id).isEmpty())
            assertTrue(repo.cashEntries(shop.id).isEmpty())
            assertTrue(repo.expenses(shop.id).isEmpty())
            val actor=repo.auditEvents(shop.id).single {it.kind=="stock.disposed"}.actor
            assertEquals("Sample manager",actor?.name)
            repo.validateInventoryDispositions(shop.id)
        }
    }
    @Test fun largeLongRemovalPreservesStockAndUnsupportedProjectionRollsBack() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Large quantities")
            val item=repo.addProduct(shop.id,"Bulk stock","piece",1,5_000_000_000.0)
            repo.recordInventoryDisposition(shop.id,item.id,3_000_000_000L,InventoryDispositionCategory.OWNER_USE,"Owner use","",repo.inventoryDispositionBaseline(shop.id,item.id))
            assertEquals(2_000_000_000L,repo.products(shop.id).single().exactStockScaled)
            val fine=repo.addProduct(shop.id,"Fine bulk","litre",1,9_000_000_000.0,quantityPrecision=6)
            val baseline=repo.inventoryDispositionBaseline(shop.id,fine.id)
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.recordInventoryDisposition(shop.id,fine.id,1,InventoryDispositionCategory.SAMPLE,"Sample","",baseline)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun retryConflictOverdrawAndDeniedReplayNeverDeductAgain() {
        PosRepository(":memory:").use {repo ->
            val(shop,item)=repo.fixture()
            val baseline=repo.inventoryDispositionBaseline(shop.id,item.id)
            val saved=repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.DONATION,"Community donation","",baseline,"donation")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            assertEquals(saved,repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.DONATION,"Community donation","",baseline,"donation"))
            assertFails {repo.recordInventoryDisposition(shop.id,item.id,126,InventoryDispositionCategory.DONATION,"Community donation","",baseline,"donation")}
            val current=repo.inventoryDispositionBaseline(shop.id,item.id)
            assertFails {repo.recordInventoryDisposition(shop.id,item.id,current.stockScaled+1,InventoryDispositionCategory.THEFT_LOSS,"Missing goods","",current)}
            assertEquals(snapshot,repo.exportTenantSnapshot(shop.id))
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.BACKUP)}
            assertFails {repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.DONATION,"Community donation","",baseline,"donation")}
            repo.setPermissionCheck(null) // Trusted inspection after the denied operation.
            assertEquals(snapshot,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun staleBaselineIncludingNetZeroMovementsAndOtherTenantIsRejected() {
        PosRepository(":memory:").use {repo ->
            val(shop,item)=repo.fixture()
            val other=repo.createTenant("Other shop")
            val baseline=repo.inventoryDispositionBaseline(shop.id,item.id)
            repo.openCashShift(shop.id,100000)
            val sale=repo.checkout(shop.id,listOf(CartLine(item,125)),12500,operationId="sale")
            repo.returnItems(shop.id,sale.id,mapOf(item.id to 125),true,"Returned goods","return")
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.SPOILAGE_EXPIRY,"Expired goods","",baseline)}
            assertFails {repo.recordInventoryDisposition(other.id,item.id,125,InventoryDispositionCategory.SPOILAGE_EXPIRY,"Expired goods","",baseline)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun actorFailureRollsBackStockHistoryAndOutboxTogether() {
        PosRepository(":memory:").use {repo ->
            val(shop,item)=repo.fixture()
            val baseline=repo.inventoryDispositionBaseline(shop.id,item.id)
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setActorProvider {throw IllegalArgumentException("No active recording identity")}
            assertFails {repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.THEFT_LOSS,"Missing goods","",baseline)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun reopenBackupAndImmutableHistoryPreserveRemovalAndRejectOmissions() {
        val directory=Files.createTempDirectory("disposition-restart-")
        val file=directory.resolve("shop.db")
        var tenant="";var product=""
        try {
            PosRepository(file.toString()).use {repo ->
                val(shop,item)=repo.fixture();tenant=shop.id;product=item.id
                repo.recordInventoryDisposition(shop.id,item.id,125,InventoryDispositionCategory.SAMPLE,"Display sample","",repo.inventoryDispositionBaseline(shop.id,item.id),"sample")
                val snapshot=repo.exportTenantSnapshot(shop.id)
                assertTrue(snapshot.tables.containsKey("inventory_dispositions"),"Root must wire disposition backup tables")
                val backup=directory.resolve("stock-removals.retailbackup")
                BackupService.create(repo,shop.id,"sample-backup-password",backup)
                val decoded=BackupService.read(backup,"sample-backup-password")
                assertEquals(snapshot,decoded)
                val restored=repo.restoreTenantSnapshot(decoded,"Recovered stock removals")
                assertEquals("10",repo.products(restored.id).single().stockLabel)
                assertEquals("0.125",repo.inventoryDispositions(restored.id).single().quantityLabel)
                val before=repo.tenants()
                assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-inventoryDispositionSnapshotTables.toSet()),"Missing removal history")}
                val bad=snapshot.copy(tables=snapshot.tables+("inventory_dispositions" to snapshot.tables.getValue("inventory_dispositions").map {it+("after_scaled" to "1")}))
                assertFails {repo.restoreTenantSnapshot(bad,"Corrupt stock removal")}
                assertEquals(before,repo.tenants())
            }
            PosRepository(file.toString()).use {repo ->
                repo.initializeInventoryDispositions()
                assertEquals("10",repo.products(tenant).single {it.id==product}.stockLabel)
                assertEquals("sample",repo.inventoryDispositions(tenant).single().id)
            }
            DriverManager.getConnection("jdbc:sqlite:$file").use {db ->
                assertFails {db.createStatement().use {it.executeUpdate("DELETE FROM inventory_dispositions")}}
                assertFails {db.createStatement().use {it.executeUpdate("UPDATE inventory_dispositions SET reason='changed'")}}
            }
        } finally {directory.toFile().deleteRecursively()}
    }
}
