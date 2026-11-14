package pos.backup

import pos.data.PosRepository
import pos.domain.LocalBackupPolicy
import pos.domain.StaffPermission
import pos.output.BackupService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class AutoBackupStatus(
    val policy:LocalBackupPolicy?,val runningForSession:Boolean,val sessionExpiresAt:Instant?,
    val nextDueAt:Instant?,val latestFile:Path?,val latestCreatedAt:Instant?,val recordedVersions:Int,
    val checkedFile:Path?,val checkedAt:Instant?,val error:String?,val retentionWarning:String?,
)

/** Local-only, opt-in scheduler. The app must call lock() on lock, sign-out, tenant change and close.
 * The retained passphrase is a wiped char array; legacy encryption calls use temporary JVM strings.
 * A file encryption/password check is deliberately not described as a successful restore drill.
 */
class AutoBackupCoordinator(
    private val repo:PosRepository,
    dataDirectory:Path,
    private val clock:()->Instant={Instant.now()},
) : AutoCloseable {
    private val store=LocalBackupStore(dataDirectory)
    private var activeTenant:String?=null
    private var secret:CharArray?=null
    private var openedAt:Instant?=null
    private var expiresAt:Instant?=null
    private var nextDue:Instant?=null
    private var scheduler:ScheduledExecutorService?=null
    private var closed=false
    private val errors=mutableMapOf<String,String>()
    private val warnings=mutableMapOf<String,String>()
    private val checked=mutableMapOf<String,Pair<Path,Instant>>()

    private fun authorize(tenantId:String) = repo.featureRead(tenantId,StaffPermission.BACKUP) {Unit}
    @Synchronized fun policy(tenantId:String):LocalBackupPolicy? {authorize(tenantId);return store.configuration(tenantId)?.policy}

    /** Consumes and clears the supplied char array, including on failure. Starts with a verified new copy. */
    @Synchronized fun enable(tenantId:String,policy:LocalBackupPolicy,password:CharArray):Path {
        try {
            check(!closed) {"The backup session has closed."};authorize(tenantId)
            require(policy.enabled) {"Enable the schedule before starting this session."}
            require(password.size in 8..1024) {"Use a backup password of 8 to 1024 characters."}
            require(Path.of(policy.destinationDirectory).isAbsolute && Files.isDirectory(Path.of(policy.destinationDirectory))) {"Choose an existing local backup folder."}
            val actualPolicy=policy.copy(destinationDirectory=Path.of(policy.destinationDirectory).toRealPath().toString())
            val old=store.configuration(tenantId)
            // Existing versions in this location cannot silently switch to a mistyped password.
            if(old!=null && old.policy.destinationDirectory==actualPolicy.destinationDirectory) {
                store.versions(tenantId,old).firstOrNull()?.let {verify(tenantId,old,it,password)}
            }
            val configuration=store.saveConfiguration(tenantId,actualPolicy)
            lock()
            activeTenant=tenantId;secret=password.copyOf();openedAt=clock();expiresAt=openedAt!!.plus(Duration.ofHours(4));nextDue=openedAt
            errors.remove(tenantId);warnings.remove(tenantId)
            return saveVersion(tenantId,configuration,password)
        } catch(e:Exception) {errors[tenantId]=e.message ?: "Automatic backup could not start.";throw e}
        finally {password.fill('\u0000')}
    }

    @Synchronized fun pause(tenantId:String) {
        authorize(tenantId)
        val configuration=store.configuration(tenantId)
        if(configuration!=null) store.saveConfiguration(tenantId,configuration.policy.copy(enabled=false))
        if(activeTenant==tenantId) lock()
    }
    @Synchronized fun runNow(tenantId:String):Path {
        authorize(tenantId);expire()
        require(activeTenant==tenantId && secret!=null) {"Enter the backup password to start automatic backups for this session."}
        val configuration=requireNotNull(store.configuration(tenantId)) {"Choose automatic backup settings first."}
        require(configuration.policy.enabled) {"Automatic backups are paused."}
        return try {saveVersion(tenantId,configuration,secret!!)} catch(e:Exception) {
            errors[tenantId]=e.message ?: "Automatic backup failed. Earlier copies were kept."
            nextDue=clock().plusSeconds(60);throw e
        }
    }
    /** No records are imported. This verifies the latest indexed file's password, encryption and shop ID. */
    @Synchronized fun checkLatest(tenantId:String,password:CharArray):Path {
        try {
            authorize(tenantId)
            val configuration=requireNotNull(store.configuration(tenantId)) {"No automatic backup settings have been saved."}
            val version=store.versions(tenantId,configuration).firstOrNull() ?: error("No automatic backup has been saved.")
            val path=verify(tenantId,configuration,version,password)
            checked[tenantId]=path to clock();errors.remove(tenantId);return path
        } catch(e:Exception) {checked.remove(tenantId);errors[tenantId]=e.message ?: "The encrypted file could not be checked.";throw e}
        finally {password.fill('\u0000')}
    }
    @Synchronized fun status(tenantId:String):AutoBackupStatus {
        authorize(tenantId);expire()
        val configuration=store.configuration(tenantId)
        val versions=configuration?.let {store.versions(tenantId,it)}.orEmpty()
        val latest=versions.firstOrNull()
        return AutoBackupStatus(configuration?.policy,activeTenant==tenantId && secret!=null,if(activeTenant==tenantId) expiresAt else null,
            if(activeTenant==tenantId) nextDue else null,latest?.let {store.directory(tenantId,configuration!!).resolve(it.name)},latest?.createdAt,versions.size,
            checked[tenantId]?.first,checked[tenantId]?.second,errors[tenantId],warnings[tenantId])
    }
    /** Idempotent; one daemon thread per coordinator. No cloud/network work is performed. */
    @Synchronized fun startScheduler() {
        check(!closed)
        if(scheduler!=null) return
        scheduler=Executors.newSingleThreadScheduledExecutor {r ->Thread(r,"retail-local-backups").apply {isDaemon=true}}.also {
            it.scheduleWithFixedDelay({tick()},30,30,TimeUnit.SECONDS)
        }
    }
    @Synchronized fun tick() {
        if(closed) return
        expire()
        val tenantId=activeTenant ?: return
        if(nextDue?.let {clock().isBefore(it)}==true) return
        try {runNow(tenantId)} catch(e:Exception) {
            errors[tenantId]=e.message ?: "Automatic backup failed. Earlier copies were kept."
            // A revoked/locked session must not retain a key or keep retrying privileged operations.
            try {authorize(tenantId)} catch(_:Exception) {lock()}
        }
    }
    @Synchronized fun lock() {
        secret?.fill('\u0000');secret=null;activeTenant=null;openedAt=null;expiresAt=null;nextDue=null
        checked.clear()
    }
    @Synchronized override fun close() {lock();closed=true;scheduler?.shutdownNow();scheduler=null}
    private fun expire() {
        val now=clock()
        if(openedAt?.let {now.isBefore(it)}==true || expiresAt?.let {!now.isBefore(it)}==true) lock()
    }
    private fun verify(tenantId:String,configuration:BackupConfiguration,version:BackupVersion,password:CharArray):Path {
        val path=store.directory(tenantId,configuration).resolve(version.name)
        require(store.matches(path,version)) {"The saved backup is missing or has changed. Existing copies were kept; choose a known good file for recovery."}
        val snapshot=BackupService.read(path,String(password))
        require(snapshot.tenant.id==tenantId) {"The backup file belongs to another shop."}
        return path
    }
    private fun saveVersion(tenantId:String,configuration:BackupConfiguration,password:CharArray):Path {
        authorize(tenantId)
        val now=clock()
        val previous=store.versions(tenantId,configuration)
        require(previous.none {it.createdAt>now}) {"The computer clock moved backwards. Correct it before creating another automatic backup."}
        require(Files.isDirectory(Path.of(configuration.policy.destinationDirectory))) {"The backup folder is unavailable. Reconnect the drive; earlier copies were kept."}
        val directory=store.ensureDirectory(tenantId,configuration)
        val destination=directory.resolve("backup-${now.epochSecond}-${UUID.randomUUID()}.rpos")
        BackupService.create(repo,tenantId,String(password),destination)
        val version=BackupVersion(destination.fileName.toString(),now,store.digest(destination))
        // Never prune until the new encrypted file has opened and its index has been durably saved.
        verify(tenantId,configuration,version,password)
        val all=listOf(version)+previous
        store.saveVersions(tenantId,configuration,all)
        checked[tenantId]=destination to now
        errors.remove(tenantId);warnings.remove(tenantId)
        val valid=all.filter {candidate ->
            try {verify(tenantId,configuration,candidate,password);true} catch(_:Exception) {false}
        }
        // Damaged copies never consume the quota for recoverable versions.
        val prune=valid.drop(configuration.policy.retainedVersions)
        val invalid=all.size-valid.size
        if(prune.isNotEmpty()) {
            // Remove ownership from the index before deleting; failed deletion preserves an orphan copy.
            store.saveVersions(tenantId,configuration,all.filterNot {it in prune})
            var failed=0
            prune.forEach {candidate ->try {
                // Recheck after index replacement as another process may have changed the file.
                verify(tenantId,configuration,candidate,password)
                Files.delete(directory.resolve(candidate.name))
            } catch(_:Exception) {failed++}}
            if(failed>0) warnings[tenantId]="$failed older copies could not be removed. The new backup was saved."
        }
        if(invalid>0) warnings[tenantId]="$invalid older missing or changed copies were kept in the index; they were not deleted."
        nextDue=now.plusSeconds(configuration.policy.intervalMinutes*60L)
        return destination
    }
}
