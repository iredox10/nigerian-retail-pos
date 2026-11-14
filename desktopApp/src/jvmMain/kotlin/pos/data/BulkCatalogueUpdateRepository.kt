package pos.data

import pos.domain.*
import java.io.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal val bulkCatalogueUpdateSnapshotTables=listOf("catalogue_update_batches","catalogue_update_batch_rows")
internal fun PosRepository.initializeBulkCatalogueUpdates() {
    featureSchema("CREATE TABLE IF NOT EXISTS catalogue_update_batches(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),reason TEXT NOT NULL,created_at TEXT NOT NULL,fingerprint TEXT NOT NULL,row_count INTEGER NOT NULL CHECK(row_count BETWEEN 1 AND 10000),PRIMARY KEY(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS catalogue_update_batch_rows(tenant_id TEXT NOT NULL,batch_id TEXT NOT NULL,product_id TEXT NOT NULL,record INTEGER NOT NULL CHECK(record>=2),expected_version TEXT NOT NULL,before_values TEXT NOT NULL,after_values TEXT NOT NULL,PRIMARY KEY(tenant_id,batch_id,product_id),UNIQUE(tenant_id,batch_id,record),FOREIGN KEY(tenant_id,batch_id) REFERENCES catalogue_update_batches(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES products(tenant_id,id))")
    featureSchema("CREATE INDEX IF NOT EXISTS bulk_review_catalogue_history ON catalogue_changes(tenant_id,product_id)")
    featureSchema("CREATE INDEX IF NOT EXISTS bulk_review_stock_settings ON stock_settings_changes(tenant_id,product_id)")
    bulkCatalogueUpdateSnapshotTables.forEach {table ->listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Completed catalogue batches cannot be edited or deleted'); END")}}
}

fun PosRepository.bulkCatalogueTemplateRows(tenantId:String):List<BulkCatalogueUpdateRow> = featureRead(tenantId,StaffPermission.MANAGE_STOCK) {
    products(tenantId).also {require(it.size<=10000) {"Export supports at most 10,000 active goods."}}.mapIndexed {index,item ->BulkCatalogueUpdateRow(index+2,item.id,catalogueVersion(tenantId,item),item.bulkCatalogueValues())}
}
fun PosRepository.previewBulkCatalogueUpdates(tenantId:String,rows:List<BulkCatalogueUpdateRow>):BulkCataloguePreview = featureRead(tenantId,StaffPermission.MANAGE_STOCK) { cataloguePreview(tenantId,rows) }
fun PosRepository.bulkCatalogueBatches(tenantId:String):List<BulkCatalogueBatch> = featureRead(tenantId,StaffPermission.MANAGE_STOCK) { catalogueBatchesUnchecked(tenantId) }

fun PosRepository.applyBulkCatalogueUpdates(tenantId:String,rows:List<BulkCatalogueUpdateRow>,reason:String,operationId:String=UUID.randomUUID().toString()):BulkCatalogueBatch = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    require(operationId.length in 1..200 && operationId.none {it.isISOControl()}) {"Batch reference is invalid."}
    require(rows.size in 1..10000 && rows.map {it.productId}.distinct().size==rows.size && rows.map {it.record}.distinct().size==rows.size) {"Provide 1 to 10,000 different product records."}
    val requestFingerprint=catalogueRequestFingerprint(rows,reason.trim())
    val existing=catalogueBatchUnchecked(tenantId,operationId)
    if(existing!=null) {
        require(catalogueRequestFingerprint(existing.rows.map {BulkCatalogueUpdateRow(it.record,it.productId,it.expectedVersion,it.after)},existing.reason)==requestFingerprint) {"This catalogue batch was already saved with different details."}
        return@featureWrite existing
    }
    val preview=cataloguePreview(tenantId,rows)
    require(preview.issues.isEmpty()) {preview.issues.take(10).joinToString("\n") {"Record ${it.record}: ${it.message}"}}
    require(preview.changedCount>0) {"No catalogue values have changed."}
    val savedRows=preview.rows.map {BulkCatalogueBatchRow(it.requested.productId,it.requested.record,it.requested.expectedVersion,requireNotNull(it.before),it.requested.values)}
    val batch=BulkCatalogueBatch(operationId,tenantId,reason.trim(),Instant.now().toString(),catalogueBatchFingerprint(savedRows,reason.trim()),savedRows)
    // Release only identifiers that change, with normal immutable history, inside this same transaction.
    // This permits reviewed A↔B barcode/SKU swaps without exposing an intermediate catalogue.
    batch.rows.forEach {row ->
        if(row.before.barcode!=null && row.before.barcode!=row.after.barcode) updateProduct(tenantId,row.productId,row.before.name,row.before.unit,row.before.priceMinor,null,catalogueChildId(batch.id,row.productId,"release-barcode"))
        if(row.before.metadata.sku!=null && row.before.metadata.sku!=row.after.metadata.sku) updateProductMetadata(tenantId,row.productId,row.before.metadata.copy(sku=null),catalogueChildId(batch.id,row.productId,"release-sku"),row.before.metadata)
    }
    batch.rows.forEach {row ->
        var current=row.before.copy(barcode=if(row.before.barcode!=row.after.barcode) null else row.before.barcode,metadata=if(row.before.metadata.sku!=row.after.metadata.sku) row.before.metadata.copy(sku=null) else row.before.metadata)
        val target=row.after
        if(current.name!=target.name || current.priceMinor!=target.priceMinor || current.barcode!=target.barcode) {
            updateProduct(tenantId,row.productId,target.name,target.unit,target.priceMinor,target.barcode,catalogueChildId(batch.id,row.productId,"details"))
            current=current.copy(name=target.name,priceMinor=target.priceMinor,barcode=target.barcode)
        }
        if(current.metadata!=target.metadata) updateProductMetadata(tenantId,row.productId,target.metadata,catalogueChildId(batch.id,row.productId,"metadata"),current.metadata)
        if(current.reorderLevel!=target.reorderLevel) setReorderLevel(tenantId,row.productId,target.reorderLevel,catalogueChildId(batch.id,row.productId,"reorder"))
    }
    batch.rows.forEach {row ->require(catalogueValuesUnchecked(tenantId,row.productId)==row.after) {"An item did not match the reviewed result. No part of the catalogue batch was saved."}}
    featureExecute("INSERT INTO catalogue_update_batches VALUES(?,?,?,?,?,?)",batch.id,tenantId,batch.reason,batch.createdAt,batch.fingerprint,batch.rows.size)
    batch.rows.forEach {row ->featureExecute("INSERT INTO catalogue_update_batch_rows VALUES(?,?,?,?,?,?,?)",tenantId,batch.id,row.productId,row.record,row.expectedVersion,encodeCatalogueValues(row.before),encodeCatalogueValues(row.after))}
    featureEvent(tenantId,"catalogue.bulk_updated",batch.id)
    batch
}

