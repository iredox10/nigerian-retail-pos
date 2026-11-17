package pos.data

import pos.domain.*
import java.time.Instant
import java.util.UUID

internal val ownerCashSnapshotTables=listOf("owner_cash_records")
internal fun PosRepository.initializeOwnerCash() {
    featureSchema("CREATE TABLE IF NOT EXISTS owner_cash_records(tenant_id TEXT NOT NULL REFERENCES tenants(id),id TEXT NOT NULL,kind TEXT NOT NULL,amount_minor INTEGER NOT NULL,owner_name TEXT NOT NULL,reason TEXT NOT NULL,shift_id TEXT NOT NULL,cash_entry_id TEXT NOT NULL,created_at TEXT NOT NULL,before_cash_minor INTEGER NOT NULL,reverses_id TEXT,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,cash_entry_id),UNIQUE(tenant_id,reverses_id),FOREIGN KEY(tenant_id,shift_id) REFERENCES cash_shifts(tenant_id,id),FOREIGN KEY(tenant_id,cash_entry_id) REFERENCES cash_entries(tenant_id,id),FOREIGN KEY(tenant_id,reverses_id) REFERENCES owner_cash_records(tenant_id,id))")
    listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS owner_cash_records_no_${action.lowercase()} BEFORE $action ON owner_cash_records BEGIN SELECT RAISE(ABORT,'Owner cash history cannot be edited or removed'); END")}
}
fun PosRepository.ownerCashRecords(tenantId:String):List<OwnerCashRecord> =featureRead(tenantId,StaffPermission.MANAGE_MONEY) {ownerCashRows(tenantId)}
fun PosRepository.recordOwnerCash(tenantId:String,kind:String,amountMinor:Long,ownerName:String,reason:String,expectedShiftId:String,expectedCashMinor:Long,operationId:String=UUID.randomUUID().toString()):OwnerCashRecord =featureWrite(tenantId,StaffPermission.MANAGE_MONEY) {
    require(kind in setOf("CONTRIBUTION","DRAWING") && amountMinor>0) {"Choose owner money added or taken and enter a positive amount."}
    writeOwnerCash(tenantId,kind,if(kind=="DRAWING") Math.negateExact(amountMinor) else amountMinor,ownerName,reason,expectedShiftId,expectedCashMinor,null,operationId)
}
fun PosRepository.reverseOwnerCash(tenantId:String,originalId:String,reason:String,expectedShiftId:String,expectedCashMinor:Long,operationId:String=UUID.randomUUID().toString()):OwnerCashRecord =featureWrite(tenantId,StaffPermission.MANAGE_MONEY) {
    val original=ownerCashRows(tenantId).singleOrNull {it.id==originalId} ?: error("Original owner cash record is unavailable in this shop.")
    require(original.kind!="REVERSAL") {"A correction cannot itself be undone. Record the actual new owner movement instead."}
    writeOwnerCash(tenantId,"REVERSAL",Math.negateExact(original.amountMinor),original.ownerName,reason,expectedShiftId,expectedCashMinor,original.id,operationId)
}
private fun ownerCashSource(id:String)="owner-cash:$id"
private fun ownerCashDescription(kind:String,owner:String,reason:String)=when(kind) {"CONTRIBUTION" ->"Owner contribution";"DRAWING" ->"Owner drawing";else ->"Owner cash correction"}+": $owner · $reason"
private fun PosRepository.writeOwnerCash(tenantId:String,kind:String,signedAmount:Long,owner:String,reason:String,expectedShiftId:String,expectedCash:Long,reverses:String?,operationId:String):OwnerCashRecord {
    require(operationId.length in 1..200 && operationId.none {it.isISOControl()}) {"Owner cash reference is invalid."}
    val existing=ownerCashRows(tenantId).singleOrNull {it.id==operationId}
    if(existing!=null) {
        require(existing.kind==kind && existing.amountMinor==signedAmount && existing.ownerName==owner.trim() && existing.reason==reason.trim() && existing.shiftId==expectedShiftId && existing.beforeCashMinor==expectedCash && existing.reversesId==reverses) {"This owner cash request was already recorded with different details."}
        return existing
    }
    if(reverses!=null) require(ownerCashRows(tenantId).none {it.reversesId==reverses}) {"This owner movement already has a correction."}
    val shift=requireNotNull(activeCashShift(tenantId)) {"Open a cash drawer before recording owner money."}
    require(shift.id==expectedShiftId && expectedCash(tenantId,expectedShiftId)==expectedCash) {"The drawer or cash balance changed. Refresh and review before saving."}
    val now=Instant.now()
    val latest=cashEntries(tenantId).maxOfOrNull {Instant.parse(it.createdAt)}
    require(!now.isBefore(Instant.parse(shift.openedAt)) && (latest==null || !now.isBefore(latest))) {"The computer clock moved backwards. Correct it before recording owner money."}
    // Validate all input before asking the existing ledger to write anything.
    OwnerCashRecord(operationId,tenantId,kind,signedAmount,owner.trim(),reason.trim(),expectedShiftId,"pending",now.toString(),expectedCash,reverses)
    val source=ownerCashSource(operationId)
    require(featureQuery("SELECT id FROM cash_entries WHERE tenant_id=? AND kind='manual' AND source_id=?",tenantId,source) {it.getString(1)}.isEmpty()) {"This owner cash reference conflicts with another recorded movement."}
    val cash=recordCashMovement(tenantId,signedAmount,ownerCashDescription(kind,owner.trim(),reason.trim()),source)
    val result=OwnerCashRecord(operationId,tenantId,kind,signedAmount,owner.trim(),reason.trim(),expectedShiftId,cash.id,cash.createdAt,expectedCash,reverses)
    featureExecute("INSERT INTO owner_cash_records VALUES(?,?,?,?,?,?,?,?,?,?,?)",tenantId,result.id,result.kind,result.amountMinor,result.ownerName,result.reason,result.shiftId,result.cashEntryId,result.createdAt,result.beforeCashMinor,result.reversesId)
    featureEvent(tenantId,"owner_cash.recorded",result.id)
    return result
}
private fun PosRepository.ownerCashRows(tenantId:String)=featureQuery("SELECT * FROM owner_cash_records WHERE tenant_id=? ORDER BY created_at,id",tenantId) {OwnerCashRecord(it.getString("id"),tenantId,it.getString("kind"),it.getLong("amount_minor"),it.getString("owner_name"),it.getString("reason"),it.getString("shift_id"),it.getString("cash_entry_id"),it.getString("created_at"),it.getLong("before_cash_minor"),it.getString("reverses_id"))}
internal fun PosRepository.validateOwnerCash(tenantId:String) {
    val rows=ownerCashRows(tenantId)
    val events=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='owner_cash.recorded'",tenantId) {it.getString(1)}
    require(events.size==rows.size && events.toSet()==rows.map {it.id}.toSet()) {"Backup owner cash records have incomplete activity."}
    val cash=cashEntries(tenantId).associateBy {it.id}
    rows.forEach {row ->
        val source=featureQuery("SELECT source_id FROM cash_entries WHERE tenant_id=? AND id=?",tenantId,row.cashEntryId) {it.getString(1)}.singleOrNull()
        val entry=requireNotNull(cash[row.cashEntryId]) {"Backup owner movement has no cash entry."}
        require(entry.kind=="manual" && entry.shiftId==row.shiftId && entry.amountMinor==row.amountMinor && entry.createdAt==row.createdAt && entry.description==ownerCashDescription(row.kind,row.ownerName,row.reason) && source==ownerCashSource(row.id)) {"Backup owner cash details differ from their ledger source."}
        Instant.parse(row.createdAt)
        if(row.reversesId!=null) {
            val original=requireNotNull(rows.singleOrNull {it.id==row.reversesId}) {"Backup owner correction has no original."}
            require(original.kind!="REVERSAL" && original.amountMinor==Math.negateExact(row.amountMinor) && original.ownerName==row.ownerName && !Instant.parse(row.createdAt).isBefore(Instant.parse(original.createdAt))) {"Backup owner correction differs from its original."}
        }
    }
}
