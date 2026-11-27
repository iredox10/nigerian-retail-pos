package pos.android.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import pos.data.*
import pos.domain.*
import pos.output.SupplierInvoiceCsv
import pos.ui.components.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private data class AndroidInvoiceData(val invoices: List<SupplierInvoice>, val receipts: List<StockReceipt>)

@Composable
internal fun AndroidSupplierInvoices(repo: PosRepository, tenantId: String, profile: SupplierProfile, dismiss: () -> Unit, onChanged: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SupplierInvoice?>(null) }
    var history by remember { mutableStateOf<SupplierInvoice?>(null) }
    var visible by remember { mutableIntStateOf(20) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, profile.id to revision) {
        repo.featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) {
            val linked = supplierReceiptLinks(tenantId).filter { it.supplierId == profile.id }.map { it.receiptId }.toSet()
            AndroidInvoiceData(supplierInvoices(tenantId, profile.id), stockReceipts(tenantId).filter { it.id in linked })
        }
    }
    OperationsDialog("Supplier invoices", dismiss, "Close", action = { dismiss() }) {
        Text(profile.details.name, style = MaterialTheme.typography.titleMedium)
        Text("These records annotate existing buying receipts. They do not create a purchase, confirm payment or validate tax invoices.")
        OperationsLoadState(loaded, "supplier invoices", { revision++ })
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        OutlinedButton(enabled = loaded?.isSuccess == true && !profile.archived, onClick = { creating = true }) { Text("Record supplier invoice") }
        if (profile.archived) Text("Restore this supplier before recording an active invoice.")
        loaded?.getOrNull()?.let { data ->
            if (data.invoices.isEmpty()) Text("No supplier invoices recorded.")
            data.invoices.take(visible).forEach { invoice -> key(invoice.id) {
                HorizontalDivider()
                Text(invoice.details.number, style = MaterialTheme.typography.titleMedium)
                Text("Document date ${invoice.details.invoiceDate} · Revision ${invoice.version}")
                Text(if (invoice.status == SupplierInvoiceStatus.ACTIVE) "Active metadata · ${invoice.receiptIds.size} linked buying receipts" else "Voided metadata · previous evidence retained")
                OutlinedButton(onClick = { editing = invoice }) { Text("Review or correct invoice") }
                TextButton(onClick = { history = invoice }) { Text("Invoice history and evidence") }
            } }
            if (data.invoices.size > visible) TextButton(onClick = { visible += 20 }) { Text("Show more invoices") }
            AndroidReviewedCsvSave(repo, tenantId, StaffPermission.MANAGE_SUPPLIERS, "Preview invoice records CSV", "supplier-invoices-${profile.id}.csv") {
                SupplierInvoiceCsv.current(tenantId, data.invoices, data.receipts)
            }
        }
    }
    loaded?.getOrNull()?.let { data ->
        if (creating || editing != null) AndroidSupplierInvoiceEditor(repo, tenantId, profile, editing, data, { creating = false; editing = null }) {
            creating = false; editing = null; revision++; notice = "Invoice metadata saved with history. No stock, debt or cash was posted."; onChanged()
        }
        history?.let { invoice -> AndroidSupplierInvoiceHistory(repo, tenantId, invoice, data.receipts, { history = null }) }
    }
}