/** Direct restore validation: the caller's backup authority belongs to the original shop. */
internal fun PosRepository.validateBulkCatalogueUpdates(tenantId:String) {
    val batches=catalogueBatchesUnchecked(tenantId)
    val events=featureQuery("SELECT entity_id,created_at FROM outbox WHERE tenant_id=? AND kind='catalogue.bulk_updated'",tenantId) {it.getString(1) to Instant.parse(it.getString(2))}
    require(events.size==batches.size && events.map {it.first}.toSet()==batches.map {it.id}.toSet()) {"Backup catalogue-batch history does not match its saved activity."}
    val items=products(tenantId,true).associateBy {it.id}
    batches.forEach {batch ->
        val time=Instant.parse(batch.createdAt)
        require(events.single {it.first==batch.id}.second>=time) {"Backup catalogue activity predates its batch."}
        require(catalogueBatchFingerprint(batch.rows,batch.reason)==batch.fingerprint && batch.rows.any {it.before!=it.after}) {"Backup catalogue-batch fingerprint or changes are invalid."}
        batch.rows.forEach {row ->
            val item=requireNotNull(items[row.productId]) {"Backup catalogue batch refers to a missing item."}
            require(row.before.unit==row.after.unit && row.before.precision==row.after.precision && item.unit==row.after.unit && item.quantityPrecision==row.after.precision) {"Backup catalogue batch changes an item's selling unit or precision."}
            val barcodeReleased=row.before.barcode!=null && row.before.barcode!=row.after.barcode
            val detailsNeeded=row.before.name!=row.after.name || row.before.priceMinor!=row.after.priceMinor || (if(barcodeReleased) null else row.before.barcode)!=row.after.barcode
            if(barcodeReleased) validateCatalogueDetailsEvent(tenantId,batch.id,row,"release-barcode",row.before.name,row.before.priceMinor,null,row.before.priceMinor)
            if(detailsNeeded) validateCatalogueDetailsEvent(tenantId,batch.id,row,"details",row.after.name,row.after.priceMinor,row.after.barcode,row.before.priceMinor)
            val skuReleased=row.before.metadata.sku!=null && row.before.metadata.sku!=row.after.metadata.sku
            if(skuReleased) validateCatalogueMetadataEvent(tenantId,batch.id,row,"release-sku",row.before.metadata,row.before.metadata.copy(sku=null))
            val stagedMetadata=if(skuReleased) row.before.metadata.copy(sku=null) else row.before.metadata
            if(stagedMetadata!=row.after.metadata) validateCatalogueMetadataEvent(tenantId,batch.id,row,"metadata",stagedMetadata,row.after.metadata)
            if(row.before.reorderLevel!=row.after.reorderLevel) {
                val event=featureQuery("SELECT product_id,previous_reorder_level,reorder_level FROM stock_settings_changes WHERE tenant_id=? AND id=?",tenantId,catalogueChildId(batch.id,row.productId,"reorder")) {Triple(it.getString(1),(it.getObject(2) as? Number)?.toInt(),(it.getObject(3) as? Number)?.toInt())}.singleOrNull()
                require(event==Triple(row.productId,row.before.reorderLevel,row.after.reorderLevel)) {"Backup catalogue reorder change does not match its batch."}
            }
        }
    }
}

