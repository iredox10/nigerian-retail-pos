package pos.data

import pos.domain.*
import java.io.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val minimumPriceSnapshotTables=listOf("minimum_price_policies","minimum_price_changes","sale_minimum_price_checks")
internal fun PosRepository.initializeMinimumPrices() {
    if("minimum_price_captured" !in featureQuery("PRAGMA table_info(sales)") {it.getString("name")}) featureSchema("ALTER TABLE sales ADD COLUMN minimum_price_captured INTEGER NOT NULL DEFAULT 0 CHECK(minimum_price_captured IN(0,1))")
    featureSchema("CREATE TABLE IF NOT EXISTS minimum_price_policies(tenant_id TEXT NOT NULL,product_id TEXT NOT NULL,unit_key TEXT NOT NULL,details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),updated_at TEXT NOT NULL,PRIMARY KEY(tenant_id,product_id,unit_key),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS minimum_price_changes(tenant_id TEXT NOT NULL,id TEXT NOT NULL,product_id TEXT NOT NULL,unit_key TEXT NOT NULL,previous_details TEXT,current_details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,product_id,unit_key,version),FOREIGN KEY(tenant_id,product_id,unit_key) REFERENCES minimum_price_policies(tenant_id,product_id,unit_key))")
    featureSchema("CREATE TABLE IF NOT EXISTS sale_minimum_price_checks(tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,line_key TEXT NOT NULL,product_id TEXT NOT NULL,unit_key TEXT NOT NULL,policy_version INTEGER NOT NULL,required_minor INTEGER NOT NULL CHECK(required_minor>=0),PRIMARY KEY(tenant_id,sale_id,line_key,product_id,unit_key),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),FOREIGN KEY(tenant_id,product_id,unit_key,policy_version) REFERENCES minimum_price_changes(tenant_id,product_id,unit_key,version))")
    listOf("minimum_price_changes","sale_minimum_price_checks").forEach {table ->listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Minimum-price history cannot be edited or removed'); END")}}
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

/** Must receive normalized final lines after their basket-discount allocations. Caller owns sale transaction. */
internal fun PosRepository.validateMinimumPricesAtCheckout(tenantId:String,lines:List<CartLine>) {
    val now=Instant.now()
    minimumPolicies(tenantId).forEach {policy ->lines.forEach {line ->policy.terms.minimumFor(line)?.let {required ->
        require(!Instant.parse(policy.updatedAt).isAfter(now)) {"The saved minimum price is in the future. Correct the computer clock before selling."}
        require(line.totalMinor>=required) {"${line.product.name}: ${line.quantityLabel} ${line.sellingUnit} must total at least ${money(required)} after all discounts. Review the sale or ask the owner/manager to review the minimum price under Stock."}
    }}}
}
internal fun PosRepository.captureMinimumPriceChecks(tenantId:String,saleId:String,lines:List<CartLine>) {
    val saleTime=featureQuery("SELECT created_at FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId) {Instant.parse(it.getString(1))}.single()
    minimumPolicies(tenantId).forEach {policy ->lines.forEach {line ->policy.terms.minimumFor(line)?.let {required ->
        require(!Instant.parse(policy.updatedAt).isAfter(saleTime) && line.totalMinor>=required) {"Minimum-price review changed before saving. No sale was recorded."}
        featureExecute("INSERT INTO sale_minimum_price_checks VALUES(?,?,?,?,?,?,?)",tenantId,saleId,line.key,policy.terms.productId,policy.terms.sellingUnitId.orEmpty(),policy.version,required)
    }}}
    featureExecute("UPDATE sales SET minimum_price_captured=1 WHERE tenant_id=? AND id=?",tenantId,saleId)
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
    val expected=mutableSetOf<MinimumCheck>()
    sales(tenantId).filter {it.id in captured}.forEach {sale ->
        val time=Instant.parse(sale.createdAt)
        changes.filter {!Instant.parse(it.createdAt).isAfter(time)}.groupBy {it.current.key}.values.map {it.maxBy {event ->event.version}}.forEach {event ->sale.lines.forEach {line ->
            event.current.minimumFor(line)?.let {required ->
                require(line.totalMinor>=required) {"Backup sale is below its recorded minimum price."}
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
