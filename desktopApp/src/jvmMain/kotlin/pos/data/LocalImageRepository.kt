package pos.data

import pos.domain.StaffPermission
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val IMAGE_BUDGET=16_777_216L
internal val localImageSnapshotTables=listOf("local_image_assets","local_image_selections","local_image_changes","sale_image_snapshots")
data class LocalImageSelection(val ownerKind:String,val ownerId:String,val assetId:String?,val version:Long)
data class LocalImageChange(val id:String,val assetId:String?,val version:Long,val reason:String,val createdAt:String)

internal fun PosRepository.initializeLocalImages() {
    if("receipt_image_captured" !in featureQuery("PRAGMA table_info(sales)") {it.getString("name")}) featureSchema("ALTER TABLE sales ADD COLUMN receipt_image_captured INTEGER NOT NULL DEFAULT 0 CHECK(receipt_image_captured IN(0,1))")
    featureSchema("CREATE TABLE IF NOT EXISTS local_image_assets(tenant_id TEXT NOT NULL REFERENCES tenants(id),id TEXT NOT NULL,content TEXT NOT NULL,byte_size INTEGER NOT NULL CHECK(byte_size BETWEEN 1 AND 524288),PRIMARY KEY(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS local_image_selections(tenant_id TEXT NOT NULL REFERENCES tenants(id),owner_kind TEXT NOT NULL CHECK(owner_kind IN('shop','product')),owner_id TEXT NOT NULL,asset_id TEXT,version INTEGER NOT NULL CHECK(version>0),PRIMARY KEY(tenant_id,owner_kind,owner_id),FOREIGN KEY(tenant_id,asset_id) REFERENCES local_image_assets(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS local_image_changes(tenant_id TEXT NOT NULL REFERENCES tenants(id),id TEXT NOT NULL,owner_kind TEXT NOT NULL CHECK(owner_kind IN('shop','product')),owner_id TEXT NOT NULL,previous_asset TEXT,asset_id TEXT,version INTEGER NOT NULL CHECK(version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,owner_kind,owner_id,version),FOREIGN KEY(tenant_id,asset_id) REFERENCES local_image_assets(tenant_id,id),FOREIGN KEY(tenant_id,previous_asset) REFERENCES local_image_assets(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS sale_image_snapshots(tenant_id TEXT NOT NULL,sale_id TEXT NOT NULL,asset_id TEXT,PRIMARY KEY(tenant_id,sale_id),FOREIGN KEY(tenant_id,sale_id) REFERENCES sales(tenant_id,id),FOREIGN KEY(tenant_id,asset_id) REFERENCES local_image_assets(tenant_id,id))")
    listOf("local_image_assets","local_image_changes","sale_image_snapshots").forEach {table ->listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Saved image history cannot be changed'); END")}}
}
private fun imagePermission(kind:String)=when(kind) {"shop" ->StaffPermission.MANAGE_SETTINGS;"product" ->StaffPermission.MANAGE_STOCK;else ->error("Unsupported image location.")}
private fun PosRepository.validateImageOwner(tenantId:String,kind:String,owner:String) {
    imagePermission(kind)
    require(if(kind=="shop") owner=="shop" else featureQuery("SELECT id FROM products WHERE tenant_id=? AND id=?",tenantId,owner) {it.getString(1)}.size==1) {"Image location does not belong to this shop."}
}
private fun PosRepository.selectionUnchecked(tenantId:String,kind:String,owner:String):LocalImageSelection =featureQuery("SELECT * FROM local_image_selections WHERE tenant_id=? AND owner_kind=? AND owner_id=?",tenantId,kind,owner) {LocalImageSelection(kind,owner,it.getString("asset_id"),it.getLong("version"))}.singleOrNull() ?: LocalImageSelection(kind,owner,null,0)
fun PosRepository.imageSelection(tenantId:String,ownerKind:String,ownerId:String):LocalImageSelection=featureRead(tenantId,StaffPermission.SELL) {
    validateImageOwner(tenantId,ownerKind,ownerId);selectionUnchecked(tenantId,ownerKind,ownerId)
}
fun PosRepository.imageBytes(tenantId:String,assetId:String):ByteArray=featureRead(tenantId,StaffPermission.SELL) {imageBytesUnchecked(tenantId,assetId)}
private fun PosRepository.imageBytesUnchecked(tenantId:String,assetId:String):ByteArray {
    val stored=featureQuery("SELECT content,byte_size FROM local_image_assets WHERE tenant_id=? AND id=?",tenantId,assetId) {it.getString(1) to it.getInt(2)}.singleOrNull() ?: error("Saved image is unavailable in this shop.")
    require(stored.first.length<=699_052) {"Saved image is too large."}
    return Base64.getDecoder().decode(stored.first).also {require(it.size==stored.second && imageHash(it)==assetId) {"Saved image integrity check failed."};validateStoredImage(it)}
}
fun PosRepository.imageHistory(tenantId:String,ownerKind:String,ownerId:String):List<LocalImageChange> =featureRead(tenantId,imagePermission(ownerKind)) {
    validateImageOwner(tenantId,ownerKind,ownerId)
    featureQuery("SELECT * FROM local_image_changes WHERE tenant_id=? AND owner_kind=? AND owner_id=? ORDER BY version",tenantId,ownerKind,ownerId) {LocalImageChange(it.getString("id"),it.getString("asset_id"),it.getLong("version"),it.getString("reason"),it.getString("created_at"))}
}
fun PosRepository.saveImage(tenantId:String,ownerKind:String,ownerId:String,upload:ImageUpload?,expectedVersion:Long,reason:String,operationId:String=UUID.randomUUID().toString()):LocalImageSelection=featureWrite(tenantId,imagePermission(ownerKind)) {
    validateImageOwner(tenantId,ownerKind,ownerId)
    require(operationId.length in 1..200 && operationId.none {it.isISOControl()} && expectedVersion>=0) {"Image save reference is invalid."}
    require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 250 printable characters."}
    val asset=upload?.assetId
    val replay=featureQuery("SELECT * FROM local_image_changes WHERE tenant_id=? AND id=?",tenantId,operationId) {listOf(it.getString("owner_kind"),it.getString("owner_id"),it.getString("asset_id"),it.getString("version"),it.getString("reason"))}.singleOrNull()
    if(replay!=null) {
        require(replay==listOf(ownerKind,ownerId,asset,Math.addExact(expectedVersion,1).toString(),reason)) {"This image request was already saved with different details."}
        return@featureWrite selectionUnchecked(tenantId,ownerKind,ownerId)
    }
    if(ownerKind=="product") require(featureQuery("SELECT archived FROM products WHERE tenant_id=? AND id=?",tenantId,ownerId) {it.getInt(1)}.single()==0) {"Restore this item before changing its photo."}
    val previous=selectionUnchecked(tenantId,ownerKind,ownerId)
    require(previous.version==expectedVersion) {"The image changed while this form was open. Reload and review it."}
    require(asset!=previous.assetId) {"Choose a different image before saving."}
    val now=Instant.now()
    val lastTime=featureQuery("SELECT created_at FROM local_image_changes WHERE tenant_id=? AND owner_kind=? AND owner_id=? ORDER BY version DESC LIMIT 1",tenantId,ownerKind,ownerId) {Instant.parse(it.getString(1))}.singleOrNull()
    require(lastTime==null || !now.isBefore(lastTime)) {"Correct the computer clock before changing this image."}
    if(upload!=null) {
        val bytes=upload.bytes;validateStoredImage(bytes)
        val exists=featureQuery("SELECT id FROM local_image_assets WHERE tenant_id=? AND id=?",tenantId,asset) {it.getString(1)}.isNotEmpty()
        if(!exists) {
            val total=featureQuery("SELECT COALESCE(SUM(byte_size),0) FROM local_image_assets WHERE tenant_id=?",tenantId) {it.getLong(1)}.single()
            require(total+bytes.size<=IMAGE_BUDGET) {"This shop has reached its 16 MB image-history limit. No image was changed."}
            featureExecute("INSERT INTO local_image_assets VALUES(?,?,?,?)",tenantId,asset,Base64.getEncoder().encodeToString(bytes),bytes.size)
        }
    }
    val version=Math.addExact(expectedVersion,1)
    featureExecute("INSERT INTO local_image_selections VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,owner_kind,owner_id) DO UPDATE SET asset_id=excluded.asset_id,version=excluded.version",tenantId,ownerKind,ownerId,asset,version)
    featureExecute("INSERT INTO local_image_changes VALUES(?,?,?,?,?,?,?,?,?)",tenantId,operationId,ownerKind,ownerId,previous.assetId,asset,version,reason,now.toString())
    featureEvent(tenantId,"image.changed",operationId)
    LocalImageSelection(ownerKind,ownerId,asset,version)
}
internal fun PosRepository.captureReceiptImage(tenantId:String,saleId:String) {
    val saleTime=featureQuery("SELECT created_at FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId) {Instant.parse(it.getString(1))}.single()
    val lastChange=featureQuery("SELECT created_at FROM local_image_changes WHERE tenant_id=? AND owner_kind='shop' ORDER BY version DESC LIMIT 1",tenantId) {Instant.parse(it.getString(1))}.singleOrNull()
    require(lastChange==null || !saleTime.isBefore(lastChange)) {"The computer clock is earlier than the saved shop logo. Correct the clock before selling."}
    featureExecute("INSERT INTO sale_image_snapshots VALUES(?,?,?)",tenantId,saleId,selectionUnchecked(tenantId,"shop","shop").assetId)
    featureExecute("UPDATE sales SET receipt_image_captured=1 WHERE tenant_id=? AND id=?",tenantId,saleId)
}
fun PosRepository.receiptImageBytes(tenantId:String,saleId:String):ByteArray? =featureRead(tenantId,StaffPermission.VIEW_SALES) {
    require(featureQuery("SELECT id FROM sales WHERE tenant_id=? AND id=?",tenantId,saleId) {it.getString(1)}.size==1) {"Receipt is unavailable in this shop."}
    featureQuery("SELECT asset_id FROM sale_image_snapshots WHERE tenant_id=? AND sale_id=?",tenantId,saleId) {it.getString(1)}.singleOrNull()?.let {imageBytesUnchecked(tenantId,it)}
}
internal fun PosRepository.validateLocalImages(tenantId:String) {
    val ids=featureQuery("SELECT id FROM local_image_assets WHERE tenant_id=?",tenantId) {it.getString(1)}
    var total=0L
    ids.forEach {total+=imageBytesUnchecked(tenantId,it).size;require(total<=IMAGE_BUDGET) {"Backup image history exceeds 16 MB."}}
    val history=featureQuery("SELECT * FROM local_image_changes WHERE tenant_id=? ORDER BY version",tenantId) {r ->listOf(r.getString("id"),r.getString("owner_kind"),r.getString("owner_id"),r.getString("previous_asset"),r.getString("asset_id"),r.getString("version"),r.getString("reason"),r.getString("created_at"))}
    val selections=featureQuery("SELECT owner_kind,owner_id FROM local_image_selections WHERE tenant_id=?",tenantId) {it.getString(1) to it.getString(2)}
    require(selections.toSet()==history.map {it[1] to it[2]}.toSet()) {"Backup image selections have incomplete history."}
    history.groupBy {it[1] to it[2]}.forEach { (owner,rows) ->
        val kind=requireNotNull(owner.first);val ownerId=requireNotNull(owner.second);validateImageOwner(tenantId,kind,ownerId)
        rows.forEachIndexed {index,row ->
            require(row[5]?.toLongOrNull()==index+1L && row[3]==rows.getOrNull(index-1)?.get(4) && row[3]!=row[4]) {"Backup image revision history is inconsistent."}
            val reason=requireNotNull(row[6]);require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Backup image reason is invalid."}
            val time=Instant.parse(row[7]);require(index==0 || !time.isBefore(Instant.parse(rows[index-1][7]))) {"Backup image dates are inconsistent."}
        }
        val last=rows.last();val current=selectionUnchecked(tenantId,kind,ownerId)
        require(current.version==last[5]?.toLong() && current.assetId==last[4]) {"Backup image selection differs from history."}
    }
    val witnesses=featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind='image.changed'",tenantId) {it.getString(1)}
    require(witnesses.size==history.size && witnesses.toSet()==history.map {it[0]}.toSet()) {"Backup image activity is incomplete."}
    require(ids.toSet()==history.mapNotNull {it[4]}.toSet()) {"Backup contains image bytes without source history."}
    val captured=featureQuery("SELECT id FROM sales WHERE tenant_id=? AND receipt_image_captured=1",tenantId) {it.getString(1)}.toSet()
    val snapshots=featureQuery("SELECT sale_id FROM sale_image_snapshots WHERE tenant_id=?",tenantId) {it.getString(1)}.toSet()
    require(captured==snapshots) {"Backup receipt image snapshots are incomplete."}
    featureQuery("SELECT s.asset_id,s.sale_id,v.created_at FROM sale_image_snapshots s JOIN sales v ON v.tenant_id=s.tenant_id AND v.id=s.sale_id WHERE s.tenant_id=?",tenantId) {Triple(it.getString(1),it.getString(2),Instant.parse(it.getString(3)))}.forEach {(asset,_,time) ->
        val prior=history.filter {it[1]=="shop" && !Instant.parse(it[7]).isAfter(time)}.maxByOrNull {it[5]!!.toLong()}
        require(asset==prior?.get(4)) {"Backup receipt logo differs from its recorded shop image."}
    }
}