private fun PosRepository.cataloguePreview(tenantId:String,rows:List<BulkCatalogueUpdateRow>):BulkCataloguePreview {
    require(rows.size<=10000) {"Review at most 10,000 records at once."}
    val items=products(tenantId,true).associateBy {it.id}
    val issues=mutableListOf<BulkCatalogueIssue>()
    rows.groupBy {it.productId}.values.filter {it.size>1}.forEach {group ->group.forEach {issues+=BulkCatalogueIssue(it.record,"Product ID appears more than once in this file.")}}
    rows.groupBy {it.record}.values.filter {it.size>1}.forEach {group ->issues+=BulkCatalogueIssue(group.first().record,"Record number appears more than once in this review.")}
    val preview=rows.map {row ->
        val item=items[row.productId]
        when {
            item==null ->issues+=BulkCatalogueIssue(row.record,"Product ID does not belong to this shop.")
            item.archived ->issues+=BulkCatalogueIssue(row.record,"Restore this archived item before changing its catalogue.")
            row.values.unit!=item.unit || row.values.precision!=item.quantityPrecision ->issues+=BulkCatalogueIssue(row.record,"Selling unit and quantity precision must remain unchanged.")
            row.expectedVersion!=catalogueVersion(tenantId,item) ->issues+=BulkCatalogueIssue(row.record,"Catalogue changed after export. Export fresh values and review the edits again.")
        }
        BulkCataloguePreviewRow(row,item?.bulkCatalogueValues())
    }
    val requested=rows.associateBy {it.productId}
    val finalValues=items.mapValues {(id,item)->requested[id]?.values ?: item.bulkCatalogueValues()}
    val barcodes=finalValues.mapNotNull {(id,value)->value.barcode?.let {it to id}}+sellingUnits(tenantId,includeArchived=true).mapNotNull {it.barcode?.let {code->code to (it.productId+"~"+it.id)}}
    barcodes.groupBy {it.first}.values.filter {it.size>1}.forEach {group ->rows.filter {row->group.any {it.second==row.productId}}.forEach {issues+=BulkCatalogueIssue(it.record,"Barcode would duplicate another item or selling pack, including archived goods.")}}
    finalValues.entries.filter {it.value.metadata.sku!=null}.groupBy {it.value.metadata.sku!!.lowercase(Locale.ROOT)}.values.filter {it.size>1}.forEach {group ->rows.filter {row->group.any {it.key==row.productId}}.forEach {issues+=BulkCatalogueIssue(it.record,"SKU would duplicate another item, including archived goods.")}}
    return BulkCataloguePreview(preview,issues.distinct().sortedBy {it.record})
}
private fun PosRepository.catalogueValuesUnchecked(tenant:String,id:String):BulkCatalogueValues = featureQuery("SELECT name,unit,quantity_precision,price_minor,barcode,sku,category,brand,aliases,favourite,reorder_level FROM products WHERE tenant_id=? AND id=?",tenant,id) {r ->
    BulkCatalogueValues(r.getString(1),r.getString(2),r.getInt(3),r.getLong(4),r.getString(5),ProductMetadata(r.getString(6),r.getString(7),r.getString(8),decodeProductAliases(r.getString(9)),r.getInt(10)==1),(r.getObject(11) as? Number)?.toInt())
}.single()
private fun PosRepository.catalogueVersion(tenantId:String,item:Product):String {
    val events=featureQuery("SELECT id,'details' AS kind,created_at FROM catalogue_changes WHERE tenant_id=? AND product_id=? UNION ALL SELECT id,'metadata',created_at FROM product_metadata_changes WHERE tenant_id=? AND product_id=? UNION ALL SELECT id,'reorder',created_at FROM stock_settings_changes WHERE tenant_id=? AND product_id=? ORDER BY kind,id",tenantId,item.id,tenantId,item.id,tenantId,item.id) {listOf(it.getString(1),it.getString(2),it.getString(3))}
    val values=listOf("bulk-catalogue-v1",tenantId,item.id,encodeCatalogueValues(item.bulkCatalogueValues()),item.archived.toString())+events.flatMap {it}
    return catalogueHash(values.joinToString("") {it.length.toString()+":"+it}.toByteArray(Charsets.UTF_8))
}
private fun catalogueChildId(batch:String,product:String,kind:String)="bulk-catalogue:"+catalogueHash(listOf(batch,product,kind).joinToString("") {it.length.toString()+":"+it}.toByteArray(Charsets.UTF_8))
private fun catalogueRequestFingerprint(rows:List<BulkCatalogueUpdateRow>,reason:String):String = catalogueHash((listOf(reason)+rows.sortedBy {it.productId}.flatMap {listOf(it.record.toString(),it.productId,it.expectedVersion,encodeCatalogueValues(it.values))}).joinToString("") {it.length.toString()+":"+it}.toByteArray(Charsets.UTF_8))
private fun catalogueBatchFingerprint(rows:List<BulkCatalogueBatchRow>,reason:String):String = catalogueHash((listOf(reason)+rows.sortedBy {it.productId}.flatMap {listOf(it.record.toString(),it.productId,it.expectedVersion,encodeCatalogueValues(it.before),encodeCatalogueValues(it.after))}).joinToString("") {it.length.toString()+":"+it}.toByteArray(Charsets.UTF_8))
private fun catalogueHash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}

