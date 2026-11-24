package pos.cloud.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pos.cloud.protocol.*
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

private data class StoredBusiness(val id:String,val name:String,val schemaHash:String,val revision:Long,val snapshot:CloudSnapshot,val productVersions:Map<String,Long>)
private data class StoredOperation(val deviceId:String,val requestHash:String,val status:String,val revision:Long?,val assessedRevision:Long,val conflicts:List<CloudConflict>)

@Service
class CloudBusinessService(private val jdbc:JdbcTemplate,private val tx:TransactionTemplate,private val accounts:CloudAccountService,private val rules:CloudBusinessRules,private val clock:Clock) {
    private val schema:CloudSchema by lazy {rules.schema()}
    @Suppress("UNCHECKED_CAST") private fun <T> transaction(block:()->T):T=tx.execute {block()} as T
    private fun requireSchema(hash:String) {if(hash!=schema.fingerprint)throw CloudApiProblem(409,"schema_mismatch","This app and the cloud use different record versions. Update the app before syncing; local work remains saved.")}
    fun businesses(header:String?):List<CloudBusinessSummary> {
        val account=accounts.account(header)
        return jdbc.query("SELECT b.id,b.name,b.revision FROM cloud_businesses b JOIN cloud_memberships m ON m.business_id=b.id WHERE m.account_id=? AND m.active=TRUE AND m.role='OWNER' ORDER BY b.created_at,b.id",RowMapper {r,_->CloudBusinessSummary(r.getString(1),r.getString(2),r.getLong(3))},uuid(account.id))
    }
    fun create(header:String?,request:CloudCreateBusinessRequest):CloudBusinessSummary=transaction {
        val account=accounts.account(header);safeId(request.operationId);requireSchema(request.schemaHash)
        val name=safeName(request.name)
        val requestHash=CloudCodec.fingerprint(request)
        jdbc.query("SELECT id FROM cloud_accounts WHERE id=? FOR UPDATE",RowMapper {r,_->r.getString(1)},uuid(account.id)).single()
        val previous=jdbc.query("SELECT id,name,revision,creation_hash FROM cloud_businesses WHERE creator_account_id=? AND creation_key=?",RowMapper {r,_->listOf(r.getString(1),r.getString(2),r.getLong(3).toString(),r.getString(4))},uuid(account.id),request.operationId).singleOrNull()
        if(previous!=null) {
            if(previous[3]!=requestHash)throw CloudApiProblem(409,"operation_reused","This publishing request was already used for different records.")
            return@transaction CloudBusinessSummary(previous[0],previous[1],previous[2].toLong())
        }
        val original=CloudMerge.normalize(request.snapshot,schema)
        if(original.tables.values.flatten().any {it["tenant_id"]?.let {tenant ->tenant!=original.tenantId}==true})badRequest("Business records must all belong to the selected shop.")
        val id=UUID.randomUUID().toString()
        val snapshot=CloudMerge.normalize(original.copy(tenantId=id,tenantName=name,tables=original.tables.mapValues {(_,rows)->rows.map {row ->if("tenant_id" in row)row+("tenant_id" to id) else row}}),schema)
        rules.validateInitial(snapshot)
        val now=Timestamp.from(clock.instant())
        val products=snapshot.tables["products"].orEmpty().mapNotNull {it["id"]}.associateWith {0L}
        jdbc.update("INSERT INTO cloud_businesses(id,name,schema_hash,snapshot,product_versions,created_at,creator_account_id,creation_key,creation_hash) VALUES(?,?,?,?::jsonb,?::jsonb,?,?,?,?)",uuid(id),name,schema.fingerprint,CloudCodec.encode(snapshot),CloudCodec.encode(products),now,uuid(account.id),request.operationId,requestHash)
        jdbc.update("INSERT INTO cloud_memberships(business_id,account_id,role) VALUES(?,?,'OWNER')",uuid(id),uuid(account.id))
        CloudBusinessSummary(id,name,0)
    }
    fun bootstrap(header:String?,businessId:String):CloudBootstrapReply=transaction {
        accounts.device(header,businessId)
        val business=business(businessId,false)
        requireSchema(business.schemaHash)
        CloudBootstrapReply(business.id,business.revision,schema,business.snapshot)
    }
    fun pull(header:String?,businessId:String,after:Long,limit:Int):CloudPullReply=transaction {
        accounts.device(header,businessId)
        if(after<0 || limit !in 1..100)badRequest("Use a non-negative cursor and a page size from 1 to 100.")
        val business=business(businessId,false)
        requireSchema(business.schemaHash)
        if(after>business.revision)throw CloudApiProblem(409,"future_cursor","The saved cursor is newer than this cloud business. Reconnect without deleting local records.")
        val changes=jdbc.query("SELECT revision,operation_id,device_id,delta FROM cloud_changes WHERE business_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT ?",RowMapper {r,_->CloudChangeEnvelope(r.getLong(1),r.getString(2),r.getString(3),CloudCodec.decode<CloudDelta>(r.getString(4)))},uuid(businessId),after,business.revision,limit)
        CloudPullReply(after,changes.lastOrNull()?.revision ?: after,business.revision,changes)
    }
    fun push(header:String?,businessId:String,request:CloudPushRequest):CloudPushReply=transaction {
        accounts.device(header,businessId)
        val current=business(businessId,true)
        val device=accounts.device(header,businessId,true)
        safeId(request.operationId);requireSchema(request.schemaHash);requireSchema(current.schemaHash)
        if(request.baseRevision<0 || request.baseRevision>current.revision)throw CloudApiProblem(409,"future_cursor","This change does not refer to a known cloud revision.")
        val hash=CloudCodec.fingerprint(request)
        val stored=operation(businessId,request.operationId)
        if(stored!=null) {
            if(stored.deviceId!=device.id || stored.requestHash!=hash)throw CloudApiProblem(409,"operation_reused","This synchronization reference was already used with different records or by another device.")
            if(stored.status=="accepted")return@transaction CloudPushReply(request.operationId,"accepted",requireNotNull(stored.revision))
            if(stored.assessedRevision==current.revision)return@transaction CloudPushReply(request.operationId,"conflict",current.revision,stored.conflicts)
        }
        val merge=CloudMerge.merge(current.snapshot,request.delta,schema)
        var conflicts=merge.conflicts
        var touched=emptySet<String>()
        val snapshot=merge.snapshot
        if(conflicts.isEmpty() && snapshot!=null) {
            if(snapshot.tenantId!=businessId)badRequest("A synchronization change cannot select another business.")
            try {touched=rules.validateMerge(current.snapshot,snapshot,request.delta,request.baseRevision,current.productVersions)}
            catch(error:IllegalArgumentException) {
                val message=error.message?.filterNot {it.isISOControl()}?.take(500)?.takeIf {it.isNotBlank()} ?: "These changes do not reconcile with the cloud's saved financial history. Review them before retrying."
                conflicts=listOf(CloudConflict("business","",emptyList(),message))
            }
        }
        val now=Timestamp.from(clock.instant())
        if(conflicts.isNotEmpty() || snapshot==null) {
            val details=if(conflicts.isEmpty())listOf(CloudConflict("business","",emptyList(),"The merged records need review.")) else conflicts
            saveOperation(current,device,request,hash,"conflict",null,details,now,stored!=null)
            return@transaction CloudPushReply(request.operationId,"conflict",current.revision,details)
        }
        val acceptedDelta=CloudMerge.diff(current.snapshot,snapshot,schema)
        val changed=acceptedDelta.changes.isNotEmpty() || acceptedDelta.beforeName!=acceptedDelta.afterName
        val revision=if(changed)Math.addExact(current.revision,1L) else current.revision
        saveOperation(current,device,request,hash,"accepted",revision,emptyList(),now,stored!=null)
        if(changed) {
            val versions=current.productVersions+touched.associateWith {revision}
            jdbc.update("UPDATE cloud_businesses SET name=?,snapshot=?::jsonb,product_versions=?::jsonb,revision=? WHERE id=?",snapshot.tenantName,CloudCodec.encode(snapshot),CloudCodec.encode(versions),revision,uuid(businessId))
            jdbc.update("INSERT INTO cloud_changes(business_id,revision,operation_id,device_id,delta,created_at) VALUES(?,?,?,?,?::jsonb,?)",uuid(businessId),revision,request.operationId,uuid(device.id),CloudCodec.encode(acceptedDelta),now)
        }
        CloudPushReply(request.operationId,"accepted",revision)
    }
    private fun business(id:String,lock:Boolean):StoredBusiness=jdbc.query("SELECT id,name,schema_hash,revision,snapshot,product_versions FROM cloud_businesses WHERE id=?"+(if(lock)" FOR UPDATE" else ""),RowMapper {r,_->StoredBusiness(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),CloudCodec.decode(r.getString(5)),CloudCodec.decode(r.getString(6)))},uuid(id)).singleOrNull() ?: throw CloudApiProblem(404,"business_not_found","The cloud business is unavailable to this connection.")
    private fun operation(businessId:String,id:String)=jdbc.query("SELECT device_id,request_hash,status,revision,last_assessed_revision,conflicts FROM cloud_operations WHERE business_id=? AND operation_id=?",RowMapper {r,_->StoredOperation(r.getString(1),r.getString(2),r.getString(3),r.getObject(4)?.let {(it as Number).toLong()},r.getLong(5),CloudCodec.decode(r.getString(6)))},uuid(businessId),id).singleOrNull()
    private fun saveOperation(business:StoredBusiness,device:DeviceIdentity,request:CloudPushRequest,hash:String,status:String,revision:Long?,conflicts:List<CloudConflict>,now:Timestamp,existing:Boolean) {
        val details=CloudCodec.encode(conflicts)
        if(!existing) jdbc.update("INSERT INTO cloud_operations(business_id,operation_id,device_id,request_hash,request_payload,status,revision,last_assessed_revision,conflicts,created_at,updated_at) VALUES(?,?,?,?,?::jsonb,?,?,?,?::jsonb,?,?)",uuid(business.id),request.operationId,uuid(device.id),hash,CloudCodec.encode(request),status,revision,business.revision,details,now,now)
        else jdbc.update("UPDATE cloud_operations SET status=?,revision=?,last_assessed_revision=?,conflicts=?::jsonb,updated_at=? WHERE business_id=? AND operation_id=?",status,revision,business.revision,details,now,uuid(business.id),request.operationId)
        jdbc.update("INSERT INTO cloud_operation_assessments(business_id,operation_id,assessed_revision,status,conflicts,created_at) VALUES(?,?,?,?,?::jsonb,?)",uuid(business.id),request.operationId,business.revision,status,details,now)
    }
}
