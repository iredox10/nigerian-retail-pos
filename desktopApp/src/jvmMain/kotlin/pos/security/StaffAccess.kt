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
    private val idleTimeoutMillis:Long = 15 * 60 * 1000L,
    private val pinAuthorizationMillis:Long = 12 * 60 * 60 * 1000L
):AutoCloseable {
    private val owners=WorkspaceAccess(directory)
    private val random=SecureRandom()
    private val approvalProofs by lazy { MinimumPriceApprovalSigner(directory) }
    private val db:java.sql.Connection
    private data class Issued(val session:StaffSession,val version:Long?,var lastActivityMillis:Long,val pinAuthorizationId:String?=null,val absoluteExpiryMillis:Long?=null,val ownerFingerprint:String)
    private val sessions=mutableMapOf<String,Issued>()

    init {
        require(pinAuthorizationMillis > 0) { "PIN authorization duration must be greater than zero." }
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
            it.execute("CREATE TABLE IF NOT EXISTS staff_pins(tenant_id TEXT NOT NULL,staff_id TEXT NOT NULL,salt TEXT NOT NULL,pin_hash TEXT NOT NULL,staff_version INTEGER NOT NULL CHECK(staff_version>0),authorization_id TEXT NOT NULL,authorized_at_ms INTEGER NOT NULL CHECK(authorized_at_ms>=0),expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>=0),last_used_ms INTEGER NOT NULL CHECK(last_used_ms>=0),owner_fingerprint TEXT,PRIMARY KEY(tenant_id,staff_id))")
            it.execute("CREATE TABLE IF NOT EXISTS staff_audit(id TEXT NOT NULL,tenant_id TEXT NOT NULL,kind TEXT NOT NULL,entity_id TEXT NOT NULL,created_at TEXT NOT NULL,actor_id TEXT,actor_name TEXT NOT NULL,actor_role TEXT NOT NULL CHECK(actor_role IN('OWNER','MANAGER','CASHIER')),description TEXT CHECK(description IS NULL OR length(description)<=1000),PRIMARY KEY(tenant_id,id))")
            it.execute("""CREATE TABLE IF NOT EXISTS price_approval_grants(
                tenant_id TEXT NOT NULL,id TEXT NOT NULL,requester_token_hash TEXT NOT NULL,
                requester_version INTEGER,approver_version INTEGER,owner_fingerprint TEXT NOT NULL,
                proof TEXT NOT NULL,created_ms INTEGER NOT NULL CHECK(created_ms>=0),
                expires_ms INTEGER NOT NULL CHECK(expires_ms>created_ms),PRIMARY KEY(tenant_id,id))""")
            it.execute("""CREATE TABLE IF NOT EXISTS price_approval_usage(
                tenant_id TEXT NOT NULL,grant_id TEXT NOT NULL,operation_id TEXT NOT NULL,fingerprint TEXT NOT NULL,
                first_used_ms INTEGER NOT NULL CHECK(first_used_ms>=0),last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms>=first_used_ms),
                PRIMARY KEY(tenant_id,grant_id),FOREIGN KEY(tenant_id,grant_id) REFERENCES price_approval_grants(tenant_id,id))""")
            for (action in listOf("UPDATE", "DELETE")) it.execute("""CREATE TRIGGER IF NOT EXISTS price_grants_no_${action.lowercase()}
                BEFORE $action ON price_approval_grants BEGIN SELECT RAISE(ABORT,'Approval history cannot be changed'); END""")
            it.execute("""CREATE TRIGGER IF NOT EXISTS price_usage_no_delete BEFORE DELETE ON price_approval_usage
                BEGIN SELECT RAISE(ABORT,'Approval use history cannot be removed'); END""")
            it.execute("""CREATE TRIGGER IF NOT EXISTS price_usage_scope_immutable BEFORE UPDATE ON price_approval_usage
                WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.grant_id<>OLD.grant_id OR NEW.operation_id<>OLD.operation_id
                    OR NEW.fingerprint<>OLD.fingerprint OR NEW.first_used_ms<>OLD.first_used_ms OR NEW.last_seen_ms<OLD.last_seen_ms
                BEGIN SELECT RAISE(ABORT,'Approval use scope cannot be changed'); END""")
            it.execute("""CREATE TABLE IF NOT EXISTS cash_handover_grants(
                tenant_id TEXT NOT NULL,id TEXT NOT NULL,sender_token_hash TEXT NOT NULL,sender_version INTEGER,receiver_version INTEGER,
                owner_fingerprint TEXT NOT NULL,proof TEXT NOT NULL,created_ms INTEGER NOT NULL CHECK(created_ms>=0),expires_ms INTEGER NOT NULL CHECK(expires_ms>created_ms),PRIMARY KEY(tenant_id,id))""")
            it.execute("""CREATE TABLE IF NOT EXISTS cash_handover_usage(tenant_id TEXT NOT NULL,grant_id TEXT NOT NULL,operation_id TEXT NOT NULL,first_used_ms INTEGER NOT NULL,last_seen_ms INTEGER NOT NULL CHECK(last_seen_ms>=first_used_ms),PRIMARY KEY(tenant_id,grant_id))""")
            for(action in listOf("UPDATE","DELETE")) it.execute("CREATE TRIGGER IF NOT EXISTS handover_grants_no_${action.lowercase()} BEFORE $action ON cash_handover_grants BEGIN SELECT RAISE(ABORT,'Handover consent cannot be edited'); END")
            it.execute("CREATE TRIGGER IF NOT EXISTS handover_usage_no_delete BEFORE DELETE ON cash_handover_usage BEGIN SELECT RAISE(ABORT,'Handover consent use cannot be deleted'); END")
            it.execute("""CREATE TRIGGER IF NOT EXISTS handover_usage_scope_immutable BEFORE UPDATE ON cash_handover_usage
                WHEN NEW.tenant_id<>OLD.tenant_id OR NEW.grant_id<>OLD.grant_id OR NEW.operation_id<>OLD.operation_id OR NEW.first_used_ms<>OLD.first_used_ms OR NEW.last_seen_ms<OLD.last_seen_ms
                BEGIN SELECT RAISE(ABORT,'Handover consent scope cannot be changed'); END""")
            it.execute("CREATE TRIGGER IF NOT EXISTS staff_audit_no_update BEFORE UPDATE ON staff_audit BEGIN SELECT RAISE(ABORT,'Staff audit history cannot be edited'); END")
            it.execute("CREATE TRIGGER IF NOT EXISTS staff_audit_no_delete BEFORE DELETE ON staff_audit BEGIN SELECT RAISE(ABORT,'Staff audit history cannot be removed'); END")
        }
        if (rows("PRAGMA table_info(staff_pins)").none { it["name"] == "owner_fingerprint" }) {
            execute("ALTER TABLE staff_pins ADD COLUMN owner_fingerprint TEXT")
        }
        rows("SELECT DISTINCT tenant_id FROM staff_pins WHERE owner_fingerprint IS NULL").forEach { row ->
            val tenantId = row.getValue("tenant_id")
            val fingerprint = owners.credentialFingerprint(tenantId)
            if (fingerprint == null) execute("DELETE FROM staff_pins WHERE tenant_id=?", tenantId)
            else execute("UPDATE staff_pins SET owner_fingerprint=? WHERE tenant_id=? AND owner_fingerprint IS NULL", fingerprint, tenantId)
        }
        if(Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
    }

    @Synchronized fun unlockOwner(tenantId:String,password:String):StaffSession? {
        if(!validTenant(tenantId)) return null
        reconcileOwnerCredentialAudit(tenantId)
        return attemptLogin(tenantId,"owner:") {
            owners.authenticateOwner(tenantId,password)?.let { fingerprint ->
                LoginIdentity(null,"Owner",StaffRole.OWNER,null,ownerFingerprint=fingerprint)
            }
        }
    }
    /** A different owner or manager proves their full password for this exact sale. */
    @Synchronized fun authorizeMinimumPrice(
        requester: StaffSession, request: MinimumPriceApprovalRequest, approverStaffId: String?,
        fullPassword: String, reason: String,
    ): MinimumPriceApprovalWitness? {
        requireSession(requester, StaffPermission.SELL)
        require(request.tenantId == requester.tenantId && request.requiresApproval) { "Review a sale that needs price approval." }
        require(approverStaffId != requester.staffId) { "A different owner or manager must approve this sale." }
        val clean = reason.trim()
        require(clean.length in 3..250 && clean.none { it.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." }
        val approver = if (approverStaffId == null) unlockOwner(request.tenantId, fullPassword)
            else unlockStaff(request.tenantId, approverStaffId, fullPassword)
        approver ?: return null
        try {
            requireSession(approver, StaffPermission.MANAGE_SETTINGS)
            return transaction {
                val now = clockMillis()
                val requesting = validSession(requester, now)
                val approving = validSession(approver, now)
                require(now >= 0 && now <= Long.MAX_VALUE - 300_000) { "Correct the computer clock before approving prices." }
                val at = Instant.ofEpochMilli(now)
                val witness = approvalProofs.sign(MinimumPriceApprovalWitness(
                    UUID.randomUUID().toString(), request, requester.staffId, requester.name, requester.role,
                    approver.staffId, approver.name, approver.role, clean, at.toString(), at.plusSeconds(300).toString(),
                    approvalProofs.publicKey, ""))
                execute("INSERT INTO price_approval_grants VALUES(?,?,?,?,?,?,?,?,?)", request.tenantId, witness.id,
                    tokenFingerprint(requester.token), requesting.version, approving.version, approving.ownerFingerprint,
                    MinimumPriceApprovalProof.encode(witness), now, now + 300_000)
                appendStaffAudit(approver, "approval.sale_prices_granted", witness.id,
                    "Approved sale prices for ${requester.name}, sale ${request.operationId}, total ${money(request.totalMinor)}. Reason: $clean")
                witness
            }
        } finally { logout(approver) }
    }

    /** Financial retries retain one operation and fingerprint, so another checkout cannot reuse a grant. */
    @Synchronized fun requireMinimumPriceApproval(
        requester: StaffSession, grantId: String, request: MinimumPriceApprovalRequest,
    ): MinimumPriceApprovalWitness = transaction {
        requireSession(requester, StaffPermission.SELL)
        require(request.tenantId == requester.tenantId) { "Approval belongs to another shop." }
        val row = requireNotNull(rows("SELECT * FROM price_approval_grants WHERE tenant_id=? AND id=?", request.tenantId, grantId).singleOrNull()) {
            "Price approval is unavailable. Ask a different owner or manager to approve this sale."
        }
        val now = clockMillis()
        require(now >= row.getValue("created_ms").toLong() && now < row.getValue("expires_ms").toLong()) {
            "Price approval expired or the clock changed. Ask for a new approval."
        }
        val proof = MinimumPriceApprovalProof.decode(row.getValue("proof"))
        require(proof.id == grantId && proof.request == request && proof.issuerPublicKey == approvalProofs.publicKey &&
            proof.requesterId == requester.staffId && proof.requesterName == requester.name && proof.requesterRole == requester.role &&
            row["requester_token_hash"] == tokenFingerprint(requester.token) &&
            row["owner_fingerprint"] == owners.credentialFingerprint(request.tenantId)) { "The sale, account or price policy changed after approval." }
        val requesting = validSession(requester, now)
        require(row["requester_version"]?.toLongOrNull() == requesting.version) { "Requesting staff access changed after approval." }
        if (proof.approverId != null) {
            val person = rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?", request.tenantId, proof.approverId).singleOrNull()
            require(person != null && person["active"] == "1" && person["version"] == row["approver_version"] &&
                person["role"] == proof.approverRole.name && person["name"] == proof.approverName &&
                proof.approverRole == StaffRole.MANAGER) { "The approving account changed or was disabled. Ask for a new approval." }
        }
        val used = rows("SELECT * FROM price_approval_usage WHERE tenant_id=? AND grant_id=?", request.tenantId, grantId).singleOrNull()
        if (used == null) {
            execute("INSERT INTO price_approval_usage VALUES(?,?,?,?,?,?)", request.tenantId, grantId, request.operationId, request.fingerprint, now, now)
            appendStaffAudit(requester, "approval.sale_prices_used", grantId, "Used price approval for sale ${request.operationId}")
        } else {
            require(used["operation_id"] == request.operationId && used["fingerprint"] == request.fingerprint &&
                now >= used.getValue("last_seen_ms").toLong()) { "This price approval was used for another sale or the clock changed." }
            execute("UPDATE price_approval_usage SET last_seen_ms=? WHERE tenant_id=? AND grant_id=?", now, request.tenantId, grantId)
        }
        proof
    }

    /** Sender authorizes the movement; a different active recipient personally accepts the exact count. */
    @Synchronized fun authorizeCashHandover(sender:StaffSession,request:CashHandoverRequest,receiverStaffId:String?,fullPassword:String):CashHandoverWitness? {
        requireSession(sender,StaffPermission.MANAGE_MONEY)
        require(request.tenantId==sender.tenantId && receiverStaffId!=sender.staffId) {"A different person must accept this shop's drawer."}
        val receiver=if(receiverStaffId==null) unlockOwner(request.tenantId,fullPassword) else unlockStaff(request.tenantId,receiverStaffId,fullPassword)
        receiver ?: return null
        try {
            requireSession(receiver,StaffPermission.SELL)
            return transaction {
                val now=clockMillis();require(now>=0 && now<=Long.MAX_VALUE-300_000)
                val sending=validSession(sender,now);val receiving=validSession(receiver,now)
                val at=Instant.ofEpochMilli(now)
                val unsigned=CashHandoverWitness(UUID.randomUUID().toString(),request,sender.staffId,sender.name,sender.role,receiver.staffId,receiver.name,receiver.role,at.toString(),at.plusSeconds(300).toString(),approvalProofs.publicKey,"")
                val witness=unsigned.copy(signature=approvalProofs.signPayload(CashHandoverProof.unsignedBytes(unsigned)))
                CashHandoverProof.verify(witness)
                execute("INSERT INTO cash_handover_grants VALUES(?,?,?,?,?,?,?,?,?)",request.tenantId,witness.id,tokenFingerprint(sender.token),sending.version,receiving.version,sending.ownerFingerprint,CashHandoverProof.encode(witness),now,now+300_000)
                appendStaffAudit(receiver,"cash.handover_accepted",witness.id,"Accepted ${money(request.countedMinor)} from ${sender.name}, shift ${request.shiftId}. Reason: ${request.reason}")
                witness
            }
        } finally {logout(receiver)}
    }
    @Synchronized fun requireCashHandover(sender:StaffSession,grantId:String,request:CashHandoverRequest):CashHandoverWitness=transaction {
        requireSession(sender,StaffPermission.MANAGE_MONEY)
        require(sender.tenantId==request.tenantId)
        val row=requireNotNull(rows("SELECT * FROM cash_handover_grants WHERE tenant_id=? AND id=?",request.tenantId,grantId).singleOrNull()) {"Recipient consent is unavailable. Ask the recipient to accept again."}
        val now=clockMillis()
        require(now>=row.getValue("created_ms").toLong() && now<row.getValue("expires_ms").toLong()) {"Recipient consent expired or the clock changed."}
        val witness=CashHandoverProof.decode(row.getValue("proof"))
        val sending=validSession(sender,now)
        require(witness.id==grantId && witness.request==request && witness.issuerPublicKey==approvalProofs.publicKey && witness.senderId==sender.staffId && witness.senderName==sender.name && witness.senderRole==sender.role && row["sender_token_hash"]==tokenFingerprint(sender.token) && row["sender_version"]?.toLongOrNull()==sending.version && row["owner_fingerprint"]==owners.credentialFingerprint(request.tenantId)) {"Handover details or staff access changed. Request fresh consent."}
        if(witness.receiverId!=null) {
            val person=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",request.tenantId,witness.receiverId).singleOrNull()
            require(person!=null && person["active"]=="1" && person["version"]==row["receiver_version"] && person["name"]==witness.receiverName && person["role"]==witness.receiverRole.name) {"Receiving staff access changed. Request fresh consent."}
        }
        val used=rows("SELECT * FROM cash_handover_usage WHERE tenant_id=? AND grant_id=?",request.tenantId,grantId).singleOrNull()
        if(used==null) {
            execute("INSERT INTO cash_handover_usage VALUES(?,?,?,?,?)",request.tenantId,grantId,request.operationId,now,now)
            appendStaffAudit(sender,"cash.handover_consent_used",grantId,"Used recipient consent for handover ${request.operationId}")
        } else {
            require(used["operation_id"]==request.operationId && now>=used.getValue("last_seen_ms").toLong()) {"Consent was used for another handover or the clock changed."}
            execute("UPDATE cash_handover_usage SET last_seen_ms=? WHERE tenant_id=? AND grant_id=?",now,request.tenantId,grantId)
        }
        witness
    }

    private fun tokenFingerprint(token: String): String = encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    @Synchronized fun ownerRecoveryEnabled(ownerSession: StaffSession): Boolean {
        requireSession(ownerSession, StaffPermission.MANAGE_STAFF)
        reconcileOwnerCredentialAudit(ownerSession.tenantId)
        return owners.recoveryEnabled(ownerSession.tenantId)
    }

    /** The returned 256-bit code is displayed once and is never stored as plaintext. */
    @Synchronized fun configureOwnerRecovery(ownerSession: StaffSession, fullPassword: String): String? {
        requireSession(ownerSession, StaffPermission.MANAGE_STAFF)
        val verified = unlockOwner(ownerSession.tenantId, fullPassword) ?: return null
        try {
            requireSession(ownerSession, StaffPermission.MANAGE_STAFF)
            reconcileOwnerCredentialAudit(ownerSession.tenantId)
            val code = owners.configureRecovery(ownerSession.tenantId, fullPassword, clockMillis()) ?: return null
            // The credential record retains its event until the next successful reconciliation.
            runCatching { reconcileOwnerCredentialAudit(ownerSession.tenantId) }
            return code
        } finally { logout(verified) }
    }

    /** Recovery uses the same persistent owner attempt counter as password sign-in. */
    @Synchronized fun recoverOwner(tenantId: String, recoveryCode: String, newPassword: String): Boolean {
        if (!validTenant(tenantId)) return false
        validatePassword(newPassword)
        reconcileOwnerCredentialAudit(tenantId)
        val verified = attemptLogin(tenantId, "owner:") { now ->
            if (!owners.recoverOwner(tenantId, recoveryCode, newPassword, now)) null
            else LoginIdentity(null, "Owner", StaffRole.OWNER, null,
                ownerFingerprint = owners.credentialFingerprint(tenantId))
        } ?: return false
        sessions.entries.removeIf { it.value.session.tenantId == tenantId }
        // Fingerprint checks revoke old PINs even if cleanup is interrupted.
        runCatching { execute("DELETE FROM staff_pins WHERE tenant_id=?", tenantId) }
        runCatching { reconcileOwnerCredentialAudit(tenantId) }
        logout(verified)
        return true
    }

    private fun reconcileOwnerCredentialAudit(tenantId: String) {
        val event = owners.credentialEvent(tenantId) ?: return
        val description = when (event.kind) {
            "owner.recovery_enrolled" -> "Owner enrolled or rotated the recovery code on this computer"
            "owner.password_recovered" -> "Owner password recovered using the enrolled code; previous sessions and PIN authorizations revoked"
            else -> error("Unknown owner credential event")
        }
        execute("""INSERT OR IGNORE INTO staff_audit(
            id,tenant_id,kind,entity_id,created_at,actor_id,actor_name,actor_role,description)
            VALUES(?,?,?,?,?,NULL,'Owner','OWNER',?)""",event.id,tenantId,event.kind,tenantId,
            Instant.ofEpochMilli(event.atMillis).toString(),description)
    }

    @Synchronized fun signInMembers(tenantId:String):List<StaffMember> {
        validateTenant(tenantId)
        return rows("SELECT * FROM local_staff WHERE tenant_id=? AND active=1 ORDER BY name,id",tenantId).map(::member)
    }
    @Synchronized fun unlockStaff(tenantId:String,staffId:String,password:String):StaffSession? {
        if(!validTenant(tenantId) || staffId.isBlank() || staffId.length>256) return null
        return attemptLogin(tenantId,"staff:$staffId") { now ->
            authenticateStaff(tenantId,staffId,password)?.also { refreshPinAuthorization(tenantId,staffId,now) }
        }
    }

    private fun authenticateStaff(tenantId:String,staffId:String,password:String):LoginIdentity? {
        if(password.length !in 8..1024) return null
        val ownerFingerprint = owners.credentialFingerprint(tenantId) ?: return null
        val record=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",tenantId,staffId).singleOrNull() ?: return null
        if(record["active"]!="1") return null
        val salt=Base64.getDecoder().decode(record.getValue("salt"))
        val expected=Base64.getDecoder().decode(record.getValue("password_hash"))
        if(salt.size!=24 || expected.size!=32) return null
        val actual=derive(tenantId,staffId,password,salt)
        val matches=try {MessageDigest.isEqual(actual,expected)} finally {actual.fill(0);expected.fill(0);salt.fill(0)}
        if(!matches) return null
        val person=member(record)
        return LoginIdentity(staffId,person.name,person.role,record.getValue("version").toLong(),ownerFingerprint=ownerFingerprint)
    }

    /** Six-digit convenience unlock never renews the fixed full-password authorization. */
    @Synchronized fun unlockStaffPin(tenantId:String,staffId:String,pin:String):StaffSession? {
        if(!validTenant(tenantId) || staffId.isBlank() || staffId.length>256) return null
        return attemptLogin(tenantId,"staff:$staffId") { now ->
            if(!validPin(pin)) null else {
                val person=rows("SELECT * FROM local_staff WHERE tenant_id=? AND id=?",tenantId,staffId).singleOrNull()
                val saved=rows("SELECT * FROM staff_pins WHERE tenant_id=? AND staff_id=?",tenantId,staffId).singleOrNull()
                if(person==null || saved==null || saved["owner_fingerprint"] != owners.credentialFingerprint(tenantId) ||
                    saved["owner_fingerprint"] == null || person["active"]!="1" || person["version"]!=saved["staff_version"] ||
                    now<saved.getValue("last_used_ms").toLong() || now>=saved.getValue("expires_at_ms").toLong()) null
                else {
                    val salt=Base64.getDecoder().decode(saved.getValue("salt"))
                    val expected=Base64.getDecoder().decode(saved.getValue("pin_hash"))
                    if(salt.size!=24 || expected.size!=32) null else {
                        val actual=derive(tenantId,"pin:$staffId",pin,salt)
                        val matches=try {MessageDigest.isEqual(actual,expected)} finally {actual.fill(0);expected.fill(0);salt.fill(0)}
                        if(!matches) null else {
                            execute("UPDATE staff_pins SET last_used_ms=? WHERE tenant_id=? AND staff_id=?",now,tenantId,staffId)
                            val member=member(person)
                            LoginIdentity(staffId,member.name,member.role,person.getValue("version").toLong(),saved.getValue("authorization_id"),saved.getValue("expires_at_ms").toLong(),saved.getValue("owner_fingerprint"))
                        }
                    }
                }
            }
        }
    }

    /** Setup/removal always proves the account's full password, sharing the login cooldown. */
    @Synchronized fun configureStaffPin(tenantId:String,staffId:String,password:String,pin:String?):Boolean {
        require(pin==null || validPin(pin)) { "Use exactly six digits from 0 to 9 for the staff PIN." }
        val verified=unlockStaff(tenantId,staffId,password) ?: return false
        try {
            transaction {
                val now=clockMillis()
                validSession(verified,now)
                val previous=rows("SELECT * FROM staff_pins WHERE tenant_id=? AND staff_id=?",tenantId,staffId).singleOrNull()
                require(previous==null || now>=previous.getValue("last_used_ms").toLong()) { "The computer clock moved backwards. Correct the clock before setting a PIN." }
                if(pin==null) execute("DELETE FROM staff_pins WHERE tenant_id=? AND staff_id=?",tenantId,staffId)
                else {
                    require(now<=Long.MAX_VALUE-pinAuthorizationMillis) { "The computer clock cannot authorize a PIN." }
                    val salt=ByteArray(24).also(random::nextBytes)
                    val hash=derive(tenantId,"pin:$staffId",pin,salt)
                    try {
                        execute("INSERT INTO staff_pins(tenant_id,staff_id,salt,pin_hash,staff_version,authorization_id,authorized_at_ms,expires_at_ms,last_used_ms,owner_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,staff_id) DO UPDATE SET salt=excluded.salt,pin_hash=excluded.pin_hash,staff_version=excluded.staff_version,authorization_id=excluded.authorization_id,authorized_at_ms=excluded.authorized_at_ms,expires_at_ms=excluded.expires_at_ms,last_used_ms=excluded.last_used_ms,owner_fingerprint=excluded.owner_fingerprint",
                            tenantId,staffId,encode(salt),encode(hash),requireNotNull(sessions[verified.token]?.version),UUID.randomUUID().toString(),now,now+pinAuthorizationMillis,now,requireNotNull(sessions[verified.token]).ownerFingerprint)
                    } finally {salt.fill(0);hash.fill(0)}
                }
                appendStaffAudit(verified,if(pin==null) "staff.pin_removed" else "staff.pin_configured",staffId,if(pin==null) "Removed convenience PIN" else "Configured six-digit convenience PIN on this computer")
            }
            return true
        } finally { logout(verified) }
    }

    private fun refreshPinAuthorization(tenantId:String,staffId:String,now:Long) {
        // A clock rollback cannot reauthorize a persisted PIN. Full-password access remains available.
        val saved=rows("SELECT * FROM staff_pins WHERE tenant_id=? AND staff_id=?",tenantId,staffId).singleOrNull() ?: return
        if (saved["owner_fingerprint"] != owners.credentialFingerprint(tenantId)) {
            execute("DELETE FROM staff_pins WHERE tenant_id=? AND staff_id=?",tenantId,staffId)
            return
        }
        if(now<saved.getValue("last_used_ms").toLong() || now>Long.MAX_VALUE-pinAuthorizationMillis) return
        execute("UPDATE staff_pins SET authorization_id=?,authorized_at_ms=?,expires_at_ms=?,last_used_ms=? WHERE tenant_id=? AND staff_id=?",
            UUID.randomUUID().toString(),now,now+pinAuthorizationMillis,now,tenantId,staffId)
    }

    private fun validPin(pin:String)=pin.length==6 && pin.all {it in '0'..'9'}

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
        if (issued.ownerFingerprint != owners.credentialFingerprint(session.tenantId)) {
            sessions.remove(session.token)
            throw IllegalArgumentException("Owner access changed. Sign in again.")
        }
        if(now<issued.lastActivityMillis || now-issued.lastActivityMillis>=idleTimeoutMillis ||
            (issued.absoluteExpiryMillis!=null && now>=issued.absoluteExpiryMillis)) {
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
        if(issued.pinAuthorizationId!=null) {
            val changed=execute("UPDATE staff_pins SET last_used_ms=? WHERE tenant_id=? AND staff_id=? AND authorization_id=? AND staff_version=? AND last_used_ms<=? AND expires_at_ms>?",
                now,session.tenantId,session.staffId,issued.pinAuthorizationId,issued.version,now,now)
            if(changed!=1) {
                sessions.remove(session.token)
                throw IllegalArgumentException("PIN access expired or changed. Sign in with your full password.")
            }
        }
        return issued
    }

    private data class LoginIdentity(val staffId:String?,val name:String,val role:StaffRole,val version:Long?,val pinAuthorizationId:String?=null,val absoluteExpiryMillis:Long?=null,val ownerFingerprint:String?=null)

    /** Failed attempts survive restart; cooldown is per shop and sign-in identity. */
    private fun attemptLogin(tenantId:String,subject:String,authenticate:(Long)->LoginIdentity?):StaffSession? {
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
            val person=try { authenticate(now) } catch(_:Exception) { null }
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
        return identity?.let {
            val fingerprint = it.ownerFingerprint ?: owners.credentialFingerprint(tenantId) ?: return@let null
            issue(tenantId,it.staffId,it.name,it.role,it.version,now,it.pinAuthorizationId,it.absoluteExpiryMillis,fingerprint)
        }
    }
    @Synchronized fun logout(session:StaffSession) { sessions.remove(session.token) }

    @Synchronized fun members(ownerSession:StaffSession):List<StaffMember> {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        return rows("SELECT * FROM local_staff WHERE tenant_id=? ORDER BY name,id",ownerSession.tenantId).map(::member)
    }
    /** Local security history is separate from transferable business backup data. */
    @Synchronized fun auditEvents(ownerSession:StaffSession):List<AuditEvent> {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        return rows("SELECT * FROM staff_audit WHERE tenant_id=? ORDER BY created_at DESC,id DESC",ownerSession.tenantId).map { row ->
            AuditEvent(row.getValue("id"),row.getValue("tenant_id"),row.getValue("kind"),row.getValue("entity_id"),row.getValue("created_at"),
                AuditActor(row.getValue("tenant_id"),row["actor_id"],row.getValue("actor_name"),StaffRole.valueOf(row.getValue("actor_role"))),row["description"])
        }
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
        appendStaffAudit(ownerSession,"staff.created",person.id,"Created staff: name=${person.name}; role=${person.role.name}; active=${person.active}")
        person
    }
    @Synchronized fun updateMember(ownerSession:StaffSession,id:String,name:String,role:StaffRole,active:Boolean) = transaction {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        val old=requireMember(ownerSession.tenantId,id)
        val changed=old.copy(name=name.trim(),role=role,active=active)
        requireUniqueName(changed.tenantId,changed.name,id)
        if(changed!=old) {
            execute("UPDATE local_staff SET name=?,role=?,active=?,version=version+1 WHERE tenant_id=? AND id=?",changed.name,role.name,if(active)1 else 0,changed.tenantId,id)
            execute("DELETE FROM staff_pins WHERE tenant_id=? AND staff_id=?",changed.tenantId,id)
            invalidate(changed.tenantId,id)
            appendStaffAudit(ownerSession,"staff.updated",id,"Staff before: name=${old.name}; role=${old.role.name}; active=${old.active}. Staff after: name=${changed.name}; role=${changed.role.name}; active=${changed.active}.")
        }
        changed
    }
    @Synchronized fun resetPassword(ownerSession:StaffSession,id:String,password:String) = transaction {
        requireSession(ownerSession,StaffPermission.MANAGE_STAFF)
        validatePassword(password)
        val person=requireMember(ownerSession.tenantId,id)
        val salt=ByteArray(24).also(random::nextBytes)
        val hash=derive(ownerSession.tenantId,id,password,salt)
        try {
            // The creation fingerprint is deliberately retained for duplicate create retries.
            execute("UPDATE local_staff SET salt=?,password_hash=?,version=version+1 WHERE tenant_id=? AND id=?",encode(salt),encode(hash),ownerSession.tenantId,id)
            execute("DELETE FROM staff_pins WHERE tenant_id=? AND staff_id=?",ownerSession.tenantId,id)
            invalidate(ownerSession.tenantId,id)
            appendStaffAudit(ownerSession,"staff.password_reset",id,"Password reset for staff: name=${person.name}")
        } finally {hash.fill(0);salt.fill(0)}
    }

    private fun appendStaffAudit(session:StaffSession,kind:String,entityId:String,description:String) {
        require(description.length<=1000)
        execute("INSERT INTO staff_audit(id,tenant_id,kind,entity_id,created_at,actor_id,actor_name,actor_role,description) VALUES(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),session.tenantId,kind,entityId,Instant.now().toString(),session.staffId,session.name,session.role.name,description)
    }

    private fun issue(tenantId:String,staffId:String?,name:String,role:StaffRole,version:Long?,issuedAt:Long,pinAuthorizationId:String?=null,absoluteExpiryMillis:Long?=null,ownerFingerprint:String):StaffSession {
        val token=encode(ByteArray(32).also(random::nextBytes))
        val session=StaffSession(token,tenantId,staffId,name,role)
        sessions[token]=Issued(session,version,issuedAt,pinAuthorizationId,absoluteExpiryMillis,ownerFingerprint)
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
