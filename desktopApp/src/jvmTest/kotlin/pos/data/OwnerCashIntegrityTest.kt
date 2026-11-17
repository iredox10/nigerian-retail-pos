package pos.data

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import pos.output.BackupService

class OwnerCashIntegrityTest {
    @Test fun contributionsDrawingsAndLinkedCorrectionsAffectOnlyDrawerCash() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val shift=repo.openCashShift(shop.id,1000)
            val contribution=repo.recordOwnerCash(shop.id,"CONTRIBUTION",500,"Amina","Working capital",shift.id,1000,"capital")
            val drawing=repo.recordOwnerCash(shop.id,"DRAWING",200,"Amina","Personal use",shift.id,1500,"drawing")
            assertEquals(1300L,repo.expectedCash(shop.id,shift.id))
            val correction=repo.reverseOwnerCash(shop.id,drawing.id,"Cash returned by owner",shift.id,1300,"correction")
            assertEquals(drawing.id,correction.reversesId);assertEquals(1500L,repo.expectedCash(shop.id,shift.id))
            assertTrue(repo.sales(shop.id).isEmpty());assertTrue(repo.expenses(shop.id).isEmpty())
            assertEquals(contribution,repo.recordOwnerCash(shop.id,"CONTRIBUTION",500,"Amina","Working capital",shift.id,1000,"capital"))
            assertEquals(3,repo.cashEntries(shop.id).size)
            assertFails {repo.reverseOwnerCash(shop.id,drawing.id,"Another correction",shift.id,1500,"again")}
            assertFails {repo.reverseOwnerCash(shop.id,correction.id,"Undo correction",shift.id,1500,"undo")}
        }
    }
    @Test fun staleBalanceInsufficientCashAndChangedRetryNeverMutateLedgers() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val shift=repo.openCashShift(shop.id,1000)
            val first=repo.recordOwnerCash(shop.id,"DRAWING",400,"Owner","Personal cash",shift.id,1000,"draw")
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.recordOwnerCash(shop.id,"CONTRIBUTION",100,"Owner","Old review",shift.id,1000,"stale")}
            assertFails {repo.recordOwnerCash(shop.id,"DRAWING",601,"Owner","Too much",shift.id,600,"over")}
            assertFails {repo.recordOwnerCash(shop.id,"DRAWING",401,"Owner","Personal cash",shift.id,1000,"draw")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.closeCashShift(shop.id,shift.id,600)
            assertEquals(first,repo.recordOwnerCash(shop.id,"DRAWING",400,"Owner","Personal cash",shift.id,1000,"draw"))
        }
    }
    @Test fun tenantDenialAndLateActorFailureRollBackClassificationAndCashTogether() {
        PosRepository(":memory:").use {repo ->
            val a=repo.createTenant("A");val b=repo.createTenant("B");val shift=repo.openCashShift(a.id,1000)
            assertFails {repo.recordOwnerCash(b.id,"DRAWING",1,"Owner","Wrong shop",shift.id,1000,"foreign")}
            repo.setPermissionCheck {_,_->error("Denied")}
            assertFails {repo.recordOwnerCash(a.id,"DRAWING",1,"Owner","Forbidden",shift.id,1000,"denied")}
            repo.setPermissionCheck(null)
            val before=repo.exportTenantSnapshot(a.id)
            repo.setActorProvider {error("Revoked")}
            assertFails {repo.recordOwnerCash(a.id,"CONTRIBUTION",100,"Owner","No partial write",shift.id,1000,"actor")}
            repo.setActorProvider(null)
            assertEquals(before,repo.exportTenantSnapshot(a.id))
        }
    }
    @Test fun encryptedRecoveryPreservesClassificationAndRejectsMissingOrChangedHistory() {
        val directory=Files.createTempDirectory("owner-cash-test")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val shift=repo.openCashShift(shop.id,1000)
            val first=repo.recordOwnerCash(shop.id,"DRAWING",300,"Amina","Personal use",shift.id,1000,"drawing")
            repo.reverseOwnerCash(shop.id,first.id,"Owner returned cash",shift.id,700,"correction")
            val file=directory.resolve("shop.backup");BackupService.create(repo,shop.id,"owner-test-password",file)
            val snapshot=BackupService.read(file,"owner-test-password")
            val recovered=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(1000L,repo.expectedCash(recovered.id,shift.id));assertEquals(2,repo.ownerCashRecords(recovered.id).size)
            val tenants=repo.tenants()
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-ownerCashSnapshotTables.toSet()),"Missing")}
            val changed=snapshot.copy(tables=snapshot.tables+("owner_cash_records" to snapshot.tables.getValue("owner_cash_records").map {it+("owner_name" to "Wrong owner")}))
            assertFails {repo.restoreTenantSnapshot(changed,"Changed")}
            assertEquals(tenants,repo.tenants())
        }} finally {directory.toFile().deleteRecursively()}
    }
    @Test fun reservedCashSourceCannotBeReclassifiedAsASecondOwnerMovement() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val shift=repo.openCashShift(shop.id,100)
            repo.recordCashMovement(shop.id,100,"Other cash","owner-cash:used")
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.recordOwnerCash(shop.id,"CONTRIBUTION",100,"Owner","Working cash",shift.id,200,"used")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
}
