package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.nio.file.Files
import java.sql.DriverManager

class RepackingIntegrityTest {
    private data class Fixture(val shop:Tenant,val source:Product,val target:Product,val recipe:RepackingRecipe)
    private fun PosRepository.fixture():Fixture {
        initializeRepacking()
        val shop=createTenant("Repacking shop")
        val source=addProduct(shop.id,"Rice bag","bag",5000000,5.0)
        val target=addProduct(shop.id,"Loose rice","kg",100000,2.125,quantityPrecision=3)
        val details=RepackingRecipeDetails("Open rice bag",source.id,source.name,source.unit,source.quantityPrecision,1,target.id,target.name,target.unit,target.quantityPrecision,50000)
        return Fixture(shop,source,target,saveRepackingRecipe(shop.id,"rice-recipe",details,0,"Measured 50 kg per bag","recipe-created"))
    }
    @Test fun explicitRecipePostsTwoExactMovementsAndNoFinancialEvent() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val review=repo.repackingReview(f.shop.id,f.recipe.id,2)
            repo.setActorProvider {AuditActor(it,"manager","Sample manager",StaffRole.MANAGER)}
            val run=repo.executeRepacking(f.shop.id,f.recipe.id,2,"Open two checked bags",review,"run")
            val products=repo.products(f.shop.id).associateBy {it.id}
            assertEquals(3L,products.getValue(f.source.id).exactStockScaled)
            assertEquals(102125L,products.getValue(f.target.id).exactStockScaled)
            assertEquals(-2.0,repo.inventoryMovements(f.shop.id,f.source.id).single {it.kind=="repacking_out"}.quantityDelta)
            assertEquals(100.0,repo.inventoryMovements(f.shop.id,f.target.id).single {it.kind=="repacking_in"}.quantityDelta)
            assertTrue(repo.cashEntries(f.shop.id).isEmpty());assertTrue(repo.sales(f.shop.id).isEmpty());assertTrue(repo.expenses(f.shop.id).isEmpty());assertTrue(repo.stockReceipts(f.shop.id).isEmpty())
            assertEquals("Sample manager",repo.auditEvents(f.shop.id).single {it.kind=="stock.repacked"}.actor?.name)
            assertEquals(run,repo.repackingHistory(f.shop.id).single());repo.validateRepacking(f.shop.id)
        }
    }
    @Test fun stableRetryCannotRepackAgainAndChangedRequestRollsBack() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val review=repo.repackingReview(f.shop.id,f.recipe.id,1)
            val run=repo.executeRepacking(f.shop.id,f.recipe.id,1,"Open checked bag",review,"run")
            val saved=repo.exportTenantSnapshot(f.shop.id)
            assertEquals(run,repo.executeRepacking(f.shop.id,f.recipe.id,1,"Open checked bag",review,"run"))
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,2,"Open checked bag",review,"run")}
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,1,"Different reason",review,"run")}
            assertEquals(saved,repo.exportTenantSnapshot(f.shop.id))
            repo.setPermissionCheck {_,_->error("Denied")}
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,1,"Open checked bag",review,"run")}
        }
    }
    @Test fun staleNetZeroMovementsAndRecipeRevisionRequireNewReview() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val review=repo.repackingReview(f.shop.id,f.recipe.id,1)
            repo.openCashShift(f.shop.id,0)
            val sale=repo.checkout(f.shop.id,listOf(CartLine(f.source,1)),f.source.priceMinor)
            repo.returnItems(f.shop.id,sale.id,mapOf(f.source.id to 1),true,"Returned unopened bag")
            val before=repo.exportTenantSnapshot(f.shop.id)
            assertEquals(5L,repo.products(f.shop.id).single {it.id==f.source.id}.exactStockScaled)
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,1,"Stale physical review",review,"run")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            val fresh=repo.repackingReview(f.shop.id,f.recipe.id,1)
            repo.saveRepackingRecipe(f.shop.id,f.recipe.id,f.recipe.details.copy(destinationPerBatch=49000),1,"Actual usable amount changed","revision")
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,1,"Stale recipe review",fresh,"run")}
            assertEquals(2L,repo.repackingRecipes(f.shop.id).single().version)
        }
    }
    @Test fun insufficientSourceArchivedRenamedOrForeignGoodsCannotBeRepacked() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.repackingReview(f.shop.id,f.recipe.id,6)}
            assertFails {repo.repackingReview(f.shop.id,f.recipe.id,0)}
            val foreign=repo.createTenant("Other shop")
            assertFails {repo.repackingReview(foreign.id,f.recipe.id,1)}
            assertFails {repo.saveRepackingRecipe(foreign.id,"foreign",f.recipe.details,0,"Foreign conversion")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.updateProduct(f.shop.id,f.source.id,"Different bag",f.source.unit,f.source.priceMinor,null)
            assertFails {repo.repackingReview(f.shop.id,f.recipe.id,1)}
            val current=repo.products(f.shop.id).single {it.id==f.source.id}
            val revised=repo.saveRepackingRecipe(f.shop.id,f.recipe.id,f.recipe.details.copy(sourceName=current.name),1,"Explicitly reviewed renamed bag")
            repo.archiveProduct(f.shop.id,f.target.id,true)
            assertFails {repo.repackingReview(f.shop.id,revised.id,1)}
        }
    }
    @Test fun interruptedSecondStockWriteAndLateActorFailureRollBackBothSides() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val review=repo.repackingReview(f.shop.id,f.recipe.id,1);val before=repo.exportTenantSnapshot(f.shop.id)
            repo.featureSchema("CREATE TRIGGER reject_repacking_target BEFORE UPDATE OF stock ON products WHEN OLD.id='${f.target.id}' BEGIN SELECT RAISE(ABORT,'Injected target failure'); END")
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,1,"Both movements must save",review,"run")}
            repo.featureSchema("DROP TRIGGER reject_repacking_target")
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.setActorProvider {error("Actor revoked")}
            assertFails {repo.executeRepacking(f.shop.id,f.recipe.id,1,"Actor was revoked",review,"run")}
            repo.setActorProvider(null);assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun repackingInvalidatesAnAlreadyStartedPhysicalCount() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture()
            val session=repo.createStockCountSession(f.shop.id,"Count rice bags",listOf(f.source.id))
            repo.executeRepacking(f.shop.id,f.recipe.id,1,"Repack after count started",repo.repackingReview(f.shop.id,f.recipe.id,1))
            repo.saveStockCountDraft(f.shop.id,session.id,mapOf(f.source.id to 5),"Physical count",session.version)
            assertFails {repo.postStockCountSession(f.shop.id,session.id,session.version+1)}
        }
    }
    @Test fun encryptedRestoreKeepsRecipeVersionsRunsAndRejectsMissingOrTamperedHistory() {
        val directory=Files.createTempDirectory("repacking-backup-")
        try {PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val run=repo.executeRepacking(f.shop.id,f.recipe.id,1,"Open first bag",repo.repackingReview(f.shop.id,f.recipe.id,1),"run")
            repo.saveRepackingRecipe(f.shop.id,f.recipe.id,f.recipe.details.copy(name="New recipe name"),1,"Rename future batches")
            val file=directory.resolve("shop.backup");BackupService.create(repo,f.shop.id,"repacking-password",file)
            val snapshot=BackupService.read(file,"repacking-password");val restored=repo.restoreTenantSnapshot(snapshot,"Recovered repacking")
            assertEquals(run.copy(tenantId=restored.id),repo.repackingHistory(restored.id).single())
            assertEquals(2,repo.repackingRecipeHistory(restored.id,f.recipe.id).size)
            val tenants=repo.tenants()
            val modified=snapshot.copy(tables=snapshot.tables+("repacking_runs" to snapshot.tables.getValue("repacking_runs").map {it+("destination_after" to "999")}))
            assertFails {repo.restoreTenantSnapshot(modified,"Invalid movement")}
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-repackingSnapshotTables.toSet()),"Missing repacking")}
            val projection=snapshot.copy(tables=snapshot.tables+("repacking_recipes" to snapshot.tables.getValue("repacking_recipes").map {it+("version" to "1")}))
            assertFails {repo.restoreTenantSnapshot(projection,"Wrong projection")}
            assertEquals(tenants,repo.tenants())
        }} finally {directory.toFile().deleteRecursively()}
    }
    @Test fun persistedRunsAndRecipeHistoryAreImmutableAndLegacyBackupStillRestores() {
        val directory=Files.createTempDirectory("repacking-reopen-")
        try {
            val path=directory.resolve("shop.db").toString();var tenant="";var runId=""
            PosRepository(path).use {repo ->val f=repo.fixture();tenant=f.shop.id;runId=repo.executeRepacking(tenant,f.recipe.id,1,"Open bag",repo.repackingReview(tenant,f.recipe.id,1)).id}
            DriverManager.getConnection("jdbc:sqlite:$path").use {db ->assertFails {db.createStatement().execute("DELETE FROM repacking_runs")};assertFails {db.createStatement().execute("UPDATE repacking_recipe_versions SET reason='Rewrite'")}}
            PosRepository(path).use {repo ->assertEquals(runId,repo.repackingHistory(tenant).single().id)}
            PosRepository(":memory:").use {repo ->val t=repo.createTenant("Legacy");val snapshot=repo.exportTenantSnapshot(t.id);val restored=repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-repackingSnapshotTables.toSet()),"Recovered legacy");assertTrue(repo.repackingRecipes(restored.id).isEmpty())}
        } finally {directory.toFile().deleteRecursively()}
    }
}
