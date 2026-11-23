package pos.security

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Owner credentials and explicitly enrolled recovery proof are scoped to this computer and shop. */
class WorkspaceAccess(
    directory: Path = Paths.get(
        System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos")
    )
) {
    private val credentials = directory.resolve("credentials")
    private val random = SecureRandom()
    internal data class CredentialEvent(val id: String, val kind: String, val atMillis: Long)
    private data class Record(val salt: ByteArray, val hash: ByteArray, val recovery: ByteArray?, val event: CredentialEvent?)

    init {
        Files.createDirectories(credentials)
        restrictPermissions(credentials, "rwx------")
    }

    /** Creates a credential once. Re-registration fails before changing the original. */
    fun register(tenantId: String, password: String) = withCredentialLock {
        validateTenant(tenantId)
        validatePassword(password)
        val target = credentialFile(tenantId)
        check(!Files.exists(target)) { "This shop already has an owner password" }
        val salt = ByteArray(24).also(random::nextBytes)
        val hash = derive(tenantId, password, salt)
        try { writeRecord(target, Record(salt, hash, null, null), replace = false) }
        finally { salt.fill(0); hash.fill(0) }
    }

    fun unlock(tenantId: String, password: String): Boolean = authenticateOwner(tenantId, password) != null

    /** Authentication returns the exact verified password generation for subsequent session checks. */
    internal fun authenticateOwner(tenantId: String, password: String): String? {
        if (!validTenant(tenantId) || password.length !in 8..1024) return null
        val record = readRecord(tenantId) ?: return null
        return try {
            val actual = derive(tenantId, password, record.salt)
            try { if (MessageDigest.isEqual(record.hash, actual)) fingerprint(record) else null }
            finally { actual.fill(0) }
        } finally { clear(record) }
    }

    internal fun credentialFingerprint(tenantId: String): String? {
        val record = readRecord(tenantId) ?: return null
        return try { fingerprint(record) } finally { clear(record) }
    }

    internal fun recoveryEnabled(tenantId: String): Boolean {
        val record = readRecord(tenantId) ?: return false
        return try { record.recovery != null } finally { clear(record) }
    }

    internal fun credentialEvent(tenantId: String): CredentialEvent? {
        val record = readRecord(tenantId) ?: return null
        return try { record.event } finally { clear(record) }
    }

    /** Full password proof is checked again under the same lock as the atomic replacement. */
    internal fun configureRecovery(tenantId: String, password: String, atMillis: Long): String? = withCredentialLock {
        if (!validTenant(tenantId) || password.length !in 8..1024 || atMillis < 0) return@withCredentialLock null
        val record = readRecord(tenantId) ?: return@withCredentialLock null
        try {
            if (record.event?.atMillis?.let { atMillis < it } == true) return@withCredentialLock null
            val actual = derive(tenantId, password, record.salt)
            val verified = try { MessageDigest.isEqual(record.hash, actual) } finally { actual.fill(0) }
            if (!verified) return@withCredentialLock null
            val proof = ByteArray(32).also(random::nextBytes)
            val digest = recoveryDigest(tenantId, proof)
            try {
                val event = CredentialEvent(UUID.randomUUID().toString(), "owner.recovery_enrolled", atMillis)
                writeRecord(credentialFile(tenantId), record.copy(recovery = digest, event = event), replace = true)
                proof.joinToString("") { "%02X".format(it.toInt() and 255) }.chunked(8).joinToString("-")
            } finally { proof.fill(0); digest.fill(0) }
        } finally { clear(record) }
    }

    /** Consuming the recovery proof and changing the password share one atomic file replacement. */
    internal fun recoverOwner(tenantId: String, code: String, newPassword: String, atMillis: Long): Boolean = withCredentialLock {
        if (!validTenant(tenantId) || newPassword.length !in 8..1024 || atMillis < 0) return@withCredentialLock false
        val proof = decodeRecovery(code) ?: return@withCredentialLock false
        try {
            val record = readRecord(tenantId) ?: return@withCredentialLock false
            try {
                val expected = record.recovery ?: return@withCredentialLock false
                if (record.event?.atMillis?.let { atMillis < it } == true) return@withCredentialLock false
                val actual = recoveryDigest(tenantId, proof)
                val verified = try { MessageDigest.isEqual(expected, actual) } finally { actual.fill(0) }
                if (!verified) return@withCredentialLock false
                val salt = ByteArray(24).also(random::nextBytes)
                val hash = derive(tenantId, newPassword, salt)
                try {
                    writeRecord(credentialFile(tenantId), Record(salt, hash, null,
                        CredentialEvent(UUID.randomUUID().toString(), "owner.password_recovered", atMillis)), replace = true)
                    true
                } finally { salt.fill(0); hash.fill(0) }
            } finally { clear(record) }
        } finally { proof.fill(0) }
    }

    private fun readRecord(tenantId: String): Record? {
        if (!validTenant(tenantId)) return null
        return try {
            val file = credentialFile(tenantId)
            if (!Files.isRegularFile(file) || Files.size(file) > 2048) return null
            val fields = Files.readString(file, Charsets.UTF_8).split(':')
            if (fields.size !in setOf(4, 8) || fields[1] != ITERATIONS.toString() ||
                (fields[0] == "1" && fields.size != 4) || (fields[0] == "2" && fields.size != 8) || fields[0] !in setOf("1", "2")) return null
            val salt = Base64.getDecoder().decode(fields[2])
            val hash = Base64.getDecoder().decode(fields[3])
            if (salt.size != 24 || hash.size != 32) return null
            val recovery = if (fields.size == 8 && fields[4] != "-") Base64.getDecoder().decode(fields[4]) else null
            if (recovery != null && recovery.size != 32) return null
            val event = if (fields.size == 8) {
                require(fields[5] in setOf("owner.recovery_enrolled", "owner.password_recovered"))
                require(UUID.fromString(fields[6]).toString() == fields[6])
                val time = fields[7].toLong()
                require(time >= 0)
                CredentialEvent(fields[6], fields[5], time)
            } else null
            Record(salt, hash, recovery, event)
        } catch (_: Exception) { null }
    }

    private fun writeRecord(target: Path, record: Record, replace: Boolean) {
        val encoder = Base64.getEncoder()
        val passwordFields = "$ITERATIONS:${encoder.encodeToString(record.salt)}:${encoder.encodeToString(record.hash)}"
        val content = record.event?.let { event ->
            "2:$passwordFields:${record.recovery?.let(encoder::encodeToString) ?: "-"}:${event.kind}:${event.id}:${event.atMillis}"
        } ?: "1:$passwordFields"
        val temporary = Files.createTempFile(credentials, ".credential-", ".tmp")
        try {
            restrictPermissions(temporary, "rw-------")
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { output ->
                val buffer = java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            if (replace) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun <T> withCredentialLock(block: () -> T): T {
        val lockPath = credentials.resolve("registration.lock").toAbsolutePath().normalize()
        return synchronized(credentialLocks.computeIfAbsent(lockPath.toString()) { Any() }) {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                restrictPermissions(lockPath, "rw-------")
                channel.lock().use { block() }
            }
        }
    }

    private fun fingerprint(record: Record): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(record.salt + record.hash))
    private fun recoveryDigest(tenantId: String, proof: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("pos-owner-recovery-v1:${tenantId.length}:$tenantId:".toByteArray(Charsets.UTF_8) + proof)
    private fun decodeRecovery(code: String): ByteArray? {
        if (code.length > 128) return null
        val normalized = code.filterNot { it == '-' || it == ' ' || it == '\r' || it == '\n' || it == '\t' }
        if (!normalized.matches(Regex("[0-9A-Fa-f]{64}"))) return null
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun clear(record: Record) { record.salt.fill(0); record.hash.fill(0); record.recovery?.fill(0) }
    private fun credentialFile(tenantId: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(tenantId.toByteArray(Charsets.UTF_8))
        return credentials.resolve(digest.joinToString("") { "%02x".format(it.toInt() and 255) } + ".credential")
    }
    private fun derive(tenantId: String, password: String, salt: ByteArray): ByteArray {
        val boundSalt = salt + tenantId.toByteArray(Charsets.UTF_8)
        val characters = password.toCharArray()
        val spec = PBEKeySpec(characters, boundSalt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); characters.fill('\u0000'); boundSalt.fill(0) }
    }
    private fun validTenant(tenantId: String) = tenantId.isNotBlank() && tenantId.length <= 256
    private fun validateTenant(tenantId: String) { require(validTenant(tenantId)) { "Invalid shop identifier" } }
    private fun validatePassword(password: String) { require(password.length in 8..1024) { "Use a password between 8 and 1024 characters" } }
    private fun restrictPermissions(path: Path, mode: String) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }
    private companion object {
        const val ITERATIONS = 210_000
        val credentialLocks = ConcurrentHashMap<String, Any>()
    }
}
