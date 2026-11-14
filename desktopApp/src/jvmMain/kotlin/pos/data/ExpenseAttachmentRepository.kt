package pos.data

import pos.domain.StaffPermission
import pos.domain.SupplierInvoiceAttachmentRef
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val expenseAttachmentSnapshotTables=listOf("expense_attachment_assets","expense_attachment_versions")
private const val EXPENSE_EVIDENCE_BUDGET=8_388_608L

data class ExpenseAttachmentRevision(
    val id:String,val expenseId:String,val version:Long,val attachment:SupplierInvoiceAttachmentRef?,
    val reason:String,val createdAt:String,
)

internal fun PosRepository.initializeExpenseAttachments() {
    featureSchema("CREATE TABLE IF NOT EXISTS expense_attachment_assets(tenant_id TEXT NOT NULL REFERENCES tenants(id),hash TEXT NOT NULL,media_type TEXT NOT NULL,byte_size INTEGER NOT NULL CHECK(byte_size BETWEEN 1 AND 1048576),payload TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,hash))")
    featureSchema("CREATE TABLE IF NOT EXISTS expense_attachment_versions(tenant_id TEXT NOT NULL,id TEXT NOT NULL,expense_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),attachment_hash TEXT,attachment_name TEXT,reason TEXT NOT NULL,created_at TEXT NOT NULL,CHECK((attachment_hash IS NULL)=(attachment_name IS NULL)),PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,expense_id,version),FOREIGN KEY(tenant_id,expense_id) REFERENCES expenses(tenant_id,id),FOREIGN KEY(tenant_id,attachment_hash) REFERENCES expense_attachment_assets(tenant_id,hash))")
    expenseAttachmentSnapshotTables.forEach {table ->listOf("UPDATE","DELETE").forEach {action ->
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Expense receipt history cannot be changed'); END")
    }}
}

fun PosRepository.expenseAttachment(tenantId:String,expenseId:String):ExpenseAttachmentRevision?=featureRead(tenantId,StaffPermission.MANAGE_MONEY) {
    requireExpenseEvidenceOwner(tenantId,expenseId)
    expenseEvidenceHistoryUnchecked(tenantId,expenseId).lastOrNull()
}
fun PosRepository.expenseAttachmentHistory(tenantId:String,expenseId:String):List<ExpenseAttachmentRevision> =featureRead(tenantId,StaffPermission.MANAGE_MONEY) {
    requireExpenseEvidenceOwner(tenantId,expenseId)
    expenseEvidenceHistoryUnchecked(tenantId,expenseId)
}
fun PosRepository.saveExpenseAttachment(
    tenantId:String,expenseId:String,upload:SupplierInvoiceAttachmentUpload?,expectedVersion:Long,reason:String,
    operationId:String=UUID.randomUUID().toString(),
):ExpenseAttachmentRevision=featureWrite(tenantId,StaffPermission.MANAGE_MONEY) {
    val expenseAt=requireExpenseEvidenceOwner(tenantId,expenseId)
    require(operationId.length in 1..200 && operationId.none {it.isISOControl()} && expectedVersion>=0) {"Use a valid receipt-change reference."}
    require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 250 printable characters."}
    val selected=upload?.reference
    val replay=featureQuery("SELECT * FROM expense_attachment_versions WHERE tenant_id=? AND id=?",tenantId,operationId) {expenseEvidenceRevision(tenantId,it)}.singleOrNull()
    if(replay!=null) {
        require(replay.expenseId==expenseId && replay.version==Math.addExact(expectedVersion,1) && replay.attachment==selected && replay.reason==reason) {"This expense receipt request was already saved with different details."}
        return@featureWrite replay
    }
    val old=expenseEvidenceHistoryUnchecked(tenantId,expenseId).lastOrNull()
    require((old?.version ?: 0L)==expectedVersion) {"This expense receipt changed while you were editing. Reload and review the latest version."}
    require(old?.attachment!=selected) {"Choose a different file before saving; there is no receipt change."}
    val now=Instant.now()
    require(!now.isBefore(expenseAt) && (old==null || !now.isBefore(Instant.parse(old.createdAt)))) {"The computer clock moved backwards. Correct it before changing expense evidence."}
    upload?.let {storeExpenseEvidence(tenantId,it,now)}
    val saved=ExpenseAttachmentRevision(operationId,expenseId,Math.addExact(expectedVersion,1),selected,reason,now.toString())
    featureExecute("INSERT INTO expense_attachment_versions VALUES(?,?,?,?,?,?,?,?)",tenantId,operationId,expenseId,saved.version,selected?.hash,selected?.fileName,reason,saved.createdAt)
    featureEvent(tenantId,"expense.attachment_changed",operationId)
    saved
}
fun PosRepository.expenseAttachmentBytes(tenantId:String,expenseId:String,version:Long?=null):ByteArray=featureRead(tenantId,StaffPermission.MANAGE_MONEY) {
    requireExpenseEvidenceOwner(tenantId,expenseId)
    val history=expenseEvidenceHistoryUnchecked(tenantId,expenseId)
    val revision=if(version==null) history.lastOrNull() else history.singleOrNull {it.version==version}
    val ref=requireNotNull(revision?.attachment) {"This expense receipt revision has no file."}
    expenseEvidenceBytesUnchecked(tenantId,ref)
}

