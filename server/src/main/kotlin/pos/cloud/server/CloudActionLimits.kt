package pos.cloud.server

import org.springframework.stereotype.Service
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.sql.Timestamp

/** Committed separately so a rejected request cannot roll its own attempt counter back. */
@Service
class CloudActionLimits(private val jdbc:JdbcTemplate,private val tx:TransactionTemplate,private val clock:Clock) {
    fun take(key:String,maximum:Int,seconds:Long=600) {
        val now=clock.instant()
        val count=tx.execute {
            jdbc.queryForObject("""INSERT INTO cloud_action_limits(action_key,window_start,attempts) VALUES(?,?,1)
                ON CONFLICT(action_key) DO UPDATE SET
                attempts=CASE WHEN cloud_action_limits.window_start<? THEN 1 ELSE cloud_action_limits.attempts+1 END,
                window_start=CASE WHEN cloud_action_limits.window_start<? THEN EXCLUDED.window_start ELSE cloud_action_limits.window_start END
                RETURNING attempts""",Int::class.java,sha256(key),Timestamp.from(now),Timestamp.from(now.minusSeconds(seconds)),Timestamp.from(now.minusSeconds(seconds)))!!
        }!!
        if(count>maximum)throw CloudApiProblem(429,"action_paused","Too many attempts. Wait a few minutes and try again.")
    }
}
