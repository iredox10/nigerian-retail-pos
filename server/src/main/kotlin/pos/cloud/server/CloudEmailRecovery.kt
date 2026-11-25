package pos.cloud.server

import org.springframework.stereotype.Service
import org.springframework.core.env.Environment
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionTemplate
import pos.cloud.protocol.*
import java.time.Clock
import java.sql.Timestamp
import java.util.UUID
import java.security.SecureRandom

interface CloudEmailDelivery {
    fun available():Boolean
    fun send(address:String,purpose:String,code:String)
}

@Service
class SmtpCloudEmailDelivery(private val environment:Environment):CloudEmailDelivery {
    override fun available()=!environment.getProperty("CLOUD_MAIL_HOST").isNullOrBlank() && !environment.getProperty("CLOUD_MAIL_FROM").isNullOrBlank()
    override fun send(address:String,purpose:String,code:String) {
        check(available())
        val mail=JavaMailSenderImpl().apply {
            host=environment.getRequiredProperty("CLOUD_MAIL_HOST")
            port=environment.getProperty("CLOUD_MAIL_PORT","587").toInt()
            username=environment.getProperty("CLOUD_MAIL_USERNAME")
            password=environment.getProperty("CLOUD_MAIL_PASSWORD")
            javaMailProperties.apply {
                setProperty("mail.smtp.auth",(!username.isNullOrBlank()).toString())
                setProperty("mail.smtp.starttls.enable","true");setProperty("mail.smtp.starttls.required","true")
                setProperty("mail.smtp.connectiontimeout","5000");setProperty("mail.smtp.timeout","5000");setProperty("mail.smtp.writetimeout","5000")
            }
        }
        mail.send(SimpleMailMessage().apply {
            setFrom(environment.getRequiredProperty("CLOUD_MAIL_FROM"));setTo(address)
            subject=if(purpose=="verify") "Verify your Retail POS email" else "Reset your Retail POS account password"
            text="Your Retail POS code is $code. It expires in 10 minutes and can be used once. Enter it only in the Retail POS app. If you did not request this, ignore this email. Never share this code with anyone."
        })
    }
}

@Service
class CloudEmailRecovery(private val jdbc:JdbcTemplate,private val tx:TransactionTemplate,private val clock:Clock,private val accounts:CloudAccountService,private val limits:CloudActionLimits,private val delivery:CloudEmailDelivery) {
    fun capabilities()=CloudAccountCapabilities(delivery.available())
    private fun available() {if(!delivery.available())throw CloudApiProblem(503,"email_unavailable","Email recovery is not configured yet. Your local shop access is unchanged.")}
    private fun canonical(email:String)=email.trim().lowercase(java.util.Locale.ROOT).also {if(it.length !in 3..254 || !it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))badRequest("Enter your account email address.")}
    private fun issue(id:String,address:String,purpose:String) {
        val alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";val random=SecureRandom()
        val code=(1..10).map {alphabet[random.nextInt(alphabet.length)]}.joinToString("")
        tx.execute {
            jdbc.queryForObject("SELECT id FROM cloud_accounts WHERE id=? FOR UPDATE",String::class.java,uuid(id))
            jdbc.update("UPDATE cloud_email_challenges SET consumed_at=? WHERE account_id=? AND purpose=? AND consumed_at IS NULL",Timestamp.from(clock.instant()),uuid(id),purpose)
            jdbc.update("INSERT INTO cloud_email_challenges(id,account_id,purpose,code_hash,created_at,expires_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),uuid(id),purpose,sha256(code),Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(600)))
            delivery.send(address,purpose,code.chunked(5).joinToString("-"))
        }
    }
    fun sendVerification(header:String?):CloudAccountNotice {
        available();val account=accounts.account(header);limits.take("verify-send:${account.id}",3)
        val address=jdbc.queryForObject("SELECT email FROM cloud_accounts WHERE id=?",String::class.java,uuid(account.id))!!
        issue(account.id,address,"verify")
        return CloudAccountNotice("Check your email for a verification code. It expires in 10 minutes.")
    }
    fun verify(header:String?,request:CloudVerifyEmail):CloudAccountNotice {
        available();val account=accounts.account(header);limits.take("verify-code:${account.id}",5)
        tx.execute {consume(account.id,"verify",request.code);jdbc.update("UPDATE cloud_accounts SET email_verified_at=? WHERE id=?",Timestamp.from(clock.instant()),uuid(account.id))}
        return CloudAccountNotice("Email verified. You can now use email password recovery.")
    }
    fun requestReset(request:CloudEmailRequest,address:String):CloudAccountNotice {
        available();val email=canonical(request.email)
        limits.take("reset-address:$address",30);limits.take("reset-send:$email",3)
        val id=jdbc.query("SELECT id FROM cloud_accounts WHERE email=? AND email_verified_at IS NOT NULL AND disabled=FALSE",RowMapper {r,_->r.getString(1)},email).singleOrNull()
        if(id!=null)issue(id,email,"reset")
        return CloudAccountNotice("If this address belongs to a verified account, a reset code has been sent. Check your inbox and spam folder.")
    }
    private fun consume(id:String,purpose:String,code:String) {
        // Serializes reset/verification attempts for an account. One code cannot be consumed twice.
        jdbc.queryForObject("SELECT id FROM cloud_accounts WHERE id=? FOR UPDATE",String::class.java,uuid(id))
        val normalized=code.trim().uppercase(java.util.Locale.ROOT).replace("-","").replace(" ","")
        if(normalized.length!=10 || jdbc.update("UPDATE cloud_email_challenges SET consumed_at=? WHERE account_id=? AND purpose=? AND code_hash=? AND consumed_at IS NULL AND expires_at>?",Timestamp.from(clock.instant()),uuid(id),purpose,sha256(normalized),Timestamp.from(clock.instant()))!=1)throw CloudApiProblem(400,"code_not_accepted","The code is incorrect, expired or already used. Request a new code.")
    }
    fun reset(request:CloudResetPassword,address:String):CloudAccountNotice {
        available();val email=canonical(request.email)
        limits.take("reset-check-address:$address",30);limits.take("reset-code:$email",5)
        val password=CloudPassword.create(request.password)
        tx.execute {
            val id=jdbc.query("SELECT id FROM cloud_accounts WHERE email=? AND email_verified_at IS NOT NULL AND disabled=FALSE FOR UPDATE",RowMapper {r,_->r.getString(1)},email).singleOrNull() ?: throw CloudApiProblem(400,"code_not_accepted","The code is incorrect, expired or already used. Request a new code.")
            consume(id,"reset",request.code)
            jdbc.update("UPDATE cloud_accounts SET password_salt=?,password_hash=? WHERE id=?",password.first,password.second,uuid(id))
            jdbc.update("DELETE FROM cloud_account_sessions WHERE account_id=?",uuid(id))
            jdbc.update("UPDATE cloud_devices SET revoked_at=COALESCE(revoked_at,?) WHERE account_id=?",Timestamp.from(clock.instant()),uuid(id))
            jdbc.update("UPDATE cloud_pairings SET finished_at=COALESCE(finished_at,?) WHERE account_id=?",Timestamp.from(clock.instant()),uuid(id))
            jdbc.update("DELETE FROM cloud_auth_attempts WHERE attempt_key=?",sha256("login:$email"))
        }
        return CloudAccountNotice("Account password changed. Sign in again on your devices to restore sharing. Saved shop records and local passwords are unchanged.")
    }
}
