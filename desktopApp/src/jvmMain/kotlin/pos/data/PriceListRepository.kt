package pos.data

import pos.domain.*
import java.io.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val priceListSnapshotTables=listOf("price_lists","price_list_changes")
internal fun PosRepository.initializePriceLists() {
    featureSchema("CREATE TABLE IF NOT EXISTS price_lists(tenant_id TEXT NOT NULL REFERENCES tenants(id),id TEXT NOT NULL,details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),updated_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS price_list_changes(tenant_id TEXT NOT NULL,id TEXT NOT NULL,list_id TEXT NOT NULL,previous_details TEXT,current_details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,list_id,version),FOREIGN KEY(tenant_id,list_id) REFERENCES price_lists(tenant_id,id))")
    listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS price_list_changes_no_${action.lowercase()} BEFORE $action ON price_list_changes BEGIN SELECT RAISE(ABORT,'Price-list history cannot be edited or deleted'); END")}
}
fun PosRepository.priceLists(tenantId:String):List<PriceList> =featureRead(tenantId,StaffPermission.MANAGE_STOCK) {listRows(tenantId)}
fun PosRepository.priceListHistory(tenantId:String,listId:String):List<PriceListChange> =featureRead(tenantId,StaffPermission.MANAGE_STOCK) {
    require(listRows(tenantId).any {it.id==listId}) {"Price list is unavailable in this shop."}
    changeRows(tenantId,listId)
}
fun PosRepository.savePriceList(tenantId:String,listId:String,details:PriceListDetails,expectedVersion:Long,reason:String,operationId:String=UUID.randomUUID().toString()):PriceList=featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    require(listId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && operationId.isNotBlank() && operationId.length<=200 && operationId.none {it.isISOControl()} && expectedVersion>=0) {"Price-list request reference is invalid."}
    val replay=featureQuery("SELECT * FROM price_list_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::priceChangeRow).singleOrNull()
    if(replay!=null) {
        require(replay.listId==listId && replay.current==details && replay.version==Math.addExact(expectedVersion,1) && replay.reason==reason) {"This price-list request was already saved with different details."}
        return@featureWrite requireNotNull(listRows(tenantId).find {it.id==listId})
    }
    val old=listRows(tenantId).find {it.id==listId}
    require((old?.version ?: 0)==expectedVersion && old?.details!=details) {"Price list changed or nothing was edited. Reload and review it."}
    validatePriceDetails(tenantId,details,current=!details.archived)
    require(listRows(tenantId).none {it.id!=listId && !it.details.archived && !details.archived && it.details.name.equals(details.name,true)}) {"An active price list already uses this name."}
    val now=Instant.now()
    require(old==null || !now.isBefore(Instant.parse(old.updatedAt))) {"The computer clock moved backwards. Correct it before editing prices."}
    val change=PriceListChange(operationId,tenantId,listId,old?.details,details,Math.addExact(expectedVersion,1),reason,now.toString())
    featureExecute("INSERT INTO price_lists VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,id) DO UPDATE SET details=excluded.details,version=excluded.version,updated_at=excluded.updated_at",tenantId,listId,encodePriceDetails(details),change.version,change.createdAt)
    featureExecute("INSERT INTO price_list_changes VALUES(?,?,?,?,?,?,?,?)",tenantId,operationId,listId,old?.details?.let(::encodePriceDetails),encodePriceDetails(details),change.version,change.reason,change.createdAt)
    featureEvent(tenantId,"price_list.changed",operationId)
    PriceList(listId,tenantId,details,change.version,change.createdAt)
}

