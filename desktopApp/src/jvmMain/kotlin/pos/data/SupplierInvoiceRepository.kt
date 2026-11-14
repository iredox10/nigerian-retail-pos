package pos.data

import pos.domain.*
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val supplierInvoiceSnapshotTables=listOf("supplier_invoice_attachments","supplier_invoices","supplier_invoice_versions","supplier_invoice_receipts")

internal fun PosRepository.initializeSupplierInvoices() {
    featureSchema("CREATE TABLE IF NOT EXISTS supplier_invoice_attachments(tenant_id TEXT NOT NULL REFERENCES tenants(id),hash TEXT NOT NULL,media_type TEXT NOT NULL,byte_size INTEGER NOT NULL CHECK(byte_size BETWEEN 1 AND 1048576),payload TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(tenant_id,hash))")
    featureSchema("CREATE TABLE IF NOT EXISTS supplier_invoices(tenant_id TEXT NOT NULL,id TEXT NOT NULL,supplier_id TEXT NOT NULL,number TEXT NOT NULL,canonical_number TEXT NOT NULL,invoice_date TEXT NOT NULL,reference TEXT,notes TEXT,duplicate_review_reason TEXT,status TEXT NOT NULL CHECK(status IN('ACTIVE','VOIDED')),version INTEGER NOT NULL CHECK(version>0),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,attachment_hash TEXT,attachment_name TEXT,CHECK((attachment_hash IS NULL)=(attachment_name IS NULL)),PRIMARY KEY(tenant_id,id),FOREIGN KEY(tenant_id,supplier_id) REFERENCES supplier_profiles(tenant_id,id),FOREIGN KEY(tenant_id,attachment_hash) REFERENCES supplier_invoice_attachments(tenant_id,hash))")
    featureSchema("CREATE UNIQUE INDEX IF NOT EXISTS supplier_invoice_number_date_active ON supplier_invoices(tenant_id,supplier_id,canonical_number,invoice_date) WHERE status='ACTIVE'")
    featureSchema("CREATE TABLE IF NOT EXISTS supplier_invoice_versions(tenant_id TEXT NOT NULL,invoice_id TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),operation_id TEXT NOT NULL,supplier_id TEXT NOT NULL,number TEXT NOT NULL,canonical_number TEXT NOT NULL,invoice_date TEXT NOT NULL,reference TEXT,notes TEXT,duplicate_review_reason TEXT,status TEXT NOT NULL CHECK(status IN('ACTIVE','VOIDED')),created_at TEXT NOT NULL,recorded_at TEXT NOT NULL,attachment_hash TEXT,attachment_name TEXT,reason TEXT NOT NULL,CHECK((attachment_hash IS NULL)=(attachment_name IS NULL)),PRIMARY KEY(tenant_id,invoice_id,version),UNIQUE(tenant_id,operation_id),FOREIGN KEY(tenant_id,invoice_id) REFERENCES supplier_invoices(tenant_id,id),FOREIGN KEY(tenant_id,supplier_id) REFERENCES supplier_profiles(tenant_id,id),FOREIGN KEY(tenant_id,attachment_hash) REFERENCES supplier_invoice_attachments(tenant_id,hash))")
    featureSchema("CREATE TABLE IF NOT EXISTS supplier_invoice_receipts(tenant_id TEXT NOT NULL,invoice_id TEXT NOT NULL,version INTEGER NOT NULL,receipt_id TEXT NOT NULL,PRIMARY KEY(tenant_id,invoice_id,version,receipt_id),FOREIGN KEY(tenant_id,invoice_id,version) REFERENCES supplier_invoice_versions(tenant_id,invoice_id,version),FOREIGN KEY(tenant_id,receipt_id) REFERENCES stock_receipts(tenant_id,id))")
    listOf("supplier_invoice_attachments","supplier_invoice_versions","supplier_invoice_receipts").forEach {table ->
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT,'Supplier invoice evidence cannot be edited'); END")
        featureSchema("CREATE TRIGGER IF NOT EXISTS ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'Supplier invoice evidence cannot be removed'); END")
    }
}

