package pos.cloud.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pos.cloud.protocol.*
import pos.cloud.client.*
import pos.data.*
import java.nio.file.Files
import pos.data.PosRepository
import pos.data.TenantSnapshot
import pos.domain.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

/** Real PostgreSQL and real HTTP. Not substituted with an in-memory SQL dialect. */
@EnabledIfEnvironmentVariable(named="CLOUD_TEST_JDBC_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CloudServerIntegrationTest {
    @LocalServerPort private var port:Int=0
    @Autowired private lateinit var jdbc:JdbcTemplate
    @Autowired private lateinit var rules:CloudBusinessRules
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val schema get()=rules.schema()
    private data class Reply(val status:Int,val body:String)
    private fun send(method:String,path:String,body:String?=null,token:String?=null):Reply {
        val builder=HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1$path")).timeout(Duration.ofSeconds(60)).header("Accept","application/json")
        if(token!=null)builder.header("Authorization","Bearer $token")
        if(body!=null)builder.header("Content-Type","application/json")
        builder.method(method,if(body==null)HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(),response.body())
    }
    private fun account():CloudAccountSession {
        val reply=send("POST","/accounts/register",CloudCodec.encode(CloudAccountRequest("test-${UUID.randomUUID()}@example.test","Cloud-test-password-2026")))
        assertEquals(201,reply.status,reply.body);return CloudCodec.decode(reply.body)
    }
    private fun snapshot():CloudSnapshot=PosRepository(":memory:").use {r ->
        val shop=r.createTenant("Cloud test shop")
        r.addProduct(shop.id,"Soap","piece",100,10.0)
        r.openCashShift(shop.id,1000)
        val s=r.exportTenantSnapshot(shop.id)
        CloudMerge.normalize(CloudSnapshot(s.tenant.id,s.tenant.name,s.tables),schema)
    }
    private fun publish(account:CloudAccountSession,snapshot:CloudSnapshot=snapshot()):CloudBusinessSummary {
        val reply=send("POST","/businesses",CloudCodec.encode(CloudCreateBusinessRequest(UUID.randomUUID().toString(),snapshot.tenantName,schema.fingerprint,snapshot)),account.accessToken)
        assertEquals(201,reply.status,reply.body);return CloudCodec.decode(reply.body)
    }
    private fun enroll(account:CloudAccountSession,business:CloudBusinessSummary,name:String="Counter",installation:String=UUID.randomUUID().toString()):CloudDeviceSession {
        val reply=send("POST","/businesses/${business.id}/devices",CloudCodec.encode(CloudDeviceEnrollRequest(name,installation)),account.accessToken)
        assertEquals(200,reply.status,reply.body);return CloudCodec.decode(reply.body)
    }
    private fun bootstrap(device:CloudDeviceSession):CloudBootstrapReply {
        val reply=send("GET","/businesses/${device.businessId}/bootstrap",token=device.deviceToken)
        assertEquals(200,reply.status,reply.body);return CloudCodec.decode(reply.body)
    }
    private fun localSale(base:CloudBootstrapReply,quantity:Int,operation:String):CloudSnapshot=PosRepository(":memory:").use {r ->
        val t=r.restoreTenantSnapshot(TenantSnapshot(Tenant(base.snapshot.tenantId,base.snapshot.tenantName),base.snapshot.tables),base.snapshot.tenantName)
        val item=r.products(t.id).single()
        r.checkout(t.id,listOf(CartLine(item,quantity)),item.priceMinor*quantity,operationId=operation)
        val local=r.exportTenantSnapshot(t.id)
        CloudMerge.normalize(CloudSnapshot(base.businessId,base.snapshot.tenantName,local.tables.mapValues {(_,rows)->rows.map {row ->if("tenant_id" in row) row+("tenant_id" to base.businessId) else row}}),schema)
    }
    @Test fun twoDisconnectedDevicesRetryAndConvergeThroughRealHttpWithoutDuplicateCashOrStock() {
        val owner=account();val business=publish(owner)
        val a=enroll(owner,business,"Desktop");val b=enroll(owner,business,"Phone")
        val base=bootstrap(a)
        val da=CloudMerge.diff(base.snapshot,localSale(base,2,"desktop-sale"),schema)
        val db=CloudMerge.diff(base.snapshot,localSale(base,2,"phone-sale"),schema)
        val first=CloudPushRequest("first",0,schema.fingerprint,da)
        val firstReply=send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(first),a.deviceToken)
        assertEquals(200,firstReply.status,firstReply.body)
        assertEquals("accepted",CloudCodec.decode<CloudPushReply>(firstReply.body).status)
        assertEquals(firstReply,send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(first),a.deviceToken))
        val second=send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(CloudPushRequest("second",0,schema.fingerprint,db)),b.deviceToken)
        assertEquals(200,second.status,second.body);assertEquals("accepted",CloudCodec.decode<CloudPushReply>(second.body).status)
        val head=bootstrap(b)
        assertEquals(2,head.revision)
        assertEquals(2,head.snapshot.tables.getValue("sales").size)
        PosRepository(":memory:").use {r ->
            val t=r.restoreTenantSnapshot(TenantSnapshot(Tenant(head.snapshot.tenantId,head.snapshot.tenantName),head.snapshot.tables),"Recovered cloud")
            assertEquals(6L,r.products(t.id).single().exactStockScaled)
            assertEquals(1400L,r.expectedCash(t.id,r.activeCashShift(t.id)!!.id))
        }
        val page1=send("GET","/businesses/${business.id}/sync/pull?after=0&limit=1",token=a.deviceToken).let {assertEquals(200,it.status,it.body);CloudCodec.decode<CloudPullReply>(it.body)}
        assertEquals(1,page1.changes.size);assertEquals(1,page1.nextRevision);assertEquals(2,page1.headRevision)
        val page2=send("GET","/businesses/${business.id}/sync/pull?after=1&limit=1",token=a.deviceToken).let {CloudCodec.decode<CloudPullReply>(it.body)}
        val pulled=(page1.changes+page2.changes).fold(base.snapshot) {s,event ->CloudMerge.applyExact(s,event.delta,schema)}
        assertEquals(head.snapshot,pulled)
        val changed=send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(first.copy(baseRevision=1)),a.deviceToken)
        assertEquals(409,changed.status)
        assertEquals(2L,bootstrap(a).revision)
    }
    @Test fun simultaneousRequestsCommitConsecutiveRevisionsUnderTheBusinessRowLock() {
        val owner=account();val business=publish(owner);val a=enroll(owner,business);val b=enroll(owner,business)
        val base=bootstrap(a)
        val pa=CloudPushRequest("parallel-a",0,schema.fingerprint,CloudMerge.diff(base.snapshot,localSale(base,1,"a"),schema))
        val pb=CloudPushRequest("parallel-b",0,schema.fingerprint,CloudMerge.diff(base.snapshot,localSale(base,1,"b"),schema))
        val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=listOf(a to pa,b to pb).map {(device,push)->pool.submit<Reply> {send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(push),device.deviceToken)}}
            val replies=futures.map {it.get()}
            replies.forEach {assertEquals(200,it.status,it.body);assertEquals("accepted",CloudCodec.decode<CloudPushReply>(it.body).status)}
            assertEquals(setOf(1L,2L),replies.map {CloudCodec.decode<CloudPushReply>(it.body).revision}.toSet())
            assertEquals("8",bootstrap(a).snapshot.tables.getValue("products").single()["stock_scaled"])
        } finally {pool.shutdownNow()}
    }
    @Test fun revokedExpiredAndWrongBusinessCredentialsCannotReadOrReplayRecords() {
        val owner=account();val business=publish(owner);val device=enroll(owner,business)
        val other=account();val otherBusiness=publish(other);val otherDevice=enroll(other,otherBusiness)
        assertEquals(401,send("GET","/businesses/${business.id}/bootstrap",token=otherDevice.deviceToken).status)
        assertEquals(403,send("GET","/businesses/${business.id}/devices",token=other.accessToken).status)
        assertEquals(403,send("POST","/businesses/${business.id}/devices",CloudCodec.encode(CloudDeviceEnrollRequest("Denied",UUID.randomUUID().toString())),other.accessToken).status)
        assertEquals(204,send("DELETE","/businesses/${business.id}/devices/${device.deviceId}",token=owner.accessToken).status)
        assertEquals(401,send("GET","/businesses/${business.id}/bootstrap",token=device.deviceToken).status)
        val expired=enroll(owner,business)
        jdbc.update("UPDATE cloud_devices SET expires_at=enrolled_at + interval '1 second',enrolled_at=enrolled_at - interval '2 days' WHERE id=?",UUID.fromString(expired.deviceId))
        // The database clock is not substituted: set the stored interval wholly in the past.
        jdbc.update("UPDATE cloud_devices SET enrolled_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE id=?",UUID.fromString(expired.deviceId))
        assertEquals(401,send("GET","/businesses/${business.id}/bootstrap",token=expired.deviceToken).status)
        val hash=jdbc.queryForObject("SELECT token_hash FROM cloud_devices WHERE id=?",String::class.java,UUID.fromString(device.deviceId))
        assertNotEquals(device.deviceToken,hash);assertEquals(64,hash!!.length)
    }
    @Test fun retainedConflictsAndRejectedSchemaChangesDoNotOverwriteAcceptedBusinessRecords() {
        val owner=account();val business=publish(owner);val device=enroll(owner,business);val base=bootstrap(device)
        val forged=base.snapshot.copy(tables=base.snapshot.tables+("products" to base.snapshot.tables.getValue("products").map {it+("stock_scaled" to "9")+("stock" to "9.0")}))
        val push=CloudPushRequest("unexplained-stock",0,schema.fingerprint,CloudMerge.diff(base.snapshot,forged,schema))
        val reply=send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(push),device.deviceToken)
        assertEquals(200,reply.status,reply.body);assertEquals("conflict",CloudCodec.decode<CloudPushReply>(reply.body).status)
        assertEquals(base,bootstrap(device))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cloud_operations WHERE business_id=? AND operation_id=? AND status='conflict'",Int::class.java,UUID.fromString(business.id),push.operationId))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cloud_operation_assessments WHERE business_id=? AND operation_id=?",Int::class.java,UUID.fromString(business.id),push.operationId))
        val wrong=send("POST","/businesses/${business.id}/sync/push",CloudCodec.encode(push.copy(operationId="wrong-schema",schemaHash="0".repeat(64))),device.deviceToken)
        assertEquals(409,wrong.status);assertEquals(0L,bootstrap(device).revision)
    }
    @Test fun publishingRetriesAndDeviceReenrollmentDoNotDuplicateBusinessesOrInstallations() {
        val owner=account();val initial=snapshot();val request=CloudCreateBusinessRequest("publish-once",initial.tenantName,schema.fingerprint,initial)
        val first=send("POST","/businesses",CloudCodec.encode(request),owner.accessToken)
        assertEquals(201,first.status,first.body)
        val repeated=send("POST","/businesses",CloudCodec.encode(request),owner.accessToken)
        assertEquals(first,repeated)
        assertEquals(409,send("POST","/businesses",CloudCodec.encode(request.copy(name="Other")),owner.accessToken).status)
        val business=CloudCodec.decode<CloudBusinessSummary>(first.body);val installation=UUID.randomUUID().toString()
        val a=enroll(owner,business,installation=installation);val b=enroll(owner,business,installation=installation)
        assertEquals(a.deviceId,b.deviceId);assertNotEquals(a.deviceToken,b.deviceToken)
        assertEquals(401,send("GET","/businesses/${business.id}/bootstrap",token=a.deviceToken).status)
        assertEquals(200,send("GET","/businesses/${business.id}/bootstrap",token=b.deviceToken).status)
        assertEquals(1,CloudCodec.decode<List<CloudDeviceSummary>>(send("GET","/businesses/${business.id}/devices",token=owner.accessToken).body).size)
    }
    @Test fun failedLoginThrottlePersistsAndBadInputsReturnNoSecrets() {
        val email="login-${UUID.randomUUID()}@example.test";val password="Cloud-test-password-2026"
        assertEquals(201,send("POST","/accounts/register",CloudCodec.encode(CloudAccountRequest(email,password))).status)
        repeat(5) {assertEquals(401,send("POST","/accounts/login",CloudCodec.encode(CloudAccountRequest(email,"Wrong-password-2026"))).status)}
        val locked=send("POST","/accounts/login",CloudCodec.encode(CloudAccountRequest(email,password)))
        assertEquals(429,locked.status);assertFalse(locked.body.contains(password));assertFalse(locked.body.contains(email))
        val malformed=send("POST","/accounts/login","{\"password\":\"must-not-be-echoed\"}")
        assertEquals(400,malformed.status);assertFalse(malformed.body.contains("must-not-be-echoed"))
        assertEquals(401,send("GET","/businesses").status)
        assertEquals(200,send("GET","/health").status)
    }

    @Test fun durableCoordinatorsRecoverLostAcknowledgmentAndPreserveNewerOfflineWrites() {
        PosRepository(":memory:").use {a ->PosRepository(":memory:").use {b ->
            val shop=a.createTenant("Desktop shop");val product=a.addProduct(shop.id,"Soap","piece",100,10.0);a.openCashShift(shop.id,1000)
            val bootstrapShop=b.createTenant("Phone setup")
            val endpoint=CloudEndpoint.parse("http://127.0.0.1:$port",true)
            val transport=HttpCloudTransport()
            val login=transport.register(endpoint,CloudAccountRequest("coordinator-${UUID.randomUUID()}@example.test","Cloud-test-password-2026"))
            fun local(repo:PosRepository)=object:CloudLocalStore {
                override fun authorizeCloudSetup(localShopId:String) {require(repo.tenants().any {it.id==localShopId})}
                override fun schema(localShopId:String)=repo.cloudBusinessSchema()
                override fun snapshot(localShopId:String,businessId:String)=repo.cloudBusinessSnapshot(localShopId,businessId)
                override fun applyRemote(localShopId:String,checkpointId:String,base:CloudSnapshot,remote:CloudSnapshot,schema:CloudSchema)=repo.applyCloudBusinessSnapshot(localShopId,checkpointId,base,remote)
                override fun importShop(authorizingLocalShopId:String,importId:String,bootstrap:CloudBootstrapReply,localName:String,localPassword:String)=repo.importCloudBusinessShop(authorizingLocalShopId,importId,bootstrap.snapshot,localName) {}.id
            }
            val filesA=PrivateCloudFiles(Files.createTempDirectory("cloud-coordinator-a"))
            val stateA=CloudStateStore(filesA);val secretsA=FileCloudCredentialStore(filesA)
            val filesB=PrivateCloudFiles(Files.createTempDirectory("cloud-coordinator-b"))
            val stateB=CloudStateStore(filesB);val secretsB=FileCloudCredentialStore(filesB)
            val published=CloudCoordinator(local(a),transport,stateA,secretsA).use {it.publish(shop.id,endpoint,login,"Desktop")}
            val phone=CloudCoordinator(local(b),transport,stateB,secretsB).use {it.join(bootstrapShop.id,endpoint,login,published.businessId,"Phone","Phone shop","Local-owner-password")}
            a.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="desktop-offline")
            b.checkout(phone,listOf(CartLine(b.products(phone).single(),2)),200,operationId="phone-offline")
            // Server committed; its reply was lost and another local write completed during the upload.
            val lost=object:CloudTransport by transport {
                override fun push(endpoint:CloudEndpoint,deviceToken:String,businessId:String,request:CloudPushRequest):CloudPushReply {
                    transport.push(endpoint,deviceToken,businessId,request)
                    a.recordExpense(shop.id,"Transport","Saved during upload",50,"during-upload")
                    throw CloudConnectionException("Simulated lost acknowledgment")
                }
            }
            CloudCoordinator(local(a),lost,stateA,secretsA).use {assertEquals("retry_wait",it.syncNow(shop.id).phase)}
            assertNotNull(stateA.load(shop.id)!!.pendingUpload)
            CloudCoordinator(local(b),transport,stateB,secretsB).use {assertEquals("up_to_date",it.syncNow(phone).phase)}
            CloudCoordinator(local(a),transport,stateA,secretsA).use {
                assertEquals("local_pending",it.syncNow(shop.id).phase)
                assertEquals("up_to_date",it.syncNow(shop.id).phase)
                assertEquals("up_to_date",it.syncNow(shop.id).phase)
            }
            CloudCoordinator(local(b),transport,stateB,secretsB).use {assertEquals("up_to_date",it.syncNow(phone).phase)}
            assertEquals(2,a.sales(shop.id).size);assertEquals(2,b.sales(phone).size)
            assertEquals(7L,a.products(shop.id).single().exactStockScaled);assertEquals(7L,b.products(phone).single().exactStockScaled)
            assertEquals(1250L,a.expectedCash(shop.id,a.activeCashShift(shop.id)!!.id))
            assertEquals(1250L,b.expectedCash(phone,b.activeCashShift(phone)!!.id))
            assertNull(stateA.load(shop.id)!!.pendingUpload)
            val token=secretsA.read(stateA.load(shop.id)!!.credentialKey)!!
            assertFalse(Files.readString(filesA.directory.resolve(filesA.names().single {it.endsWith(".link.json")})).contains(token))
            transport.revokeDevice(endpoint,login.accessToken,published.businessId,published.deviceId)
            CloudCoordinator(local(a),transport,stateA,secretsA).use {assertEquals("auth_required",it.syncNow(shop.id).phase)}
            assertEquals(2,a.sales(shop.id).size)
        }}
    }
    companion object {
        @JvmStatic @DynamicPropertySource fun properties(registry:DynamicPropertyRegistry) {
            registry.add("CLOUD_JDBC_URL") {System.getenv("CLOUD_TEST_JDBC_URL")}
            registry.add("CLOUD_DB_USER") {System.getenv("CLOUD_TEST_DB_USER") ?: "retailpos"}
            registry.add("CLOUD_DB_PASSWORD") {System.getenv("CLOUD_TEST_DB_PASSWORD")}
        }
    }
}
