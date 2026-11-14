package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.*
import java.nio.file.Files

class ShopProfileIntegrityTest {
    @Test fun unrestrictedLegacyWorkspaceNameDoesNotBlockOrdinarySales() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Legacy workspace "+"x".repeat(151))
            val product=repo.addProduct(shop.id,"Soap","piece",100,3.0)
            val sale=repo.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="legacy-name-sale")
            assertNull(repo.receiptProfile(shop.id,sale.id))
            assertContains(BrandedReceiptService.format(shop,sale),shop.name)
            repo.saveShopProfile(shop.id,ShopDetails("Configured receipt name"),0,"Choose receipt name","setup")
            val second=repo.checkout(shop.id,listOf(CartLine(repo.products(shop.id).single(),1)),100,operationId="new-name-sale")
            assertEquals("Configured receipt name",repo.receiptProfile(shop.id,second.id)?.details?.displayName)
        }
    }
    @Test fun historyOptimisticEditsAndRetriesNeverRewriteEarlierReceipts() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Workspace")
            val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val first=ShopDetails("Adewale Stores","+2348012345678","Old market address","Thank you","80mm")
            val profile=repo.saveShopProfile(shop.id,first,0,"Initial receipt setup","setup")
            val sale=repo.checkout(shop.id,listOf(CartLine(item,1)),100,operationId="first-sale")
            val captured=requireNotNull(repo.receiptProfile(shop.id,sale.id))
            assertEquals(first,captured.details)
            val changed=first.copy(displayName="Adewale Supermarket",address="New market address",receiptPaper="A4")
            repo.saveShopProfile(shop.id,changed,profile.version,"Moved to new shop","move")
            val before=repo.exportTenantSnapshot(shop.id)
            repo.saveShopProfile(shop.id,first,0,"Initial receipt setup","setup")
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            assertFails {repo.saveShopProfile(shop.id,first,profile.version,"Stale screen","stale")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val text=BrandedReceiptService.format(shop,sale,profile=repo.receiptProfile(shop.id,sale.id))
            assertContains(text,"Old market address");assertFalse(text.contains("New market address"));assertEquals(captured,repo.receiptProfile(shop.id,sale.id))
            assertEquals(2,repo.shopProfileHistory(shop.id).size)
        }
    }
    @Test fun defaultSnapshotIsCapturedWithoutInventingContactDetails() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Unconfigured shop")
            val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val sale=repo.checkout(shop.id,listOf(CartLine(item,1)),100,operationId="sale")
            val snapshot=requireNotNull(repo.receiptProfile(shop.id,sale.id))
            assertEquals(0L,snapshot.profileVersion);assertEquals(ShopDetails(shop.name),snapshot.details)
            repo.saveShopProfile(shop.id,ShopDetails("Configured shop",address="Later address"),0,"Add shop contacts","setup")
            assertEquals(snapshot,repo.receiptProfile(shop.id,sale.id))
            val legacy=BrandedReceiptService.format(shop,sale,profile=null)
            assertContains(legacy,"not captured for this sale");assertFalse(legacy.contains("Later address"))
        }
    }
    @Test fun settingsAndCapturedIdentitiesSurviveEncryptedBackupAndReopen() {
        val directory=Files.createTempDirectory("shop-profile-test-")
        val database=directory.resolve("shop.db")
        var shopId="";var saleId=""
        try {
            PosRepository(database.toString()).use {repo ->
                val shop=repo.createTenant("Original");shopId=shop.id
                repo.saveShopProfile(shop.id,ShopDetails("Original branding",address="Main Market",receiptPaper="58mm"),0,"Configure receipts","settings")
                val product=repo.addProduct(shop.id,"Rice","kg",100,2.0,quantityPrecision=3)
                val sale=repo.checkout(shop.id,listOf(CartLine(product,125)),13,operationId="sale");saleId=sale.id
                val backup=directory.resolve("shop.posbackup")
                BackupService.create(repo,shop.id,"Backup-password-2026",backup)
                val restored=repo.restoreTenantSnapshot(BackupService.read(backup,"Backup-password-2026"),"Recovered")
                assertEquals(repo.shopProfile(shop.id)?.details,repo.shopProfile(restored.id)?.details)
                assertEquals(repo.receiptProfile(shop.id,sale.id)?.details,repo.receiptProfile(restored.id,sale.id)?.details)
            }
            PosRepository(database.toString()).use {repo ->
                assertEquals("58mm",repo.shopProfile(shopId)?.details?.receiptPaper)
                assertEquals("Original branding",repo.receiptProfile(shopId,saleId)?.details?.displayName)
            }
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun missingOrCorruptedCaptureAndHistoryRejectRestoreAtomically() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Original")
            repo.saveShopProfile(shop.id,ShopDetails("Shop",address="Market"),0,"Receipt setup","setup")
            val product=repo.addProduct(shop.id,"Soap","piece",100,2.0)
            repo.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="sale")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val before=repo.tenants()
            val variants=listOf(
                snapshot.copy(tables=snapshot.tables-"sale_receipt_profiles"),
                snapshot.copy(tables=snapshot.tables-"shop_profile_changes"),
                snapshot.copy(tables=snapshot.tables+("sale_receipt_profiles" to snapshot.tables.getValue("sale_receipt_profiles").map {it+("profile_version" to "999")})),
                snapshot.copy(tables=snapshot.tables+("shop_profiles" to snapshot.tables.getValue("shop_profiles").map {it+("details" to "not-base64")})),
            )
            variants.forEach {bad ->assertFails {repo.restoreTenantSnapshot(bad,"Bad")};assertEquals(before,repo.tenants())}
        }
    }
    @Test fun legacyBackupWithoutReceiptProfileColumnsRemainsExplicitlyUnknown() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Legacy shop")
            val product=repo.addProduct(shop.id,"Soap","piece",100,2.0)
            val sale=repo.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="sale")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val legacy=snapshot.copy(tables=snapshot.tables.filterKeys {it !in shopProfileSnapshotTables}+(
                "sales" to snapshot.tables.getValue("sales").map {it-"receipt_profile_captured"}))
            val restored=repo.restoreTenantSnapshot(legacy,"Recovered legacy")
            assertNull(repo.shopProfile(restored.id));assertNull(repo.receiptProfile(restored.id,sale.id))
            assertContains(BrandedReceiptService.format(restored,repo.sales(restored.id).single()),"not captured")
        }
    }
    @Test fun permissionsAndTenantBoundariesApplyToReadsWritesAndRetries() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val other=repo.createTenant("Other")
            val product=repo.addProduct(shop.id,"Soap","piece",100,2.0)
            repo.saveShopProfile(shop.id,ShopDetails("Printed name"),0,"Initial setup","setup")
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission ->require(permission in setOf(StaffPermission.SELL,StaffPermission.VIEW_SALES,StaffPermission.BACKUP)) {"Cashier denied settings"}}
            assertFails {repo.shopProfile(shop.id)}
            assertFails {repo.saveShopProfile(shop.id,ShopDetails("Printed name"),0,"Initial setup","setup")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val sale=repo.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="sale")
            assertNotNull(repo.receiptProfile(shop.id,sale.id))
            assertFails {repo.receiptProfile(other.id,sale.id)}
        }
    }
    @Test fun actorFailureAndHistoryMutationLeaveAllRecordsUnchanged() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setActorProvider {throw IllegalStateException("Actor expired")}
            assertFails {repo.saveShopProfile(shop.id,ShopDetails("Printed name"),0,"Initial setup","setup")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.setActorProvider(null)
            repo.saveShopProfile(shop.id,ShopDetails("Printed name"),0,"Initial setup","setup")
            val saved=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.featureWrite(shop.id,StaffPermission.MANAGE_SETTINGS) {featureExecute("UPDATE shop_profile_changes SET reason='Rewrite' WHERE tenant_id=?",shop.id)}}
            assertEquals(saved,repo.exportTenantSnapshot(shop.id))
        }
    }
}
