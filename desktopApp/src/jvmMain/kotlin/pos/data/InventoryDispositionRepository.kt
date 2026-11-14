package pos.data

import pos.domain.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal val inventoryDispositionSnapshotTables=listOf("inventory_dispositions")
internal fun PosRepository.initializeInventoryDispositions() {
    featureSchema("CREATE TABLE IF NOT EXISTS inventory_dispositions(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),product_id TEXT NOT NULL,product_name TEXT NOT NULL,unit TEXT NOT NULL,precision INTEGER NOT NULL CHECK(precision BETWEEN 0 AND 6),quantity_scaled INTEGER NOT NULL CHECK(quantity_scaled>0),before_scaled INTEGER NOT NULL CHECK(before_scaled>=quantity_scaled AND before_scaled<=9007199254740991),after_scaled INTEGER NOT NULL CHECK(after_scaled=before_scaled-quantity_scaled),category TEXT NOT NULL CHECK(category IN('DAMAGE','SPOILAGE_EXPIRY','THEFT_LOSS','SAMPLE','DONATION','OWNER_USE')),reason TEXT NOT NULL,notes TEXT NOT NULL,created_at TEXT NOT NULL,baseline_token TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
    listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS inventory_dispositions_no_${action.lowercase()} BEFORE $action ON inventory_dispositions BEGIN SELECT RAISE(ABORT,'Completed stock removals cannot be edited or deleted'); END")}
}

fun PosRepository.inventoryDispositions(tenantId:String,productId:String?=null):List<InventoryDisposition> = featureRead(tenantId,StaffPermission.MANAGE_STOCK) { dispositionRows(tenantId,productId) }

fun PosRepository.inventoryDispositionBaseline(tenantId:String,productId:String):InventoryDispositionBaseline = featureRead(tenantId,StaffPermission.MANAGE_STOCK) {
    val item=requireDispositionItem(tenantId,productId)
    require(!item.archived) {"Restore this item before recording a stock removal."}
    require(item.stockScaled!=null || item.stock==item.stock.toLong().toDouble()) {"Reconcile this legacy fractional balance using a single-item stock count first."}
    InventoryDispositionBaseline(item.exactStockScaled,dispositionBaselineToken(tenantId,item))
}

fun PosRepository.recordInventoryDisposition(
    tenantId:String,productId:String,quantityScaled:Long,category:InventoryDispositionCategory,
    reason:String,notes:String,expected:InventoryDispositionBaseline,operationId:String=UUID.randomUUID().toString(),
):InventoryDisposition = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    require(operationId.length in 1..200 && operationId.none {it.isISOControl()}) {"Stock-removal reference is invalid."}
    val old=featureQuery("SELECT * FROM inventory_dispositions WHERE tenant_id=? AND id=?",tenantId,operationId,map=::dispositionRow).singleOrNull()
    if(old!=null) {
        require(old.productId==productId && old.quantityScaled==quantityScaled && old.category==category && old.reason==reason.trim() && old.notes==notes.trim() && old.beforeScaled==expected.stockScaled && old.baselineToken==expected.token) {"This stock removal was already saved with different details."}
        return@featureWrite old
    }
    val item=requireDispositionItem(tenantId,productId)
    require(!item.archived && item.exactStockScaled==expected.stockScaled && dispositionBaselineToken(tenantId,item)==expected.token) {"Stock or item details changed while this form was open. Reopen it and check the physical quantity; nothing was removed."}
    require(item.stockScaled!=null || item.stock==item.stock.toLong().toDouble()) {"Reconcile this legacy fractional balance using a single-item stock count first."}
    require(item.stock==projectedQuantity(item.exactStockScaled,item.quantityPrecision) && dispositionProjectionExact(item.exactStockScaled,item.quantityPrecision)) {"This stock quantity exceeds the exact supported range for its decimal places. Nothing was removed."}
    val now=Instant.now()
    val latest=inventoryMovements(tenantId,productId).maxOfOrNull {Instant.parse(it.createdAt)}
    require(latest==null || !now.isBefore(latest)) {"The computer clock moved backwards. Correct it before recording stock removed."}
    val removal=InventoryDisposition(operationId,tenantId,productId,item.name,item.unit,item.quantityPrecision,quantityScaled,expected.stockScaled,expected.stockScaled-quantityScaled,category,reason.trim(),notes.trim(),now.toString(),expected.token)
    require(dispositionProjectionExact(removal.quantityScaled,removal.precision) && dispositionProjectionExact(removal.afterScaled,removal.precision)) {"The remaining stock exceeds the exact supported range for its decimal places. Nothing was removed."}
    check(featureExecute("UPDATE products SET stock_scaled=?,stock=? WHERE tenant_id=? AND id=? AND archived=0 AND quantity_precision=? AND (stock_scaled=? OR (stock_scaled IS NULL AND stock=?))",removal.afterScaled,projectedQuantity(removal.afterScaled,removal.precision),tenantId,productId,removal.precision,removal.beforeScaled,item.stock)==1) {"Stock changed. Reopen the item and check the quantity."}
    featureExecute("INSERT INTO inventory_dispositions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",removal.id,tenantId,productId,removal.productName,removal.unit,removal.precision,removal.quantityScaled,removal.beforeScaled,removal.afterScaled,removal.category.name,removal.reason,removal.notes,removal.createdAt,removal.baselineToken)
    featureEvent(tenantId,"stock.disposed",removal.id)
    removal
}

