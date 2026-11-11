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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local owner unlock gate, not a server authorization boundary or database encryption.
 * A person with filesystem access can read the unencrypted business database or replace
 * credentials. Production deployments need device protection and server-side membership
 * checks in addition to this convenience gate. Password recovery is deliberately absent:
 * this class must never turn an unknown tenant into an unlocked workspace.
 */
class WorkspaceAccess(
    directory: Path = Paths.get(
        System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos")
    )
) {
    private val credentials = directory.resolve("credentials")
    private val random = SecureRandom()

    init {
        Files.createDirectories(credentials)
        restrictPermissions(credentials, "rwx------")
    }

    /** Creates a credential once. Re-registration, including with the same password, fails. */
    @Synchronized
    fun register(tenantId: String, password: String) {
        validateTenant(tenantId)
        require(password.length in 8..1024) { "Use a password between 8 and 1024 characters" }
        val target = credentialFile(tenantId)
        val lockPath = credentials.resolve("registration.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            restrictPermissions(lockPath, "rw-------")
            channel.lock().use {
                check(!Files.exists(target)) { "This shop already has an owner password" }
                val salt = ByteArray(24).also(random::nextBytes)
                val hash = derive(tenantId, password, salt)
                val encoder = Base64.getEncoder()
                val record = "1:$ITERATIONS:${encoder.encodeToString(salt)}:${encoder.encodeToString(hash)}"
                val temporary = Files.createTempFile(credentials, ".credential-", ".tmp")
                try {
                    restrictPermissions(temporary, "rw-------")
                    FileChannel.open(temporary, StandardOpenOption.WRITE).use { output ->
                        val bytes = java.nio.ByteBuffer.wrap(record.toByteArray(Charsets.UTF_8))
                        while (bytes.hasRemaining()) output.write(bytes)
                        output.force(true)
                    }
                    // Fail if the platform cannot provide an atomic move. Never expose a
                    // partly written credential or silently fall back to a non-atomic copy.
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(temporary)
                    hash.fill(0)
                }
            }
        }
    }

    /** Missing, corrupt, or unsupported records fail closed. No credential is created here. */
    fun unlock(tenantId: String, password: String): Boolean {
        if (tenantId.isBlank() || tenantId.length > 256 || password.length !in 8..1024) return false
        val target = credentialFile(tenantId)
        return try {
            if (!Files.isRegularFile(target) || Files.size(target) > 1024) return false
            val parts = Files.readString(target, Charsets.UTF_8).split(':')
            if (parts.size != 4 || parts[0] != "1" || parts[1] != ITERATIONS.toString()) return false
            val salt = Base64.getDecoder().decode(parts[2])
            val expected = Base64.getDecoder().decode(parts[3])
            if (salt.size != 24 || expected.size != 32) return false
            val actual = derive(tenantId, password, salt)
            try { MessageDigest.isEqual(expected, actual) } finally { actual.fill(0); expected.fill(0) }
        } catch (_: Exception) {
            false
        }
    }

    private fun credentialFile(tenantId: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(tenantId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return credentials.resolve("$name.credential")
    }

    private fun derive(tenantId: String, password: String, salt: ByteArray): ByteArray {
        // Bind credentials to the tenant: copying one credential file cannot unlock another.
        val tenantSalt = salt + tenantId.toByteArray(Charsets.UTF_8)
        val characters = password.toCharArray()
        val spec = PBEKeySpec(characters, tenantSalt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); characters.fill('\u0000'); tenantSalt.fill(0) }
    }

    private fun validateTenant(tenantId: String) {
        require(tenantId.isNotBlank() && tenantId.length <= 256) { "Invalid shop identifier" }
    }

    private fun restrictPermissions(path: Path, mode: String) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        }
    }

    private companion object { const val ITERATIONS = 210_000 }
}
