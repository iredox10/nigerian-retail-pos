package pos.cloud.client

import pos.cloud.protocol.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Status is local observation, not payment verification or a guarantee about another offline device. */
data class CloudSyncStatus(
    val phase:String="not_connected",val pendingRows:Int=0,val lastSyncedAt:String?=null,
    val lastContactAt:String?=null,val nextRetryAt:String?=null,val message:String?=null,
    val conflicts:List<CloudConflict> = emptyList(),
)
data class CloudConnectionInfo(val localShopId:String,val endpoint:CloudEndpoint,val businessId:String,val deviceId:String,val deviceName:String,val revision:Long,val lastSyncedAt:String?)

class CloudCoordinator(
    private val local:CloudLocalStore,
    private val transport:CloudTransport,
    private val states:CloudStateStore,
    private val credentials:CloudCredentialStore,
    private val clock:()->Instant={Instant.now()},
) : AutoCloseable {
    private val locks=ConcurrentHashMap<String,ReentrantLock>()
    private val statuses=ConcurrentHashMap<String,CloudSyncStatus>()
    private val failures=ConcurrentHashMap<String,Int>()
    private val retryAt=ConcurrentHashMap<String,Instant>()
    private val generation=AtomicLong()
    @Volatile private var activeShop:String?=null
    @Volatile private var closed=false
    private val scheduler=Executors.newSingleThreadScheduledExecutor {r ->Thread(r,"retail-cloud-sync").apply {isDaemon=true}}
    @Volatile private var schedulerStarted=false

    fun status(localShopId:String):CloudSyncStatus=statuses[localShopId] ?: CloudSyncStatus()
    /** Invalidate a successful observation immediately after the app saves new local work. */
    fun localChanged(localShopId:String) {
        statuses.computeIfPresent(localShopId) {_,old ->
            if(old.phase=="up_to_date") old.copy(phase="local_pending",message="New local work is saved here and waiting to sync.") else old
        }
    }
    /** Disk-backed metadata; call from a background/UI IO context, not while drawing a frame. */
    fun connection(localShopId:String):CloudConnectionInfo?=states.load(localShopId)?.let(::info)
    fun register(endpoint:CloudEndpoint,email:String,password:String):CloudAccountSession=transport.register(endpoint,CloudAccountRequest(email.trim(),password))
    fun signIn(endpoint:CloudEndpoint,email:String,password:String):CloudAccountSession=transport.login(endpoint,CloudAccountRequest(email.trim(),password))
    fun businesses(endpoint:CloudEndpoint,account:CloudAccountSession):List<CloudBusinessSummary> =transport.businesses(endpoint,account.accessToken)

    fun capabilities(endpoint:CloudEndpoint)=transport.capabilities(endpoint)
    fun sendVerification(endpoint:CloudEndpoint,account:CloudAccountSession)=transport.sendVerification(endpoint,account.accessToken)
    fun verifyEmail(endpoint:CloudEndpoint,account:CloudAccountSession,code:String)=transport.verifyEmail(endpoint,account.accessToken,CloudVerifyEmail(code))
    fun requestPasswordReset(endpoint:CloudEndpoint,email:String)=transport.requestReset(endpoint,CloudEmailRequest(email))
    fun resetAccountPassword(endpoint:CloudEndpoint,email:String,code:String,password:String)=transport.resetPassword(endpoint,CloudResetPassword(email,code,password))
    fun startPairing(shop:String,endpoint:CloudEndpoint,name:String):CloudPairingTicket=lockFor("pairing:$shop").withLock {
        local.authorizeCloudSetup(shop);validDeviceName(name)
        states.pairing(shop)?.let {attempt ->
            if(attempt.endpoint==endpoint && Instant.parse(attempt.ticket.expiresAt).isAfter(clock())) {
                credentials.read("pairing:"+attempt.ticket.id)?.let {return@withLock attempt.ticket.copy(secret=it)}
            }
        }
        val ticket=transport.startPairing(endpoint,CloudPairingStart(name,states.installationId))
        require(ticket.secret.matches(Regex("[A-Za-z0-9_-]{43}")) && Instant.parse(ticket.expiresAt).isAfter(clock()))
        credentials.write("pairing:"+ticket.id,ticket.secret)
        states.savePairing(CloudPairingAttempt(shop,endpoint,ticket.copy(secret=""),name))
        ticket
    }
    fun discardPairing(shop:String) = lockFor("pairing:$shop").withLock {
        states.pairing(shop)?.let {attempt ->
            credentials.read("pairing:"+attempt.ticket.id)?.let {secret ->
                runCatching {transport.finishPairing(attempt.endpoint,CloudPairingClaim(attempt.ticket.id,secret))}
            }
            credentials.remove("pairing:"+attempt.ticket.id)
        }
        states.finishPairing(shop)
    }
    fun pollPairing(endpoint:CloudEndpoint,ticket:CloudPairingTicket)=transport.claimPairing(endpoint,CloudPairingClaim(ticket.id,ticket.secret))
    fun reviewPairing(shop:String,account:CloudAccountSession,code:String):CloudPairingReview {
        local.authorizeCloudSetup(shop);val link=requireNotNull(states.load(shop))
        return transport.reviewPairing(link.endpoint,account.accessToken,link.businessId,CloudPairingCode(code))
    }
    fun approvePairing(shop:String,account:CloudAccountSession,code:String,review:CloudPairingReview) {
        local.authorizeCloudSetup(shop);val link=requireNotNull(states.load(shop))
        transport.approvePairing(link.endpoint,account.accessToken,link.businessId,CloudPairingCode(code,review.id))
    }
    fun joinPaired(shop:String,endpoint:CloudEndpoint,ticket:CloudPairingTicket,localName:String,password:String):String=lockFor("pairing:$shop").withLock {
        ensureOpen()
        val result=pollPairing(endpoint,ticket)
        val device=requireNotNull(result.device) {"Ask the owner to approve this device first."}
        require(result.status=="approved")
        states.links().find {it.endpoint==endpoint && it.businessId==device.businessId}?.let {return@withLock it.localShopId}
        local.authorizeCloudSetup(shop)
        require(localName.isNotBlank() && password.length in 8..1024) {"Enter a shop name and a device unlock password of at least 8 characters."}
        val schema=local.schema(shop)
        val bootstrap=transport.bootstrap(endpoint,device.deviceToken,device.businessId)
        checkBootstrap(bootstrap,device.businessId,schema)
        local.authorizeCloudSetup(shop)
        val attempt=states.joinAttempt(shop,device.businessId) ?: CloudJoinAttempt(shop,endpoint,device.businessId,requireNotNull(states.pairing(shop)).deviceName,ticket.id).also(states::saveJoin)
        require(attempt.endpoint==endpoint)
        val key="device:"+endpoint.url+":"+device.businessId+":"+states.installationId
        credentials.write(key,device.deviceToken)
        val id=local.importShop(shop,attempt.importId,bootstrap,localName.trim(),password)
        val now=clock().toString()
        states.save(CloudLinkState(id,endpoint,device.businessId,device.deviceId,attempt.deviceName,states.installationId,bootstrap.revision,schema,bootstrap.snapshot,lastSyncedAt=now))
        states.finishJoin(attempt);states.finishPairing(shop);credentials.remove("pairing:"+ticket.id)
        // Local success is durable even when acknowledgment cleanup cannot reach the server.
        runCatching {transport.finishPairing(endpoint,CloudPairingClaim(ticket.id,ticket.secret))}
        statuses[id]=CloudSyncStatus("up_to_date",lastSyncedAt=now,lastContactAt=now,message="Shop downloaded and saved on this device.")
        id
    }
    fun deviceDetails(shop:String,account:CloudAccountSession):List<CloudDeviceDetails> {
        local.authorizeCloudSetup(shop);val link=requireNotNull(states.load(shop))
        return transport.deviceDetails(link.endpoint,account.accessToken,link.businessId)
    }

    /** The exact first request is checkpointed before publication, including across lost replies. */
    fun publish(localShopId:String,endpoint:CloudEndpoint,account:CloudAccountSession,deviceName:String):CloudConnectionInfo=lockFor(localShopId).withLock {
        ensureOpen();local.authorizeCloudSetup(localShopId);validDeviceName(deviceName)
        states.load(localShopId)?.let {existing ->
            require(existing.endpoint==endpoint) {"This shop is already linked to a different cloud server."}
            return@withLock info(reauthorizeLocked(existing,account))
        }
        val schema=local.schema(localShopId)
        var attempt=states.publishAttempt(localShopId)
        if(attempt==null) {
            val snapshot=CloudMerge.normalize(local.snapshot(localShopId,localShopId),schema)
            attempt=CloudPublishAttempt(localShopId,endpoint,deviceName.trim(),CloudCreateBusinessRequest(UUID.randomUUID().toString(),snapshot.tenantName,schema.fingerprint,snapshot))
            states.savePublish(attempt)
        }
        require(attempt.endpoint==endpoint && attempt.request.schemaHash==schema.fingerprint) {"A pending publication uses another server or data version. Keep its checkpoint and review the connection."}
        val business=transport.createBusiness(endpoint,account.accessToken,attempt.request)
        local.authorizeCloudSetup(localShopId)
        val device=transport.enrollDevice(endpoint,account.accessToken,business.id,CloudDeviceEnrollRequest(attempt.deviceName,states.installationId))
        require(device.businessId==business.id) {"Cloud device belongs to a different business."}
        val bootstrap=transport.bootstrap(endpoint,device.deviceToken,business.id)
        checkBootstrap(bootstrap,business.id,schema)
        local.authorizeCloudSetup(localShopId)
        val original=rebind(attempt.request.snapshot,business.id,schema)
        var state=CloudLinkState(localShopId,endpoint,business.id,device.deviceId,attempt.deviceName,states.installationId,
            bootstrap.revision,schema,bootstrap.snapshot,pendingPull=CloudPendingPull(UUID.randomUUID().toString(),original,bootstrap.snapshot,bootstrap.revision))
        credentials.write(state.credentialKey,device.deviceToken)
        states.save(state)
        state=finishPull(state,clock().toString())
        states.finishPublish(localShopId)
        statuses[localShopId]=CloudSyncStatus("local_pending",lastSyncedAt=state.lastSyncedAt,lastContactAt=clock().toString(),message="Cloud shop connected. Automatic sync will check remaining local work.")
        failures.remove(localShopId);retryAt.remove(localShopId)
        info(state)
    }

    /** Import gets an idempotent local marker; it can never replace the currently selected shop. */
    fun join(authorizingLocalShopId:String,endpoint:CloudEndpoint,account:CloudAccountSession,businessId:String,deviceName:String,localName:String,localPassword:String):String=lockFor("join:$authorizingLocalShopId:$businessId").withLock {
        ensureOpen();local.authorizeCloudSetup(authorizingLocalShopId);validDeviceName(deviceName)
        require(localName.trim().isNotEmpty() && localPassword.length in 8..1024) {"Enter the name and owner password for this shop on this device."}
        states.links().find {it.endpoint==endpoint && it.businessId==businessId}?.let {existing ->return@withLock existing.localShopId}
        val schema=local.schema(authorizingLocalShopId)
        val attempt=states.joinAttempt(authorizingLocalShopId,businessId) ?: CloudJoinAttempt(authorizingLocalShopId,endpoint,businessId,deviceName.trim(),UUID.randomUUID().toString()).also(states::saveJoin)
        require(attempt.endpoint==endpoint) {"A pending import is connected to another cloud server."}
        val device=transport.enrollDevice(endpoint,account.accessToken,businessId,CloudDeviceEnrollRequest(attempt.deviceName,states.installationId))
        require(device.businessId==businessId)
        val bootstrap=transport.bootstrap(endpoint,device.deviceToken,businessId)
        checkBootstrap(bootstrap,businessId,schema)
        local.authorizeCloudSetup(authorizingLocalShopId)
        val credentialKey="device:"+endpoint.url+":"+businessId+":"+states.installationId
        credentials.write(credentialKey,device.deviceToken)
        val localShopId=local.importShop(authorizingLocalShopId,attempt.importId,bootstrap,localName.trim(),localPassword)
        val state=CloudLinkState(localShopId,endpoint,businessId,device.deviceId,attempt.deviceName,states.installationId,bootstrap.revision,schema,bootstrap.snapshot,lastSyncedAt=clock().toString())
        states.save(state);states.finishJoin(attempt)
        statuses[localShopId]=CloudSyncStatus("up_to_date",lastSyncedAt=state.lastSyncedAt,lastContactAt=state.lastSyncedAt,message="Cloud records downloaded and saved on this device.")
        localShopId
    }

    fun reconnect(localShopId:String,account:CloudAccountSession):CloudConnectionInfo=lockFor(localShopId).withLock {
        ensureOpen();local.authorizeCloudSetup(localShopId)
        info(reauthorizeLocked(requireNotNull(states.load(localShopId)) {"Connect this shop first."},account))
    }
    private fun reauthorizeLocked(state:CloudLinkState,account:CloudAccountSession):CloudLinkState {
        val device=transport.enrollDevice(state.endpoint,account.accessToken,state.businessId,CloudDeviceEnrollRequest(state.deviceName,states.installationId))
        require(device.businessId==state.businessId && device.deviceId==state.deviceId) {"Cloud device identity changed. Existing checkpoints were kept for review."}
        local.authorizeCloudSetup(state.localShopId)
        credentials.write(state.credentialKey,device.deviceToken)
        retryAt.remove(state.localShopId);failures.remove(state.localShopId)
        statuses[state.localShopId]=CloudSyncStatus("local_pending",lastSyncedAt=state.lastSyncedAt,message="Cloud access renewed. Pending work will be checked.")
        return state
    }
    fun devices(localShopId:String,account:CloudAccountSession):List<CloudDeviceSummary> {
        local.authorizeCloudSetup(localShopId);val state=requireNotNull(states.load(localShopId)) {"Connect this shop first."}
        return transport.devices(state.endpoint,account.accessToken,state.businessId)
    }
    fun revokeDevice(localShopId:String,account:CloudAccountSession,deviceId:String) {
        local.authorizeCloudSetup(localShopId);val state=requireNotNull(states.load(localShopId)) {"Connect this shop first."}
        transport.revokeDevice(state.endpoint,account.accessToken,state.businessId,deviceId)
        if(deviceId==state.deviceId) {credentials.remove(state.credentialKey);statuses[localShopId]=CloudSyncStatus("auth_required",lastSyncedAt=state.lastSyncedAt,message="This device's cloud access was revoked. Local records remain available.")}
    }

    /** Nonblocking status reads; root starts/stops this only for the currently authorized shop. */
    @Synchronized fun activate(localShopId:String) {
        ensureOpen();require(localShopId.isNotBlank());activeShop=localShopId;generation.incrementAndGet()
        if(!schedulerStarted) {schedulerStarted=true;scheduler.scheduleWithFixedDelay({tick()},0,15,TimeUnit.SECONDS)}
        else scheduler.execute {tick()}
    }
    @Synchronized fun lock() {
        activeShop?.let {shop ->statuses[shop]=status(shop).copy(phase="paused",nextRetryAt=null,message="Cloud sync paused while this shop is locked.")}
        activeShop=null;generation.incrementAndGet()
    }
    private fun tick() {
        if(closed)return
        val shop=activeShop ?:return
        if(retryAt[shop]?.isAfter(clock())==true)return
        syncNow(shop,automatic=true)
    }
    fun syncNow(localShopId:String):CloudSyncStatus=syncNow(localShopId,false)
    private fun syncNow(localShopId:String,automatic:Boolean):CloudSyncStatus {
        ensureOpen()
        val lock=lockFor(localShopId)
        if(!lock.tryLock())return status(localShopId)
        val scope=generation.get()
        try {
            var state=states.load(localShopId) ?:return CloudSyncStatus().also {statuses[localShopId]=it}
            statuses[localShopId]=status(localShopId).copy(phase="syncing",message="Checking cloud records…",nextRetryAt=null)
            require(local.schema(localShopId).fingerprint==state.schema.fingerprint) {"This app's data version changed. Reconnect with a matching cloud server before syncing."}
            val token=credentials.read(state.credentialKey) ?:throw CloudHttpException(401,"device_not_authorized","Cloud access is unavailable. Sign in again; local records were kept.")
            if(state.pendingPull!=null) state=finishPull(state,clock().toString())
            var pending=state.pendingUpload
            val latest=current(state)
            if(pending!=null && pending.conflict.isNotEmpty()) {
                val changed=CloudMerge.diff(pending.sentSnapshot,latest,state.schema)
                if(changed.changes.isNotEmpty() || changed.beforeName!=changed.afterName) {
                    states.archiveConflict(localShopId,pending)
                    pending=null
                } else if(automatic) {
                    return CloudSyncStatus("conflict",pending.request.delta.changes.size,state.lastSyncedAt,status(localShopId).lastContactAt,message="Local changes need review before they can be synchronized.",conflicts=pending.conflict).also {statuses[localShopId]=it}
                }
            }
            if(pending==null) {
                val delta=CloudMerge.diff(state.baseline,latest,state.schema)
                if(delta.changes.isNotEmpty() || delta.beforeName!=delta.afterName) pending=CloudPendingUpload(CloudPushRequest(UUID.randomUUID().toString(),state.revision,state.schema.fingerprint,delta),latest)
                state=state.copy(pendingUpload=pending);states.save(state)
            }
            var localBase=state.baseline
            if(pending!=null) {
                // This exact payload and operationId have already been persisted; never regenerate a retry.
                val response=transport.push(state.endpoint,token,state.businessId,pending.request)
                ensureScope(scope,automatic,localShopId)
                require(response.operationId==pending.request.operationId && response.revision>=state.revision) {"Cloud acknowledgment did not match this upload."}
                if(response.status=="conflict") {
                    require(response.conflicts.isNotEmpty()) {"Cloud conflict details are missing."}
                    state=state.copy(pendingUpload=pending.copy(conflict=response.conflicts));states.save(state)
                    return CloudSyncStatus("conflict",pending.request.delta.changes.size,state.lastSyncedAt,clock().toString(),message="Local changes need review before they can be synchronized.",conflicts=response.conflicts).also {statuses[localShopId]=it}
                }
                require(response.status=="accepted") {"Cloud upload status is unsupported."}
                // The server contains our accepted changes. Merge only writes made AFTER this exact snapshot.
                localBase=pending.sentSnapshot
            }
            val downloaded=download(state,token)
            ensureScope(scope,automatic,localShopId)
            val receivedAt=clock().toString()
            if(pending!=null || downloaded.second!=state.revision) {
                state=state.copy(pendingPull=CloudPendingPull(UUID.randomUUID().toString(),localBase,downloaded.first,downloaded.second))
                states.save(state) // crash-safe before any local business mutation
                state=finishPull(state,receivedAt)
            }
            ensureScope(scope,automatic,localShopId)
            val remaining=CloudMerge.diff(state.baseline,current(state),state.schema)
            val count=remaining.changes.size
            val clean=count==0 && remaining.beforeName==remaining.afterName
            if(clean && state.lastSyncedAt!=receivedAt) {state=state.copy(lastSyncedAt=receivedAt);states.save(state)}
            failures.remove(localShopId);retryAt.remove(localShopId)
            return CloudSyncStatus(if(clean) "up_to_date" else "local_pending",count,state.lastSyncedAt,receivedAt,message=if(clean) "Cloud records updated and saved on this device." else "Newer local work is saved here and waiting for the next upload.").also {statuses[localShopId]=it}
        } catch(e:Exception) {
            val old=status(localShopId)
            if(closed || automatic && (generation.get()!=scope || activeShop!=localShopId)) return old.copy(phase="paused",message="Cloud sync paused; local work remains saved.").also {statuses[localShopId]=it}
            val auth=e is CloudHttpException && e.status in setOf(401,403)
            val count=(failures[localShopId] ?:0).plus(1).coerceAtMost(8);failures[localShopId]=count
            val delay=if(e is CloudHttpException && e.retryAfterSeconds!=null) e.retryAfterSeconds else (15L shl (count-1)).coerceAtMost(300)
            val next=if(auth) null else clock().plusSeconds(delay)
            if(next!=null)retryAt[localShopId]=next
            return old.copy(phase=if(auth) "auth_required" else "retry_wait",nextRetryAt=next?.toString(),message=safeMessage(e)).also {statuses[localShopId]=it}
        } finally {lock.unlock()}
    }

    private fun download(state:CloudLinkState,token:String):Pair<CloudSnapshot,Long> {
        var revision=state.revision;var remote=state.baseline;var target:Long?=null;var pages=0
        do {
            require(++pages<=10_000) {"Cloud download needs another pass. The existing cursor was kept."}
            val page=transport.pull(state.endpoint,token,state.businessId,revision,100)
            require(page.afterRevision==revision && page.headRevision>=page.nextRevision && page.nextRevision>=revision) {"Cloud download cursor is inconsistent."}
            if(target==null)target=page.headRevision
            var expected=revision
            for(change in page.changes) {
                require(change.revision==expected+1) {"Cloud change sequence is incomplete. The cursor was kept."}
                expected=change.revision
                if(change.revision<=requireNotNull(target)) {remote=CloudMerge.applyExact(remote,change.delta,state.schema);revision=change.revision}
            }
            require(page.nextRevision==expected && (page.changes.isNotEmpty() || page.headRevision==page.afterRevision)) {"Cloud download omitted changes. The cursor was kept."}
        } while(revision<requireNotNull(target))
        return remote to revision
    }
    private fun finishPull(state:CloudLinkState,receivedAt:String):CloudLinkState {
        val prepared=state.pendingPull ?:return state
        require(prepared.remote.tenantId==state.businessId && prepared.localBase.tenantId==state.businessId && prepared.revision>=state.revision)
        local.applyRemote(state.localShopId,prepared.checkpointId,prepared.localBase,prepared.remote,state.schema)
        return state.copy(revision=prepared.revision,baseline=prepared.remote,pendingUpload=null,pendingPull=null,lastSyncedAt=state.lastSyncedAt).also(states::save)
    }
    private fun current(state:CloudLinkState)=CloudMerge.normalize(local.snapshot(state.localShopId,state.businessId).copy(tenantName=state.baseline.tenantName),state.schema)
    private fun rebind(snapshot:CloudSnapshot,businessId:String,schema:CloudSchema)=CloudMerge.normalize(snapshot.copy(tenantId=businessId,tables=snapshot.tables.mapValues {(_,rows)->rows.map {row ->if("tenant_id" in row) row+("tenant_id" to businessId) else row}}),schema)
    private fun checkBootstrap(value:CloudBootstrapReply,businessId:String,schema:CloudSchema) {
        require(value.businessId==businessId && value.snapshot.tenantId==businessId && value.revision>=0 && value.schema.fingerprint==schema.fingerprint) {"Cloud shop or data version does not match this app. Existing records were kept."}
        CloudMerge.normalize(value.snapshot,schema)
    }
    private fun validDeviceName(value:String) {require(value==value.trim() && value.length in 1..100 && value.none {it.isISOControl()}) {"Enter a device name of 1 to 100 printable characters."}}
    private fun info(s:CloudLinkState)=CloudConnectionInfo(s.localShopId,s.endpoint,s.businessId,s.deviceId,s.deviceName,s.revision,s.lastSyncedAt)
    private fun lockFor(id:String)=locks.computeIfAbsent(id) {ReentrantLock()}
    private fun ensureScope(start:Long,automatic:Boolean,shop:String) {check(!closed && (!automatic || generation.get()==start && activeShop==shop)) {"Cloud sync was paused."}}
    private fun ensureOpen() {check(!closed) {"Cloud connection has closed."}}
    private fun safeMessage(e:Exception):String=when(e) {
        is CloudHttpException -> e.message ?: "Cloud access needs review. Local work is saved."
        is CloudConnectionException -> e.message ?: "Cloud connection unavailable. Local work is saved."
        else -> "Cloud data could not be safely synchronized. Local records and checkpoints were kept for review."
    }
    @Synchronized override fun close() {if(!closed) {lock();closed=true;scheduler.shutdownNow()}}
}
