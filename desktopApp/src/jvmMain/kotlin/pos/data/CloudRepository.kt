package pos.data

import pos.cloud.protocol.*
import pos.domain.*
import java.math.BigDecimal
import java.time.Instant

/** Trusted schema policy, shared by server and both installed clients. Never supplied by a peer. */
private val cloudMutableColumns=mapOf(
    "products" to setOf("aliases","archived","barcode","brand","category","favourite","name","price_minor","reorder_level","sku","stock","stock_scaled"),
    "customers" to setOf("address","balance_minor","credit_limit_minor","name","phone"),
    "cash_shifts" to setOf("closed_at","closing_blind","closing_count_sheet","counted_minor","expected_minor"),
    "purchase_units" to setOf("archived"), "selling_units" to setOf("archived"),
    "purchase_orders" to setOf("approved_at","cancellation_reason","cancelled_at","notes","status","supplier_id","supplier_name","supplier_version","version"),
    "quotations" to setOf("sale_id","snapshot","status","version"),
    "stock_count_sessions" to setOf("completed_at","reason","status","version"),
    "stock_count_session_lines" to setOf("counted_scaled"),
    "supplier_invoices" to setOf("attachment_hash","attachment_name","canonical_number","duplicate_review_reason","invoice_date","notes","number","reference","status","updated_at","version"),
    "supplier_profiles" to setOf("archived","details","version"),
    "repacking_recipes" to setOf("details","version","updated_at"),
    "price_lists" to setOf("details","version","updated_at"),
    "minimum_price_policies" to setOf("details","version","updated_at"),
    "shop_profiles" to setOf("details","version","updated_at"),
    "local_image_selections" to setOf("asset_id","version"),
)

fun PosRepository.cloudBusinessSchema():CloudSchema = synchronized(this) {
    CloudSchema(tables=cloudBusinessTables().map {table ->
        val columns=featureQuery("PRAGMA table_info($table)") {r ->
            CloudColumn(r.getString("name"),r.getString("type"),r.getInt("pk"),table in setOf("sale_lines","return_lines") && r.getString("name")=="id")
        }
        val keys=columns.filter {it.primaryKeyPosition>0 && !it.generated}.sortedBy {it.primaryKeyPosition}.map {it.name}
        val generated=columns.any {it.generated}
        val mutable=if(table=="purchase_order_lines") columns.map {it.name}.toSet()-keys else cloudMutableColumns[table].orEmpty()
        require(mutable.all {name ->columns.any {it.name==name}}) {"Cloud schema policy does not match $table. Update both clients and server."}
        CloudTable(table,columns,if(generated) emptyList() else keys,mutable,
            when(table) {"products" ->setOf("stock","stock_scaled");"customers" ->setOf("balance_minor");else ->emptySet()},
            allowDelete=table=="purchase_order_lines")
    })
}

fun PosRepository.cloudBusinessSnapshot(localTenantId:String,businessId:String):CloudSnapshot {
    val snapshot=exportCloudBusinessSnapshot(localTenantId)
    // Delivery is installation metadata: never send a local 'synced' flag as a business edit.
    return CloudMerge.normalize(CloudSnapshot(businessId,snapshot.tenant.name,snapshot.tables.mapValues {(table,rows) ->rows.map {
        it+("tenant_id" to businessId)+if(table=="outbox") mapOf("status" to "pending") else emptyMap()
    }}),cloudBusinessSchema())
}

/** Import into a new workspace under the signed-in owner's authority, never over existing records. */
fun PosRepository.importCloudBusinessShop(authorizingTenantId:String,importId:String,snapshot:CloudSnapshot,newName:String,provisionOwner:(Tenant)->Unit):Tenant = featureWrite(authorizingTenantId,StaffPermission.BACKUP) {
    require(importId.length in 1..200)
    featureSchema("CREATE TABLE IF NOT EXISTS cloud_imports(import_id TEXT PRIMARY KEY,tenant_id TEXT NOT NULL REFERENCES tenants(id))")
    featureQuery("SELECT tenant_id FROM cloud_imports WHERE import_id=?",importId) {it.getString(1)}.singleOrNull()?.let {id ->
        return@featureWrite tenants().single {it.id==id}
    }
    // restoreTenantSnapshot checks the existing owner's tenant before creating the new one.
    val rebound=TenantSnapshot(Tenant(authorizingTenantId,snapshot.tenantName),snapshot.tables.mapValues {(_,rows)->rows.map {it+("tenant_id" to authorizingTenantId)}})
    restoreTenantSnapshot(rebound,newName) {created ->
        provisionOwner(created)
        featureExecute("INSERT INTO cloud_imports VALUES(?,?)",importId,created.id)
    }
}

