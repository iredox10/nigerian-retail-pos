package pos.data

import pos.domain.*
import java.io.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val supplierProfileSnapshotTables = listOf("supplier_profiles","supplier_profile_changes","supplier_receipt_links")

/** Called on the owning connection during startup; these tables participate in the business backup. */
internal fun PosRepository.initializeSupplierProfiles() {
    featureExecute("CREATE TABLE IF NOT EXISTS supplier_profiles(id TEXT NOT NULL,tenant_id TEXT NOT NULL REFERENCES tenants(id),details TEXT NOT NULL,archived INTEGER NOT NULL CHECK(archived IN(0,1)),version INTEGER NOT NULL CHECK(version>0),created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id))")
    featureExecute("CREATE TABLE IF NOT EXISTS supplier_profile_changes(id TEXT NOT NULL,tenant_id TEXT NOT NULL,supplier_id TEXT NOT NULL,previous_details TEXT,current_details TEXT NOT NULL,previous_archived INTEGER CHECK(previous_archived IS NULL OR previous_archived IN(0,1)),current_archived INTEGER NOT NULL CHECK(current_archived IN(0,1)),version INTEGER NOT NULL CHECK(version>0),reason TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,supplier_id,version),FOREIGN KEY(tenant_id,supplier_id) REFERENCES supplier_profiles(tenant_id,id))")
    featureExecute("CREATE TABLE IF NOT EXISTS supplier_receipt_links(tenant_id TEXT NOT NULL,receipt_id TEXT NOT NULL,supplier_id TEXT NOT NULL,operation_id TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,receipt_id),UNIQUE(tenant_id,operation_id),FOREIGN KEY(tenant_id,supplier_id) REFERENCES supplier_profiles(tenant_id,id),FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id))")
    listOf("supplier_profile_changes","supplier_receipt_links").forEach { table ->
        featureExecute("CREATE TRIGGER IF NOT EXISTS ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'Supplier history cannot be edited'); END")
        featureExecute("CREATE TRIGGER IF NOT EXISTS ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'Supplier history cannot be removed'); END")
    }
}

fun PosRepository.supplierProfiles(tenantId:String,includeArchived:Boolean=false):List<SupplierProfile> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    featureQuery("SELECT * FROM supplier_profiles WHERE tenant_id=?"+(if(includeArchived) "" else " AND archived=0")+" ORDER BY created_at,id",tenantId,map=::supplierProfile)
}
fun PosRepository.supplierProfileChanges(tenantId:String,supplierId:String):List<SupplierProfileChange> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    featureQuery("SELECT * FROM supplier_profile_changes WHERE tenant_id=? AND supplier_id=? ORDER BY version",tenantId,supplierId,map=::supplierChange)
}
fun PosRepository.supplierReceiptLinks(tenantId:String):List<SupplierReceiptLink> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    featureQuery("SELECT * FROM supplier_receipt_links WHERE tenant_id=? ORDER BY created_at,receipt_id",tenantId,map=::supplierLink)
}
fun PosRepository.addSupplierProfile(tenantId:String,details:SupplierDetails,operationId:String=UUID.randomUUID().toString()):SupplierProfile = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validSupplierOperation(operationId)
    val existing=featureQuery("SELECT * FROM supplier_profile_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::supplierChange).singleOrNull()
    if(existing!=null) {
        require(existing.supplierId==operationId && existing.version==1L && existing.current==details) { "This supplier request was already saved with different details." }
        return@featureWrite requireSupplierProfile(tenantId,operationId)
    }
    val profile=SupplierProfile(operationId,tenantId,details,false,1,Instant.now().toString())
    featureExecute("INSERT INTO supplier_profiles VALUES(?,?,?,?,?,?)",profile.id,tenantId,encodeDetails(details),0,1,profile.createdAt)
    featureExecute("INSERT INTO supplier_profile_changes VALUES(?,?,?,?,?,?,?,?,?,?)",operationId,tenantId,profile.id,null,encodeDetails(details),null,0,1,"Supplier added",profile.createdAt)
    featureEvent(tenantId,"supplier.created",profile.id)
    profile
}
fun PosRepository.updateSupplierProfile(tenantId:String,supplierId:String,details:SupplierDetails,archived:Boolean,expectedVersion:Long,reason:String,operationId:String=UUID.randomUUID().toString()):SupplierProfile = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validSupplierOperation(operationId)
    val previous=featureQuery("SELECT * FROM supplier_profile_changes WHERE tenant_id=? AND id=?",tenantId,operationId,map=::supplierChange).singleOrNull()
    if(previous!=null) {
        require(previous.supplierId==supplierId && previous.current==details && previous.currentArchived==archived && previous.version==Math.addExact(expectedVersion,1) && previous.reason==reason) { "This supplier change was already saved with different details." }
        return@featureWrite requireSupplierProfile(tenantId,supplierId)
    }
    val old=requireSupplierProfile(tenantId,supplierId)
    require(old.version==expectedVersion) { "This supplier changed while you were editing. Reopen the profile and review it." }
    require(old.details!=details || old.archived!=archived) { "Change a detail or the archive status before saving." }
    val now=Instant.now()
    val last=featureQuery("SELECT created_at FROM supplier_profile_changes WHERE tenant_id=? AND supplier_id=? ORDER BY version DESC LIMIT 1",tenantId,supplierId) {Instant.parse(it.getString(1))}.single()
    require(!now.isBefore(last)) {"The computer clock moved backwards. Correct it before editing this supplier."}
    val event=SupplierProfileChange(operationId,tenantId,supplierId,old.details,details,old.archived,archived,Math.addExact(old.version,1),reason,now.toString())
    featureExecute("UPDATE supplier_profiles SET details=?,archived=?,version=? WHERE tenant_id=? AND id=?",encodeDetails(details),if(archived)1 else 0,event.version,tenantId,supplierId)
    featureExecute("INSERT INTO supplier_profile_changes VALUES(?,?,?,?,?,?,?,?,?,?)",event.id,tenantId,supplierId,encodeDetails(old.details),encodeDetails(details),if(old.archived)1 else 0,if(archived)1 else 0,event.version,event.reason,event.createdAt)
    featureEvent(tenantId,"supplier.updated",supplierId)
    old.copy(details=details,archived=archived,version=event.version)
}

