package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.nio.file.Files
import java.sql.DriverManager

class SupplierProfileIntegrityTest {
    @Test fun profilesHaveStableIdentityAndEditsPreservePurchaseNamesMoneyAndHistory() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val supplier=repo.addSupplierProfile(shop.id,SupplierDetails("Amina supplies",normalizeNigerianPhone("08012345678")),"supplier-a")
            val item=repo.addProduct(shop.id,"Rice","bag",200,0.0)
            val receipt=repo.receiveStockForSupplier(shop.id,item.id,2,100,0,supplier.id,"purchase-a")
            val beforeStock=repo.products(shop.id)
            val beforeCash=repo.cashEntries(shop.id)
            val edited=repo.updateSupplierProfile(shop.id,supplier.id,supplier.details.copy(name="Amina wholesale",paymentTerms="14 days"),false,1,"Updated trading name","edit-a")
            assertEquals(supplier.id,edited.id)
            assertEquals("Amina supplies",repo.stockReceipts(shop.id).single().supplierName)
            assertEquals(200L,repo.supplierOutstanding(shop.id,receipt.id))
            assertEquals(beforeStock,repo.products(shop.id))
            assertEquals(beforeCash,repo.cashEntries(shop.id))
            assertEquals(listOf(1L,2L),repo.supplierProfileChanges(shop.id,supplier.id).map {it.version})
            assertEquals(supplier.details,repo.supplierProfileChanges(shop.id,supplier.id).last().previous)
            assertEquals(supplier.id,repo.supplierReceiptLinks(shop.id).single().supplierId)
        }
    }
    @Test fun retriesAndStaleChangesNeverDuplicateOrOverwriteIdentity() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val a=repo.addSupplierProfile(shop.id,SupplierDetails("Same name"),"a")
            val b=repo.addSupplierProfile(shop.id,SupplierDetails("Same name"),"b")
            assertNotEquals(a.id,b.id)
            assertEquals(a,repo.addSupplierProfile(shop.id,a.details,"a"))
            assertFails {repo.addSupplierProfile(shop.id,a.details.copy(name="Changed"),"a")}
            val edited=repo.updateSupplierProfile(shop.id,a.id,a.details.copy(address="Market"),false,1,"Added location","edit")
            assertEquals(edited,repo.updateSupplierProfile(shop.id,a.id,edited.details,false,1,"Added location","edit"))
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.updateSupplierProfile(shop.id,a.id,a.details,true,1,"Stale archive","stale")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun receivingLinksAtomicallyAndArchivedProfilesAllowOnlyExactPurchaseReplays() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val supplier=repo.addSupplierProfile(shop.id,SupplierDetails("Supplier"),"supplier")
            val other=repo.addSupplierProfile(shop.id,SupplierDetails("Other"),"other")
            val item=repo.addProduct(shop.id,"Measured rice","kg",200,0.0,quantityPrecision=3)
            val receipt=repo.receiveStockForSupplier(shop.id,item.id,1250,100,0,supplier.id,"purchase")
            repo.updateSupplierProfile(shop.id,supplier.id,supplier.details.copy(name="Renamed"),true,1,"Stopped trading","archive")
            assertEquals(receipt,repo.receiveStockForSupplier(shop.id,item.id,1250,100,0,supplier.id,"purchase"))
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.receiveStockForSupplier(shop.id,item.id,1250,100,0,supplier.id,"new")}
            assertFails {repo.receiveStockForSupplier(shop.id,item.id,1251,100,0,supplier.id,"purchase")}
            assertFails {repo.receiveStockForSupplier(shop.id,item.id,1250,100,0,other.id,"purchase")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            assertEquals("1.25",repo.products(shop.id).single().stockLabel)
        }
    }
    @Test fun oldReceiptsRequireExplicitReviewAndCannotBeReassignedOrCrossLinked() {
        PosRepository(":memory:").use {repo ->
            val a=repo.createTenant("A");val b=repo.createTenant("B")
            val profile=repo.addSupplierProfile(a.id,SupplierDetails("Amina"),"profile")
            val other=repo.addSupplierProfile(a.id,SupplierDetails("Amina"),"other")
            val foreign=repo.addSupplierProfile(b.id,SupplierDetails("Amina"),"foreign")
            val item=repo.addProduct(a.id,"Rice","bag",200,0.0)
            val old=repo.receiveStock(a.id,item.id,1,100,0,"Amina","old")
            assertTrue(repo.supplierReceiptLinks(a.id).isEmpty())
            assertFails {repo.receiveStockForSupplier(a.id,item.id,1,100,0,profile.id,"old")}
            assertTrue(repo.supplierReceiptLinks(a.id).isEmpty())
            assertFails {repo.linkSupplierReceipt(a.id,old.id,foreign.id,"cross")}
            val link=repo.linkSupplierReceipt(a.id,old.id,profile.id,"reviewed")
            assertEquals(link,repo.linkSupplierReceipt(a.id,old.id,profile.id,"reviewed"))
            assertFails {repo.linkSupplierReceipt(a.id,old.id,other.id,"reassign")}
            assertFails {repo.linkSupplierReceipt(b.id,old.id,foreign.id,"foreign-receipt")}
            assertEquals(old,repo.stockReceipts(a.id).single())
        }
    }
    @Test fun deniedActionsAndLateLinkFailuresRollBackAllBusinessEffects() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val profile=repo.addSupplierProfile(shop.id,SupplierDetails("Supplier"),"supplier")
            val item=repo.addProduct(shop.id,"Rice","bag",200,0.0)
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission -> if(permission!=StaffPermission.BACKUP) error("Denied")}
            assertFails {repo.addSupplierProfile(shop.id,SupplierDetails("Denied"),"denied")}
            assertFails {repo.supplierProfiles(shop.id)}
            assertFails {repo.receiveStockForSupplier(shop.id,item.id,1,100,0,profile.id,"denied-receive")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.setPermissionCheck(null)
            var actorCalls=0
            repo.setActorProvider {tenant -> actorCalls++; if(actorCalls==2) error("Cannot record link actor") else AuditActor(tenant,null,"Owner",StaffRole.OWNER)}
            assertFails {repo.receiveStockForSupplier(shop.id,item.id,1,100,0,profile.id,"atomic")}
            repo.setActorProvider(null)
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun profilesLinksAndHistorySurviveEncryptedBackupAndRejectTampering() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val profile=repo.addSupplierProfile(shop.id,SupplierDetails("Supplier"),"supplier")
            val item=repo.addProduct(shop.id,"Rice","bag",200,0.0)
            repo.receiveStockForSupplier(shop.id,item.id,1,100,0,profile.id,"purchase")
            repo.updateSupplierProfile(shop.id,profile.id,profile.details.copy(paymentTerms="14 days"),false,1,"Terms agreed","terms")
            val file=Files.createTempDirectory("supplier-backup-").resolve("shop.backup")
            BackupService.create(repo,shop.id,"supplier-backup-password",file)
            val snapshot=BackupService.read(file,"supplier-backup-password")
            val recovered=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(2L,repo.supplierProfiles(recovered.id).single().version)
            assertEquals(profile.id,repo.supplierReceiptLinks(recovered.id).single().supplierId)
            assertEquals(2,repo.supplierProfileChanges(recovered.id,profile.id).size)
            val before=repo.tenants()
            val bad=snapshot.copy(tables=snapshot.tables+("supplier_profiles" to snapshot.tables.getValue("supplier_profiles").map {it+("version" to "9")}))
            assertFails {repo.restoreTenantSnapshot(bad,"Bad")}
            assertEquals(before,repo.tenants())
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-supplierProfileSnapshotTables.toSet()),"Missing supplier records")}
            assertEquals(before,repo.tenants())
        }
    }
    @Test fun historyCannotBeEditedAndProfilesPersistAcrossDatabaseReopen() {
        val path=Files.createTempDirectory("supplier-reopen-").resolve("shop.db").toString()
        var tenant=""
        PosRepository(path).use {repo -> tenant=repo.createTenant("Shop").id;repo.addSupplierProfile(tenant,SupplierDetails("Supplier"),"supplier")}
        DriverManager.getConnection("jdbc:sqlite:$path").use {db ->
            assertFails {db.createStatement().use {it.execute("UPDATE supplier_profile_changes SET reason='Changed'")}}
            assertFails {db.createStatement().use {it.execute("DELETE FROM supplier_profile_changes")}}
        }
        PosRepository(path).use {repo -> assertEquals("Supplier",repo.supplierProfiles(tenant).single().details.name);assertEquals(1,repo.supplierProfileChanges(tenant,"supplier").size)}
    }
    @Test fun legacyBackupsWithoutProfileTablesKeepTheirUnlinkedPurchaseNames() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Old shop")
            val item=repo.addProduct(shop.id,"Rice","bag",200,0.0)
            repo.receiveStock(shop.id,item.id,1,100,0,"Old supplier name","old-purchase")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val legacy=repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-supplierProfileSnapshotTables.toSet()),"Legacy restored")
            assertTrue(repo.supplierProfiles(legacy.id).isEmpty())
            assertTrue(repo.supplierReceiptLinks(legacy.id).isEmpty())
            assertEquals("Old supplier name",repo.stockReceipts(legacy.id).single().supplierName)
        }
    }
    @Test fun staleSupplierSelectionCannotSilentlyReceiveUnderRenamedDetails() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val p=repo.addSupplierProfile(shop.id,SupplierDetails("Original"),"p")
            val item=repo.addProduct(shop.id,"Rice","bag",200,0.0)
            repo.updateSupplierProfile(shop.id,p.id,p.details.copy(name="Renamed"),false,1,"Renamed business","rename")
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.receiveStockForSupplier(shop.id,item.id,1,100,0,p.id,"stale",expectedSupplierVersion=1)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
}
