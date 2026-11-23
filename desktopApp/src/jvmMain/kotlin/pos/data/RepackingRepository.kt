package pos.data

import pos.domain.*
import java.io.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val repackingSnapshotTables=listOf("repacking_recipes","repacking_recipe_versions","repacking_runs")
internal fun PosRepository.initializeRepacking() {
    featureSchema("CREATE TABLE IF NOT EXISTS repacking_recipes(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),details TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),updated_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS repacking_recipe_versions(id TEXT NOT NULL,tenant_id TEXT NOT NULL,recipe_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),previous_details TEXT,details TEXT NOT NULL,reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,recipe_id,version),FOREIGN KEY(tenant_id,recipe_id) REFERENCES repacking_recipes(tenant_id,id))")
    featureSchema("CREATE TABLE IF NOT EXISTS repacking_runs(id TEXT NOT NULL,tenant_id TEXT NOT NULL,recipe_id TEXT NOT NULL,recipe_version INTEGER NOT NULL,batches INTEGER NOT NULL CHECK(batches>0),source_before INTEGER NOT NULL,source_after INTEGER NOT NULL,destination_before INTEGER NOT NULL,destination_after INTEGER NOT NULL,reason TEXT NOT NULL,created_at TEXT NOT NULL,review_token TEXT NOT NULL,PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,recipe_id,recipe_version) REFERENCES repacking_recipe_versions(tenant_id,recipe_id,version))")
    listOf("repacking_recipe_versions","repacking_runs").forEach {table ->listOf("UPDATE","DELETE").forEach {action ->featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_${action.lowercase()} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'Completed repacking history cannot be changed'); END")}}
}
fun PosRepository.repackingRecipes(tenantId:String):List<RepackingRecipe> =featureRead(tenantId,StaffPermission.MANAGE_STOCK) {recipeRows(tenantId)}
fun PosRepository.repackingHistory(tenantId:String):List<RepackingRun> =featureRead(tenantId,StaffPermission.MANAGE_STOCK) {runRows(tenantId)}
fun PosRepository.repackingRecipeHistory(tenantId:String,recipeId:String):List<RepackingRecipeChange> =featureRead(tenantId,StaffPermission.MANAGE_STOCK) {
    requireRecipe(tenantId,recipeId);recipeChanges(tenantId).filter {it.recipeId==recipeId}
}
fun PosRepository.saveRepackingRecipe(tenantId:String,recipeId:String,details:RepackingRecipeDetails,expectedVersion:Long,reason:String,operationId:String=UUID.randomUUID().toString()):RepackingRecipe =featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    validRepackId(recipeId);validRepackId(operationId);require(expectedVersion>=0);validRepackReason(reason)
    val replay=recipeChanges(tenantId).singleOrNull {it.id==operationId}
    if(replay!=null) {
        require(replay.recipeId==recipeId && replay.version==Math.addExact(expectedVersion,1) && replay.current==details && replay.reason==reason.trim()) {"This recipe request was already saved with different details."}
        return@featureWrite requireRecipe(tenantId,recipeId)
    }
    val old=recipeRows(tenantId).singleOrNull {it.id==recipeId}
    require((old?.version ?: 0)==expectedVersion) {"This recipe changed while the form was open. Reload and review it."}
    require(old?.details!=details) {"Change a recipe detail before saving."}
    validateCurrentRecipeItems(tenantId,details,allowArchived=details.archived)
    val now=Instant.now();require(old==null || now>=Instant.parse(old.updatedAt)) {"Correct the computer clock before editing this recipe."}
    val version=Math.addExact(expectedVersion,1)
    featureExecute("INSERT INTO repacking_recipes VALUES(?,?,?,?,?) ON CONFLICT(tenant_id,id) DO UPDATE SET details=excluded.details,version=excluded.version,updated_at=excluded.updated_at",recipeId,tenantId,encodeRecipe(details),version,now.toString())
    featureExecute("INSERT INTO repacking_recipe_versions VALUES(?,?,?,?,?,?,?,?)",operationId,tenantId,recipeId,version,old?.details?.let(::encodeRecipe),encodeRecipe(details),reason.trim(),now.toString())
    featureEvent(tenantId,"stock.repacking_recipe_saved",operationId)
    RepackingRecipe(recipeId,tenantId,details,version,now.toString())
}
fun PosRepository.repackingReview(tenantId:String,recipeId:String,batches:Long):RepackingReview =featureRead(tenantId,StaffPermission.MANAGE_STOCK) {reviewRepacking(tenantId,recipeId,batches)}
fun PosRepository.executeRepacking(tenantId:String,recipeId:String,batches:Long,reason:String,expected:RepackingReview,operationId:String=UUID.randomUUID().toString()):RepackingRun =featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    validRepackId(operationId);validRepackReason(reason)
    val old=runRows(tenantId).singleOrNull {it.id==operationId}
    if(old!=null) {
        require(old.recipeId==recipeId && old.batches==batches && old.reason==reason.trim() && old.recipeVersion==expected.recipeVersion && expected.recipeId==recipeId && expected.batches==batches && old.sourceBefore==expected.sourceBefore && old.destinationBefore==expected.destinationBefore && old.reviewToken==expected.token) {"This repacking operation was already saved with different details."}
        return@featureWrite old
    }
    val review=reviewRepacking(tenantId,recipeId,batches)
    require(expected==review) {"Stock, item details or this recipe changed after review. Review again; neither stock balance changed."}
    val recipe=requireRecipe(tenantId,recipeId);val d=recipe.details
    val source=requireRepackItem(tenantId,d.sourceId);val destination=requireRepackItem(tenantId,d.destinationId)
    val used=d.sourceQuantity(batches);val made=d.destinationQuantity(batches)
    val sourceAfter=Math.subtractExact(source.exactStockScaled,used);val destinationAfter=Math.addExact(destination.exactStockScaled,made)
    val now=Instant.now()
    val latest=(inventoryMovements(tenantId,source.id)+inventoryMovements(tenantId,destination.id)).maxOfOrNull {Instant.parse(it.createdAt)}
    require(now>=Instant.parse(recipe.updatedAt) && (latest==null || now>=latest)) {"Correct the computer clock before repacking."}
    val run=RepackingRun(operationId,tenantId,recipeId,recipe.version,d,batches,source.exactStockScaled,sourceAfter,destination.exactStockScaled,destinationAfter,reason.trim(),now.toString(),review.token)
    require(repackProjectionExact(sourceAfter,source.quantityPrecision) && repackProjectionExact(destinationAfter,destination.quantityPrecision)) {"The resulting stock exceeds the exact supported quantity range."}
    fun update(item:Product,after:Long) {check(featureExecute("UPDATE products SET stock_scaled=?,stock=? WHERE tenant_id=? AND id=? AND archived=0 AND quantity_precision=? AND (stock_scaled=? OR (stock_scaled IS NULL AND stock=?))",after,projectedQuantity(after,item.quantityPrecision),tenantId,item.id,item.quantityPrecision,item.exactStockScaled,item.stock)==1) {"Stock changed. Review the repacking again."}}
    update(source,sourceAfter);update(destination,destinationAfter)
    featureExecute("INSERT INTO repacking_runs VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",run.id,tenantId,recipeId,recipe.version,batches,run.sourceBefore,run.sourceAfter,run.destinationBefore,run.destinationAfter,run.reason,run.createdAt,run.reviewToken)
    featureEvent(tenantId,"stock.repacked",run.id)
    run
}
internal fun PosRepository.repackingMovements(tenantId:String,productId:String):List<InventoryMovement> =runRows(tenantId).mapNotNull {run ->
    val d=run.details
    when(productId) {
        d.sourceId ->InventoryMovement("repacking-out:${run.id}",tenantId,productId,"repacking_out",-projectedQuantity(d.sourceQuantity(run.batches),d.sourcePrecision),run.id,run.createdAt,"Used in ${d.name}: ${run.reason}")
        d.destinationId ->InventoryMovement("repacking-in:${run.id}",tenantId,productId,"repacking_in",projectedQuantity(d.destinationQuantity(run.batches),d.destinationPrecision),run.id,run.createdAt,"Packed from ${d.sourceName}: ${run.reason}")
        else ->null
    }
}
internal fun PosRepository.validateRepacking(tenantId:String) {
    val recipes=recipeRows(tenantId);val history=recipeChanges(tenantId);val runs=runRows(tenantId)
    require(recipes.map {it.id}.toSet()==history.map {it.recipeId}.toSet()) {"Backup repacking recipes have incomplete history."}
    recipes.forEach {recipe ->
        val versions=history.filter {it.recipeId==recipe.id}.sortedBy {it.version}
        versions.forEachIndexed {index,event ->
            require(event.version==index+1L && event.previous==versions.getOrNull(index-1)?.current) {"Backup repacking recipe revisions are inconsistent."}
            validRepackReason(event.reason);Instant.parse(event.createdAt)
            require(index==0 || Instant.parse(event.createdAt)>=Instant.parse(versions[index-1].createdAt)) {"Backup recipe chronology is inconsistent."}
            listOf(event.current.sourceId to (event.current.sourceUnit to event.current.sourcePrecision),event.current.destinationId to (event.current.destinationUnit to event.current.destinationPrecision)).forEach {(id,unit) ->
                val item=requireRepackItem(tenantId,id)
                require(item.unit==unit.first && item.quantityPrecision==unit.second) {"Backup repacking unit or precision differs from its catalogue item."}
                item.createdAt?.let {require(Instant.parse(event.createdAt)>=Instant.parse(it)) {"Backup repacking recipe predates its goods."}}
            }
        }
        val last=versions.last();require(recipe.version==last.version && recipe.details==last.current && recipe.updatedAt==last.createdAt) {"Backup recipe differs from its saved revision."}
    }
    fun witness(kind:String,records:List<Pair<String,String>>) {
        val events=featureQuery("SELECT entity_id,created_at FROM outbox WHERE tenant_id=? AND kind=?",tenantId,kind) {it.getString(1) to Instant.parse(it.getString(2))}
        require(events.size==records.size && events.map {it.first}.toSet()==records.map {it.first}.toSet()) {"Backup repacking activity is incomplete."}
        records.forEach {record ->require(events.single {it.first==record.first}.second>=Instant.parse(record.second)) {"Backup repacking activity predates its record."}}
    }
    witness("stock.repacking_recipe_saved",history.map {it.id to it.createdAt});witness("stock.repacked",runs.map {it.id to it.createdAt})
    runs.forEach {run ->
        val event=history.singleOrNull {it.recipeId==run.recipeId && it.version==run.recipeVersion} ?: error("Backup repacking run has no recipe revision.")
        val latest=history.filter {it.recipeId==run.recipeId && Instant.parse(it.createdAt)<=Instant.parse(run.createdAt)}.maxByOrNull {it.version}
        require(!event.current.archived && run.details==event.current && latest?.version==run.recipeVersion) {"Backup repacking run did not use the current active recipe revision."}
        listOf(run.sourceBefore to run.details.sourcePrecision,run.sourceAfter to run.details.sourcePrecision,run.details.sourceQuantity(run.batches) to run.details.sourcePrecision,run.destinationBefore to run.details.destinationPrecision,run.destinationAfter to run.details.destinationPrecision,run.details.destinationQuantity(run.batches) to run.details.destinationPrecision).forEach {(quantity,precision) ->require(repackProjectionExact(quantity,precision)) {"Backup repacking stock exceeds exact display projections."}}
    }
}
private fun PosRepository.reviewRepacking(tenantId:String,recipeId:String,batches:Long):RepackingReview {
    val recipe=requireRecipe(tenantId,recipeId);val d=recipe.details;require(!d.archived) {"Restore this recipe before repacking."}
    validateCurrentRecipeItems(tenantId,d,false)
    val source=requireRepackItem(tenantId,d.sourceId);val destination=requireRepackItem(tenantId,d.destinationId)
    val used=d.sourceQuantity(batches);val made=d.destinationQuantity(batches)
    require(used<=source.exactStockScaled) {"There is not enough ${source.name} for this many batches."}
    requireUnreservedStock(tenantId, source, used)
    val after=Math.addExact(destination.exactStockScaled,made)
    require(after<=9_007_199_254_740_991L && repackProjectionExact(after,d.destinationPrecision) && repackProjectionExact(source.exactStockScaled-used,d.sourcePrecision)) {"The resulting stock exceeds the exact supported range."}
    val fields=mutableListOf(recipe.id,recipe.version.toString(),encodeRecipe(d),batches.toString())
    listOf(source,destination).forEach {item ->
        fields+=listOf(item.id,item.name,item.unit,item.quantityPrecision.toString(),item.exactStockScaled.toString(),item.archived.toString())
        fields+=inventoryMovements(tenantId,item.id).map {listOf(it.id,it.kind,it.sourceId,it.quantityDelta.toString(),it.createdAt).joinToString("|")}
        fields+=featureQuery("SELECT id,created_at FROM catalogue_changes WHERE tenant_id=? AND product_id=? AND kind='archive' ORDER BY created_at,id",tenantId,item.id) {it.getString(1)+":"+it.getString(2)}
    }
    val token=MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") {it.length.toString()+":"+it}.toByteArray()).joinToString("") {"%02x".format(it)}
    return RepackingReview(recipeId,recipe.version,batches,source.exactStockScaled,destination.exactStockScaled,token)
}
private fun PosRepository.validateCurrentRecipeItems(tenantId:String,d:RepackingRecipeDetails,allowArchived:Boolean) {
    listOf(Triple(d.sourceId,d.sourceName,d.sourceUnit to d.sourcePrecision),Triple(d.destinationId,d.destinationName,d.destinationUnit to d.destinationPrecision)).forEach {(id,name,unit) ->
        val item=requireRepackItem(tenantId,id)
        require((allowArchived || !item.archived) && item.name==name && item.unit==unit.first && item.quantityPrecision==unit.second) {"Goods were renamed, archived or changed. Edit the recipe and choose the current goods again."}
        require(item.stockScaled!=null || item.stock==item.stock.toLong().toDouble()) {"Reconcile legacy fractional opening stock with a stock count before repacking."}
        require(repackProjectionExact(item.exactStockScaled,item.quantityPrecision) && projectedQuantity(item.exactStockScaled,item.quantityPrecision)==item.stock) {"Stock exceeds the exact supported projection range."}
    }
}
private fun PosRepository.requireRepackItem(tenantId:String,id:String)=requireNotNull(products(tenantId,true).singleOrNull {it.id==id}) {"Repacking goods do not belong to this shop."}
private fun PosRepository.requireRecipe(tenantId:String,id:String)=requireNotNull(recipeRows(tenantId).singleOrNull {it.id==id}) {"Repacking recipe does not belong to this shop."}
private fun PosRepository.recipeRows(tenantId:String)=featureQuery("SELECT * FROM repacking_recipes WHERE tenant_id=? ORDER BY updated_at DESC,id",tenantId) {RepackingRecipe(it.getString("id"),tenantId,decodeRecipe(it.getString("details")),it.getLong("version"),it.getString("updated_at"))}
private fun PosRepository.recipeChanges(tenantId:String)=featureQuery("SELECT * FROM repacking_recipe_versions WHERE tenant_id=? ORDER BY recipe_id,version",tenantId) {RepackingRecipeChange(it.getString("id"),it.getString("recipe_id"),it.getLong("version"),it.getString("previous_details")?.let(::decodeRecipe),decodeRecipe(it.getString("details")),it.getString("reason"),it.getString("created_at"))}
private fun PosRepository.runRows(tenantId:String):List<RepackingRun> =featureQuery("SELECT r.*,v.details AS recipe_details FROM repacking_runs r JOIN repacking_recipe_versions v ON v.tenant_id=r.tenant_id AND v.recipe_id=r.recipe_id AND v.version=r.recipe_version WHERE r.tenant_id=? ORDER BY r.created_at DESC,r.id",tenantId) {r ->RepackingRun(r.getString("id"),tenantId,r.getString("recipe_id"),r.getLong("recipe_version"),decodeRecipe(r.getString("recipe_details")),r.getLong("batches"),r.getLong("source_before"),r.getLong("source_after"),r.getLong("destination_before"),r.getLong("destination_after"),r.getString("reason"),r.getString("created_at"),r.getString("review_token"))}
private fun repackProjectionExact(scaled:Long,precision:Int)=scaled in 0..9_007_199_254_740_991L && BigDecimal.valueOf(projectedQuantity(scaled,precision)).movePointRight(precision).compareTo(BigDecimal.valueOf(scaled))==0
private fun validRepackId(value:String) {require(value.length in 1..200 && value.none {it.isISOControl()}) {"Repacking reference is invalid."}}
private fun validRepackReason(value:String) {require(value.trim().length in 3..500 && value.trim().none {it.isISOControl()}) {"Enter a reason from 3 to 500 printable characters."}}
private fun encodeRecipe(d:RepackingRecipeDetails):String=ByteArrayOutputStream().also {bytes ->DataOutputStream(bytes).use {out ->out.writeInt(1);listOf(d.name,d.sourceId,d.sourceName,d.sourceUnit,d.destinationId,d.destinationName,d.destinationUnit).forEach(out::writeUTF);out.writeInt(d.sourcePrecision);out.writeLong(d.sourcePerBatch);out.writeInt(d.destinationPrecision);out.writeLong(d.destinationPerBatch);out.writeBoolean(d.archived)}}.toByteArray().let {Base64.getEncoder().encodeToString(it)}
private fun decodeRecipe(value:String):RepackingRecipeDetails {
    require(value.length<=12000) {"Repacking recipe exceeds its supported size."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use {input ->
        require(input.readInt()==1);val fields=List(7) {input.readUTF()};val sourcePrecision=input.readInt();val source=input.readLong();val destinationPrecision=input.readInt();val destination=input.readLong();val archived=input.readBoolean();require(input.read()==-1)
        RepackingRecipeDetails(fields[0],fields[1],fields[2],fields[3],sourcePrecision,source,fields[4],fields[5],fields[6],destinationPrecision,destination,archived)
    }
}