@Composable
private fun AndroidSupplierInvoiceHistory(repo: PosRepository, tenantId: String, invoice: SupplierInvoice, receipts: List<StockReceipt>, dismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var visible by remember { mutableIntStateOf(20) }
    val loaded = operationsLoad(repo, tenantId, invoice.id to revision) { repo.supplierInvoiceRevisions(tenantId, invoice.id) }
    OperationsDialog("Invoice history and evidence", dismiss, "Close", action = { dismiss() }) {
        OperationsLoadState(loaded, "invoice history", { revision++ })
        loaded?.getOrNull()?.let { history ->
            history.asReversed().take(visible).forEach { event -> key(event.invoice.version) {
                val saved = event.invoice
                HorizontalDivider()
                Text("Revision ${saved.version} · ${saved.updatedAt.take(19)} UTC", style = MaterialTheme.typography.titleMedium)
                Text("${saved.details.number} · ${saved.details.invoiceDate} · ${if (saved.status == SupplierInvoiceStatus.ACTIVE) "Active metadata" else "Voided metadata"}")
                Text(event.reason)
                saved.details.reference?.let { Text("Reference: $it") }
                saved.details.notes?.let { Text("Notes: $it") }
                saved.details.duplicateReviewReason?.let { Text("Repeated-number review: $it") }
                Text("Linked buying receipts: ${saved.receiptIds.joinToString { it.takeLast(8) }.ifBlank { "None on this revision" }}")
                AndroidInvoiceEvidenceSave(repo, tenantId, saved)
            } }
            if (history.size > visible) TextButton(onClick = { visible += 20 }) { Text("Show earlier invoice revisions") }
            AndroidReviewedCsvSave(repo, tenantId, StaffPermission.MANAGE_SUPPLIERS, "Preview invoice history CSV", "invoice-history-${invoice.id}.csv") {
                SupplierInvoiceCsv.history(tenantId, history, receipts)
            }
        }
    }
}