fun PosRepository.supplierInvoices(tenantId:String,supplierId:String):List<SupplierInvoice> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {invoicesUnchecked(tenantId).filter {it.supplierId==supplierId}}
fun PosRepository.supplierInvoiceRevisions(tenantId:String,invoiceId:String):List<SupplierInvoiceRevision> = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    requireInvoice(tenantId,invoiceId)
    invoiceRevisionsUnchecked(tenantId,invoiceId)
}
fun PosRepository.recordSupplierInvoice(tenantId:String,supplierId:String,details:SupplierInvoiceDetails,receiptIds:List<String>,attachment:SupplierInvoiceAttachmentUpload?=null,operationId:String=UUID.randomUUID().toString()):SupplierInvoice = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validInvoiceOperation(operationId)
    val receipts=canonicalInvoiceReceipts(receiptIds)
    val replay=invoiceRevisionByOperation(tenantId,operationId)
    if(replay!=null) {
        require(replay.invoice.id==operationId && replay.invoice.version==1L && replay.invoice.supplierId==supplierId && replay.invoice.details==details && replay.invoice.receiptIds==receipts && replay.invoice.attachment==attachment?.reference) {"This invoice request was already saved with different details."}
        return@featureWrite requireInvoice(tenantId,operationId)
    }
    require(activeInvoiceSupplier(tenantId,supplierId)) {"Restore this supplier before recording an invoice."}
    validateInvoiceAssociations(tenantId,supplierId,receipts,null)
    validateInvoiceDuplicate(tenantId,supplierId,details,null)
    val now=Instant.now().toString()
    attachment?.let {storeInvoiceAttachment(tenantId,it,now)}
    val invoice=SupplierInvoice(operationId,tenantId,supplierId,details,SupplierInvoiceStatus.ACTIVE,1,now,now,receipts,attachment?.reference)
    featureExecute("INSERT INTO supplier_invoices VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",tenantId,invoice.id,supplierId,details.number,details.canonicalNumber,details.invoiceDate,details.reference,details.notes,details.duplicateReviewReason,invoice.status.name,1,now,now,invoice.attachment?.hash,invoice.attachment?.fileName)
    appendInvoiceRevision(invoice,operationId,"Supplier invoice recorded")
    invoice
}

/** Corrections replace only the metadata projection; every prior version and attachment remains in history. */
fun PosRepository.correctSupplierInvoice(tenantId:String,invoiceId:String,details:SupplierInvoiceDetails,receiptIds:List<String>,status:SupplierInvoiceStatus,attachment:SupplierInvoiceAttachmentRef?,newAttachment:SupplierInvoiceAttachmentUpload?=null,reason:String,expectedVersion:Long,operationId:String=UUID.randomUUID().toString()):SupplierInvoice = featureWrite(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    validInvoiceOperation(operationId)
    val ids=canonicalInvoiceReceipts(receiptIds)
    val selectedAttachment=newAttachment?.reference ?: attachment
    val replay=invoiceRevisionByOperation(tenantId,operationId)
    if(replay!=null) {
        require(replay.invoice.id==invoiceId && replay.invoice.version==Math.addExact(expectedVersion,1) && replay.invoice.details==details && replay.invoice.receiptIds==ids && replay.invoice.status==status && replay.invoice.attachment==selectedAttachment && replay.reason==reason) {"This invoice correction was already saved with different details."}
        return@featureWrite requireInvoice(tenantId,invoiceId)
    }
    val old=requireInvoice(tenantId,invoiceId)
    require(old.version==expectedVersion) {"This invoice changed while you were editing. Reopen it and review the latest version."}
    val now=Instant.now();require(!now.isBefore(Instant.parse(old.updatedAt))) {"The computer clock moved backwards. Correct it before recording this correction."}
    if(status==SupplierInvoiceStatus.ACTIVE) {
        require(activeInvoiceSupplier(tenantId,old.supplierId)) {"Restore this supplier before changing active invoice records."}
        validateInvoiceAssociations(tenantId,old.supplierId,ids,invoiceId)
        validateInvoiceDuplicate(tenantId,old.supplierId,details,invoiceId)
    }
    if(newAttachment!=null) storeInvoiceAttachment(tenantId,newAttachment,now.toString())
    selectedAttachment?.let {require(attachmentInfoUnchecked(tenantId,it.hash,it.fileName)==it) {"Attachment metadata does not match this shop's saved evidence."}}
    val updated=old.copy(details=details,status=status,version=Math.addExact(old.version,1),updatedAt=now.toString(),receiptIds=ids,attachment=selectedAttachment)
    require(updated.copy(version=old.version,updatedAt=old.updatedAt)!=old) {"Change invoice details, receipt associations, attachment or status before saving."}
    val revision=SupplierInvoiceRevision(operationId,updated,reason)
    featureExecute("UPDATE supplier_invoices SET number=?,canonical_number=?,invoice_date=?,reference=?,notes=?,duplicate_review_reason=?,status=?,version=?,updated_at=?,attachment_hash=?,attachment_name=? WHERE tenant_id=? AND id=?",details.number,details.canonicalNumber,details.invoiceDate,details.reference,details.notes,details.duplicateReviewReason,status.name,updated.version,updated.updatedAt,selectedAttachment?.hash,selectedAttachment?.fileName,tenantId,invoiceId)
    appendInvoiceRevision(revision.invoice,operationId,reason)
    updated
}