/** Expensive validation is done on an isolated database, never by deleting live trading history. */
fun validateCloudBusinessSnapshot(snapshot:CloudSnapshot) {
    PosRepository(":memory:").use {staging ->
        val normalized=CloudMerge.normalize(snapshot,staging.cloudBusinessSchema())
        staging.restoreTenantSnapshot(TenantSnapshot(Tenant(normalized.tenantId,normalized.tenantName),normalized.tables),normalized.tenantName)
    }
}

/** Source-linked stock checks supplement all the existing cash, debt, version and recovery checks. */
fun validateCloudBusinessMerge(before:CloudSnapshot,after:CloudSnapshot,delta:CloudDelta,baseRevision:Long,productVersions:Map<String,Long>):Set<String> {
    require(before.tenantId==after.tenantId) {"Cloud changes belong to another shop."}
    validateCloudBusinessSnapshot(after)
    fun load(snapshot:CloudSnapshot,block:(PosRepository,Tenant)->Unit) {
        PosRepository(":memory:").use {repo ->
            val tenant=repo.restoreTenantSnapshot(TenantSnapshot(Tenant(snapshot.tenantId,snapshot.tenantName),snapshot.tables),snapshot.tenantName)
            block(repo,tenant)
        }
    }
    data class Stock(val stock:BigDecimal,val movements:Map<String,InventoryMovement>)
    val previous=linkedMapOf<String,Stock>()
    load(before) {repo,tenant ->repo.products(tenant.id,true).forEach {p ->previous[p.id]=Stock(BigDecimal.valueOf(p.stock),repo.inventoryMovements(tenant.id,p.id).associateBy {it.id})}}
    val changed=linkedSetOf<String>()
    load(after) {repo,tenant ->repo.products(tenant.id,true).forEach {p ->
        val old=previous[p.id]
        val moves=repo.inventoryMovements(tenant.id,p.id).associateBy {it.id}
        if(old!=null) {
            require(old.movements.all {(id,m)->moves[id]?.quantityDelta==m.quantityDelta}) {"An existing stock movement changed. Saved financial history must remain intact."}
            val additions=moves.filterKeys {it !in old.movements}.values
            if(additions.isNotEmpty()) changed+=p.id
            if(additions.any {it.kind=="count"}) require((productVersions[p.id] ?: 0L)<=baseRevision) {"Stock moved on another device after this count's cloud baseline. Review the count; nothing was discarded."}
            val expected=additions.fold(old.stock) {sum,m ->sum.add(BigDecimal.valueOf(m.quantityDelta))}
            require(expected.compareTo(BigDecimal.valueOf(p.stock))==0) {"Stock does not match its saved sales, receipts, returns and corrections."}
        } else {
            require(p.openingStock!=null) {"A new cloud item needs its counted opening quantity."}
            val expected=moves.values.fold(BigDecimal.ZERO) {sum,m ->sum.add(BigDecimal.valueOf(m.quantityDelta))}
            require(expected.compareTo(BigDecimal.valueOf(p.stock))==0) {"A new item's stock does not match its opening count and saved movements."}
            changed+=p.id
        }
    }}
    require(previous.keys.all {id ->after.tables.getValue("products").any {it["id"]==id}}) {"Recorded products cannot be removed by synchronization."}
    return changed
}

