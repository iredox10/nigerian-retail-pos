package pos.backup

import pos.domain.LocalBackupPolicy
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.UUID

internal data class BackupConfiguration(val policy:LocalBackupPolicy,val ownerId:String)
internal data class BackupVersion(val name:String,val createdAt:Instant,val sha256:String)

/** This index names only files written by this installation; arbitrary .rpos files are never adopted. */
internal class LocalBackupStore(private val dataDirectory:Path) {
    private fun key(tenantId:String)=sha256(tenantId.toByteArray())
    private fun configPath(tenantId:String)=dataDirectory.resolve("local-backup-policies").resolve(key(tenantId)+".properties")
    fun configuration(tenantId:String):BackupConfiguration? {
        val path=configPath(tenantId)
        if(!Files.exists(path,NOFOLLOW_LINKS)) return null
        val props=read(path)
        require(props.getProperty("format")=="1" && props.getProperty("tenant")==key(tenantId)) {"Automatic backup settings do not belong to this shop."}
        return BackupConfiguration(LocalBackupPolicy(props.getProperty("directory"),props.getProperty("minutes").toInt(),props.getProperty("versions").toInt(),props.getProperty("enabled").toBooleanStrict()),props.getProperty("owner").also {UUID.fromString(it)})
    }
    fun saveConfiguration(tenantId:String,policy:LocalBackupPolicy):BackupConfiguration {
        require(Path.of(policy.destinationDirectory).isAbsolute) {"Choose an absolute local folder path."}
        val value=BackupConfiguration(policy,configuration(tenantId)?.ownerId ?: UUID.randomUUID().toString())
        val props=Properties().apply {
            setProperty("format","1");setProperty("tenant",key(tenantId));setProperty("owner",value.ownerId)
            setProperty("directory",policy.destinationDirectory);setProperty("minutes",policy.intervalMinutes.toString())
            setProperty("versions",policy.retainedVersions.toString());setProperty("enabled",policy.enabled.toString())
        }
        privateDirectory(configPath(tenantId).parent)
        atomicProperties(configPath(tenantId),props)
        return value
    }
    fun directory(tenantId:String,configuration:BackupConfiguration):Path =
        Path.of(configuration.policy.destinationDirectory).resolve("RetailPOS-${key(tenantId).take(16)}-${configuration.ownerId}")
    fun ensureDirectory(tenantId:String,configuration:BackupConfiguration):Path = directory(tenantId,configuration).also(::privateDirectory)
    private fun indexPath(tenantId:String,configuration:BackupConfiguration)=directory(tenantId,configuration).resolve("versions.properties")
    fun versions(tenantId:String,configuration:BackupConfiguration):List<BackupVersion> {
        val path=indexPath(tenantId,configuration)
        if(!Files.exists(path,NOFOLLOW_LINKS)) return emptyList()
        val props=read(path)
        require(props.getProperty("format")=="1" && props.getProperty("owner")==configuration.ownerId && props.getProperty("tenant")==key(tenantId)) {"Automatic backup file index is not valid for this shop. Existing copies were kept."}
        val count=props.getProperty("count").toInt().also {require(it in 0..10000)}
        val versions=(0 until count).map {index ->
            val name=props.getProperty("$index.name")
            require(name.matches(Regex("backup-[0-9]+-[a-f0-9-]{36}\\.rpos"))) {"Automatic backup index contains an invalid filename."}
            val hash=props.getProperty("$index.sha256").also {require(it.matches(Regex("[a-f0-9]{64}")))}
            BackupVersion(name,Instant.parse(props.getProperty("$index.time")),hash)
        }
        require(versions.map {it.name}.distinct().size==versions.size) {"Automatic backup index contains repeated versions."}
        require(versions.zipWithNext().all {(newer,older) -> newer.createdAt>=older.createdAt}) {"Automatic backup versions are out of order."}
        return versions
    }
    fun saveVersions(tenantId:String,configuration:BackupConfiguration,versions:List<BackupVersion>) {
        val props=Properties().apply {
            setProperty("format","1");setProperty("tenant",key(tenantId));setProperty("owner",configuration.ownerId);setProperty("count",versions.size.toString())
            versions.forEachIndexed {index,version ->setProperty("$index.name",version.name);setProperty("$index.sha256",version.sha256);setProperty("$index.time",version.createdAt.toString())}
        }
        atomicProperties(indexPath(tenantId,configuration),props)
    }
    fun matches(path:Path,version:BackupVersion):Boolean =
        Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path)<=65L*1024*1024 && sha256(Files.readAllBytes(path))==version.sha256
    fun digest(path:Path)=sha256(Files.readAllBytes(path))
    private fun read(path:Path):Properties {
        require(Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path)<=1024*1024) {"Automatic backup settings or index are missing, unsafe or too large."}
        return Properties().apply {Files.newInputStream(path).use(::load)}
    }
    private fun privateDirectory(path:Path) {
        require(!Files.isSymbolicLink(path)) {"Choose a backup folder without a redirected managed directory."}
        val existed=Files.exists(path,NOFOLLOW_LINKS)
        Files.createDirectories(path)
        if(!existed) setPrivate(path,true)
    }
    private fun atomicProperties(path:Path,properties:Properties) {
        require(!Files.isSymbolicLink(path)) {"Automatic backup settings cannot replace a redirected file."}
        val temp=Files.createTempFile(path.parent,".retail-backup-index-",".tmp")
        try {
            setPrivate(temp,false)
            FileOutputStream(temp.toFile()).use {out ->properties.store(out,"Retail POS local backup preferences - no passwords or keys");out.fd.sync()}
            Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally {Files.deleteIfExists(temp)}
    }
    private fun setPrivate(path:Path,directory:Boolean) {
        if(Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(if(directory) "rwx------" else "rw-------"))
    }
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}
}
