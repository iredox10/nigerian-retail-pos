package pos.data

import pos.domain.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal val stockCountSessionSnapshotTables=listOf("stock_count_sessions","stock_count_session_lines")

internal fun PosRepository.initializeStockCountSessions() {
    featureSchema("CREATE TABLE IF NOT EXISTS stock_count_sessions(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),name TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN('DRAFT','POSTED','CANCELLED')),reason TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),created_at TEXT NOT NULL,completed_at TEXT,PRIMARY KEY(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS stock_count_session_lines(tenant_id TEXT NOT NULL,session_id TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,unit TEXT NOT NULL,precision INTEGER NOT NULL CHECK(precision BETWEEN 0 AND 6),baseline_scaled INTEGER NOT NULL CHECK(baseline_scaled>=0),baseline_token TEXT NOT NULL,counted_scaled INTEGER CHECK(counted_scaled>=0 AND counted_scaled<=2147483647),position INTEGER NOT NULL CHECK(position>=0),PRIMARY KEY(tenant_id,session_id,product_id),UNIQUE(tenant_id,session_id,position),FOREIGN KEY(tenant_id,session_id) REFERENCES stock_count_sessions(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
    featureSchema("CREATE TRIGGER IF NOT EXISTS stock_count_session_final_no_update BEFORE UPDATE ON stock_count_sessions WHEN OLD.status<>'DRAFT' BEGIN SELECT RAISE(ABORT,'Completed stock counts cannot be edited'); END")
    featureSchema("CREATE TRIGGER IF NOT EXISTS stock_count_session_no_delete BEFORE DELETE ON stock_count_sessions BEGIN SELECT RAISE(ABORT,'Stock count history cannot be deleted'); END")
    listOf("UPDATE","DELETE").forEach { action ->
        featureSchema("CREATE TRIGGER IF NOT EXISTS stock_count_lines_final_no_${action.lowercase()} BEFORE $action ON stock_count_session_lines WHEN (SELECT status FROM stock_count_sessions WHERE tenant_id=OLD.tenant_id AND id=OLD.session_id)<>'DRAFT' BEGIN SELECT RAISE(ABORT,'Completed stock count lines cannot be edited'); END")
    }
}

fun PosRepository.stockCountSessions(tenantId:String):List<StockCountSession> = featureRead(tenantId,StaffPermission.MANAGE_STOCK) { countSessionsUnchecked(tenantId) }

fun PosRepository.createStockCountSession(tenantId:String,name:String,productIds:List<String>,operationId:String=UUID.randomUUID().toString()):StockCountSession = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    validCountId(operationId)
    require(productIds.size in 1..500 && productIds.distinct().size==productIds.size) {"Choose from 1 to 500 different items."}
    val old=countSessionUnchecked(tenantId,operationId)
    if(old!=null) {
        require(old.name==name.trim() && old.lines.map {it.productId}==productIds) {"This stock-count request was already saved with different details."}
        return@featureWrite old
    }
    val items=products(tenantId,true).associateBy {it.id}
    val lines=productIds.mapIndexed {index,id ->
        val item=requireNotNull(items[id]) {"An item does not belong to this shop."}
        require(!item.archived) {"Restore archived items before counting them."}
        require(item.stockScaled!=null || item.stock==item.stock.toLong().toDouble()) {"Reconcile the legacy fractional balance for ${item.name} using its single-item count before starting this session."}
        StockCountLine(id,item.name,item.unit,item.quantityPrecision,item.exactStockScaled,countBaselineToken(tenantId,item),null,index)
    }
    val session=StockCountSession(operationId,tenantId,name.trim(),StockCountStatus.DRAFT,"",1,Instant.now().toString(),null,lines)
    featureExecute("INSERT INTO stock_count_sessions VALUES(?,?,?,?,?,?,?,?)",session.id,tenantId,session.name,session.status.name,"",1,session.createdAt,null)
    lines.forEach {line -> featureExecute("INSERT INTO stock_count_session_lines VALUES(?,?,?,?,?,?,?,?,?,?)",tenantId,session.id,line.productId,line.productName,line.unit,line.precision,line.baselineScaled,line.baselineToken,null,line.position)}
    featureEvent(tenantId,"stock_count.started",session.id)
    session
}