/** Port called by the coordinator. A checkpoint marker closes the DB/file crash-recovery gap. */
fun PosRepository.applyCloudBusinessSnapshot(localTenantId:String,checkpointId:String,base:CloudSnapshot,remote:CloudSnapshot):CloudSnapshot = featureWrite(localTenantId,StaffPermission.SELL) {
    require(checkpointId.length in 1..200 && base.tenantId==remote.tenantId) {"Cloud checkpoint reference is invalid."}
    featureSchema("CREATE TABLE IF NOT EXISTS cloud_applied_checkpoints(tenant_id TEXT NOT NULL,checkpoint_id TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,checkpoint_id),FOREIGN KEY(tenant_id) REFERENCES tenants(id))")
    val schema=cloudBusinessSchema()
    if(featureQuery("SELECT checkpoint_id FROM cloud_applied_checkpoints WHERE tenant_id=? AND checkpoint_id=?",localTenantId,checkpointId) {it.getString(1)}.isNotEmpty()) return@featureWrite cloudBusinessSnapshot(localTenantId,remote.tenantId)
    val current=cloudBusinessSnapshot(localTenantId,remote.tenantId)
    val pending=CloudMerge.diff(base,current,schema)
    val result=CloudMerge.merge(remote,pending,schema)
    val merged=requireNotNull(result.snapshot) {result.conflicts.joinToString("; ") {it.message}.take(1000)}
    validateCloudBusinessSnapshot(merged)
    // Never let a peer erase or rewrite a completed local source record.
    val importDelta=CloudMerge.diff(current,merged,schema)
    validateCloudBusinessMerge(current,merged,importDelta,Long.MAX_VALUE,emptyMap())
    val checked=CloudMerge.merge(current,importDelta,schema)
    require(checked.snapshot!=null) {"Cloud changes would alter saved local history. Review the conflict."}
    val byName=schema.tables.associateBy {it.name}
    fun local(row:CloudRow)=row+("tenant_id" to localTenantId)
    fun where(table:CloudTable,row:CloudRow):Pair<String,Array<Any?>> {
        val keys=table.identityColumns.ifEmpty {row.keys.sorted()}
        require(keys.isNotEmpty() && keys.all {name ->table.columns.any {it.name==name}})
        return keys.joinToString(" AND ") {"$it IS ?"} to keys.map {local(row)[it]}.toTypedArray()
    }
    importDelta.changes.filter {it.after==null}.forEach {change ->
        val table=byName.getValue(change.table);require(table.allowDelete) {"Saved business history cannot be deleted."}
        val (clause,args)=where(table,requireNotNull(change.before))
        check(featureExecute("DELETE FROM ${table.name} WHERE $clause",*args)==1) {"Local records changed. Retry synchronization."}
    }
    // Insert parents and append-only sources in the same dependency order as encrypted recovery.
    cloudBusinessTables().forEach {name ->importDelta.changes.filter {it.table==name && it.before==null}.forEach {change ->
        val data=local(requireNotNull(change.after));val columns=data.keys.sorted()
        featureExecute("INSERT INTO $name (${columns.joinToString(",")}) VALUES(${columns.joinToString(",") {"?"}})",*columns.map {data[it]}.toTypedArray())
    }}
    // Existing draft children must be updated before their parent is finalized.
    cloudBusinessTables().asReversed().forEach {name ->importDelta.changes.filter {it.table==name && it.before!=null && it.after!=null}.forEach {change ->
        val table=byName.getValue(name);val old=requireNotNull(change.before);val after=requireNotNull(change.after)
        val columns=after.keys.filter {old[it]!=after[it]}
        require(columns.all {it in table.mutableColumns}) {"Cloud changes cannot rewrite a completed financial record."}
        if(columns.isNotEmpty()) {
            val (clause,args)=where(table,old);val data=local(after)
            check(featureExecute("UPDATE $name SET ${columns.joinToString(",") {"$it=?"}} WHERE $clause",*(columns.map {data[it]}+args.toList()).toTypedArray())==1) {"Local records changed. Retry synchronization."}
        }
    }}
    if(current.tenantName!=merged.tenantName) featureExecute("UPDATE tenants SET name=? WHERE id=?",merged.tenantName,localTenantId)
    // Only server-acknowledged source IDs are marked sent, never newer local work.
    remote.tables["outbox"].orEmpty().forEach {row ->featureExecute("UPDATE outbox SET status='synced' WHERE tenant_id=? AND id=?",localTenantId,row["id"])}
    featureExecute("INSERT INTO cloud_applied_checkpoints VALUES(?,?,?)",localTenantId,checkpointId,Instant.now().toString())
    cloudBusinessSnapshot(localTenantId,remote.tenantId)
}
