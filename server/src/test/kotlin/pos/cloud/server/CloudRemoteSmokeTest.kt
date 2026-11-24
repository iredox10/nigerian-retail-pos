package pos.cloud.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import pos.cloud.client.*
import pos.cloud.protocol.*
import pos.data.*
import pos.domain.*
import java.util.UUID
import kotlin.test.*

/** Explicit staging opt-in; never reads installed-app workspaces or merchant credentials. */
@EnabledIfEnvironmentVariable(named="CLOUD_LIVE_URL",matches="https://.+")
class CloudRemoteSmokeTest {
    @Test fun twoIndependentSqliteDevicesConvergeOverDeployedHttps() {
        val endpoint=CloudEndpoint.parse(System.getenv("CLOUD_LIVE_URL"));val transport=HttpCloudTransport()
        val account=transport.register(endpoint,CloudAccountRequest("desktop-qa-${UUID.randomUUID()}@example.test",UUID.randomUUID().toString()))
        PosRepository(":memory:").use {a ->PosRepository(":memory:").use {b ->
            val shop=a.createTenant("Desktop cloud QA · disposable");val product=a.addProduct(shop.id,"QA soap","piece",100,10.0);a.openCashShift(shop.id,1000)
            val schema=a.cloudBusinessSchema()
            val business=transport.createBusiness(endpoint,account.accessToken,CloudCreateBusinessRequest(UUID.randomUUID().toString(),shop.name,schema.fingerprint,a.cloudBusinessSnapshot(shop.id,shop.id)))
            val desktop=transport.enrollDevice(endpoint,account.accessToken,business.id,CloudDeviceEnrollRequest("Desktop HTTPS QA",UUID.randomUUID().toString()))
            val phone=transport.enrollDevice(endpoint,account.accessToken,business.id,CloudDeviceEnrollRequest("Second SQLite HTTPS QA",UUID.randomUUID().toString()))
            try {
                val base=transport.bootstrap(endpoint,desktop.deviceToken,business.id)
                val other=b.restoreTenantSnapshot(TenantSnapshot(Tenant(base.businessId,base.snapshot.tenantName),base.snapshot.tables),shop.name)
                // Each saves while disconnected from the cloud; only then do their independent uploads run.
                a.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="desktop-sale")
                b.checkout(other.id,listOf(CartLine(b.products(other.id).single(),2)),200,operationId="second-sale")
                val sentA=a.cloudBusinessSnapshot(shop.id,business.id);val sentB=b.cloudBusinessSnapshot(other.id,business.id)
                val pushA=CloudPushRequest(UUID.randomUUID().toString(),base.revision,schema.fingerprint,CloudMerge.diff(base.snapshot,sentA,schema))
                val pushB=CloudPushRequest(UUID.randomUUID().toString(),base.revision,schema.fingerprint,CloudMerge.diff(base.snapshot,sentB,schema))
                val accepted=transport.push(endpoint,desktop.deviceToken,business.id,pushA)
                assertEquals("accepted",accepted.status);assertEquals(accepted,transport.push(endpoint,desktop.deviceToken,business.id,pushA))
                assertEquals("accepted",transport.push(endpoint,phone.deviceToken,business.id,pushB).status)
                val head=transport.bootstrap(endpoint,desktop.deviceToken,business.id)
                a.applyCloudBusinessSnapshot(shop.id,"live-a",sentA,head.snapshot)
                b.applyCloudBusinessSnapshot(other.id,"live-b",sentB,head.snapshot)
                listOf(a to shop.id,b to other.id).forEach {(r,id)->
                    assertEquals(7L,r.products(id).single().exactStockScaled);assertEquals(2,r.sales(id).size)
                    assertEquals(1300L,r.expectedCash(id,r.activeCashShift(id)!!.id))
                }
            } finally {
                transport.revokeDevice(endpoint,account.accessToken,business.id,desktop.deviceId)
                transport.revokeDevice(endpoint,account.accessToken,business.id,phone.deviceId)
            }
        }}
    }
}