@Composable
private fun AndroidSupplierInvoiceEditor(repo: PosRepository, tenantId: String, profile: SupplierProfile, current: SupplierInvoice?, data: AndroidInvoiceData, dismiss: () -> Unit, saved: () -> Unit) {
    var number by rememberSaveable { mutableStateOf(current?.details?.number.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(current?.details?.invoiceDate ?: LocalDate.now(ZoneId.of("Africa/Lagos")).toString()) }
    var reference by rememberSaveable { mutableStateOf(current?.details?.reference.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(current?.details?.notes.orEmpty()) }
    var duplicateReason by rememberSaveable { mutableStateOf(current?.details?.duplicateReviewReason.orEmpty()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf(current?.status ?: SupplierInvoiceStatus.ACTIVE) }
    var selected by remember { mutableStateOf(current?.receiptIds.orEmpty().toSet()) }
    var upload by remember { mutableStateOf<SupplierInvoiceAttachmentUpload?>(null) }
    var removeAttachment by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var pickerBusy by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableIntStateOf(20) }
    val operation = rememberSaveable { UUID.randomUUID().toString() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailable = data.invoices.filter { it.status == SupplierInvoiceStatus.ACTIVE && it.id != current?.id }.flatMap { it.receiptIds }.toSet()
    val available = data.receipts.filter { it.id !in unavailable }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) pickerBusy = false
        else scope.launch {
            try {
                upload = withContext(Dispatchers.IO) {
                    repo.featureRead(tenantId, StaffPermission.MANAGE_SUPPLIERS) { Unit }
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "invoice-evidence"
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "This document cannot be read." }.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(count > 0) { "The document provider returned an empty read. Choose the file again." }; require(output.size() + count <= 1_048_576) { "Choose evidence up to 1 MB." }; output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                    SupplierInvoiceAttachmentUpload.fromBytes(name, bytes)
                }
                removeAttachment = false; reviewed = false; attachmentError = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { attachmentError = e.message ?: "This evidence document could not be read." }
            finally { pickerBusy = false }
        }
    }
    OperationsDialog(if (current == null) "Record supplier invoice" else "Correct supplier invoice", dismiss, if (reviewed) "Save invoice metadata" else "Review invoice metadata", action = {
        require(!pickerBusy) { "Finish choosing the evidence document before reviewing the invoice." }
        val details = SupplierInvoiceDetails(number.trim(), date.trim(), reference.trim().ifBlank { null }, notes.trim().ifBlank { null }, duplicateReason.trim().ifBlank { null })
        val targetStatus = status
        val ids = if (targetStatus == SupplierInvoiceStatus.VOIDED) emptyList() else selected.sorted()
        require(targetStatus == SupplierInvoiceStatus.VOIDED || (ids.isNotEmpty() && ids.size <= 200 && ids.all { id -> available.any { it.id == id } })) { "Select 1 to 200 available buying receipts linked to this supplier." }
        val correctionReason = reason.trim()
        if (current != null) require(correctionReason.length in 3..250 && correctionReason.none { it.isISOControl() }) { "Explain this correction in 3 to 250 printable characters." }
        val evidence = upload
        val retained = if (removeAttachment) null else current?.attachment
        if (!reviewed) reviewed = true else {
            withContext(Dispatchers.IO) {
                if (current == null) repo.recordSupplierInvoice(tenantId, profile.id, details, ids, evidence, operation)
                else repo.correctSupplierInvoice(tenantId, current.id, details, ids, targetStatus, retained, evidence, correctionReason, current.version, operation)
            }
            saved()
        }
    }) {
        Text("Metadata and document evidence only. Saved purchases and payments are not changed.")
        PosTextField(number, { number = it; reviewed = false }, "Number written on invoice")
        PosTextField(date, { date = it; reviewed = false }, "Invoice document date (YYYY-MM-DD)")
        PosTextField(reference, { reference = it; reviewed = false }, "Reference (optional)")
        PosTextField(notes, { notes = it; reviewed = false }, "Notes (optional)")
        PosTextField(duplicateReason, { duplicateReason = it; reviewed = false }, "Why is a repeated invoice number different? (if needed)")
        if (current != null) {
            OperationsCheck(status == SupplierInvoiceStatus.VOIDED, { status = if (it) SupplierInvoiceStatus.VOIDED else SupplierInvoiceStatus.ACTIVE; reviewed = false }, "Void current metadata and release its receipt links; retain history.")
            PosTextField(reason, { reason = it; reviewed = false }, "Why is this invoice record changing?")
        }
        if (status == SupplierInvoiceStatus.ACTIVE) {
            Text("Choose the buying receipts covered by this document. Only this supplier’s explicitly linked, available receipts are listed.")
            if (available.isEmpty()) Text("No available linked receipts. Receive goods with this supplier account first, or explicitly correct an existing invoice’s links.")
            available.take(visible).forEach { receipt -> OperationsCheck(receipt.id in selected, { checked -> selected = if (checked) selected + receipt.id else selected - receipt.id; reviewed = false }, "${receipt.productName} · ${receipt.id.takeLast(8)} · ${money(receipt.totalCostMinor)}") }
            if (available.size > visible) TextButton(onClick = { visible += 20 }) { Text("Show more buying receipts") }
            Text("${selected.size} receipts selected")
        }
        Text("PDF, PNG or JPEG evidence · up to 1 MB. Original saved documents remain in revision history.")
        OutlinedButton(enabled = !pickerBusy, onClick = {
            attachmentError = null; reviewed = false; pickerBusy = true
            runCatching { choose.launch(arrayOf("application/pdf", "image/png", "image/jpeg")) }.onFailure { pickerBusy = false; attachmentError = it.message }
        }) { Text(if (pickerBusy) "Reading evidence…" else "Choose invoice evidence") }
        (upload?.reference ?: current?.attachment?.takeUnless { removeAttachment })?.let { Text("Selected: ${it.fileName} · ${it.mediaType} · ${it.byteSize} bytes") }
        if (upload != null || (current?.attachment != null && !removeAttachment)) TextButton(onClick = { upload = null; removeAttachment = true; reviewed = false }) { Text("Remove evidence from this revision") }
        attachmentError?.let { InlineNotice("Evidence needs checking", it, NoticeTone.Danger) }
        if (reviewed) Text("Save invoice ${number.trim()} dated ${date.trim()} with ${if (status == SupplierInvoiceStatus.VOIDED) 0 else selected.size} current receipt links. No stock, cash or debt will be posted.")
    }
}