/** Direct scoped rows: called from inventoryMovements, never recursively reads that ledger. */
internal fun PosRepository.inventoryDispositionMovements(tenantId:String,productId:String):List<InventoryMovement> = dispositionRows(tenantId,productId).map {
    InventoryMovement("disposition:${it.id}",tenantId,productId,"disposition",-projectedQuantity(it.quantityScaled,it.precision),it.id,it.createdAt,"${it.category.label}: ${it.reason}")
}

/** Business restore is authorized against the source tenant, not a new restored tenant's session. */
internal fun PosRepository.validateInventoryDispositions(tenantId:String) {
    val items=products(tenantId,true).associateBy {it.id}
    val removals=dispositionRows(tenantId,null)
    val events=featureQuery("SELECT entity_id,created_at FROM outbox WHERE tenant_id=? AND kind='stock.disposed'",tenantId) {it.getString(1) to it.getString(2)}
    require(events.map {it.first}.toSet()==removals.map {it.id}.toSet() && events.size==removals.size) {"Backup stock-removal activity does not match its saved history."}
    removals.forEach {entry ->
        Instant.parse(entry.createdAt)
        require(dispositionProjectionExact(entry.beforeScaled,entry.precision) && dispositionProjectionExact(entry.quantityScaled,entry.precision) && dispositionProjectionExact(entry.afterScaled,entry.precision)) {"Backup stock-removal quantities exceed the exact supported range."}
        val item=requireNotNull(items[entry.productId]) {"Backup stock removal has no item."}
        require(item.unit==entry.unit && item.quantityPrecision==entry.precision) {"Backup stock-removal unit or precision differs from its item."}
        require(events.single {it.first==entry.id}.second.let(Instant::parse)>=Instant.parse(entry.createdAt)) {"Backup stock-removal activity predates its record."}
        item.createdAt?.let {require(Instant.parse(entry.createdAt)>=Instant.parse(it)) {"Backup stock removal predates its item."}}
    }
}

private fun PosRepository.requireDispositionItem(tenantId:String,id:String)=requireNotNull(products(tenantId,true).singleOrNull {it.id==id}) {"Item does not belong to this shop."}
private fun PosRepository.dispositionBaselineToken(tenantId:String,item:Product):String {
    fun fields(values:List<String>)=values.joinToString("") {it.length.toString()+":"+it}
    val records=listOf(listOf("item",item.id,item.name,item.unit,item.quantityPrecision.toString(),item.exactStockScaled.toString(),item.archived.toString()))+
        inventoryMovements(tenantId,item.id).map {listOf("movement",it.id,it.kind,it.sourceId,it.quantityDelta.toString(),it.createdAt)}+
        featureQuery("SELECT id,created_at FROM catalogue_changes WHERE tenant_id=? AND product_id=? AND kind='archive' ORDER BY created_at,id",tenantId,item.id) {listOf("archive",it.getString(1),it.getString(2))}
    val text=records.joinToString("") {fields(listOf(it.size.toString())+it)}
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}
private fun PosRepository.dispositionRows(tenantId:String,productId:String?):List<InventoryDisposition> = featureQuery("SELECT * FROM inventory_dispositions WHERE tenant_id=?"+(if(productId==null) "" else " AND product_id=?")+" ORDER BY created_at DESC,id",*listOfNotNull(tenantId,productId).toTypedArray(),map=::dispositionRow)
private fun dispositionRow(r:java.sql.ResultSet)=InventoryDisposition(r.getString("id"),r.getString("tenant_id"),r.getString("product_id"),r.getString("product_name"),r.getString("unit"),r.getInt("precision"),r.getLong("quantity_scaled"),r.getLong("before_scaled"),r.getLong("after_scaled"),InventoryDispositionCategory.valueOf(r.getString("category")),r.getString("reason"),r.getString("notes"),r.getString("created_at"),r.getString("baseline_token"))

private fun dispositionProjectionExact(scaled:Long,precision:Int)=java.math.BigDecimal.valueOf(projectedQuantity(scaled,precision)).movePointRight(precision).compareTo(java.math.BigDecimal.valueOf(scaled))==0
