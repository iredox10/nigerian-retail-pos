package pos.security

import pos.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local application sessions only. Business data and staff identities are not encrypted by this gate. */
class StaffAccess(
    directory:Path = Path.of(System.getProperty("pos.dataDir",System.getProperty("user.home")+"/.nigerian-retail-pos"))
):AutoCloseable {
    private val owners=WorkspaceAccess(directory)
    private val random=SecureRandom()
    private val db:java.sql.Connection
    private data class Issued(val session:StaffSession,val version:Long?)
    private val sessions=mutableMapOf<String,Issued>()

    init {
        Files.createDirectories(directory)
        Class.forName("org.sqlite.JDBC")
        val file=directory.resolve("staff-access.db")
        db=DriverManager.getConnection("jdbc:sqlite:$file")
        db.createStatement().use {
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("PRAGMA synchronous=FULL")
            it.execute("CREATE TABLE IF NOT EXISTS local_staff(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN('MANAGER','CASHIER')),active INTEGER NOT NULL CHECK(active IN(0,1)),created_at TEXT NOT NULL,salt TEXT NOT NULL,password_hash TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),creation_fingerprint TEXT NOT NULL,creation_salt TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
        }
        if(Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
    }

    @Synchronized fun unlockOwner(tenantId:String,password:String):StaffSession? {
        if(!owners.unlock(tenantId,password))return null
        return issue(tenantId,null,"Owner",StaffRole.OWNER,null)
    }
    @Synchronized fun signInMembers(tenantId:String):List<StaffMember> {
        validateTenant(tenantId)
        return rows("SELECT * FROM local_staff WHERE tenant_id=? AND active=1 ORDER BY name,id",tenantId).map(::member)
    }
    @Synchronized fun unlockStaff(tenantId:String,staffId:String,password:String):StaffSession? {
        if(tenantId.isBlank() || tenantId.length>256 || staffId.isBlank() || password.length !in 8..1024)return null
        val record=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",tenantId,staffId).singleOrNull() ?: return null
        return try {
            if(record["active"]!="1")return null
            val salt=Base64.getDecoder().decode(record.getValue("salt"))
            val expected=Base64.getDecoder().decode(record.getValue("password_hash"))
            if(salt.size!=24 || expected.size!=32)return null
            val actual=derive(tenantId,staffId,password,salt)
            val matches=try {MessageDigest.isEqual(actual,expected)} finally {actual.fill(0);expected.fill(0)}
            if(!matches)return null
            val person=member(record)
            issue(tenantId,staffId,person.name,person.role,record.getValue("version").toLong())
        } catch(_:Exception) { null }
    }
    @Synchronized fun requireSession(session:StaffSession,permission:StaffPermission) {
        val issued=sessions[session.token]
        require(issued!=null && issued.session==session) { "This session is no longer valid. Lock the shop and sign in again." }
        if(session.staffId!=null) {
            val record=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",session.tenantId,session.staffId).singleOrNull()
            require(record!=null && record["active"]=="1" && record["version"]?.toLongOrNull()==issued.version &&
                record["role"]==session.role.name && record["name"]==session.name) { "This staff account changed or was disabled. Sign in again." }
        } else require(session.role==StaffRole.OWNER)
        require(session.role.allows(permission)) { "Your role cannot perform this action. Ask an owner or manager to sign in." }
    }
    @Synchronized fun logout(session:StaffSession) { sessions.remove(session.token) }

    @Synchronized fun members(ownerSession:StaffSession):List<StaffMember> {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        return rows("SELECT * FROM local_staff WHERE tenant_id=? ORDER BY name,id",ownerSession.tenantId).map(::member)
    }
    @Synchronized fun addMember(ownerSession:StaffSession,name:String,role:StaffRole,password:String,operationId:String?=null):StaffMember = transaction {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        validatePassword(password)
        require(operationId==null || (operationId.isNotBlank() && operationId.length<=200))
        val id=operationId ?: UUID.randomUUID().toString()
        val person=StaffMember(id,ownerSession.tenantId,name.trim(),role,true,Instant.now().toString())
        val old=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",person.tenantId,id).singleOrNull()
        if(old!=null) {
            val salt=Base64.getDecoder().decode(old.getValue("creation_salt"))
            val fingerprint=creationFingerprint(person.tenantId,id,person.name,role,password,salt)
            require(MessageDigest.isEqual(fingerprint.toByteArray(),old.getValue("creation_fingerprint").toByteArray())) { "This staff account request was already saved with different details." }
            return@transaction member(old)
        }
        requireUniqueName(person.tenantId,person.name,null)
        val salt=ByteArray(24).also(random::nextBytes)
        val creationSalt=ByteArray(24).also(random::nextBytes)
        val hash=derive(person.tenantId,id,password,salt)
        try {
            execute("INSERT INTO local_staff VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,person.tenantId,person.name,role.name,1,person.createdAt,encode(salt),encode(hash),1L,creationFingerprint(person.tenantId,id,person.name,role,password,creationSalt),encode(creationSalt))
        } finally {hash.fill(0);salt.fill(0);creationSalt.fill(0)}
        person
    }
    @Synchronized fun updateMember(ownerSession:StaffSession,id:String,name:String,role:StaffRole,active:Boolean) = transaction {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        val old=requireMember(ownerSession.tenantId,id)
        val changed=old.copy(name=name.trim(),role=role,active=active)
        requireUniqueName(changed.tenantId,changed.name,id)
        if(changed!=old) {
            execute("UPDATE local_staff SET name=?,role=?,active=?,version=version+1 WHERE tenant_id=? AND id=?",changed.name,role.name,if(active)1 else 0,changed.tenantId,id)
            invalidate(changed.tenantId,id)
        }
        changed
    }
    @Synchronized fun resetPassword(ownerSession:StaffSession,id:String,password:String) = transaction {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        validatePassword(password)
        requireMember(ownerSession.tenantId,id)
        val salt=ByteArray(24).also(random::nextBytes)
        val hash=derive(ownerSession.tenantId,id,password,salt)
        try {
            // The creation fingerprint is deliberately retained for duplicate create retries.
            execute("UPDATE local_staff SET salt=?,password_hash=?,version=version+1 WHERE tenant_id=? AND id=?",encode(salt),encode(hash),ownerSession.tenantId,id)
            invalidate(ownerSession.tenantId,id)
        } finally {hash.fill(0);salt.fill(0)}
    }

    private fun issue(tenantId:String,staffId:String?,name:String,role:StaffRole,version:Long?):StaffSession {
        val token=encode(ByteArray(32).also(random::nextBytes))
        val session=StaffSession(token,tenantId,staffId,name,role)
        sessions[token]=Issued(session,version)
        return session
    }
    private fun invalidate(tenantId:String,id:String) {sessions.entries.removeIf {it.value.session.tenantId==tenantId && it.value.session.staffId==id}}
    private fun requireMember(tenantId:String,id:String)=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",tenantId,id).singleOrNull()?.let(::member)
        ?: throw IllegalArgumentException("Staff account not found in this shop.")
    private fun requireUniqueName(tenantId:String,name:String,except:String?) {
        require(rows("SELECT * FROM local_staff WHERE tenant_id=?",tenantId).none {it["id"]!=except && it.getValue("name").equals(name,true)}) { "A staff account in this shop already uses this name, including disabled accounts." }
    }
    private fun member(row:Map<String,String>)=StaffMember(row.getValue("id"),row.getValue("tenant_id"),row.getValue("name"),StaffRole.valueOf(row.getValue("role")),row.getValue("active")=="1",row.getValue("created_at"))
    private fun validateTenant(id:String) {require(id.isNotBlank() && id.length<=256) {"Choose a valid shop."}}
    private fun validatePassword(password:String) {require(password.length in 8..1024) {"Use a password from 8 to 1024 characters."}}
    private fun encode(bytes:ByteArray)=Base64.getEncoder().encodeToString(bytes)
    private fun derive(tenantId:String,id:String,password:String,salt:ByteArray):ByteArray {
        val bound=salt+("${tenantId.length}:$tenantId${id.length}:$id").toByteArray(Charsets.UTF_8)
        val chars=password.toCharArray()
        val spec=PBEKeySpec(chars,bound,210_000,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}
        finally {spec.clearPassword();chars.fill('\u0000');bound.fill(0)}
    }
    private fun creationFingerprint(tenantId:String,id:String,name:String,role:StaffRole,password:String,salt:ByteArray):String {
        // A separate salted, password-derived fingerprint protects duplicate-create semantics.
        val key=derive(tenantId,id,password,salt)
        return try {
            val mac=javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key,"HmacSHA256"))
            encode(mac.doFinal((name+"\u0000"+role.name).toByteArray(Charsets.UTF_8)))
        } finally {key.fill(0)}
    }
    private fun execute(sql:String,vararg values:Any?)=db.prepareStatement(sql).use {s->values.forEachIndexed {i,v->s.setObject(i+1,v)};s.executeUpdate()}
    private fun rows(sql:String,vararg values:Any?):List<Map<String,String>> = db.prepareStatement(sql).use {s->
        values.forEachIndexed {i,v->s.setObject(i+1,v)}
        s.executeQuery().use {r->buildList {while(r.next()) add((1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)})}}
    }
    private fun <T> transaction(block:()->T):T {
        db.autoCommit=false
        return try {block().also {db.commit()}} catch(e:Throwable) {db.rollback();throw e} finally {db.autoCommit=true}
    }
    @Synchronized override fun close() {sessions.clear();db.close()}
}
