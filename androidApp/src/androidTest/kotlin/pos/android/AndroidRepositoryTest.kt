package pos.android

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import pos.security.*
import pos.output.BackupService
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidRepositoryTest {
    private fun fixture(block:(java.io.File)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val dir=java.io.File(context.cacheDir,"repository-test-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        pos.android.platform.AndroidPrivateFiles.install()
        System.setProperty("pos.dataDir",dir.path)
        try {block(dir)} finally {dir.deleteRecursively()}
    }
    @Test fun realRepositoryNativeSqlitePersistsCashDebtAndReturnsAndRestoresHistory()=fixture {dir ->
        var tenant="";var item="";var customer=""
        PosRepository(dir.resolve("pos.db").path).use {repo ->
            tenant=repo.createTenant("Android shop").id
            val product=repo.addProduct(tenant,"Soap","piece",12345,10.0);item=product.id
            customer=repo.addCustomer(tenant,"Ada").id
            val shift=repo.openCashShift(tenant,100000)
            val sale=repo.checkout(tenant,listOf(CartLine(product,2)),20000,customer,"cash-credit",cashGivenMinor=20000)
            assertEquals(24690L,sale.totalMinor);assertEquals(4690L,repo.customers(tenant).single().balanceMinor)
            assertEquals(120000L,repo.expectedCash(tenant,shift.id));assertEquals(8L,repo.products(tenant).single().exactStockScaled)
            val pixels=android.graphics.Bitmap.createBitmap(12,12,android.graphics.Bitmap.Config.ARGB_8888)
            pixels.eraseColor(android.graphics.Color.BLUE)
            val png=java.io.ByteArrayOutputStream().also {pixels.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
            val image=ImageUpload.fromBytes(png)
            repo.saveImage(tenant,"product",item,image,0,"Photo of counted goods","product-image")
            assertArrayEquals(image.bytes,repo.imageBytes(tenant,image.assetId))
            val before=repo.exportTenantSnapshot(tenant)
            try {repo.checkout(tenant,listOf(CartLine(product,100)),0,customer,"oversell");fail("oversell accepted")}catch(_:IllegalArgumentException){}
            assertEquals(before,repo.exportTenantSnapshot(tenant))
            repo.receiveCustomerAdvance(tenant,customer,3000,0,0,"Funds held","advance")
            assertEquals(3000L,repo.customerAdvanceBalance(tenant,customer))
            val backup=dir.resolve("backup.rpos")
            BackupService.create(repo,tenant,"backup-test-password",backup.toPath())
            val decoded=BackupService.read(backup.toPath(),"backup-test-password")
            val restored=repo.restoreTenantSnapshot(decoded,"Recovered")
            assertEquals(4690L,repo.customers(restored.id).single().balanceMinor)
            assertEquals(3000L,repo.customerAdvanceBalance(restored.id,customer))
            assertArrayEquals(image.bytes,repo.imageBytes(restored.id,image.assetId))
        }
        PosRepository(dir.resolve("pos.db").path).use {repo ->
            assertEquals(8L,repo.products(tenant).single {it.id==item}.exactStockScaled)
            assertEquals(1,repo.sales(tenant).size)
        }
    }
    @Test fun ownerAndStaffUseRealAndroidCredentialsAndPersistentPermissionDenial()=fixture {dir ->
        PosRepository(dir.resolve("pos.db").path).use {repo ->
            val shop=repo.createTenant("Shop")
            WorkspaceAccess(dir.toPath()).register(shop.id,"Owner-password-2026")
            StaffAccess(dir.toPath()).use {access ->
                val owner=requireNotNull(access.unlockOwner(shop.id,"Owner-password-2026"))
                val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"Cashier-password-2026")
                val cashier=requireNotNull(access.unlockStaff(shop.id,member.id,"Cashier-password-2026"))
                repo.setPermissionCheck {id,p ->require(id==cashier.tenantId);access.requireSession(cashier,p)}
                try {repo.addProduct(shop.id,"Denied","piece",100,2.0);fail("cashier added stock")}catch(_:IllegalArgumentException){}
                repo.setPermissionCheck(null)
                assertTrue(repo.products(shop.id).isEmpty())
                access.logout(cashier)
                try {access.requireSession(cashier,StaffPermission.SELL);fail("revoked session")}catch(_:IllegalArgumentException){}
            }
        }
    }
    @Test fun actualAndroidManagerSignatureAuthorizesOnlyTheReviewedCashierSale()=fixture {dir ->
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=33)
        PosRepository(dir.resolve("pos.db").path).use {repo ->
            val shop=repo.createTenant("Approval shop")
            val product=repo.addProduct(shop.id,"Rice","bag",1000,10.0)
            repo.openCashShift(shop.id,0)
            WorkspaceAccess(dir.toPath()).register(shop.id,"Owner-password-2026")
            StaffAccess(dir.toPath()).use {access ->
                val owner=requireNotNull(access.unlockOwner(shop.id,"Owner-password-2026"))
                val manager=access.addMember(owner,"Manager",StaffRole.MANAGER,"Manager-password-2026")
                val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"Cashier-password-2026")
                val cashier=requireNotNull(access.unlockStaff(shop.id,member.id,"Cashier-password-2026"))
                repo.setPermissionCheck {id,p ->require(id==cashier.tenantId);access.requireSession(cashier,p)}
                repo.setMinimumPriceApprovalCheck {_,id,request ->access.requireMinimumPriceApproval(cashier,id,request)}
                val lines=listOf(CartLine(product,1,discountPerUnitMinor=100,discountReason="Agreed clearance"))
                try {repo.checkout(shop.id,lines,900,operationId="approved");fail("Unauthenticated cashier discount")}catch(_:IllegalArgumentException){}
                val request=repo.minimumPriceApprovalRequest(shop.id,"approved",lines)
                val proof=requireNotNull(access.authorizeMinimumPrice(cashier,request,manager.id,"Manager-password-2026","Agreed clearance"))
                val saved=repo.checkout(shop.id,lines,900,operationId="approved",priceApprovalId=proof.id)
                assertEquals(900L,saved.totalMinor)
                assertEquals(proof,repo.minimumPriceApprovalForSale(shop.id,saved.id))
                assertEquals(9L,repo.products(shop.id).single().exactStockScaled)
            }
        }
    }

    @Test fun restoreRechecksRevokedBackupAuthorityBeforeAndDuringTrustedImport()=fixture {dir ->
        val database=dir.resolve("pos.db").path
        PosRepository(database).use {repo ->
            val shop=repo.createTenant("Source shop")
            repo.addProduct(shop.id,"Soap","piece",100,2.0)
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val owners=WorkspaceAccess(dir.toPath())
            repo.setPermissionCheck {_,_ ->throw IllegalArgumentException("Owner session revoked")}
            try {
                pos.android.backup.restoreAndroidBackup(repo,shop.id,snapshot,database,"Denied restore","New-owner-password",owners)
                fail("Revoked restore accepted")
            } catch(_:IllegalArgumentException) {}
            assertEquals(listOf(shop),repo.tenants())
            var checks=0
            repo.setPermissionCheck {_,permission ->if(permission==StaffPermission.BACKUP) {checks++;require(checks==1) {"Revoked during restore"}}}
            try {
                pos.android.backup.restoreAndroidBackup(repo,shop.id,snapshot,database,"Revoked during import","New-owner-password",owners)
                fail("Revoked owner provision accepted")
            } catch(_:IllegalArgumentException) {}
            repo.setPermissionCheck(null)
            assertEquals(2,checks)
            assertEquals(listOf(shop),repo.tenants())
            assertEquals(snapshot,repo.exportTenantSnapshot(shop.id))
        }
    }

    @Test fun measuredAndPackMovementsUseExactPortableIntegerScaling()=fixture {dir ->
        PosRepository(dir.resolve("pos.db").path).use {repo ->
            val shop=repo.createTenant("Measured shop")
            val product=repo.addProduct(shop.id,"Rice","kg",100000,10.0,quantityPrecision=3)
            val pack=repo.addSellingUnit(shop.id,product.id,"bag",2,190000)
            val drawer=repo.openCashShift(shop.id,0)
            val lines=listOf(CartLine(product,125),CartLine(product,1,pack.id,pack.name,pack.priceMinor,pack.baseUnitsPerUnit))
            val sale=repo.checkout(shop.id,lines,202500,operationId="mixed")
            assertEquals(7875L,repo.products(shop.id).single().exactStockScaled)
            val sold=repo.inventoryMovements(shop.id,product.id).single {it.kind=="sale"}
            assertEquals(-2.125,sold.quantityDelta,0.0)
            val returned=repo.returnItems(shop.id,sale.id,mapOf(product.id to 125),true,"Unopened rice","return")
            assertEquals(12500L,returned.refundMinor)
            assertEquals(8000L,repo.products(shop.id).single().exactStockScaled)
            assertEquals(0.125,repo.inventoryMovements(shop.id,product.id).single {it.kind=="return"}.quantityDelta,0.0)
            assertEquals(190000L,repo.expectedCash(shop.id,drawer.id))
        }
    }

}
