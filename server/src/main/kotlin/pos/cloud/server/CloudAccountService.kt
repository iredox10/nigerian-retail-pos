package pos.cloud.server

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.dao.DuplicateKeyException
import pos.cloud.protocol.*
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.util.UUID

internal data class AccountIdentity(val id:String)
internal data class DeviceIdentity(val id:String,val accountId:String,val businessId:String)

@Service
class CloudAccountService(private val jdbc:JdbcTemplate,private val tx:TransactionTemplate,private val clock:Clock) {
    @Suppress("UNCHECKED_CAST") private fun <T> transaction(block:()->T):T=tx.execute {block()} as T
    private fun email(value:String):String {
        val canonical=value.trim().lowercase(java.util.Locale.ROOT)
        if(canonical.length !in 3..254 || !canonical.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))badRequest("Enter a valid cloud sign-in email.")
        return canonical
    }
    fun register(request:CloudAccountRequest):CloudAccountSession {
        val login=email(request.email)
        val password=CloudPassword.create(request.password)
        val now=clock.instant();val id=UUID.randomUUID()
        try {
            return transaction {
                jdbc.update("INSERT INTO cloud_accounts(id,email,password_salt,password_hash,created_at) VALUES(?,?,?,?,?)",id,login,password.first,password.second,Timestamp.from(now))
                newAccountSession(id.toString(),now)
            }
        } catch(_:DuplicateKeyException) {throw CloudApiProblem(409,"account_exists","This email already has a cloud account. Sign in instead.")}
    }
    fun login(request:CloudAccountRequest):CloudAccountSession {
        val login=email(request.email)
        val key=sha256("login:$login")
        val now=clock.instant()
        val result:CloudAccountSession?=transaction {
            jdbc.update("INSERT INTO cloud_auth_attempts(attempt_key,failures,updated_at) VALUES(?,0,?) ON CONFLICT(attempt_key) DO NOTHING",key,Timestamp.from(now))
            val attempts=jdbc.query("SELECT failures,cooldown_until FROM cloud_auth_attempts WHERE attempt_key=? FOR UPDATE",RowMapper {r,_->r.getInt(1) to r.getTimestamp(2)?.toInstant()},key).single()
            if(attempts.second?.isAfter(now)==true)throw CloudApiProblem(429,"sign_in_paused","Sign-in is temporarily paused after unsuccessful attempts. Try again later.")
            val rows=jdbc.query("SELECT id,password_salt,password_hash,disabled FROM cloud_accounts WHERE email=?",RowMapper {r,_->listOf(r.getString(1),r.getString(2),r.getString(3),r.getBoolean(4).toString())},login)
            val account=rows.singleOrNull()
            val valid=CloudPassword.matches(request.password,account?.get(1) ?: CloudPassword.dummySalt,account?.get(2) ?: CloudPassword.dummyHash) && account?.get(3)=="false"
            if(!valid) {
                val previous=if(attempts.second!=null)0 else attempts.first
                val failures=previous+1
                jdbc.update("UPDATE cloud_auth_attempts SET failures=?,cooldown_until=?,updated_at=? WHERE attempt_key=?",failures,if(failures>=5)Timestamp.from(now.plusSeconds(60)) else null,Timestamp.from(now),key)
                null
            } else {
                jdbc.update("UPDATE cloud_auth_attempts SET failures=0,cooldown_until=NULL,updated_at=? WHERE attempt_key=?",Timestamp.from(now),key)
                newAccountSession(requireNotNull(account)[0],now)
            }
        }
        return result ?: throw CloudApiProblem(401,"sign_in_failed","Email or password was not accepted.")
    }
    private fun newAccountSession(accountId:String,now:Instant):CloudAccountSession {
        val token=randomSecret();val expiry=now.plus(Duration.ofMinutes(30))
        jdbc.update("INSERT INTO cloud_account_sessions(token_hash,account_id,created_at,expires_at) VALUES(?,?,?,?)",sha256(token),uuid(accountId),Timestamp.from(now),Timestamp.from(expiry))
        return CloudAccountSession(accountId,token,expiry.toString())
    }
    internal fun account(header:String?):AccountIdentity {
        val token=bearer(header)
        val result=jdbc.query("SELECT a.id FROM cloud_account_sessions s JOIN cloud_accounts a ON a.id=s.account_id WHERE s.token_hash=? AND s.expires_at>? AND a.disabled=FALSE",RowMapper {r,_->AccountIdentity(r.getString(1))},sha256(token),Timestamp.from(clock.instant())).singleOrNull()
        return result ?: unauthenticated()
    }
    internal fun owner(account:AccountIdentity,businessId:String) {
        val allowed=jdbc.query("SELECT 1 FROM cloud_memberships m JOIN cloud_accounts a ON a.id=m.account_id WHERE m.business_id=? AND m.account_id=? AND m.active=TRUE AND m.role='OWNER' AND a.disabled=FALSE",RowMapper {_,_->true},uuid(businessId),uuid(account.id)).singleOrNull()==true
        if(!allowed)denied()
    }
    internal fun device(header:String?,businessId:String,lock:Boolean=false):DeviceIdentity {
        val token=bearer(header)
        val query="SELECT d.id,d.account_id,d.business_id FROM cloud_devices d JOIN cloud_memberships m ON m.business_id=d.business_id AND m.account_id=d.account_id JOIN cloud_accounts a ON a.id=d.account_id WHERE d.token_hash=? AND d.business_id=? AND d.revoked_at IS NULL AND d.expires_at>? AND m.active=TRUE AND m.role='OWNER' AND a.disabled=FALSE"+(if(lock)" FOR SHARE OF d,m,a" else "")
        return jdbc.query(query,RowMapper {r,_->DeviceIdentity(r.getString(1),r.getString(2),r.getString(3))},sha256(token),uuid(businessId),Timestamp.from(clock.instant())).singleOrNull() ?: unauthenticated()
    }
    fun enroll(header:String?,businessId:String,request:CloudDeviceEnrollRequest):CloudDeviceSession=transaction {
        val account=account(header);owner(account,businessId)
        val name=safeName(request.name,100);val installation=uuid(request.installationId)
        jdbc.query("SELECT id FROM cloud_businesses WHERE id=? FOR UPDATE",RowMapper {r,_->r.getString(1)},uuid(businessId)).singleOrNull() ?: denied()
        val id=jdbc.query("SELECT id FROM cloud_devices WHERE business_id=? AND account_id=? AND installation_id=?",RowMapper {r,_->r.getString(1)},uuid(businessId),uuid(account.id),installation).singleOrNull() ?: UUID.randomUUID().toString()
        val now=clock.instant();val expiry=now.plus(Duration.ofDays(30));val token=randomSecret()
        jdbc.update("INSERT INTO cloud_devices(id,business_id,account_id,installation_id,name,token_hash,created_at,enrolled_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(business_id,account_id,installation_id) DO UPDATE SET name=EXCLUDED.name,token_hash=EXCLUDED.token_hash,enrolled_at=EXCLUDED.enrolled_at,expires_at=EXCLUDED.expires_at,revoked_at=NULL",uuid(id),uuid(businessId),uuid(account.id),installation,name,sha256(token),Timestamp.from(now),Timestamp.from(now),Timestamp.from(expiry))
        CloudDeviceSession(id,businessId,token,expiry.toString())
    }
    fun devices(header:String?,businessId:String):List<CloudDeviceSummary> {
        val account=account(header);owner(account,businessId)
        return jdbc.query("SELECT id,name,created_at,expires_at,revoked_at FROM cloud_devices WHERE business_id=? ORDER BY created_at,id",RowMapper {r,_->CloudDeviceSummary(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant().toString(),r.getTimestamp(4).toInstant().toString(),r.getTimestamp(5)?.toInstant()?.toString())},uuid(businessId))
    }
    fun revoke(header:String?,businessId:String,deviceId:String)=transaction {
        val account=account(header);owner(account,businessId)
        if(jdbc.update("UPDATE cloud_devices SET revoked_at=COALESCE(revoked_at,?) WHERE business_id=? AND id=?",Timestamp.from(clock.instant()),uuid(businessId),uuid(deviceId))!=1)throw CloudApiProblem(404,"device_not_found","The device is not part of this business.")
    }
    private fun bearer(header:String?):String {
        if(header==null || !header.startsWith("Bearer "))unauthenticated()
        val token=header.substring(7)
        if(!token.matches(Regex("[A-Za-z0-9_-]{43}")))unauthenticated()
        return token
    }
}
