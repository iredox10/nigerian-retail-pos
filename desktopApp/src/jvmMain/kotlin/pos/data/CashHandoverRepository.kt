package pos.data

import java.time.Instant
import pos.domain.*
import pos.security.CashHandoverProof

internal val cashHandoverSnapshotTables=listOf("cash_handovers")
internal fun PosRepository.initializeCashHandovers() {
    featureSchema("""CREATE TABLE IF NOT EXISTS cash_handovers(tenant_id TEXT NOT NULL,id TEXT NOT NULL,closed_shift_id TEXT NOT NULL,opened_shift_id TEXT NOT NULL,
        grant_id TEXT NOT NULL,source_tenant_id TEXT NOT NULL,proof TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),
        UNIQUE(tenant_id,closed_shift_id),UNIQUE(tenant_id,opened_shift_id),UNIQUE(tenant_id,grant_id),
        FOREIGN KEY(tenant_id,closed_shift_id) REFERENCES cash_shifts(tenant_id,id),FOREIGN KEY(tenant_id,opened_shift_id) REFERENCES cash_shifts(tenant_id,id))""")
    listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS cash_handovers_no_${action.lowercase()} BEFORE $action ON cash_handovers BEGIN SELECT RAISE(ABORT,'Cash handover history cannot be edited'); END")}
}
fun PosRepository.cashHandovers(tenantId:String):List<CashHandover> =featureRead(tenantId,StaffPermission.MANAGE_MONEY) {handoverRows(tenantId)}

/** The recipient accepts the counted cash. Any shortage remains recorded on the closing shift. */
fun PosRepository.handoverCash(tenantId:String,request:CashHandoverRequest,grantId:String):CashHandover =featureWrite(tenantId,StaffPermission.MANAGE_MONEY) {
    require(request.tenantId==tenantId) {"This handover belongs to another shop."}
    val replay=handoverRows(tenantId).singleOrNull {it.id==request.operationId}
    if(replay!=null) {
        require(replay.witness.request==request && replay.witness.id==grantId) {"This handover reference was saved with different details."}
        return@featureWrite replay
    }
    val active=requireNotNull(activeCashShift(tenantId)) {"Open a cash shift before handing over the drawer."}
    require(active.id==request.shiftId && expectedCash(tenantId,active.id)==request.expectedMinor) {"The shift or drawer balance changed. Recount and request fresh consent."}
    val witness=featureAuthorizeCashHandover(tenantId,grantId,request)
    CashHandoverProof.verify(witness)
    val now=Instant.now()
    require(witness.id==grantId && witness.request==request && !now.isBefore(Instant.parse(witness.acceptedAt)) && now.isBefore(Instant.parse(witness.expiresAt))) {"Recipient consent expired or the handover changed."}
    val count=request.countSheet?.let(CashCount::decode)
    val closed=closeCashShift(tenantId,active.id,request.countedMinor,count)
    val opened=openCashShift(tenantId,request.countedMinor,count)
    val completed=Instant.parse(opened.openedAt)
    require(!Instant.parse(requireNotNull(closed.closedAt)).isBefore(Instant.parse(witness.acceptedAt)) && !completed.isBefore(Instant.parse(closed.closedAt)) && completed.isBefore(Instant.parse(witness.expiresAt))) {"The clock changed or recipient consent expired while saving. No handover was recorded."}
    val result=CashHandover(request.operationId,tenantId,closed.id,opened.id,witness,opened.openedAt)
    featureExecute("INSERT INTO cash_handovers VALUES(?,?,?,?,?,?,?,?)",tenantId,result.id,closed.id,opened.id,witness.id,tenantId,CashHandoverProof.encode(witness),result.createdAt)
    featureEvent(tenantId,"cash.handover_completed",result.id)
    result
}
private fun PosRepository.handoverRows(tenantId:String):List<CashHandover> =featureQuery("SELECT * FROM cash_handovers WHERE tenant_id=? ORDER BY created_at,id",tenantId) {row ->
    val witness=CashHandoverProof.decode(row.getString("proof"))
    require(witness.id==row.getString("grant_id") && witness.request.tenantId==row.getString("source_tenant_id") && witness.request.operationId==row.getString("id") && witness.request.shiftId==row.getString("closed_shift_id")) {"Cash handover evidence scope is inconsistent."}
    CashHandover(row.getString("id"),tenantId,row.getString("closed_shift_id"),row.getString("opened_shift_id"),witness,row.getString("created_at"))
}
internal fun PosRepository.validateCashHandovers(tenantId:String) {
    val records=handoverRows(tenantId)
    val events=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='cash.handover_completed'",tenantId) {it.getString(1)}
    require(events.size==records.size && events.toSet()==records.map {it.id}.toSet()) {"Backup cash handover activity is incomplete."}
    val shifts=cashShifts(tenantId).associateBy {it.id}
    records.forEach {record ->
        val closed=requireNotNull(shifts[record.closedShiftId]) {"Cash handover closing shift is missing."}
        val opened=requireNotNull(shifts[record.openedShiftId]) {"Cash handover receiving shift is missing."}
        val request=record.witness.request;val at=Instant.parse(record.createdAt)
        require(closed.id!=opened.id && closed.closedAt!=null && closed.countedMinor==request.countedMinor && closed.expectedMinor==request.expectedMinor &&
            closed.closingCount?.encode()==request.countSheet && !closed.blindClosing && opened.openingMinor==request.countedMinor && opened.openingCount?.encode()==request.countSheet &&
            !Instant.parse(closed.closedAt).isAfter(at) && opened.openedAt==record.createdAt &&
            !Instant.parse(closed.closedAt).isBefore(Instant.parse(record.witness.acceptedAt)) && !at.isBefore(Instant.parse(record.witness.acceptedAt)) && at.isBefore(Instant.parse(record.witness.expiresAt))) {"Backup cash handover does not match its signed counts and shifts."}
    }
}
