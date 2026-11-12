package pos.data

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SaleDraft(val cart: String, val operationId: String, val cashText: String = "", val customerId: String? = null, val method: String = "cash")
data class HeldSale(val id: String, val name: String, val savedAt: String, val draft: SaleDraft)

/** Active and held baskets share one atomic, tenant-specific file; no stock is reserved. */
class DraftStore(tenantId: String, directory: Path = Path.of(System.getProperty("pos.dataDir", System.getProperty("user.home") + "/.nigerian-retail-pos"))) {
    private val file: Path
    private val lock: ReentrantLock
    private data class State(val active: SaleDraft?, val held: List<HeldSale>)
    init {
        require(tenantId.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(tenantId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        Files.createDirectories(directory.resolve("drafts"))
        file = directory.resolve("drafts").resolve("$key.properties").toAbsolutePath().normalize()
        lock = locks.computeIfAbsent(file) { ReentrantLock() }
    }
    private fun <T> locked(block: () -> T): T = lock.withLock {
        java.nio.channels.FileChannel.open(file.resolveSibling(file.fileName.toString()+".lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE).use { channel -> channel.lock().use { block() } }
    }
    fun load(): SaleDraft? = locked { read().active }
    fun heldSales(): List<HeldSale> = locked { read().held }
    fun save(draft: SaleDraft) = locked { validate(draft); val state=read(); require(state.active?.cart.isNullOrEmpty() || state.active?.operationId==draft.operationId) { "Another unfinished sale is active. It has not been overwritten." }; require(state.held.none { it.draft.operationId==draft.operationId }) { "This sale is already put aside. Resume it before editing." }; write(state.copy(active=draft)) }
    /** Clear only the completed/current basket, preserving every other held sale. */
    fun clear(operationId:String?=null) = locked { val state=read(); require(operationId==null || state.active==null || state.active.operationId==operationId) { "Another sale is now active. It has not been cleared." }; write(state.copy(active=null)) }
    fun hold(name: String, draft: SaleDraft): HeldSale = locked {
        require(name.trim().length in 1..100) { "Use a sale name from 1 to 100 characters." }
        validate(draft); require(draft.cart.isNotEmpty()) { "Add an item before putting a sale aside." }
        val state=read()
        state.held.find { it.draft.operationId==draft.operationId }?.let {
            require(it.draft==draft && it.name==name.trim()) { "This sale was already put aside with different details." }
            return@locked it
        }
        require(state.active==draft) { "The current sale changed. Reopen it before putting it aside." }
        require(state.held.size<100) { "Resume or remove a held sale before saving another. The limit is 100." }
        val held=HeldSale(UUID.randomUUID().toString(),name.trim(),Instant.now().toString(),draft)
        write(State(null,state.held+held)); held
    }
    fun resume(id: String): SaleDraft = locked {
        val state=read()
        require(state.active?.cart.isNullOrEmpty()) { "Put the current sale aside before resuming another." }
        val held=state.held.singleOrNull { it.id==id } ?: error("This held sale no longer exists.")
        write(State(held.draft,state.held.filterNot { it.id==id })); held.draft
    }
    fun discardHeld(id:String) = locked {
        val state=read(); require(state.held.any { it.id==id }) { "This held sale no longer exists." }
        write(state.copy(held=state.held.filterNot { it.id==id }))
    }
    private fun validate(draft: SaleDraft) {
        require(draft.operationId.isNotBlank() && draft.operationId.length<=200) { "Saved draft has an invalid operation reference." }
        require(draft.method in setOf("cash","credit") && draft.cashText.length<=1024 && (draft.customerId==null || draft.customerId.isNotBlank())) { "Saved payment details are invalid." }
        require(draft.cart.length<=1_000_000) { "Saved draft is too large." }
        if(draft.cart.isNotEmpty()) {
            val rows=draft.cart.split(';'); require(rows.size<=10_000)
            val ids=rows.map {
                val index=it.lastIndexOf(':')
                require(index>0 && (it.substring(index+1).toIntOrNull() ?: 0)>0) { "Saved draft contains an invalid quantity." }
                it.substring(0,index)
            }
            require(ids.distinct().size==ids.size) { "Saved draft contains duplicate items." }
        }
    }
    private fun read(): State {
        if(!Files.exists(file))return State(null,emptyList())
        require(Files.size(file)<=4*1024*1024) { "Saved basket file is too large. It has not been changed." }
        val p=Properties().apply { Files.newInputStream(file).use { load(it) } }
        require(p.getProperty("version","1") in setOf("1","2")) { "Saved basket version is not supported." }
        fun draft(prefix:String):SaleDraft {
            val value=SaleDraft(requireNotNull(p.getProperty(prefix+"cart")) { "Saved draft is incomplete." },requireNotNull(p.getProperty(prefix+"operationId")) { "Saved draft has no operation reference." },p.getProperty(prefix+"cashText",""),p.getProperty(prefix+"customerId"),p.getProperty(prefix+"method","cash"))
            validate(value);return value
        }
        val activeFields=setOf("operationId","cart","cashText","customerId","method")
        val hasActive=activeFields.any { p.containsKey(it) }
        require(!hasActive || (p.containsKey("operationId") && p.containsKey("cart"))) { "Saved active sale is incomplete. The file has not been changed." }
        require(p.isNotEmpty()) { "Saved basket file is empty. It has not been changed." }
        val active=if(hasActive)draft("") else null
        val count=p.getProperty("heldCount","0").toIntOrNull()
        require(count!=null && count in 0..100) { "Saved sale list is invalid." }
        val knownKeys=activeFields+setOf("version","heldCount")+(0 until count).flatMap { index -> (activeFields+setOf("id","name","savedAt")).map { "held.$index.$it" } }
        require(p.stringPropertyNames().all { it in knownKeys }) { "Saved basket contains incomplete or unsupported records. The file has not been changed." }
        val held=(0 until count).map { index ->
            val prefix="held.$index."
            val id=requireNotNull(p.getProperty(prefix+"id")); val name=requireNotNull(p.getProperty(prefix+"name"));val time=requireNotNull(p.getProperty(prefix+"savedAt"))
            require(id.isNotBlank() && name.length in 1..100);Instant.parse(time)
            HeldSale(id,name,time,draft(prefix))
        }
        require(held.map { it.id }.distinct().size==held.size && held.map { it.draft.operationId }.distinct().size==held.size) { "Held sales contain duplicate references." }
        require(active==null || held.none { it.draft.operationId==active.operationId }) { "A sale cannot be both active and held." }
        return State(active,held)
    }
    private fun write(state:State) {
        if(state.active==null && state.held.isEmpty()) { Files.deleteIfExists(file); return }
        val p=Properties().apply { setProperty("version","2");setProperty("heldCount",state.held.size.toString()) }
        fun put(prefix:String,draft:SaleDraft) {
            p.setProperty(prefix+"operationId",draft.operationId);p.setProperty(prefix+"cart",draft.cart);p.setProperty(prefix+"cashText",draft.cashText);p.setProperty(prefix+"method",draft.method)
            draft.customerId?.let { p.setProperty(prefix+"customerId",it) }
        }
        state.active?.let { put("",it) }
        state.held.forEachIndexed { index,held -> val prefix="held.$index.";p.setProperty(prefix+"id",held.id);p.setProperty(prefix+"name",held.name);p.setProperty(prefix+"savedAt",held.savedAt);put(prefix,held.draft) }
        val temporary=Files.createTempFile(file.parent,".draft-",".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use { p.store(it,"Local active and held sales");it.fd.sync() }
            require(Files.size(temporary)<=4*1024*1024) { "Saved baskets exceed the supported file size. Nothing changed." }
            try { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }
            catch(_:AtomicMoveNotSupportedException) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    companion object { private val locks=ConcurrentHashMap<Path,ReentrantLock>() }
}