fun PosRepository.supplierInvoiceAttachmentBytes(tenantId:String,invoiceId:String,version:Long?=null):ByteArray = featureRead(tenantId,StaffPermission.MANAGE_SUPPLIERS) {
    val invoice=if(version==null) requireInvoice(tenantId,invoiceId) else invoiceRevisionsUnchecked(tenantId,invoiceId).singleOrNull {it.invoice.version==version}?.invoice ?: error("Invoice revision does not belong to this shop.")
    val ref=requireNotNull(invoice.attachment) {"This invoice revision has no attachment."}
    val encoded=featureQuery("SELECT payload FROM supplier_invoice_attachments WHERE tenant_id=? AND hash=?",tenantId,ref.hash) {it.getString(1)}.singleOrNull() ?: error("Invoice attachment is unavailable.")
    val bytes=Base64.getDecoder().decode(encoded)
    require(SupplierInvoiceAttachmentUpload.fromBytes(ref.fileName,bytes).reference==ref) {"Saved invoice attachment failed its integrity check."}
    bytes
}

internal fun PosRepository.validateSupplierInvoiceSnapshot(tenantId:String) {
    val invoices=invoicesUnchecked(tenantId)
    val supplierIds=featureQuery("SELECT id FROM supplier_profiles WHERE tenant_id=?",tenantId) {it.getString(1)}.toSet()
    val receiptLinks=featureQuery("SELECT receipt_id,supplier_id FROM supplier_receipt_links WHERE tenant_id=?",tenantId) {it.getString(1) to it.getString(2)}.toMap()
    val claimed=mutableSetOf<String>()
    val referencedAttachments=mutableSetOf<String>()
    invoices.forEach {invoice ->
        require(invoice.supplierId in supplierIds) {"Backup invoice supplier is missing."}
        val versions=invoiceRevisionsUnchecked(tenantId,invoice.id)
        require(versions.isNotEmpty() && versions.first().invoice.version==1L && versions.first().invoice.createdAt==invoice.createdAt && versions.first().invoice.updatedAt==invoice.createdAt) {"Backup invoice creation history is incomplete."}
        versions.forEachIndexed {index,revision ->
            val historic=revision.invoice
            require(historic.version==index.toLong()+1 && historic.supplierId==invoice.supplierId && historic.createdAt==invoice.createdAt && !Instant.parse(historic.updatedAt).isBefore(Instant.parse(invoice.createdAt))) {"Backup invoice revision history is inconsistent."}
            if(index>0) require(!Instant.parse(historic.updatedAt).isBefore(Instant.parse(versions[index-1].invoice.updatedAt))) {"Backup invoice revision dates moved backwards."}
            historic.receiptIds.forEach {require(receiptLinks[it]==invoice.supplierId) {"Backup invoice refers to a receipt from a different supplier or missing purchase."}}
            historic.attachment?.let {ref ->
                referencedAttachments+=ref.hash
                val storedAt=featureQuery("SELECT created_at FROM supplier_invoice_attachments WHERE tenant_id=? AND hash=?",tenantId,ref.hash) {Instant.parse(it.getString(1))}.single()
                require(!storedAt.isAfter(Instant.parse(historic.updatedAt))) {"Backup invoice evidence was recorded after its revision."}
            }
        }
        require(versions.last().invoice==invoice) {"Backup invoice does not match its latest immutable revision."}
        val activity=featureQuery("SELECT kind FROM outbox WHERE tenant_id=? AND entity_id=? AND kind IN('supplier_invoice.recorded','supplier_invoice.corrected')",tenantId,invoice.id) {it.getString(1)}
        require(activity.count {it=="supplier_invoice.recorded"}==1 && activity.count {it=="supplier_invoice.corrected"}.toLong()==invoice.version-1) {"Backup invoice revision history does not match its recorded activity."}
        if(invoice.status==SupplierInvoiceStatus.ACTIVE) invoice.receiptIds.forEach {require(claimed.add(it)) {"Backup associates one purchase with more than one active invoice."}}
        require(invoice.details.canonicalNumber==featureQuery("SELECT canonical_number FROM supplier_invoices WHERE tenant_id=? AND id=?",tenantId,invoice.id) {it.getString(1)}.single()) {"Backup invoice duplicate-detection key is inconsistent."}
    }
    val attachments=featureQuery("SELECT * FROM supplier_invoice_attachments WHERE tenant_id=?",tenantId) {r ->
        val bytes=Base64.getDecoder().decode(r.getString("payload"))
        val upload=SupplierInvoiceAttachmentUpload.fromBytes("evidence",bytes)
        require(upload.reference.hash==r.getString("hash") && upload.reference.mediaType==r.getString("media_type") && upload.reference.byteSize.toLong()==r.getLong("byte_size")) {"Backup invoice attachment failed its integrity check."}
        Instant.parse(r.getString("created_at"));upload.reference.hash
    }.toSet()
    require(attachments==referencedAttachments) {"Backup invoice attachment references are incomplete or orphaned."}
    featureQuery("SELECT entity_id FROM outbox WHERE tenant_id=? AND kind IN('supplier_invoice.recorded','supplier_invoice.corrected')",tenantId) {it.getString(1)}.forEach {id ->require(invoices.any {it.id==id}) {"Backup invoice activity refers to missing records."}}
    // Review is based on what was already recorded when that revision was saved.
    invoices.forEach {invoice -> invoiceRevisionsUnchecked(tenantId,invoice.id).forEach {revision ->
        if(revision.invoice.status==SupplierInvoiceStatus.ACTIVE) {
            val earlier=featureQuery("SELECT recorded_at FROM supplier_invoice_versions WHERE tenant_id=? AND supplier_id=? AND canonical_number=? AND invoice_id<>?",tenantId,invoice.supplierId,revision.invoice.details.canonicalNumber,invoice.id) {Instant.parse(it.getString(1))}.any {it.isBefore(Instant.parse(revision.invoice.updatedAt))}
            require(!earlier || revision.invoice.details.duplicateReviewReason!=null) {"Backup repeated invoice number has no review reason."}
        }
    }}
}