/** Explicit linking records identity without changing the supplier name or money on the receipt. */
fun PosRepository.linkSupplierReceipt(tenantId:String,receiptId:String,supplierId:String,operationId:String=UUID.randomUUID().toString()):SupplierReceiptLink = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validSupplierOperation(operationId)
    val replay=featureQuery("SELECT * FROM supplier_receipt_links WHERE tenant_id=? AND operation_id=?",tenantId,operationId,map=::supplierLink).singleOrNull()
    if(replay!=null) {require(replay.receiptId==receiptId && replay.supplierId==supplierId) {"This receipt link was already saved with different details."};return@featureWrite replay}
    val supplier=requireSupplierProfile(tenantId,supplierId)
    require(!supplier.archived) {"Restore this supplier before linking purchases."}
    require(stockReceipts(tenantId).any {it.id==receiptId}) {"Stock receipt does not belong to this shop."}
    require(featureQuery("SELECT receipt_id FROM supplier_receipt_links WHERE tenant_id=? AND receipt_id=?",tenantId,receiptId) {it.getString(1)}.isEmpty()) {"This receipt is already linked to a supplier. Its identity cannot be silently changed."}
    val link=SupplierReceiptLink(tenantId,receiptId,supplierId,operationId,Instant.now().toString())
    featureExecute("INSERT INTO supplier_receipt_links VALUES(?,?,?,?,?)",tenantId,receiptId,supplierId,operationId,link.createdAt)
    featureEvent(tenantId,"supplier.receipt_linked",receiptId)
    link
}

/** Receipt creation and profile linking commit or roll back together on the existing connection. */
fun PosRepository.receiveStockForSupplier(tenantId:String,productId:String,quantity:Int,unitCostMinor:Long,paidCashMinor:Long,supplierId:String,operationId:String,purchaseUnitId:String?=null,expectedBaseUnitsPerUnit:Int?=null,expectedSupplierVersion:Long?=null):StockReceipt = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validSupplierOperation(operationId)
    val saved=featureQuery("SELECT * FROM supplier_receipt_links WHERE tenant_id=? AND receipt_id=?",tenantId,operationId,map=::supplierLink).singleOrNull()
    val profile=requireSupplierProfile(tenantId,supplierId)
    require(saved!=null || expectedSupplierVersion==null || profile.version==expectedSupplierVersion) {"Supplier details changed. Reopen stock receiving and review the supplier."}
    require(saved!=null || !profile.archived) {"Restore this supplier before receiving more goods."}
    require(saved==null || saved.supplierId==supplierId) {"This purchase was already linked to another supplier."}
    // A retry uses the historical name, even after a profile rename.
    val name=if(saved==null) profile.details.name else stockReceipts(tenantId).single {it.id==operationId}.supplierName
    val receipt=receiveStock(tenantId,productId,quantity,unitCostMinor,paidCashMinor,name,operationId,purchaseUnitId,expectedBaseUnitsPerUnit)
    if(saved==null) linkSupplierReceipt(tenantId,receipt.id,supplierId,"receive-link:"+UUID.nameUUIDFromBytes(operationId.toByteArray(Charsets.UTF_8)).toString())
    receipt
}

