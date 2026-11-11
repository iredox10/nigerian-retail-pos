package pos.data

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

data class SaleDraft(val cart: String, val operationId: String)

/** One durable sale draft per tenant; no shared global cart or cloud-save claim. */
class DraftStore(tenantId: String, directory: Path = Path.of(System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos"))) {
    private val file: Path
    init {
        require(tenantId.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(tenantId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        Files.createDirectories(directory.resolve("drafts"))
        file = directory.resolve("drafts").resolve("$key.properties")
    }

    fun load(): SaleDraft? {
        if (!Files.exists(file)) return null
        val properties = Properties().apply { Files.newInputStream(file).use { load(it) } }
        val id = requireNotNull(properties.getProperty("operationId")) { "Saved draft has no operation reference." }
        val cart = requireNotNull(properties.getProperty("cart")) { "Saved draft is incomplete." }
        require(id.isNotBlank()) { "Saved draft has an invalid operation reference." }
        if(cart.isNotEmpty()) cart.split(';').forEach {
            val index = it.lastIndexOf(':')
            require(index > 0 && (it.substring(index + 1).toIntOrNull() ?: 0) > 0) { "Saved draft contains an invalid quantity." }
        }
        return SaleDraft(cart, id)
    }

    fun save(draft: SaleDraft) {
        require(draft.operationId.isNotBlank())
        val temporary = Files.createTempFile(file.parent, ".draft-", ".tmp")
        try {
            val properties = Properties().apply { setProperty("operationId", draft.operationId); setProperty("cart", draft.cart) }
            java.io.FileOutputStream(temporary.toFile()).use { stream -> properties.store(stream, "Local sale draft"); stream.fd.sync() }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    fun clear() { Files.deleteIfExists(file) }
}