private fun PosRepository.validateInvoiceAssociations(tenantId:String,supplierId:String,ids:List<String>,except:String?) {
    require(ids.isNotEmpty()) {"Select the purchases covered by this invoice."}
    val links=featureQuery("SELECT receipt_id,supplier_id FROM supplier_receipt_links WHERE tenant_id=?",tenantId) {it.getString(1) to it.getString(2)}.toMap()
    require(ids.all {links[it]==supplierId}) {"Every purchase must already be linked to this supplier account."}
    val used=featureQuery("SELECT r.receipt_id FROM supplier_invoice_receipts r JOIN supplier_invoices i ON i.tenant_id=r.tenant_id AND i.id=r.invoice_id AND i.version=r.version WHERE i.tenant_id=? AND i.status='ACTIVE' AND i.id<>?",tenantId,except.orEmpty()) {it.getString(1)}.toSet()
    require(ids.none {it in used}) {"A selected purchase already belongs to an active invoice. Correct that invoice explicitly before reassigning it."}
}
private fun PosRepository.validateInvoiceDuplicate(tenantId:String,supplierId:String,details:SupplierInvoiceDetails,except:String?) {
    val exact=featureQuery("SELECT id FROM supplier_invoices WHERE tenant_id=? AND supplier_id=? AND canonical_number=? AND invoice_date=? AND status='ACTIVE' AND id<>?",tenantId,supplierId,details.canonicalNumber,details.invoiceDate,except.orEmpty()) {it.getString(1)}.firstOrNull()
    require(exact==null) {"Duplicate invoice number and date for this supplier. Open invoice ${exact?.takeLast(8)} instead."}
    val reused=featureQuery("SELECT invoice_id FROM supplier_invoice_versions WHERE tenant_id=? AND supplier_id=? AND canonical_number=? AND invoice_id<>?",tenantId,supplierId,details.canonicalNumber,except.orEmpty()) {it.getString(1)}.isNotEmpty()
    require(!reused || details.duplicateReviewReason!=null) {"This supplier invoice number appears in another current or historical record. Explain why this is a different invoice before saving."}
}
private fun PosRepository.activeInvoiceSupplier(tenantId:String,id:String)=featureQuery("SELECT archived FROM supplier_profiles WHERE tenant_id=? AND id=?",tenantId,id) {it.getInt(1)==0}.singleOrNull()==true
private fun canonicalInvoiceReceipts(ids:List<String>):List<String> {require(ids.size<=200 && ids.distinct().size==ids.size && ids.all {it.isNotBlank()}) {"Choose up to 200 different purchases."};return ids.sorted()}
private fun validInvoiceOperation(id:String) {require(id.isNotBlank() && id.length<=200 && id.none {it.isISOControl()}) {"Use a valid invoice operation reference."}}
private fun PosRepository.invoicesUnchecked(tenantId:String)=featureQuery("SELECT * FROM supplier_invoices WHERE tenant_id=? ORDER BY created_at DESC,id",tenantId,map={invoiceHeader(it)})
private fun PosRepository.requireInvoice(tenantId:String,id:String)=featureQuery("SELECT * FROM supplier_invoices WHERE tenant_id=? AND id=?",tenantId,id,map={invoiceHeader(it)}).singleOrNull() ?: error("Invoice does not belong to this shop.")
private fun PosRepository.invoiceHeader(r:ResultSet):SupplierInvoice {
    val tenant=r.getString("tenant_id");val id=r.getString("id");val version=r.getLong("version")
    val ids=featureQuery("SELECT receipt_id FROM supplier_invoice_receipts WHERE tenant_id=? AND invoice_id=? AND version=? ORDER BY receipt_id",tenant,id,version) {it.getString(1)}
    return SupplierInvoice(id,tenant,r.getString("supplier_id"),invoiceDetails(r),SupplierInvoiceStatus.valueOf(r.getString("status")),version,r.getString("created_at"),r.getString("updated_at"),ids,r.getString("attachment_hash")?.let {attachmentInfoUnchecked(tenant,it,requireNotNull(r.getString("attachment_name")))})
}
private fun invoiceDetails(r:ResultSet)=SupplierInvoiceDetails(r.getString("number"),r.getString("invoice_date"),r.getString("reference"),r.getString("notes"),r.getString("duplicate_review_reason")).also {require(it.canonicalNumber==r.getString("canonical_number")) {"Saved invoice duplicate key is inconsistent."}}
private fun PosRepository.invoiceRevisionsUnchecked(tenantId:String,id:String)=featureQuery("SELECT * FROM supplier_invoice_versions WHERE tenant_id=? AND invoice_id=? ORDER BY version",tenantId,id,map={invoiceRevision(it)})
private fun PosRepository.invoiceRevisionByOperation(tenantId:String,id:String)=featureQuery("SELECT * FROM supplier_invoice_versions WHERE tenant_id=? AND operation_id=?",tenantId,id,map={invoiceRevision(it)}).singleOrNull()
private fun PosRepository.invoiceRevision(r:ResultSet):SupplierInvoiceRevision {
    val tenant=r.getString("tenant_id");val id=r.getString("invoice_id");val version=r.getLong("version")
    val ids=featureQuery("SELECT receipt_id FROM supplier_invoice_receipts WHERE tenant_id=? AND invoice_id=? AND version=? ORDER BY receipt_id",tenant,id,version) {it.getString(1)}
    return SupplierInvoiceRevision(r.getString("operation_id"),SupplierInvoice(id,tenant,r.getString("supplier_id"),invoiceDetails(r),SupplierInvoiceStatus.valueOf(r.getString("status")),version,r.getString("created_at"),r.getString("recorded_at"),ids,r.getString("attachment_hash")?.let {attachmentInfoUnchecked(tenant,it,requireNotNull(r.getString("attachment_name")))}),r.getString("reason"))
}
private fun PosRepository.attachmentInfoUnchecked(tenantId:String,hash:String,name:String)=featureQuery("SELECT media_type,byte_size FROM supplier_invoice_attachments WHERE tenant_id=? AND hash=?",tenantId,hash) {SupplierInvoiceAttachmentRef(hash,it.getString(1),Math.toIntExact(it.getLong(2)),name)}.singleOrNull() ?: error("Invoice attachment does not belong to this shop.")
private fun PosRepository.storeInvoiceAttachment(tenantId:String,upload:SupplierInvoiceAttachmentUpload,now:String) {
    val ref=upload.reference
    val existing=featureQuery("SELECT payload,created_at FROM supplier_invoice_attachments WHERE tenant_id=? AND hash=?",tenantId,ref.hash) {it.getString(1) to Instant.parse(it.getString(2))}.singleOrNull()
    val payload=Base64.getEncoder().encodeToString(upload.bytes())
    if(existing!=null) {
        require(existing.first==payload) {"Attachment hash conflicts with different saved bytes."}
        require(!existing.second.isAfter(Instant.parse(now))) {"The computer clock moved backwards. Correct it before reusing saved evidence."}
    }
    else featureExecute("INSERT INTO supplier_invoice_attachments VALUES(?,?,?,?,?,?)",tenantId,ref.hash,ref.mediaType,ref.byteSize,payload,now)
}
private fun PosRepository.appendInvoiceRevision(invoice:SupplierInvoice,operationId:String,reason:String) {
    val revision=SupplierInvoiceRevision(operationId,invoice,reason)
    featureExecute("INSERT INTO supplier_invoice_versions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",invoice.tenantId,invoice.id,invoice.version,operationId,invoice.supplierId,invoice.details.number,invoice.details.canonicalNumber,invoice.details.invoiceDate,invoice.details.reference,invoice.details.notes,invoice.details.duplicateReviewReason,invoice.status.name,invoice.createdAt,invoice.updatedAt,invoice.attachment?.hash,invoice.attachment?.fileName,revision.reason)
    invoice.receiptIds.forEach {receipt ->featureExecute("INSERT INTO supplier_invoice_receipts VALUES(?,?,?,?)",invoice.tenantId,invoice.id,invoice.version,receipt)}
    featureEvent(invoice.tenantId,if(invoice.version==1L) "supplier_invoice.recorded" else "supplier_invoice.corrected",invoice.id)
}
