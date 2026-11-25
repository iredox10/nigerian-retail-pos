package pos.cloud.server

import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate
import pos.cloud.protocol.*
import java.time.Clock
import java.sql.Timestamp
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

@Service
class CloudPairingService(private val jdbc:JdbcTemplate,private val tx:TransactionTemplate,private val clock:Clock,private val accounts:CloudAccountService,private val limits:CloudActionLimits) {
    private val alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private data class Pairing(val id:String,val installation:String,val name:String,val account:String?,val business:String?,val device:String?,val expired:Boolean,val finished:Boolean)
    private val mapper=RowMapper {r:java.sql.ResultSet,_:Int ->Pairing(r.getString("id"),r.getString("installation_id"),r.getString("name"),r.getString("account_id"),r.getString("business_id"),r.getString("device_id"),!r.getTimestamp("expires_at").toInstant().isAfter(clock.instant()),r.getTimestamp("finished_at")!=null)}
    fun start(request:CloudPairingStart,address:String):CloudPairingTicket {
        limits.take("pair-start:$address",200)
        limits.take("pair-install:"+uuid(request.installationId),5)
        val id=UUID.randomUUID();val secret=randomSecret();val random=SecureRandom()
        val code=(1..10).map {alphabet[random.nextInt(alphabet.length)]}.joinToString("")
        val expires=clock.instant().plusSeconds(600)
        jdbc.update("INSERT INTO cloud_pairings(id,code_hash,secret_hash,installation_id,name,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",id,sha256(code),sha256(secret),uuid(request.installationId),safeName(request.name,100),Timestamp.from(clock.instant()),Timestamp.from(expires))
        return CloudPairingTicket(id.toString(),code.chunked(5).joinToString("-"),secret,expires.toString())
    }
    private fun byCode(code:String):Pairing {
        val canonical=code.trim().uppercase(java.util.Locale.ROOT).replace("-","").replace(" ","")
        if(canonical.length!=10 || canonical.any {it !in alphabet})invalid()
        return jdbc.query("SELECT * FROM cloud_pairings WHERE code_hash=? FOR UPDATE",mapper,sha256(canonical)).singleOrNull()?.also {if(it.expired || it.finished || it.business!=null)invalid()} ?: invalid()
    }
    private fun invalid():Nothing=throw CloudApiProblem(400,"pairing_unavailable","This code is incorrect, expired or already used. Ask the new device for a fresh code.")
    fun review(header:String?,businessId:String,request:CloudPairingCode):CloudPairingReview {
        val owner=accounts.account(header);accounts.owner(owner,businessId);limits.take("pair-review:${owner.id}",20)
        return tx.execute {val pair=byCode(request.code);CloudPairingReview(pair.id,pair.name,jdbc.queryForObject("SELECT expires_at::text FROM cloud_pairings WHERE id=?",String::class.java,uuid(pair.id))!!)}!!
    }
    fun approve(header:String?,businessId:String,request:CloudPairingCode) {
        val owner=accounts.account(header);accounts.owner(owner,businessId);limits.take("pair-approve:${owner.id}",20)
        tx.execute {
            val pair=byCode(request.code)
            if(request.pairingId!=pair.id)badRequest("Review this device before allowing access.")
            // An already-enrolled installation must renew explicitly; pairing cannot revive a removed device.
            if(jdbc.queryForObject("SELECT count(*) FROM cloud_devices WHERE business_id=? AND installation_id=?",Int::class.java,uuid(businessId),uuid(pair.installation))!=0)throw CloudApiProblem(409,"device_already_connected","This installation is already known. Sign in on it to renew access.")
            jdbc.update("UPDATE cloud_pairings SET account_id=?,business_id=?,approved_at=? WHERE id=?",uuid(owner.id),uuid(businessId),Timestamp.from(clock.instant()),uuid(pair.id))
        }
    }
    fun claim(request:CloudPairingClaim):CloudPairingResult=tx.execute {
        if(!request.secret.matches(Regex("[A-Za-z0-9_-]{43}")))unauthenticated()
        val pair=jdbc.query("SELECT * FROM cloud_pairings WHERE id=? AND secret_hash=? FOR UPDATE",mapper,uuid(request.id),sha256(request.secret)).singleOrNull() ?: unauthenticated()
        if(pair.expired || pair.finished)throw CloudApiProblem(410,"pairing_expired","This connection code has expired. Start again on the new device.")
        val business=pair.business ?: return@execute CloudPairingResult("waiting")
        val owner=AccountIdentity(requireNotNull(pair.account));accounts.owner(owner,business)
        // Deterministic secret-bound token permits a lost HTTP reply to be retried without persisting plaintext tokens.
        val token=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(("retail-pos-pair:"+pair.id+":"+request.secret).toByteArray()))
        val id=pair.device ?: UUID.randomUUID().toString()
        jdbc.queryForObject("SELECT id FROM cloud_businesses WHERE id=? FOR UPDATE",String::class.java,uuid(business))
        if(pair.device==null) {
            if(jdbc.queryForObject("SELECT count(*) FROM cloud_devices WHERE business_id=? AND installation_id=?",Int::class.java,uuid(business),uuid(pair.installation))!=0)throw CloudApiProblem(409,"device_already_connected","This device is already connected. Sign in to renew its access.")
            val now=clock.instant()
            jdbc.update("INSERT INTO cloud_devices(id,business_id,account_id,installation_id,name,token_hash,created_at,enrolled_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",uuid(id),uuid(business),uuid(owner.id),uuid(pair.installation),pair.name,sha256(token),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plusSeconds(30L*86400)))
            jdbc.update("UPDATE cloud_pairings SET device_id=? WHERE id=?",uuid(id),uuid(pair.id))
        }
        val device=accounts.device("Bearer $token",business)
        val expiry=jdbc.queryForObject("SELECT expires_at FROM cloud_devices WHERE id=?",Timestamp::class.java,uuid(device.id))!!.toInstant().toString()
        val name=jdbc.queryForObject("SELECT name FROM cloud_businesses WHERE id=?",String::class.java,uuid(business))!!
        CloudPairingResult("approved",name,CloudDeviceSession(id,business,token,expiry))
    }!!
    fun finish(request:CloudPairingClaim) {
        jdbc.update("UPDATE cloud_pairings SET finished_at=COALESCE(finished_at,?) WHERE id=? AND secret_hash=?",Timestamp.from(clock.instant()),uuid(request.id),sha256(request.secret))
    }
}