/** Checkout has its own DISCOUNT authority; listing does not expose customer contacts or costs. */
fun PosRepository.checkoutPriceLists(tenantId:String,customerId:String?):List<PriceList> =featureRead(tenantId,StaffPermission.DISCOUNT) {
    listRows(tenantId).filter {!it.details.archived && (it.details.customerId==null || it.details.customerId==customerId)}
}
fun PosRepository.quotePriceList(tenantId:String,listId:String,lines:List<CartLine>,customerId:String?):List<PriceListQuote> =featureRead(tenantId,StaffPermission.DISCOUNT) {
    require(lines.isNotEmpty() && lines.map {it.key}.distinct().size==lines.size) {"Choose distinct sale lines before reviewing prices."}
    val list=requireNotNull(listRows(tenantId).find {it.id==listId}) {"Price list is unavailable."}
    require(!list.details.archived && (list.details.customerId==null || list.details.customerId==customerId)) {"Choose this price list's customer and an active list."}
    lines.mapNotNull {line ->
        require(line.product.tenantId==tenantId) {"Goods belong to another shop."}
        val current=priceLine(tenantId,line.product.id,line.sellingUnitId,line.quantity)
        require(line.product.name==current.product.name && line.product.priceMinor==current.product.priceMinor && line.product.unit==current.product.unit && line.unitPriceMinor==current.unitPriceMinor && line.sellingUnit==current.sellingUnit && line.baseUnitsPerUnit==current.baseUnitsPerUnit && line.quantityPrecision==current.quantityPrecision) {"Item details changed. Review the basket before choosing prices."}
        list.details.tierFor(current)?.let {tier ->
            require(tier.unitPriceMinor<=current.unitPriceMinor) {"${current.product.name}: this list price exceeds the current normal price. Raise the normal price under Stock first, or edit this list."}
            PriceListQuote(list.id,list.details.name,list.version,list.details.kind,list.details.customerId,line.key,line.quantity,tier,current.unitPriceMinor)
        }
    }
}

/** Root checkout calls this inside its existing transaction before saving each normalized line. */
internal fun PosRepository.validatePriceListDiscount(tenantId:String,line:CartLine,customerId:String?) {
    val source=line.discountReason?.let(PriceListSource::decode) ?: return
    val list=requireNotNull(listRows(tenantId).find {it.id==source.listId}) {"The selected price list is unavailable. Review the line price."}
    require(!list.details.archived && list.version==source.version && (list.details.customerId==null || list.details.customerId==customerId)) {"Price list or customer changed. Review this line price."}
    val tier=requireNotNull(list.details.tierFor(line)) {"This quantity no longer qualifies for its price list. Review this line price."}
    require(source.quantity==line.quantity && source.minimumQuantity==tier.minimumQuantity && line.netUnitPriceMinor==tier.unitPriceMinor && line.unitPriceMinor>=tier.unitPriceMinor) {"Quantity or price-list break changed. Review this line price."}
}

