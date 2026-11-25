package pos.cloud.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import pos.cloud.protocol.*
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class CloudPendingUpload(val request:CloudPushRequest,val sentSnapshot:CloudSnapshot,val conflict:List<CloudConflict> = emptyList())
@Serializable
data class CloudPendingPull(val checkpointId:String,val localBase:CloudSnapshot,val remote:CloudSnapshot,val revision:Long)
@Serializable
data class CloudLinkState(
    val localShopId:String,val endpoint:CloudEndpoint,val businessId:String,val deviceId:String,
    val deviceName:String,val installationId:String,val revision:Long,val schema:CloudSchema,
    val baseline:CloudSnapshot,val pendingUpload:CloudPendingUpload?=null,val pendingPull:CloudPendingPull?=null,
    val lastSyncedAt:String?=null,
) {
    init {require(localShopId.isNotBlank() && businessId.isNotBlank() && deviceId.isNotBlank() && revision>=0 && baseline.tenantId==businessId)}
    val credentialKey:String get()="device:"+endpoint.url+":"+businessId+":"+installationId
}
@Serializable
data class CloudPairingAttempt(val authorizingShop:String,val endpoint:CloudEndpoint,val ticket:CloudPairingTicket,val deviceName:String="Shop device")
@Serializable
data class CloudPublishAttempt(val localShopId:String,val endpoint:CloudEndpoint,val deviceName:String,val request:CloudCreateBusinessRequest)
@Serializable
data class CloudJoinAttempt(val authorizingLocalShopId:String,val endpoint:CloudEndpoint,val businessId:String,val deviceName:String,val importId:String)

/** Inject Android's verified Os.chmod implementation; desktop defaults to owner-only POSIX modes. */
fun interface CloudFileProtection { fun protect(path:Path,directory:Boolean) }
class PrivateCloudFiles(val directory:Path,private val protection:CloudFileProtection=CloudFileProtection {path,isDirectory ->
    Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(if(isDirectory) "rwx------" else "rw-------"))
}) {
    init {Files.createDirectories(directory);require(!Files.isSymbolicLink(directory));protection.protect(directory,true)}
    @Synchronized fun read(name:String,maximumBytes:Long=160L*1024*1024):String? {
        val path=path(name)
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path)<=maximumBytes) {"Cloud checkpoint is unreadable or exceeds its supported size. It has not been changed."}
        protection.protect(path,false)
        return Files.newBufferedReader(path,Charsets.UTF_8).use {it.readText()}
    }
    @Synchronized fun write(name:String,text:String) {
        val target=path(name);require(!Files.isSymbolicLink(target)) {"Cloud checkpoint cannot be a symbolic link."}
        val bytes=text.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong()<=160L*1024*1024) {"Cloud checkpoint exceeds its supported size."}
        val temp=Files.createTempFile(directory,".cloud-",".tmp")
        try {
            protection.protect(temp,false)
            FileOutputStream(temp.toFile()).use {it.write(bytes);it.fd.sync()}
            try {Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
            catch(_:AtomicMoveNotSupportedException) {throw IllegalStateException("This storage cannot atomically save cloud checkpoints. Existing records were kept.")}
        } finally {Files.deleteIfExists(temp)}
    }
    @Synchronized fun remove(name:String) {Files.deleteIfExists(path(name))}
    fun names():List<String> = Files.list(directory).use {stream ->stream.iterator().asSequence().filter {Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS)}.map {it.fileName.toString()}.toList()}
    private fun path(name:String):Path {require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}")));return directory.resolve(name)}
}

class CloudStateStore(private val files:PrivateCloudFiles) {
    val installationId:String by lazy {
        synchronized(this) {
            val existing=files.read("installation-id",128)?.trim()
            if(existing!=null) {require(runCatching {UUID.fromString(existing)}.isSuccess) {"Cloud installation identity is damaged. Its checkpoint has not been reset."};existing}
            else UUID.randomUUID().toString().also {files.write("installation-id",it)}
        }
    }
    fun pairing(shop:String):CloudPairingAttempt?=files.read(key(shop)+".pairing.json")?.let {CloudCodec.json.decodeFromString<CloudPairingAttempt>(it)}
    fun savePairing(value:CloudPairingAttempt) {require(value.ticket.secret.isEmpty());files.write(key(value.authorizingShop)+".pairing.json",CloudCodec.encode(value))}
    fun finishPairing(shop:String)=files.remove(key(shop)+".pairing.json")
    fun load(localShopId:String):CloudLinkState? = files.read(key(localShopId)+".link.json")?.let {
        CloudCodec.json.decodeFromString<CloudLinkState>(it).also {state ->require(state.localShopId==localShopId && state.installationId==installationId) {"Cloud checkpoint belongs to a different shop or installation."}}
    }
    fun save(state:CloudLinkState) {require(state.installationId==installationId);files.write(key(state.localShopId)+".link.json",CloudCodec.json.encodeToString(state))}
    fun links():List<CloudLinkState> = files.names().filter {it.endsWith(".link.json")}.map {name ->
        CloudCodec.json.decodeFromString<CloudLinkState>(requireNotNull(files.read(name))).also {require(name==key(it.localShopId)+".link.json" && it.installationId==installationId)}
    }
    fun publishAttempt(localShopId:String):CloudPublishAttempt? =files.read(key(localShopId)+".publish.json")?.let {CloudCodec.json.decodeFromString<CloudPublishAttempt>(it).also {v ->require(v.localShopId==localShopId)}}
    fun savePublish(attempt:CloudPublishAttempt)=files.write(key(attempt.localShopId)+".publish.json",CloudCodec.json.encodeToString(attempt))
    fun finishPublish(localShopId:String)=files.remove(key(localShopId)+".publish.json")
    fun joinAttempt(authorizingLocalShopId:String,businessId:String):CloudJoinAttempt?=files.read(key(authorizingLocalShopId+":"+businessId)+".join.json")?.let {CloudCodec.json.decodeFromString<CloudJoinAttempt>(it).also {a ->require(a.authorizingLocalShopId==authorizingLocalShopId && a.businessId==businessId)}}
    fun saveJoin(attempt:CloudJoinAttempt)=files.write(key(attempt.authorizingLocalShopId+":"+attempt.businessId)+".join.json",CloudCodec.json.encodeToString(attempt))
    fun finishJoin(attempt:CloudJoinAttempt)=files.remove(key(attempt.authorizingLocalShopId+":"+attempt.businessId)+".join.json")
    fun archiveConflict(localShopId:String,pending:CloudPendingUpload)=files.write(key(localShopId)+"-"+key(pending.request.operationId)+".conflict.json",CloudCodec.json.encodeToString(pending))
    private fun key(value:String):String=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}

/** Desktop fallback: private token files. Android should inject a Keystore-backed implementation. */
class FileCloudCredentialStore(private val files:PrivateCloudFiles):CloudCredentialStore {
    private fun name(key:String)="token-"+MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    override fun read(key:String):String?=files.read(name(key),8192)
    override fun write(key:String,token:String) {require(token.length in 16..4096 && token.none {it.isWhitespace() || it.isISOControl()});files.write(name(key),token)}
    override fun remove(key:String)=files.remove(name(key))
}
