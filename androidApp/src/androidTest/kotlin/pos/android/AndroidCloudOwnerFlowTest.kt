package pos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.cloud.AndroidCloudCredentials
import pos.android.platform.AndroidPrivateFiles
import pos.android.storage.AndroidSqliteDriver
import pos.cloud.app.RepositoryCloudLocalStore
import pos.cloud.client.*
import pos.cloud.protocol.*
import pos.data.*
import pos.domain.*
import pos.security.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Actual installed-app owner/session adapter, native SQLite and Keystore; no permissive test store. */
@RunWith(AndroidJUnit4::class)
class AndroidCloudOwnerFlowTest {
    private class Device(val directory:File):AutoCloseable {
        val owners=WorkspaceAccess(directory.toPath())
        val repo=PosRepository(directory.resolve("pos.db").path)
        val access=StaffAccess(directory.toPath())
        val setup=repo.createTenant("QA setup") {owners.register(it.id,"Local-QA-owner-2026")}
        val originalOwner=requireNotNull(access.unlockOwner(setup.id,"Local-QA-owner-2026"))
        var active:StaffSession?=originalOwner
        private fun owner(id:String) {
            val current=requireNotNull(active);require(current.tenantId==id && current.role==StaffRole.OWNER)
            access.requireSession(current,StaffPermission.BACKUP,false)
        }
        private fun files(name:String)=PrivateCloudFiles(directory.resolve(name).toPath(),CloudFileProtection {path,isDir ->
            LocalFilePermissions.restrict(path,if(isDir)"rwx------" else "rw-------")
        })
        val local:RepositoryCloudLocalStore
        val cloud:CloudCoordinator
        init {
            repo.setPermissionCheck {id,permission ->val current=requireNotNull(active);require(current.tenantId==id);access.requireSession(current,permission,false)}
            local=RepositoryCloudLocalStore(repo,owners,{PosRepository(directory.resolve("pos.db").path)},::owner)
            cloud=CloudCoordinator(local,
                HttpCloudTransport(),CloudStateStore(files("checkpoints")),AndroidCloudCredentials(files("tokens")))
        }
        override fun close() {cloud.close();access.close();repo.close()}
    }
    @Test fun importedShopRequiresItsOwnOwnerUnlockEvenWithoutInternet() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"offline-owner-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open);AndroidPrivateFiles.install()
        try {Device(root.resolve("source").apply {mkdirs()}).use {a ->Device(root.resolve("receiver").apply {mkdirs()}).use {b ->
            val product=a.repo.addProduct(a.setup.id,"Offline import soap","piece",55000,10.0)
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
            val image=ImageUpload.fromBytes(ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray());bitmap.recycle()
            a.repo.saveImage(a.setup.id,"product",product.id,image,0,"Offline QA image","offline-image")
            val original=CloudBootstrapReply("offline-business",0,a.repo.cloudBusinessSchema(),a.repo.cloudBusinessSnapshot(a.setup.id,"offline-business"))
            val bootstrap=CloudCodec.decode<CloudBootstrapReply>(CloudCodec.encode(original))
            val imported=b.local.importShop(b.setup.id,"offline-import",bootstrap,"Offline downloaded QA","Separate-owner-2026")
            assertNotEquals(b.setup.id,imported)
            assertThrows(IllegalArgumentException::class.java) {b.repo.products(imported)}
            assertThrows(IllegalArgumentException::class.java) {b.repo.exportCloudBusinessSnapshot(imported)}
            assertNull(b.access.unlockOwner(imported,"Local-QA-owner-2026"))
            b.active=requireNotNull(b.access.unlockOwner(imported,"Separate-owner-2026"))
            assertEquals(product.id,b.repo.products(imported).single().id)
            assertArrayEquals(image.bytes,b.repo.imageBytes(imported,requireNotNull(b.repo.imageSelection(imported,"product",product.id).assetId)))
            b.active=b.originalOwner
            assertEquals(imported,b.local.importShop(b.setup.id,"offline-import",bootstrap,"Retry name","Other-password-2026"))
            assertEquals(2,b.repo.tenants().size)
            assertTrue(b.owners.unlock(imported,"Separate-owner-2026"))
            val cashier=b.access.addMember(b.originalOwner,"QA cashier",StaffRole.CASHIER,"Cashier-owner-2026")
            b.active=requireNotNull(b.access.unlockStaff(b.setup.id,cashier.id,"Cashier-owner-2026"))
            assertThrows(IllegalArgumentException::class.java) {b.local.importShop(b.setup.id,"denied",bootstrap,"Denied shop","Separate-owner-2026")}
            assertEquals(2,b.repo.tenants().size)
        }}} finally {root.deleteRecursively()}
    }
    @Test fun ownerPublishImageImportUnlockSyncAndRevokeUseTheRealApplicationBoundary() {
        val url=InstrumentationRegistry.getArguments().getString("cloudUrl")
        assumeTrue("Provide a live HTTPS endpoint",!url.isNullOrBlank())
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"owner-cloud-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open);AndroidPrivateFiles.install()
        try {Device(root.resolve("a").apply {mkdirs()}).use {a ->Device(root.resolve("b").apply {mkdirs()}).use {b ->
            val endpoint=CloudEndpoint.parse(requireNotNull(url))
            val account=a.cloud.register(endpoint,"owner-flow-${UUID.randomUUID()}@example.test",UUID.randomUUID().toString())
            val item=a.repo.addProduct(a.setup.id,"QA pictured soap","piece",55000,10.0)
            a.repo.openCashShift(a.setup.id,100000)
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            for(y in 0..63)for(x in 0..63)bitmap.setPixel(x,y,Color.rgb(x*4,y*4,(x+y)*2))
            val bytes=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
            val image=ImageUpload.fromBytes(bytes)
            a.repo.saveImage(a.setup.id,"product",item.id,image,0,"QA picture","qa-image")
            val link=a.cloud.publish(a.setup.id,endpoint,account,"Native owner A")
            try {
                val imported=b.cloud.join(b.setup.id,endpoint,account,link.businessId,"Native owner B","Downloaded QA shop","Imported-QA-owner-2026")
                assertNotEquals(b.setup.id,imported);assertEquals(2,b.repo.tenants().size)
                // Import must leave the original shop guarded; no implicit cross-shop session grant.
                assertThrows(IllegalArgumentException::class.java) {b.repo.products(imported)}
                b.active=requireNotNull(b.access.unlockOwner(imported,"Imported-QA-owner-2026"))
                assertEquals(item.id,b.repo.products(imported).single().id)
                assertArrayEquals(image.bytes,b.repo.imageBytes(imported,requireNotNull(b.repo.imageSelection(imported,"product",item.id).assetId)))
                a.repo.checkout(a.setup.id,listOf(CartLine(item,1)),55000,operationId="owner-a-sale")
                b.repo.checkout(imported,listOf(CartLine(b.repo.products(imported).single(),2)),110000,operationId="owner-b-sale")
                assertEquals("up_to_date",a.cloud.syncNow(a.setup.id).phase)
                assertEquals("up_to_date",b.cloud.syncNow(imported).phase)
                assertEquals("up_to_date",a.cloud.syncNow(a.setup.id).phase)
                assertEquals(7L,a.repo.products(a.setup.id).single().exactStockScaled)
                assertEquals(7L,b.repo.products(imported).single().exactStockScaled)
                assertEquals(265000L,b.repo.expectedCash(imported,b.repo.activeCashShift(imported)!!.id))
                val phoneId=requireNotNull(b.cloud.connection(imported)).deviceId
                a.cloud.revokeDevice(a.setup.id,account,phoneId)
                assertEquals("auth_required",b.cloud.syncNow(imported).phase)
                assertEquals(2,b.repo.sales(imported).size)
                b.cloud.reconnect(imported,account)
                assertEquals("up_to_date",b.cloud.syncNow(imported).phase)
                val cashier=a.access.addMember(a.originalOwner,"QA cashier",StaffRole.CASHIER,"Cashier-QA-owner-2026")
                a.active=requireNotNull(a.access.unlockStaff(a.setup.id,cashier.id,"Cashier-QA-owner-2026"))
                assertThrows(IllegalArgumentException::class.java) {a.cloud.reconnect(a.setup.id,account)}
            } finally {
                a.active=a.originalOwner
                runCatching {a.cloud.devices(a.setup.id,account).filter {it.revokedAt==null}.forEach {a.cloud.revokeDevice(a.setup.id,account,it.id)}}
            }
        }}} finally {root.deleteRecursively()}
    }
    @Test fun ownerApprovedCodeConnectsAnEmptyNativeInstallationWithoutSendingLocalPasswords() {
        val url=InstrumentationRegistry.getArguments().getString("cloudUrl")
        assumeTrue("Provide a live HTTPS endpoint",!url.isNullOrBlank())
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"paired-cloud-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open);AndroidPrivateFiles.install()
        try {Device(root.resolve("source").apply {mkdirs()}).use {source ->
            val endpoint=CloudEndpoint.parse(requireNotNull(url))
            val account=source.cloud.register(endpoint,"pair-flow-${UUID.randomUUID()}@example.test",UUID.randomUUID().toString())
            val item=source.repo.addProduct(source.setup.id,"Paired soap","piece",55000,10.0)
            source.cloud.publish(source.setup.id,endpoint,account,"Shop computer")
            val target=root.resolve("target").apply {mkdirs()};val path=target.resolve("pos.db").path
            val owners=WorkspaceAccess(target.toPath())
            PosRepository(path).use {repo ->StaffAccess(target.toPath()).use {access ->
                var session:StaffSession?=null
                repo.setPermissionCheck {id,permission ->val current=requireNotNull(session);require(current.tenantId==id);access.requireSession(current,permission,false)}
                val local=RepositoryCloudLocalStore(repo,owners,{PosRepository(path)}) {id ->val current=requireNotNull(session);require(current.tenantId==id && current.role==StaffRole.OWNER)}
                fun files(name:String)=PrivateCloudFiles(target.resolve(name).toPath(),CloudFileProtection {p,isDir ->LocalFilePermissions.restrict(p,if(isDir)"rwx------" else "rw-------")})
                val state=CloudStateStore(files("checkpoints"));val secrets=AndroidCloudCredentials(files("tokens"))
                val ticket=CloudCoordinator(local,HttpCloudTransport(),state,secrets).use {it.startPairing(CLOUD_NEW_INSTALLATION,endpoint,"Owner phone")}
                assertTrue(state.pairing(CLOUD_NEW_INSTALLATION)!!.ticket.secret.isEmpty())
                CloudCoordinator(local,HttpCloudTransport(),state,secrets).use {cloud ->
                    assertEquals(ticket,cloud.startPairing(CLOUD_NEW_INSTALLATION,endpoint,"Owner phone"))
                    assertEquals("waiting",cloud.pollPairing(endpoint,ticket).status)
                    val review=source.cloud.reviewPairing(source.setup.id,account,ticket.code)
                    assertEquals("Owner phone",review.deviceName)
                    source.cloud.approvePairing(source.setup.id,account,ticket.code,review)
                    assertEquals("approved",cloud.pollPairing(endpoint,ticket).status)
                    val id=cloud.joinPaired(CLOUD_NEW_INSTALLATION,endpoint,ticket,"My connected shop","Separate-device-password")
                    assertEquals(1,repo.tenants().size)
                    assertThrows(IllegalArgumentException::class.java) {repo.products(id)}
                    session=requireNotNull(access.unlockOwner(id,"Separate-device-password"))
                    assertEquals(item.id,repo.products(id).single().id)
                    assertEquals("Owner phone",cloud.connection(id)!!.deviceName)
                    assertEquals("up_to_date",cloud.syncNow(id).phase)
                    source.cloud.revokeDevice(source.setup.id,account,cloud.connection(id)!!.deviceId)
                    assertEquals("auth_required",cloud.syncNow(id).phase)
                    assertEquals(10L,repo.products(id).single().exactStockScaled)
                    assertNull(state.pairing(CLOUD_NEW_INSTALLATION))
                }
            }}
            source.cloud.devices(source.setup.id,account).filter {it.revokedAt==null}.forEach {source.cloud.revokeDevice(source.setup.id,account,it.id)}
        }} finally {root.deleteRecursively()}
    }

}