internal fun PosRepository.validateExpenseAttachments(tenantId:String) {
    val expenseDates=featureQuery("SELECT id,created_at FROM expenses WHERE tenant_id=?",tenantId) {it.getString(1) to Instant.parse(it.getString(2))}.toMap()
    val ids=featureQuery("SELECT DISTINCT expense_id FROM expense_attachment_versions WHERE tenant_id=?",tenantId) {it.getString(1)}
    val referenced=mutableSetOf<String>();val operations=mutableSetOf<String>()
    ids.forEach {expenseId ->
        val expenseAt=requireNotNull(expenseDates[expenseId]) {"Backup expense receipt has no original expense."}
        val history=expenseEvidenceHistoryUnchecked(tenantId,expenseId)
        history.forEachIndexed {index,revision ->
            require(revision.version==index+1L && operations.add(revision.id)) {"Backup expense receipt history is incomplete or repeated."}
            require(revision.id.length in 1..200 && revision.id.none {it.isISOControl()} && revision.reason==revision.reason.trim() && revision.reason.length in 3..250 && revision.reason.none {it.isISOControl()}) {"Backup expense receipt change details are invalid."}
            val time=Instant.parse(revision.createdAt)
            require(!time.isBefore(expenseAt) && (index==0 || !time.isBefore(Instant.parse(history[index-1].createdAt)))) {"Backup expense receipt dates precede their source history."}
            require(revision.attachment!=history.getOrNull(index-1)?.attachment) {"Backup expense receipt history includes an empty change."}
            revision.attachment?.let {ref ->
                referenced+=ref.hash;expenseEvidenceBytesUnchecked(tenantId,ref)
                val assetAt=featureQuery("SELECT created_at FROM expense_attachment_assets WHERE tenant_id=? AND hash=?",tenantId,ref.hash) {Instant.parse(it.getString(1))}.single()
                require(!assetAt.isAfter(time)) {"Backup expense receipt file was recorded after its revision."}
            }
        }
    }
    val assets=featureQuery("SELECT hash,byte_size FROM expense_attachment_assets WHERE tenant_id=?",tenantId) {it.getString(1) to it.getLong(2)}
    require(assets.map {it.first}.toSet()==referenced && assets.fold(0L) {sum,row ->Math.addExact(sum,row.second)}<=EXPENSE_EVIDENCE_BUDGET) {"Backup expense evidence is orphaned, missing or exceeds the supported 8 MB history limit."}
    val activity=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='expense.attachment_changed'",tenantId) {it.getString(1)}
    require(activity.size==operations.size && activity.toSet()==operations) {"Backup expense receipt activity does not match its revision history."}
}

private fun PosRepository.requireExpenseEvidenceOwner(tenantId:String,expenseId:String):Instant =featureQuery("SELECT created_at FROM expenses WHERE tenant_id=? AND id=?",tenantId,expenseId) {Instant.parse(it.getString(1))}.singleOrNull() ?: error("Expense does not belong to this shop or is unavailable.")
private fun PosRepository.expenseEvidenceHistoryUnchecked(tenantId:String,expenseId:String)=featureQuery("SELECT * FROM expense_attachment_versions WHERE tenant_id=? AND expense_id=? ORDER BY version",tenantId,expenseId) {expenseEvidenceRevision(tenantId,it)}
private fun PosRepository.expenseEvidenceRevision(tenantId:String,row:ResultSet)=ExpenseAttachmentRevision(row.getString("id"),row.getString("expense_id"),row.getLong("version"),row.getString("attachment_hash")?.let {hash ->
    featureQuery("SELECT media_type,byte_size FROM expense_attachment_assets WHERE tenant_id=? AND hash=?",tenantId,hash) {SupplierInvoiceAttachmentRef(hash,it.getString(1),Math.toIntExact(it.getLong(2)),row.getString("attachment_name"))}.singleOrNull() ?: error("Expense receipt file is unavailable in this shop.")
},row.getString("reason"),row.getString("created_at"))
private fun PosRepository.expenseEvidenceBytesUnchecked(tenantId:String,ref:SupplierInvoiceAttachmentRef):ByteArray {
    val payload=featureQuery("SELECT payload FROM expense_attachment_assets WHERE tenant_id=? AND hash=?",tenantId,ref.hash) {it.getString(1)}.singleOrNull() ?: error("Expense receipt file is unavailable.")
    require(payload.length<=1_398_104) {"Saved expense receipt file exceeds the supported size."}
    val bytes=Base64.getDecoder().decode(payload)
    require(SupplierInvoiceAttachmentUpload.fromBytes(ref.fileName,bytes).reference==ref) {"Saved expense receipt failed its integrity check."}
    return bytes
}
private fun PosRepository.storeExpenseEvidence(tenantId:String,upload:SupplierInvoiceAttachmentUpload,now:Instant) {
    val ref=upload.reference;val bytes=upload.bytes();val payload=Base64.getEncoder().encodeToString(bytes)
    val old=featureQuery("SELECT payload,created_at FROM expense_attachment_assets WHERE tenant_id=? AND hash=?",tenantId,ref.hash) {it.getString(1) to Instant.parse(it.getString(2))}.singleOrNull()
    if(old!=null) require(old.first==payload && !old.second.isAfter(now)) {"Receipt bytes conflict with saved evidence or the computer clock moved backwards."}
    else {
        val total=featureQuery("SELECT COALESCE(SUM(byte_size),0) FROM expense_attachment_assets WHERE tenant_id=?",tenantId) {it.getLong(1)}.single()
        require(Math.addExact(total,ref.byteSize.toLong())<=EXPENSE_EVIDENCE_BUDGET) {"This shop has reached its 8 MB expense receipt history limit. No evidence changed."}
        featureExecute("INSERT INTO expense_attachment_assets VALUES(?,?,?,?,?,?)",tenantId,ref.hash,ref.mediaType,ref.byteSize,payload,now.toString())
    }
}