internal fun PosRepository.validateSupplierProfileSnapshot(tenantId:String) {
    val profiles=featureQuery("SELECT * FROM supplier_profiles WHERE tenant_id=?",tenantId,map=::supplierProfile)
    profiles.forEach { profile ->
        Instant.parse(profile.createdAt)
        val changes=featureQuery("SELECT * FROM supplier_profile_changes WHERE tenant_id=? AND supplier_id=? ORDER BY version",tenantId,profile.id,map=::supplierChange)
        require(changes.isNotEmpty()) {"Backup supplier profile has no creation history."}
        changes.forEachIndexed { index,event ->
            Instant.parse(event.createdAt)
            require(event.version==index.toLong()+1 && (index==0 || (event.previous==changes[index-1].current && event.previousArchived==changes[index-1].currentArchived))) {"Backup supplier history is inconsistent."}
            require(index==0 || !Instant.parse(event.createdAt).isBefore(Instant.parse(changes[index-1].createdAt))) {"Backup supplier history dates are inconsistent."}
        }
        require(changes.last().current==profile.details && changes.last().currentArchived==profile.archived && changes.last().version==profile.version && changes.first().createdAt==profile.createdAt) {"Backup supplier profile does not match its history."}
    }
    featureQuery("SELECT * FROM supplier_receipt_links WHERE tenant_id=?",tenantId,map=::supplierLink).forEach {link ->
        Instant.parse(link.createdAt)
        require(profiles.any {it.id==link.supplierId}) {"Backup receipt link has no supplier."}
        require(stockReceipts(tenantId).any {it.id==link.receiptId}) {"Backup receipt link has no purchase."}
    }
}

private fun PosRepository.requireSupplierProfile(tenantId:String,id:String):SupplierProfile = featureQuery("SELECT * FROM supplier_profiles WHERE tenant_id=? AND id=?",tenantId,id,map=::supplierProfile).singleOrNull() ?: error("Supplier does not belong to this shop.")
private fun validSupplierOperation(id:String) {require(id.isNotBlank() && id.length<=200) {"Use a valid supplier operation reference."}}
private fun supplierProfile(r:ResultSet)=SupplierProfile(r.getString("id"),r.getString("tenant_id"),decodeDetails(r.getString("details")),r.getInt("archived")==1,r.getLong("version"),r.getString("created_at"))
private fun supplierChange(r:ResultSet)=SupplierProfileChange(r.getString("id"),r.getString("tenant_id"),r.getString("supplier_id"),r.getString("previous_details")?.let(::decodeDetails),decodeDetails(r.getString("current_details")),r.getObject("previous_archived")?.let {(it as Number).toInt()==1},r.getInt("current_archived")==1,r.getLong("version"),r.getString("reason"),r.getString("created_at"))
private fun supplierLink(r:ResultSet)=SupplierReceiptLink(r.getString("tenant_id"),r.getString("receipt_id"),r.getString("supplier_id"),r.getString("operation_id"),r.getString("created_at"))
private fun encodeDetails(value:SupplierDetails):String {
    val bytes=ByteArrayOutputStream()
    DataOutputStream(bytes).use {out -> out.writeInt(1);listOf(value.name,value.phone,value.contactPerson,value.address,value.paymentTerms,value.notes).forEach {out.writeBoolean(it!=null);if(it!=null)out.writeUTF(it)}}
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
}
private fun decodeDetails(value:String):SupplierDetails {
    require(value.length<=20_000) {"Supplier details are too large."}
    return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use {input ->
        require(input.readInt()==1) {"Supplier details format is not supported."}
        val fields=List(6) {if(input.readBoolean()) input.readUTF() else null}
        require(input.read()==-1) {"Supplier details contain unsupported data."}
        SupplierDetails(requireNotNull(fields[0]),fields[1],fields[2],fields[3],fields[4],fields[5])
    }
}