internal fun PosRepository.validatePriceLists(tenantId:String) {
    val lists=listRows(tenantId)
    val activeNames=lists.filter {!it.details.archived}.map {it.details.name.lowercase()}
    require(activeNames.distinct().size==activeNames.size) {"Backup contains duplicate active price-list names."}
    lists.forEach {list ->
        val events=changeRows(tenantId,list.id)
        require(events.isNotEmpty()) {"Backup price list has no revision history."}
        events.forEachIndexed {index,event ->
            Instant.parse(event.createdAt)
            validatePriceDetails(tenantId,event.current,current=false)
            require(event.version==index.toLong()+1 && event.previous==(if(index==0) null else events[index-1].current)) {"Backup price-list history is incomplete."}
            require(index==0 || !Instant.parse(event.createdAt).isBefore(Instant.parse(events[index-1].createdAt))) {"Backup price-list dates are inconsistent."}
        }
        val last=events.last()
        require(last.current==list.details && last.version==list.version && last.createdAt==list.updatedAt) {"Backup price list differs from its latest revision."}
    }
    val events=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='price_list.changed'",tenantId) {it.getString(1)}
    val changes=featureQuery("SELECT id FROM price_list_changes WHERE tenant_id=?",tenantId) {it.getString(1)}
    require(events.size==changes.size && events.toSet()==changes.toSet()) {"Backup price-list source history is missing."}
    sales(tenantId).forEach {sale ->sale.lines.forEach {line ->
        line.discountReason?.let(PriceListSource::decode)?.let {source ->
            val history=changeRows(tenantId,source.listId)
            val event=requireNotNull(history.find {it.version==source.version}) {"Backup sale has no original price-list revision."}
            val tier=requireNotNull(event.current.tierFor(line)) {"Backup sale no longer qualifies for its saved price-list tier."}
            require(!event.current.archived && (event.current.customerId==null || event.current.customerId==sale.customerId) && source.quantity==line.quantity && source.minimumQuantity==tier.minimumQuantity && tier.unitPriceMinor==line.netUnitPriceMinor && !Instant.parse(event.createdAt).isAfter(Instant.parse(sale.createdAt))) {"Backup sale does not match its price-list source."}
        }
    }}
}
private fun PosRepository.validatePriceDetails(tenantId:String,details:PriceListDetails,current:Boolean) {
    details.customerId?.let {id ->require(customers(tenantId).any {it.id==id}) {"Customer belongs to another shop or is unavailable."}}
    details.tiers.forEach {tier ->
        val item=requireNotNull(products(tenantId,true).find {it.id==tier.productId}) {"Price-list item belongs to another shop or is unavailable."}
        val unit=tier.sellingUnitId?.let {id ->requireNotNull(sellingUnits(tenantId,item.id,true).find {it.id==id}) {"Selling unit is unavailable for this item."}}
        require(tier.quantityPrecision==(if(unit==null) item.quantityPrecision else 0)) {"Price-list quantity precision differs from its selling unit."}
        if(current) {
            require(!item.archived && unit?.archived!=true) {"Choose active goods and selling units."}
            require(tier.unitPriceMinor<=(unit?.priceMinor ?: item.priceMinor)) {"List prices must not exceed the normal selling price. Raise the normal price first if needed."}
        }
    }
}
private fun PosRepository.priceLine(tenantId:String,productId:String,unitId:String?,quantity:Int):CartLine {
    val item=requireNotNull(products(tenantId,true).find {it.id==productId}) {"Item belongs to another shop."}
    require(!item.archived) {"Restore the item before applying a price list."}
    val unit=unitId?.let {id ->requireNotNull(sellingUnits(tenantId,productId,true).find {it.id==id && !it.archived}) {"Choose an active selling unit."}}
    return if(unit==null) CartLine(item,quantity) else CartLine(item,quantity,unit.id,unit.name,unit.priceMinor,unit.baseUnitsPerUnit)
}
private fun PosRepository.listRows(tenantId:String)=featureQuery("SELECT * FROM price_lists WHERE tenant_id=? ORDER BY id",tenantId,map=::priceListRow)
private fun PosRepository.changeRows(tenantId:String,listId:String)=featureQuery("SELECT * FROM price_list_changes WHERE tenant_id=? AND list_id=? ORDER BY version",tenantId,listId,map=::priceChangeRow)
private fun priceListRow(r:ResultSet)=PriceList(r.getString("id"),r.getString("tenant_id"),decodePriceDetails(r.getString("details")),r.getLong("version"),r.getString("updated_at"))
private fun priceChangeRow(r:ResultSet)=PriceListChange(r.getString("id"),r.getString("tenant_id"),r.getString("list_id"),r.getString("previous_details")?.let(::decodePriceDetails),decodePriceDetails(r.getString("current_details")),r.getLong("version"),r.getString("reason"),r.getString("created_at"))
private fun encodePriceDetails(value:PriceListDetails):String {
    val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use {out ->
        out.writeInt(1);out.writeUTF(value.name);out.writeUTF(value.kind.name);out.writeBoolean(value.customerId!=null);value.customerId?.let(out::writeUTF);out.writeBoolean(value.archived);out.writeInt(value.tiers.size)
        value.tiers.forEach {tier ->out.writeUTF(tier.productId);out.writeBoolean(tier.sellingUnitId!=null);tier.sellingUnitId?.let(out::writeUTF);out.writeInt(tier.minimumQuantity);out.writeInt(tier.quantityPrecision);out.writeLong(tier.unitPriceMinor)}
    };return Base64.getEncoder().encodeToString(bytes.toByteArray())
}
private fun decodePriceDetails(value:String):PriceListDetails {
    require(value.length<=1_000_000) {"Price-list record is too large."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use {input ->
        require(input.readInt()==1);val name=input.readUTF();val kind=PriceListKind.valueOf(input.readUTF());val customer=if(input.readBoolean()) input.readUTF() else null;val archived=input.readBoolean();val count=input.readInt();require(count in 1..500)
        val tiers=List(count) {PriceListTier(input.readUTF(),if(input.readBoolean()) input.readUTF() else null,input.readInt(),input.readInt(),input.readLong())};require(input.read()==-1)
        PriceListDetails(name,kind,customer,tiers,archived)
    }
}
