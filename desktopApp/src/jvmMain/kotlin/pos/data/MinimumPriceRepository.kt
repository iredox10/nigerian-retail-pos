package pos.data

import pos.domain.*
import java.io.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import pos.security.MinimumPriceApprovalProof

internal val minimumPriceSnapshotTables=listOf("minimum_price_policies","minimum_price_changes","sale_minimum_price_checks","sale_minimum_price_approvals")
internal fun PosRepository.initializeMinimumPrices() {
    if("minimum_price_captured" !in featureQuery("PRAGMA table_info(sales)") {it.getString("name")}) featureSchema("ALTER TABLE sales ADD COLUMN minimum_price_captured INTEGER NOT NULL DEFAULT 0 CHECK(minimum_price_captured IN(0,1))")
    featureSchema("CREATE TABLE IF NOT EXISTS minimum_price_policies(tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,unit_key TEXT NOT NULL,details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),updated_at TEXT NOT NULL,PRIMARY KEY(tenant_id,product_id,unit_key),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS minimum_price_changes(tenant_id TEXT NOT NULL,id TEXT NOT NULL,product_id TEXT NOT NULL,unit_key TEXT NOT NULL,previous_details TEXT,current_details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,product_id,unit_key,version),FOREIGN KEY(tenant_id,product_id,unit_key) REFERENCES minimum_price_policies(tenant_id,product_id,unit_key))")
    featureSchema("CREATE TABLE IF NOT EXISTS sale_minimum_price_checks(tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,line_key TEXT NOT NULL,product_id TEXT NOT NULL,unit_key TEXT NOT NULL,policy_version INTEGER NOT NULL,required_minor INTEGER NOT NULL CHECK(required_minor>=0),PRIMARY KEY(tenant_id,sale_id,line_key,product_id,unit_key),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),FOREIGN KEY(tenant_id,product_id,unit_key,policy_version) REFERENCES minimum_price_changes(tenant_id,product_id,unit_key,version))")
    featureSchema("CREATE TABLE IF NOT EXISTS sale_minimum_price_approvals(tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,grant_id TEXT NOT NULL,source_tenant_id TEXT NOT NULL,proof TEXT NOT NULL,PRIMARY KEY(tenant_id,sale_id),UNIQUE(tenant_id,grant_id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id))")
    listOf("minimum_price_changes","sale_minimum_price_checks","sale_minimum_price_approvals").forEach {table ->listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Minimum-price history cannot be edited or removed'); END")}}
}
fun PosRepository.minimumPricePolicies(tenantId:String):List<MinimumPricePolicy> =featureRead(tenantId,StaffPermission.MANAGE_SETTINGS) {minimumPolicies(tenantId)}
fun PosRepository.minimumPriceHistory(tenantId:String,productId:String,sellingUnitId:String?):List<MinimumPriceChange> =featureRead(tenantId,StaffPermission.MANAGE_SETTINGS) {
    validateFloorOwner(tenantId,productId,sellingUnitId,false)
    minimumChanges(tenantId).filter {it.current.productId==productId && it.current.sellingUnitId==sellingUnitId}
}
fun PosRepository.saveMinimumPrice(tenantId:String,productId:String,sellingUnitId:String?,minimumUnitPriceMinor:Long?,expectedVersion:Long,reason:String,operationId:String=UUID.randomUUID().toString()):MinimumPricePolicy=featureWrite(tenantId,StaffPermission.MANAGE_SETTINGS) {
    require(operationId.length in 1..200 && operationId.none {it.isISOControl()} && expectedVersion>=0) {"Minimum-price request reference is invalid."}
    require(minimumUnitPriceMinor==null || minimumUnitPriceMinor>=0) {"Minimum price cannot be negative."}
    val history=minimumChanges(tenantId)
    val replay=history.singleOrNull {it.id==operationId}
    if(replay!=null) {
        require(replay.current.productId==productId && replay.current.sellingUnitId==sellingUnitId && replay.current.minimumUnitPriceMinor==minimumUnitPriceMinor && replay.version==Math.addExact(expectedVersion,1) && replay.reason==reason) {"This minimum-price request was saved with different details."}
        return@featureWrite minimumPolicies(tenantId).single {it.terms.key==replay.current.key}
    }
    val terms=validateFloorOwner(tenantId,productId,sellingUnitId,true).copy(minimumUnitPriceMinor=minimumUnitPriceMinor)
    val old=minimumPolicies(tenantId).singleOrNull {it.terms.key==terms.key}
    require((old?.version ?: 0)==expectedVersion && (old?.terms!=terms) && (old!=null || minimumUnitPriceMinor!=null)) {"The minimum price changed, or nothing was edited. Reload and review it."}
    val now=Instant.now()
    val latestSale=sales(tenantId).maxOfOrNull {Instant.parse(it.createdAt)}
    require(latestSale==null || !now.isBefore(latestSale)) {"The computer clock is earlier than a saved sale. Correct it before changing minimum prices."}
    require(old==null || !now.isBefore(Instant.parse(old.updatedAt))) {"Correct the computer clock before changing minimum prices."}
    val change=MinimumPriceChange(operationId,tenantId,old?.terms,terms,Math.addExact(expectedVersion,1),reason,now.toString())
    featureExecute("INSERT INTO minimum_price_policies VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,product_id,unit_key) DO UPDATE SET details=excluded.details,version=excluded.version,updated_at=excluded.updated_at",tenantId,productId,sellingUnitId.orEmpty(),encodeMinimumTerms(terms),change.version,change.createdAt)
    featureExecute("INSERT INTO minimum_price_changes VALUES(?,?,?,?,?,?,?,?,?)",tenantId,operationId,productId,sellingUnitId.orEmpty(),old?.terms?.let(::encodeMinimumTerms),encodeMinimumTerms(terms),change.version,change.reason,change.createdAt)
    featureEvent(tenantId,"minimum_price.changed",operationId)
    MinimumPricePolicy(tenantId,terms,change.version,change.createdAt)
}

/** Review the normalized final cart after allocating any basket discount. */
fun PosRepository.minimumPriceApprovalRequest(tenantId:String,operationId:String,lines:List<CartLine>):MinimumPriceApprovalRequest = featureRead(tenantId,StaffPermission.SELL) {
    require(lines.all {it.product.tenantId==tenantId}) {"Sale items belong to another shop."}
    approvalRequest(tenantId,operationId,lines,currentApprovalChecks(tenantId,lines))
}
private fun PosRepository.currentApprovalChecks(tenantId:String,lines:List<CartLine>):List<MinimumPriceApprovalCheck> = minimumPolicies(tenantId).flatMap {policy ->
    lines.mapNotNull {line ->policy.terms.minimumFor(line)?.let {required -> MinimumPriceApprovalCheck(line.key,policy.terms.productId,policy.terms.sellingUnitId.orEmpty(),policy.version,required)}}
}.sortedWith(compareBy({it.lineKey},{it.productId},{it.unitKey}))
private fun approvalRequest(tenantId:String,operationId:String,lines:List<CartLine>,checks:List<MinimumPriceApprovalCheck>):MinimumPriceApprovalRequest {
    val sorted=lines.sortedBy {it.key}
    val bytes=ByteArrayOutputStream()
    DataOutputStream(bytes).use {out ->
        out.writeInt(1);out.writeUTF(tenantId);out.writeUTF(operationId);out.writeInt(sorted.size)
        sorted.forEach {line ->
            out.writeUTF(line.product.id);out.writeUTF(line.product.name);out.writeUTF(line.product.unit);out.writeLong(line.product.priceMinor);out.writeInt(line.product.quantityPrecision)
            out.writeInt(line.quantity);out.writeInt(line.quantityPrecision);out.writeBoolean(line.sellingUnitId!=null);line.sellingUnitId?.let(out::writeUTF)
            out.writeUTF(line.sellingUnit);out.writeLong(line.unitPriceMinor);out.writeInt(line.baseUnitsPerUnit);out.writeLong(line.discountPerUnitMinor)
            out.writeBoolean(line.discountReason!=null);line.discountReason?.let(out::writeUTF);out.writeLong(line.basketDiscountMinor);out.writeLong(line.totalMinor)
        }
        out.writeInt(checks.size);checks.forEach {out.writeUTF(it.lineKey);out.writeUTF(it.productId);out.writeUTF(it.unitKey);out.writeLong(it.policyVersion);out.writeLong(it.requiredMinor)}
    }
    val fingerprint=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
    return MinimumPriceApprovalRequest(tenantId,operationId,fingerprint,sorted.map {MinimumPriceApprovalLine(it.key,it.product.id,it.product.name,it.quantityLabel,it.sellingUnit,it.totalMinor)},checks,cartTotal(lines),lines.any {it.discountPerUnitMinor>0 || it.basketDiscountMinor>0})
}
private fun validateApproval(approval:MinimumPriceApprovalWitness,request:MinimumPriceApprovalRequest,time:Instant) {
    MinimumPriceApprovalProof.verify(approval)
    require(approval.request==request && !time.isBefore(Instant.parse(approval.approvedAt)) && time.isBefore(Instant.parse(approval.expiresAt))) {"Price approval expired or the sale changed. Ask for a new approval."}
}
/** Must receive normalized final lines after their basket-discount allocations. Caller owns sale transaction. */
internal fun PosRepository.validateMinimumPricesAtCheckout(tenantId:String,lines:List<CartLine>,approval:MinimumPriceApprovalWitness?=null,operationId:String?=null) {
    val now=Instant.now()
    if(approval!=null) validateApproval(approval,approvalRequest(tenantId,requireNotNull(operationId),lines,currentApprovalChecks(tenantId,lines)),now)
    minimumPolicies(tenantId).forEach {policy ->lines.forEach {line ->policy.terms.minimumFor(line)?.let {required ->
        require(!Instant.parse(policy.updatedAt).isAfter(now)) {"The saved minimum price is in the future. Correct the computer clock before selling."}
        require(line.totalMinor>=required || approval!=null) {"${line.product.name}: ${line.quantityLabel} ${line.sellingUnit} must total at least ${money(required)} after all discounts. Review the sale or request approval."}
    }}}
}
internal fun PosRepository.captureMinimumPriceChecks(tenantId:String,saleId:String,lines:List<CartLine>,approval:MinimumPriceApprovalWitness?=null) {
    val saleTime=featureQuery("SELECT created_at FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId) {Instant.parse(it.getString(1))}.single()
    if(approval!=null) {
        validateApproval(approval,approvalRequest(tenantId,saleId,lines,currentApprovalChecks(tenantId,lines)),saleTime)
        featureExecute("INSERT INTO sale_minimum_price_approvals VALUES(?,?,?,?,?)",tenantId,saleId,approval.id,tenantId,MinimumPriceApprovalProof.encode(approval))
        featureEvent(tenantId,"minimum_price.override_used",saleId)
    }
    minimumPolicies(tenantId).forEach {policy ->lines.forEach {line ->policy.terms.minimumFor(line)?.let {required ->
        require(!Instant.parse(policy.updatedAt).isAfter(saleTime) && (line.totalMinor>=required || approval!=null)) {"Minimum-price review changed before saving. No sale was recorded."}
        featureExecute("INSERT INTO sale_minimum_price_checks VALUES(?,?,?,?,?,?,?)",tenantId,saleId,line.key,policy.terms.productId,policy.terms.sellingUnitId.orEmpty(),policy.version,required)
    }}}
    featureExecute("UPDATE sales SET minimum_price_captured=1 WHERE tenant_id=? AND id=?",tenantId,saleId)
}
fun PosRepository.minimumPriceApprovalForSale(tenantId:String,saleId:String):MinimumPriceApprovalWitness? =featureRead(tenantId,StaffPermission.VIEW_SALES) {
    featureQuery("SELECT proof FROM sale_minimum_price_approvals WHERE tenant_id=? AND sale_id=?",tenantId,saleId) {MinimumPriceApprovalProof.decode(it.getString(1))}.singleOrNull()
}
internal fun PosRepository.validateMinimumPrices(tenantId:String) {
    val policies=minimumPolicies(tenantId);val changes=minimumChanges(tenantId)
    require(policies.map {it.terms.key}.toSet()==changes.map {it.current.key}.toSet()) {"Backup minimum-price policies have incomplete history."}
    changes.groupBy {it.current.key}.forEach {(_,history) ->
        history.forEachIndexed {index,event ->
            Instant.parse(event.createdAt)
            validateFloorOwner(tenantId,event.current.productId,event.current.sellingUnitId,false)
            require(event.version==index+1L && event.previous==history.getOrNull(index-1)?.current) {"Backup minimum-price revisions are inconsistent."}
            require(index==0 || !Instant.parse(event.createdAt).isBefore(Instant.parse(history[index-1].createdAt))) {"Backup minimum-price dates are inconsistent."}
        }
        val last=history.last();val policy=policies.single {it.terms.key==last.current.key}
        require(policy.terms==last.current && policy.version==last.version && policy.updatedAt==last.createdAt) {"Backup minimum price differs from its saved history."}
    }
    val witnesses=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='minimum_price.changed'",tenantId) {it.getString(1)}
    require(witnesses.size==changes.size && witnesses.toSet()==changes.map {it.id}.toSet()) {"Backup minimum-price activity is incomplete."}
    val captured=featureQuery("SELECT id FROM sales WHERE tenant_id=? AND minimum_price_captured=1",tenantId) {it.getString(1)}.toSet()
    val checks=featureQuery("SELECT * FROM sale_minimum_price_checks WHERE tenant_id=?",tenantId) {MinimumCheck(it.getString("sale_id"),it.getString("line_key"),it.getString("product_id"),it.getString("unit_key"),it.getLong("policy_version"),it.getLong("required_minor"))}
    require(checks.all {it.saleId in captured}) {"Backup minimum-price checks have no captured sale."}
    val proofs=featureQuery("SELECT * FROM sale_minimum_price_approvals WHERE tenant_id=?",tenantId) {r ->
        val proof=MinimumPriceApprovalProof.decode(r.getString("proof"))
        require(proof.id==r.getString("grant_id") && proof.request.tenantId==r.getString("source_tenant_id") && proof.request.operationId==r.getString("sale_id")) {"Backup price approval scope is inconsistent."}
        r.getString("sale_id") to proof
    }.toMap()
    require(proofs.keys.all {it in captured}) {"Backup price approval has no captured sale."}
    val approvalEvents=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='minimum_price.override_used'",tenantId) {it.getString(1)}
    require(approvalEvents.size==proofs.size && approvalEvents.toSet()==proofs.keys) {"Backup price approval activity is incomplete."}
    val expected=mutableSetOf<MinimumCheck>()
    sales(tenantId).filter {it.id in captured}.forEach {sale ->
        val time=Instant.parse(sale.createdAt)
        val revisions=changes.filter {!Instant.parse(it.createdAt).isAfter(time)}.groupBy {it.current.key}.values.map {it.maxBy {event ->event.version}}
        val proof=proofs[sale.id]
        if(proof!=null) {
            val historicalChecks=revisions.flatMap {event ->sale.lines.mapNotNull {line ->event.current.minimumFor(line)?.let {MinimumPriceApprovalCheck(line.key,event.current.productId,event.current.sellingUnitId.orEmpty(),event.version,it)}}}.sortedWith(compareBy({it.lineKey},{it.productId},{it.unitKey}))
            validateApproval(proof,approvalRequest(proof.request.tenantId,sale.id,sale.lines,historicalChecks),time)
        }
        revisions.forEach {event ->sale.lines.forEach {line ->
            event.current.minimumFor(line)?.let {required ->
                require(line.totalMinor>=required || proof!=null) {"Backup sale is below its recorded minimum price without authenticated approval."}
                expected+=MinimumCheck(sale.id,line.key,event.current.productId,event.current.sellingUnitId.orEmpty(),event.version,required)
            }
        }}
    }
    require(expected==checks.toSet()) {"Backup sale minimum-price snapshots are incomplete or inconsistent."}
}
private data class MinimumCheck(val saleId:String,val lineKey:String,val productId:String,val unitKey:String,val version:Long,val required:Long)
private fun PosRepository.validateFloorOwner(tenantId:String,productId:String,unitId:String?,active:Boolean):MinimumPriceTerms {
    val product=requireNotNull(products(tenantId,true).singleOrNull {it.id==productId}) {"Minimum-price goods belong to another shop or are unavailable."}
    val unit=unitId?.let {id ->requireNotNull(sellingUnits(tenantId,productId,true).singleOrNull {it.id==id}) {"Minimum-price selling pack is unavailable for this item."}}
    if(active) require(!product.archived && unit?.archived!=true) {"Choose active goods and selling units before changing a minimum price."}
    return MinimumPriceTerms(product.id,product.name,unit?.id,unit?.name ?: product.unit,if(unit==null) product.quantityPrecision else 0,product.quantityPrecision,unit?.baseUnitsPerUnit ?: 1,null)
}
private fun PosRepository.minimumPolicies(tenantId:String)=featureQuery("SELECT * FROM minimum_price_policies WHERE tenant_id=? ORDER BY product_id,unit_key",tenantId) {r ->
    val terms=decodeMinimumTerms(r.getString("details"));require(terms.productId==r.getString("product_id") && terms.sellingUnitId.orEmpty()==r.getString("unit_key")) {"Saved minimum-price owner is inconsistent."}
    MinimumPricePolicy(tenantId,terms,r.getLong("version"),r.getString("updated_at"))
}
private fun PosRepository.minimumChanges(tenantId:String)=featureQuery("SELECT * FROM minimum_price_changes WHERE tenant_id=? ORDER BY version",tenantId,map=::minimumChangeRow)
private fun minimumChangeRow(r:ResultSet):MinimumPriceChange {
    val current=decodeMinimumTerms(r.getString("current_details"))
    require(current.productId==r.getString("product_id") && current.sellingUnitId.orEmpty()==r.getString("unit_key")) {"Saved minimum-price history owner is inconsistent."}
    return MinimumPriceChange(r.getString("id"),r.getString("tenant_id"),r.getString("previous_details")?.let(::decodeMinimumTerms),current,r.getLong("version"),r.getString("reason"),r.getString("created_at"))
}
private fun encodeMinimumTerms(value:MinimumPriceTerms):String {
    val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use {out ->out.writeInt(1);out.writeUTF(value.productId);out.writeUTF(value.productName);out.writeBoolean(value.sellingUnitId!=null);value.sellingUnitId?.let(out::writeUTF);out.writeUTF(value.unitName);out.writeInt(value.quantityPrecision);out.writeInt(value.basePrecision);out.writeInt(value.baseUnitsPerUnit);out.writeBoolean(value.minimumUnitPriceMinor!=null);value.minimumUnitPriceMinor?.let(out::writeLong)}
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}
private fun decodeMinimumTerms(text:String):MinimumPriceTerms {
    require(text.length<=20_000) {"Minimum-price record is too large."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(text))).use {input ->
        require(input.readInt()==1);val value=MinimumPriceTerms(input.readUTF(),input.readUTF(),if(input.readBoolean()) input.readUTF() else null,input.readUTF(),input.readInt(),input.readInt(),input.readInt(),if(input.readBoolean()) input.readLong() else null);require(input.read()==-1);value
    }
}
