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
    directory:Path = Path.of(System.getProperty("pos.dataDir",System.getProperty("user.home")+"/.nigerian-retail-pos")),
    private val clockMillis:()->Long = { System.currentTimeMillis() },
    private val idleTimeoutMillis:Long = 15 * 60 * 1000L
):AutoCloseable {
    private val owners=WorkspaceAccess(directory)
    private val random=SecureRandom()
    private val db:java.sql.Connection
    private data class Issued(val session:StaffSession,val version:Long?,var lastActivityMillis:Long)
    private val sessions=mutableMapOf<String,Issued>()

    init {
        require(idleTimeoutMillis > 0) { "Session timeout must be greater than zero." }
        Files.createDirectories(directory)
        Class.forName("org.sqlite.JDBC")
        val file=directory.resolve("staff-access.db")
        db=DriverManager.getConnection("jdbc:sqlite:$file")
        db.createStatement().use {
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("PRAGMA synchronous=FULL")
            it.execute("CREATE TABLE IF NOT EXISTS login_attempts(tenant_id TEXT NOT NULL,subject TEXT NOT NULL,failures INTEGER NOT NULL CHECK(failures BETWEEN 0 AND 5),last_attempt_ms INTEGER NOT NULL CHECK(last_attempt_ms>=0),locked_until_ms INTEGER NOT NULL CHECK(locked_until_ms>=0),PRIMARY KEY(tenant_id,subject))")
            it.execute("CREATE TABLE IF NOT EXISTS local_staff(id TEXT NOT NULL,tenant_id TEXT NOT NULL,name TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN('MANAGER','CASHIER')),active INTEGER NOT NULL CHECK(active IN(0,1)),created_at TEXT NOT NULL,salt TEXT NOT NULL,password_hash TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),creation_fingerprint TEXT NOT NULL,creation_salt TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
        }
        if(Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
    }

    @Synchronized fun unlockOwner(tenantId:String,password:String):StaffSession? {
        if(!validTenant(tenantId)) return null
        return attemptLogin(tenantId,"owner:") {
            if(!owners.unlock(tenantId,password)) null
            else LoginIdentity(null,"Owner",StaffRole.OWNER,null)
        }
    }
    @Synchronized fun signInMembers(tenantId:String):List<StaffMember> {
        validateTenant(tenantId)
        return rows("SELECT * FROM local_staff WHERE tenant_id=? AND active=1 ORDER BY name,id",tenantId).map(::member)
    }
    @Synchronized fun unlockStaff(tenantId:String,staffId:String,password:String):StaffSession? {
        if(!validTenant(tenantId) || staffId.isBlank() || staffId.length>256) return null
        return attemptLogin(tenantId,"staff:$staffId") {
            if(password.length !in 8..1024) null else {
                val record=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",tenantId,staffId).singleOrNull()
                if(record==null || record["active"]!="1") null else {
                    val salt=Base64.getDecoder().decode(record.getValue("salt"))
                    val expected=Base64.getDecoder().decode(record.getValue("password_hash"))
                    if(salt.size!=24 || expected.size!=32) null else {
                        val actual=derive(tenantId,staffId,password,salt)
                        val matches=try {MessageDigest.isEqual(actual,expected)} finally {actual.fill(0);expected.fill(0);salt.fill(0)}
                        if(!matches) null else {
                            val person=member(record)
                            LoginIdentity(staffId,person.name,person.role,record.getValue("version").toLong())
                        }
                    }
                }
            }
        }
    }

    @Synchronized fun requireSession(session:StaffSession,permission:StaffPermission,touch:Boolean=true) {
        val now=clockMillis()
        val issued=validSession(session,now)
        require(session.role.allows(permission)) { "Your role cannot perform this action. Ask an owner or manager to sign in." }
        if(touch) issued.lastActivityMillis=now
    }

    /** Called for user input activity only. Background validation must not extend idle sessions. */
    @Synchronized fun touchSession(session:StaffSession) {
        val now=clockMillis()
        validSession(session,now).lastActivityMillis=now
    }

    private fun validSession(session:StaffSession,now:Long):Issued {
        val issued=sessions[session.token]
        require(issued!=null && issued.session==session) { "This session is no longer valid. Lock the shop and sign in again." }
        if(now<issued.lastActivityMillis || now-issued.lastActivityMillis>=idleTimeoutMillis) {
            sessions.remove(session.token)
            throw IllegalArgumentException("This session expired. Sign in again.")
        }
        if(session.staffId!=null) {
            val record=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",session.tenantId,session.staffId).singleOrNull()
            if(record==null || record["active"]!="1" || record["version"]?.toLongOrNull()!=issued.version ||
                record["role"]!=session.role.name || record["name"]!=session.name) {
                sessions.remove(session.token)
                throw IllegalArgumentException("This staff account changed or was disabled. Sign in again.")
            }
        } else require(session.role==StaffRole.OWNER)
        return issued
    }

    private data class LoginIdentity(val staffId:String?,val name:String,val role:StaffRole,val version:Long?)

    /** Failed attempts survive restart; cooldown is per shop and sign-in identity. */
    private fun attemptLogin(tenantId:String,subject:String,authenticate:()->LoginIdentity?):StaffSession? {
        val now=clockMillis()
        if(now<0) return null
        val identity=transaction {
            // Acquire the SQLite write lock before reading. Independent app instances cannot
            // overwrite each other's failure counts after taking concurrent read snapshots.
            execute("INSERT INTO login_attempts(tenant_id,subject,failures,last_attempt_ms,locked_until_ms) VALUES(?,?,0,?,0) ON CONFLICT(tenant_id,subject) DO NOTHING",tenantId,subject,now)
            val attempt=rows("SELECT * FROM login_attempts WHERE tenant_id=? AND subject=?",tenantId,subject).single()
            val last=attempt.getValue("last_attempt_ms").toLong()
            val lockedUntil=attempt.getValue("locked_until_ms").toLong()
            if(now<last) return@transaction null
            if(now<lockedUntil) {
                execute("UPDATE login_attempts SET last_attempt_ms=? WHERE tenant_id=? AND subject=?",now,tenantId,subject)
                return@transaction null
            }
            val person=try { authenticate() } catch(_:Exception) { null }
            if(person!=null) {
                execute("DELETE FROM login_attempts WHERE tenant_id=? AND subject=?",tenantId,subject)
                person
            } else {
                val previous=if(lockedUntil>0 && now>=lockedUntil) 0 else attempt.getValue("failures").toInt()
                val failures=(previous+1).coerceAtMost(5)
                val until=if(failures>=5) if(now>Long.MAX_VALUE-60_000L) Long.MAX_VALUE else now+60_000L else 0L
                execute("UPDATE login_attempts SET failures=?,last_attempt_ms=?,locked_until_ms=? WHERE tenant_id=? AND subject=?",failures,now,until,tenantId,subject)
                null
            }
        }
        return identity?.let { issue(tenantId,it.staffId,it.name,it.role,it.version) }
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
        sessions[token]=Issued(session,version,clockMillis())
        return session
    }
    private fun invalidate(tenantId:String,id:String) {sessions.entries.removeIf {it.value.session.tenantId==tenantId && it.value.session.staffId==id}}
    private fun requireMember(tenantId:String,id:String)=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",tenantId,id).singleOrNull()?.let(::member)
        ?: throw IllegalArgumentException("Staff account not found in this shop.")
    private fun requireUniqueName(tenantId:String,name:String,except:String?) {
        require(rows("SELECT * FROM local_staff WHERE tenant_id=?",tenantId).none {it["id"]!=except && it.getValue("name").equals(name,true)}) { "A staff account in this shop already uses this name, including disabled accounts." }
    }
    private fun member(row:Map<String,String>)=StaffMember(row.getValue("id"),row.getValue("tenant_id"),row.getValue("name"),StaffRole.valueOf(row.getValue("role")),row.getValue("active")=="1",row.getValue("created_at"))
    private fun validTenant(id:String)=id.isNotBlank() && id.length<=256
    private fun validateTenant(id:String) {require(validTenant(id)) {"Choose a valid shop."}}
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
