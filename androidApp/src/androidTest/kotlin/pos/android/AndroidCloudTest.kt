package pos.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.platform.AndroidPrivateFiles
import pos.android.storage.AndroidSqliteDriver
import pos.android.cloud.AndroidCloudCredentials
import pos.cloud.client.*
import pos.cloud.protocol.*
import pos.data.*
import pos.domain.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCloudTest {
    private fun fixture(run:(java.io.File)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=java.io.File(context.cacheDir,"cloud-test-${UUID.randomUUID()}").apply {mkdirs()}
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open);AndroidPrivateFiles.install()
        try {run(directory)} finally {directory.deleteRecursively()}
    }
    private fun files(directory:java.io.File)=PrivateCloudFiles(directory.toPath(),CloudFileProtection {path,isDirectory ->
        pos.security.LocalFilePermissions.restrict(path,if(isDirectory)"rwx------" else "rw-------")
    })
    @Test fun keystoreCredentialsAreEncryptedBoundToTheirKeyAndReadableAfterRecreation()=fixture {directory ->
        val privateFiles=files(directory.resolve("credentials"));val credentials=AndroidCloudCredentials(privateFiles)
        val token="a".repeat(43)
        credentials.write("first",token)
        assertEquals(token,AndroidCloudCredentials(privateFiles).read("first"))
        assertFalse(directory.walkTopDown().filter {it.isFile}.any {it.readText().contains(token)})
        assertNull(credentials.read("other"))
        credentials.remove("first");assertNull(credentials.read("first"))
    }
    @Test fun nativeCheckpointMergesDisconnectedSalesWithoutDuplicateLinesOrStock()=fixture {directory ->
        PosRepository(directory.resolve("a.db").path).use {a ->PosRepository(directory.resolve("b.db").path).use {b ->
            val shop=a.createTenant("Native cloud sample");val product=a.addProduct(shop.id,"Soap","piece",100,10.0);a.openCashShift(shop.id,1000)
            val phone=b.restoreTenantSnapshot(a.exportTenantSnapshot(shop.id),"Other device")
            val base=a.cloudBusinessSnapshot(shop.id,"business");val schema=a.cloudBusinessSchema()
            a.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="a-sale")
            b.checkout(phone.id,listOf(CartLine(b.products(phone.id).single(),2)),200,operationId="b-sale")
            val sent=a.cloudBusinessSnapshot(shop.id,"business")
            val remote=requireNotNull(CloudMerge.merge(sent,CloudMerge.diff(base,b.cloudBusinessSnapshot(phone.id,"business"),schema),schema).snapshot)
            a.applyCloudBusinessSnapshot(shop.id,"checkpoint",sent,remote)
            a.applyCloudBusinessSnapshot(shop.id,"checkpoint",sent,remote)
            assertEquals(7L,a.products(shop.id).single().exactStockScaled)
            assertEquals(2,a.sales(shop.id).size)
            assertEquals(1300L,a.expectedCash(shop.id,a.activeCashShift(shop.id)!!.id))
            assertEquals(remote,a.cloudBusinessSnapshot(shop.id,"business"))
        }}
    }
    /** Opt in with -e cloudUrl https://...; all records and credentials are disposable QA data. */
    @Test fun nativeHttpsTransportPublishesAndDownloadsRealCloudRecords()=fixture {directory ->
        val url=InstrumentationRegistry.getArguments().getString("cloudUrl")
        assumeTrue("Live endpoint must be explicitly provided",!url.isNullOrBlank())
        val endpoint=CloudEndpoint.parse(requireNotNull(url));val transport=HttpCloudTransport()
        val account=transport.register(endpoint,CloudAccountRequest("android-qa-${UUID.randomUUID()}@example.test",UUID.randomUUID().toString()))
        PosRepository(directory.resolve("native.db").path).use {repo ->
            val shop=repo.createTenant("Android cloud QA · disposable");val product=repo.addProduct(shop.id,"QA soap","piece",55000,10.0);repo.openCashShift(shop.id,100000)
            val schema=repo.cloudBusinessSchema()
            val snapshot=repo.cloudBusinessSnapshot(shop.id,shop.id)
            val business=transport.createBusiness(endpoint,account.accessToken,CloudCreateBusinessRequest(UUID.randomUUID().toString(),shop.name,schema.fingerprint,snapshot))
            val device=transport.enrollDevice(endpoint,account.accessToken,business.id,CloudDeviceEnrollRequest("Android native QA",UUID.randomUUID().toString()))
            try {
                val initial=transport.bootstrap(endpoint,device.deviceToken,business.id)
                assertEquals(schema.fingerprint,initial.schema.fingerprint)
                repo.checkout(shop.id,listOf(CartLine(product,2)),110000,operationId="android-qa-sale")
                val delta=CloudMerge.diff(initial.snapshot,repo.cloudBusinessSnapshot(shop.id,business.id),schema)
                val push=CloudPushRequest(UUID.randomUUID().toString(),initial.revision,schema.fingerprint,delta)
                val accepted=transport.push(endpoint,device.deviceToken,business.id,push)
                assertEquals("accepted",accepted.status)
                assertEquals(accepted,transport.push(endpoint,device.deviceToken,business.id,push))
                val head=transport.bootstrap(endpoint,device.deviceToken,business.id)
                repo.applyCloudBusinessSnapshot(shop.id,"live-ack",repo.cloudBusinessSnapshot(shop.id,business.id),head.snapshot)
                assertEquals(8L,repo.products(shop.id).single().exactStockScaled)
                assertEquals(1,repo.sales(shop.id).size)
                assertEquals(210000L,repo.expectedCash(shop.id,repo.activeCashShift(shop.id)!!.id))
            } finally {transport.revokeDevice(endpoint,account.accessToken,business.id,device.deviceId)}
        }
    }
}