private fun PosRepository.validateCatalogueDetailsEvent(tenant:String,batch:String,row:BulkCatalogueBatchRow,kind:String,name:String,price:Long,barcode:String?,previous:Long) {
    val event=featureQuery("SELECT product_id,kind,name,unit,price_minor,previous_price_minor,barcode FROM catalogue_changes WHERE tenant_id=? AND id=?",tenant,catalogueChildId(batch,row.productId,kind)) {listOf(it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getLong(5).toString(),it.getLong(6).toString(),it.getString(7))}.singleOrNull()
    require(event==listOf(row.productId,"edit",name,row.after.unit,price.toString(),previous.toString(),barcode)) {"Backup catalogue detail change does not match its batch."}
}
private fun PosRepository.validateCatalogueMetadataEvent(tenant:String,batch:String,row:BulkCatalogueBatchRow,kind:String,before:ProductMetadata,after:ProductMetadata) {
    val event=productMetadataChanges(tenant,row.productId).singleOrNull {it.id==catalogueChildId(batch,row.productId,kind)}
    require(event!=null && event.previous==before && event.current==after) {"Backup catalogue metadata change does not match its batch."}
}
private fun PosRepository.catalogueBatchesUnchecked(tenant:String)=featureQuery("SELECT id FROM catalogue_update_batches WHERE tenant_id=? ORDER BY created_at DESC,id",tenant) {it.getString(1)}.map {requireNotNull(catalogueBatchUnchecked(tenant,it))}
private fun PosRepository.catalogueBatchUnchecked(tenant:String,id:String):BulkCatalogueBatch? = featureQuery("SELECT * FROM catalogue_update_batches WHERE tenant_id=? AND id=?",tenant,id) {r ->
    val rows=featureQuery("SELECT * FROM catalogue_update_batch_rows WHERE tenant_id=? AND batch_id=? ORDER BY record",tenant,id) {line->BulkCatalogueBatchRow(line.getString("product_id"),line.getInt("record"),line.getString("expected_version"),decodeCatalogueValues(line.getString("before_values")),decodeCatalogueValues(line.getString("after_values")))}
    require(rows.size==r.getInt("row_count")) {"Catalogue batch has missing rows."}
    BulkCatalogueBatch(id,tenant,r.getString("reason"),r.getString("created_at"),r.getString("fingerprint"),rows)
}.singleOrNull()