fun PosRepository.saveStockCountDraft(tenantId:String,sessionId:String,counts:Map<String,Int?>,reason:String,expectedVersion:Long):StockCountSession = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    val old=requireCountSession(tenantId,sessionId)
    require(counts.keys==old.lines.map {it.productId}.toSet()) {"Count entries do not match this session's items."}
    if(old.status==StockCountStatus.DRAFT && old.version==Math.addExact(expectedVersion,1) && old.reason==reason.trim() && old.lines.all {it.countedScaled==counts[it.productId]}) return@featureWrite old
    require(old.status==StockCountStatus.DRAFT && old.version==expectedVersion) {"This count changed or was completed. Reopen it before editing."}
    val changed=old.copy(reason=reason.trim(),version=Math.addExact(old.version,1),lines=old.lines.map {it.copy(countedScaled=counts[it.productId])})
    changed.lines.forEach {featureExecute("UPDATE stock_count_session_lines SET counted_scaled=? WHERE tenant_id=? AND session_id=? AND product_id=?",it.countedScaled,tenantId,sessionId,it.productId)}
    featureExecute("UPDATE stock_count_sessions SET reason=?,version=? WHERE tenant_id=? AND id=?",changed.reason,changed.version,tenantId,sessionId)
    featureEvent(tenantId,"stock_count.draft_saved",sessionId)
    changed
}

/** Conservative cutoff: any intervening movement, including net-zero sale/return, requires recount. */
fun PosRepository.postStockCountSession(tenantId:String,sessionId:String,expectedVersion:Long):StockCountSession = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    val old=requireCountSession(tenantId,sessionId)
    if(old.status==StockCountStatus.POSTED) {
        require(old.version==Math.addExact(expectedVersion,1)) {"This count was already posted from a different revision."}
        return@featureWrite old
    }
    require(old.status==StockCountStatus.DRAFT && old.version==expectedVersion) {"This count changed or was cancelled. Reopen it before posting."}
    require(old.reason.isNotBlank() && old.lines.all {it.countedScaled!=null}) {"Enter every physical count and a reason, then save the draft before posting."}
    val now=Instant.now()
    require(!now.isBefore(Instant.parse(old.createdAt))) {"The computer clock moved backwards. Correct it before posting."}
    val items=products(tenantId,true).associateBy {it.id}
    old.lines.forEach {line ->
        val item=requireNotNull(items[line.productId]) {"A counted item is unavailable."}
        require(!item.archived && item.name==line.productName && item.unit==line.unit && item.quantityPrecision==line.precision && item.exactStockScaled==line.baselineScaled && countBaselineToken(tenantId,item)==line.baselineToken) {"Stock or item details changed for ${line.productName} after this count started. Cancel this session and start a fresh recount; nothing has been posted."}
    }
    old.lines.forEach {line ->
        adjustStock(tenantId,line.productId,requireNotNull(line.countedScaled),old.reason,countAdjustmentId(sessionId,line.productId),expectedStock=items.getValue(line.productId).stock)
    }
    val completed=Instant.now(); require(!completed.isBefore(now)) {"The computer clock moved backwards. No count was posted."}
    val result=old.copy(status=StockCountStatus.POSTED,version=Math.addExact(old.version,1),completedAt=completed.toString())
    featureExecute("UPDATE stock_count_sessions SET status='POSTED',version=?,completed_at=? WHERE tenant_id=? AND id=?",result.version,result.completedAt,tenantId,sessionId)
    featureEvent(tenantId,"stock_count.posted",sessionId)
    result
}

fun PosRepository.cancelStockCountSession(tenantId:String,sessionId:String,reason:String,expectedVersion:Long):StockCountSession = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    val old=requireCountSession(tenantId,sessionId)
    if(old.status==StockCountStatus.CANCELLED) {
        require(old.reason==reason.trim() && old.version==Math.addExact(expectedVersion,1)) {"This count was already cancelled with different details."}
        return@featureWrite old
    }
    require(old.status==StockCountStatus.DRAFT && old.version==expectedVersion) {"Only the current unfinished count can be cancelled."}
    val now=Instant.now();require(!now.isBefore(Instant.parse(old.createdAt))) {"The computer clock moved backwards. Correct it before cancelling."}
    val result=old.copy(status=StockCountStatus.CANCELLED,reason=reason.trim(),version=Math.addExact(old.version,1),completedAt=now.toString())
    featureExecute("UPDATE stock_count_sessions SET status='CANCELLED',reason=?,version=?,completed_at=? WHERE tenant_id=? AND id=?",result.reason,result.version,result.completedAt,tenantId,sessionId)
    featureEvent(tenantId,"stock_count.cancelled",sessionId)
    result
}

