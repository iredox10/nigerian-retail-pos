package pos.output

import pos.data.PosRepository
import pos.data.TenantSnapshot
import pos.domain.Tenant
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Tenant-only encrypted recovery files; never includes other shops or password credentials. */
object BackupService {
    private const val LIMIT = 64 * 1024 * 1024
    private const val ITERATIONS = 210_000
    private val MAGIC = "RETAILPOS-BACKUP-1".toByteArray(Charsets.US_ASCII)

    fun create(repo: PosRepository, tenantId: String, password: String, destination: Path) {
        require(password.length in 8..1024) { "Use a backup password of 8 to 1024 characters." }
        require(!Files.exists(destination)) { "Choose a new filename; existing files are never overwritten." }
        val plaintext = encode(repo.exportTenantSnapshot(tenantId))
        val random = SecureRandom()
        val salt = ByteArray(24).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = try { crypt(Cipher.ENCRYPT_MODE, plaintext, password, salt, nonce) } finally { plaintext.fill(0) }
        val parent = destination.toAbsolutePath().parent
        val temporary = Files.createTempFile(parent, ".retail-backup-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                DataOutputStream(stream).apply { write(MAGIC); write(salt); write(nonce); writeInt(encrypted.size); write(encrypted); flush() }
                stream.fd.sync()
            }
            Files.move(temporary, destination.toAbsolutePath())
        } finally { encrypted.fill(0); Files.deleteIfExists(temporary) }
    }

    fun read(source: Path, password: String): TenantSnapshot {
        require(password.length in 8..1024) { "Enter the backup password." }
        require(Files.size(source) in (MAGIC.size + 24 + 12 + 4 + 16).toLong()..(LIMIT + 1024).toLong()) { "Backup is incomplete or exceeds the supported 64 MB size." }
        val encrypted: ByteArray
        val salt = ByteArray(24)
        val nonce = ByteArray(12)
        DataInputStream(Files.newInputStream(source)).use { input ->
            val header = ByteArray(MAGIC.size).also(input::readFully)
            require(header.contentEquals(MAGIC)) { "This is not a supported Retail POS backup." }
            input.readFully(salt); input.readFully(nonce)
            val size = input.readInt()
            require(size in 16..LIMIT + 16) { "Invalid backup size." }
            encrypted = ByteArray(size).also(input::readFully)
            require(input.read() == -1) { "Unexpected data after the backup." }
        }
        val plaintext = try { crypt(Cipher.DECRYPT_MODE, encrypted, password, salt, nonce) }
        catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Wrong backup password or damaged backup. No records were restored.") }
        finally { encrypted.fill(0) }
        return try { decode(plaintext) } finally { plaintext.fill(0) }
    }

    private fun crypt(mode: Int, bytes: ByteArray, password: String, salt: ByteArray, nonce: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword(); chars.fill('\u0000') }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
            }.doFinal(bytes)
        } finally { key.fill(0) }
    }

    private fun encode(snapshot: TenantSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(b: Int) { require(bytes.size() < LIMIT) { "Backup exceeds 64 MB." }; bytes.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { require(bytes.size().toLong() + len <= LIMIT) { "Backup exceeds 64 MB." }; bytes.write(b, off, len) }
        }
        DataOutputStream(bounded).use { out ->
            out.writeInt(1); out.string(snapshot.tenant.id); out.string(snapshot.tenant.name)
            require(snapshot.tables.size <= 64)
            out.writeInt(snapshot.tables.size)
            var rows = 0
            snapshot.tables.forEach { (name, records) ->
                rows = Math.addExact(rows, records.size); require(rows <= 200_000) { "Backup exceeds 200,000 records." }
                out.string(name); out.writeInt(records.size)
                records.forEach { record ->
                    require(record.size <= 64); out.writeInt(record.size)
                    record.forEach { (column, value) -> out.string(column); out.writeBoolean(value != null); if(value != null) out.string(value) }
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): TenantSnapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 1) { "Unsupported backup format." }
        val tenant = Tenant(input.string(), input.string())
        val tables = linkedMapOf<String, List<Map<String,String?>>>()
        val tableCount = input.readInt().also { require(it in 0..64) }
        var totalRows = 0
        repeat(tableCount) {
            val name = input.string(); require(!tables.containsKey(name)) { "Duplicate backup table." }
            val count = input.readInt().also { require(it in 0..200_000) }
            totalRows = Math.addExact(totalRows,count); require(totalRows <= 200_000)
            tables[name] = List(count) {
                val record = linkedMapOf<String,String?>()
                repeat(input.readInt().also { require(it in 0..64) }) {
                    val column = input.string(); require(!record.containsKey(column)) { "Duplicate backup column." }
                    record[column] = if(input.readBoolean()) input.string() else null
                }; record
            }
        }
        require(input.read() == -1) { "Unexpected backup records." }
        TenantSnapshot(tenant,tables)
    }

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= 1024 * 1024) { "A backup field is too large." }; writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.string(): String {
        val size = readInt().also { require(it in 0..1024 * 1024) { "Invalid backup field size." } }
        require(size <= available()) { "Truncated backup field." }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
}