private fun encodeCatalogueValues(value:BulkCatalogueValues):String {
    val bytes=ByteArrayOutputStream()
    DataOutputStream(bytes).use {out ->
        fun text(s:String) {val b=s.toByteArray(Charsets.UTF_8);require(b.size<=100000);out.writeInt(b.size);out.write(b)}
        fun optional(s:String?) {out.writeBoolean(s!=null);s?.let(::text)}
        out.writeInt(1);text(value.name);text(value.unit);out.writeInt(value.precision);out.writeLong(value.priceMinor);optional(value.barcode)
        optional(value.metadata.sku);optional(value.metadata.category);optional(value.metadata.brand)
        out.writeInt(value.metadata.aliases.size);value.metadata.aliases.forEach(::text);out.writeBoolean(value.metadata.favourite)
        out.writeBoolean(value.reorderLevel!=null);value.reorderLevel?.let(out::writeInt)
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}
private fun decodeCatalogueValues(encoded:String):BulkCatalogueValues {
    require(encoded.length<=1_000_000) {"Catalogue batch values exceed the supported size."}
    val value=DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use {input ->
        fun text():String {val n=input.readInt();require(n in 0..100000 && n<=input.available());return ByteArray(n).also(input::readFully).toString(Charsets.UTF_8)}
        fun optional():String?=if(input.readBoolean()) text() else null
        require(input.readInt()==1) {"Catalogue batch format is not supported."}
        val name=text();val unit=text();val precision=input.readInt();val price=input.readLong();val barcode=optional()
        val sku=optional();val category=optional();val brand=optional();val aliasesCount=input.readInt();require(aliasesCount in 0..20)
        val aliases=(0 until aliasesCount).map {text()};val favourite=input.readBoolean();val reorder=if(input.readBoolean()) input.readInt() else null
        require(input.read()==-1) {"Catalogue batch contains trailing data."}
        BulkCatalogueValues(name,unit,precision,price,barcode,ProductMetadata(sku,category,brand,aliases,favourite),reorder)
    }
    require(encodeCatalogueValues(value)==encoded) {"Catalogue batch values are not canonical."}
    return value
}