/** Recovery runs under its original-tenant backup guard, so this validator uses direct scoped reads. */
internal fun PosRepository.validateStockCountSessions(tenantId:String) {
    val items=products(tenantId,true).associateBy {it.id}
    val adjustments=stockAdjustments(tenantId).associateBy {it.id}
    val sessions=countSessionsUnchecked(tenantId)
    val events=featureQuery("SELECT kind,entity_id FROM outbox WHERE tenant_id=? AND kind GLOB 'stock_count.*'",tenantId) {it.getString(1) to it.getString(2)}
    require(events.all {it.second in sessions.map {session ->session.id}}) {"Backup stock-count activity refers to a missing count."}
    sessions.forEach {session ->
        val history=events.filter {it.second==session.id}.map {it.first}
        val terminal=when(session.status) {StockCountStatus.DRAFT->null;StockCountStatus.POSTED->"stock_count.posted";StockCountStatus.CANCELLED->"stock_count.cancelled"}
        require(history.count {it=="stock_count.started"}==1 && history.all {it=="stock_count.started" || it=="stock_count.draft_saved" || it==terminal} && (terminal==null || history.count {it==terminal}==1)) {"Backup stock-count status does not match its activity history."}
        require(session.version==1L+history.count {it=="stock_count.draft_saved"}+(if(terminal==null) 0 else 1)) {"Backup stock-count revision does not match its activity history."}
        val start=Instant.parse(session.createdAt)
        val end=session.completedAt?.let(Instant::parse)
        require(end==null || end>=start) {"Backup stock-count completion predates its start."}
        session.lines.forEach {line ->
            val item=requireNotNull(items[line.productId]) {"Backup stock-count item is missing."}
            require(line.unit==item.unit && line.precision==item.quantityPrecision) {"Backup stock-count unit or precision differs from its item."}
            val adjustment=adjustments[countAdjustmentId(session.id,line.productId)]
            if(session.status==StockCountStatus.POSTED) {
                requireNotNull(adjustment) {"Backup posted stock count has no linked correction."}
                require(adjustment.productId==line.productId && adjustment.productName==line.productName && adjustment.unit==line.unit && adjustment.quantityPrecision==line.precision && adjustment.countedStock==line.countedScaled && adjustment.reason==session.reason && adjustment.previousStock==projectedQuantity(line.baselineScaled,line.precision)) {"Backup posted stock-count correction does not match its saved count."}
                require(Instant.parse(adjustment.createdAt)>=start && (end==null || Instant.parse(adjustment.createdAt)<=end)) {"Backup stock-count correction has an invalid recorded time."}
            } else require(adjustment==null) {"Backup unfinished or cancelled count already changed stock."}
        }
    }
}

private fun PosRepository.countBaselineToken(tenantId:String,item:Product):String {
    fun fields(values:List<String>)=values.joinToString("") {it.length.toString()+":"+it}
    val records=listOf(listOf("product",item.id,item.name,item.unit,item.quantityPrecision.toString(),item.stock.toString(),item.exactStockScaled.toString(),item.archived.toString()))+
        inventoryMovements(tenantId,item.id).map {listOf("movement",it.id,it.kind,it.sourceId,it.quantityDelta.toString(),it.createdAt)}+
        featureQuery("SELECT id,created_at FROM catalogue_changes WHERE tenant_id=? AND product_id=? AND kind='archive' ORDER BY created_at,id",tenantId,item.id) {listOf("archive",it.getString(1),it.getString(2))}
    val text=records.joinToString("") {fields(listOf(it.size.toString())+it)}
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}
private fun validCountId(id:String) {require(id.length in 1..100 && id.none {it.isISOControl()}) {"Stock-count reference is invalid."}}
private fun countAdjustmentId(sessionId:String,productId:String)="count-session:"+MessageDigest.getInstance("SHA-256").digest((sessionId.length.toString()+":"+sessionId+productId).toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
private fun PosRepository.requireCountSession(tenantId:String,id:String)=requireNotNull(countSessionUnchecked(tenantId,id)) {"Stock count does not belong to this shop."}
private fun PosRepository.countSessionsUnchecked(tenantId:String):List<StockCountSession> = featureQuery("SELECT id FROM stock_count_sessions WHERE tenant_id=? ORDER BY created_at DESC,id",tenantId) {it.getString(1)}.map {requireCountSession(tenantId,it)}
private fun PosRepository.countSessionUnchecked(tenantId:String,id:String):StockCountSession? = featureQuery("SELECT * FROM stock_count_sessions WHERE tenant_id=? AND id=?",tenantId,id) {row ->
    val lines=featureQuery("SELECT * FROM stock_count_session_lines WHERE tenant_id=? AND session_id=? ORDER BY position",tenantId,id) {r ->
        StockCountLine(r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getInt("precision"),r.getLong("baseline_scaled"),r.getString("baseline_token"),(r.getObject("counted_scaled") as? Number)?.toInt(),r.getInt("position"))
    }
    StockCountSession(row.getString("id"),tenantId,row.getString("name"),StockCountStatus.valueOf(row.getString("status")),row.getString("reason"),row.getLong("version"),row.getString("created_at"),row.getString("completed_at"),lines)
}.singleOrNull()
