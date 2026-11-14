package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.CsvService
import pos.output.SupplierInvoiceCsv
import pos.ui.components.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Supplier context only; no independent primary destination or financial posting action. */
@Composable
internal fun SupplierInvoicesDialog(repo:PosRepository,profile:SupplierProfile,dismiss:()->Unit,onChanged:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    var search by remember {mutableStateOf("")}
    var editing by remember {mutableStateOf<SupplierInvoice?>(null)}
    var creating by remember {mutableStateOf(false)}
    var historyId by remember {mutableStateOf<String?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val loaded=remember(profile.id,revision) {runCatching {Triple(repo.supplierInvoices(profile.tenantId,profile.id),repo.stockReceipts(profile.tenantId),repo.supplierReceiptLinks(profile.tenantId))}}
    val records=loaded.getOrNull()
    val linkedIds=records?.third?.filter {it.supplierId==profile.id}?.map {it.receiptId}?.toSet().orEmpty()
    val receipts=records?.second?.filter {it.id in linkedIds}.orEmpty()
    val invoices=records?.first.orEmpty()
    fun saved() {creating=false;editing=null;revision++;message="Supplier invoice metadata saved. Stock, cash and debt are unchanged.";error=null;onChanged()}
    if(creating || editing!=null) SupplierInvoiceEditor(repo,profile,editing,receipts,invoices,{creating=false;editing=null},::saved)
    AlertDialog(onDismissRequest=dismiss,title={Text("Supplier invoices · ${profile.details.name}")},text={Column(Modifier.widthIn(max=640.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Record numbers and evidence for purchases already saved. This does not create a purchase, payment, supplier credit or validated tax document.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            NeutralButton("Record supplier invoice",{creating=true},enabled=!profile.archived && loaded.isSuccess)
            NeutralButton("Refresh",{revision++})
        }
        if(profile.archived) Text("Restore this supplier to record or reactivate invoices. Existing records and corrections remain available.",style=MaterialTheme.typography.bodySmall)
        loaded.exceptionOrNull()?.let {InlineNotice("Invoice records unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
        PosTextField(search,{search=it},"Find an invoice",hint="Invoice number, date, reference or record ID")
        val visible=invoices.filter {listOf(it.id,it.details.number,it.details.invoiceDate,it.details.reference.orEmpty()).any {field ->field.contains(search,true)}}
        LazyColumn(Modifier.heightIn(max=390.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(visible.isEmpty()) item {EmptyState(if(invoices.isEmpty()) "No supplier invoice records" else "No matching invoice records","First link purchases to this supplier account, then record the invoice written on the supplier's document.")}
            items(visible,key={it.id}) {invoice ->Surface(shape=MaterialTheme.shapes.small) {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${invoice.details.number} · ${invoice.details.invoiceDate}",fontWeight=FontWeight.Medium)
                Text("${if(invoice.status==SupplierInvoiceStatus.ACTIVE) "Active metadata" else "Voided metadata"} · Revision ${invoice.version} · ${invoice.receiptIds.size} associated receipt(s)",style=MaterialTheme.typography.bodySmall)
                invoice.details.reference?.let {Text("Reference: $it",style=MaterialTheme.typography.bodySmall)}
                invoice.details.notes?.let {Text("Notes: $it",style=MaterialTheme.typography.bodySmall)}
                invoice.details.duplicateReviewReason?.let {Text("Repeated number reviewed: $it",style=MaterialTheme.typography.bodySmall)}
                invoice.attachment?.let {Text("Attachment: ${it.fileName} · ${it.mediaType} · ${it.byteSize} bytes",style=MaterialTheme.typography.bodySmall)}
                invoice.attachment?.takeIf {it.mediaType.startsWith("image/")}?.let {ref ->
                    InvoiceImagePreview(repo,profile.tenantId,invoice.id,invoice.version,ref)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton({editing=invoice}) {Text("Correct record")}
                    TextButton({historyId=if(historyId==invoice.id)null else invoice.id}) {Text(if(historyId==invoice.id) "Hide history" else "View history")}
                    if(invoice.attachment!=null) TextButton({try {
                        val ref=requireNotNull(invoice.attachment)
                        val chooser=JFileChooser().apply {dialogTitle="Save invoice attachment copy";selectedFile=java.io.File(ref.fileName)}
                        if(chooser.showSaveDialog(null)==JFileChooser.APPROVE_OPTION) {
                            val bytes=repo.supplierInvoiceAttachmentBytes(profile.tenantId,invoice.id)
                            Files.write(chooser.selectedFile.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                            message="Attachment copy saved. No invoice or financial record changed."
                        } else message="Attachment export cancelled."
                        error=null
                    } catch(e:Exception) {error=e.message ?: "Attachment copy could not be saved. Existing files are not overwritten."}}) {Text("Save file copy")}
                }
                if(historyId==invoice.id) {
                    val history=runCatching {repo.supplierInvoiceRevisions(profile.tenantId,invoice.id)}
                    history.exceptionOrNull()?.let {InlineNotice("History unavailable",it.message ?: "Refresh.",NoticeTone.Danger)}
                    history.getOrDefault(emptyList()).reversed().forEach {event ->
                        Text("Revision ${event.invoice.version} · ${event.invoice.updatedAt} · ${event.reason}",style=MaterialTheme.typography.bodySmall)
                        Text("${event.invoice.details.number} · ${event.invoice.details.invoiceDate} · ${event.invoice.status} · Receipts: ${event.invoice.receiptIds.joinToString {it.takeLast(8)}}",style=MaterialTheme.typography.bodySmall)
                        event.invoice.attachment?.let {ref ->
                            Text("Evidence retained: ${ref.fileName} · SHA-256 ${ref.hash}",style=MaterialTheme.typography.bodySmall)
                            if(ref.mediaType.startsWith("image/")) InvoiceImagePreview(repo,profile.tenantId,invoice.id,event.invoice.version,ref)
                            TextButton({try {
                                val chooser=JFileChooser().apply {dialogTitle="Save historical invoice evidence copy";selectedFile=java.io.File(ref.fileName)}
                                if(chooser.showSaveDialog(null)==JFileChooser.APPROVE_OPTION) {
                                    Files.write(chooser.selectedFile.toPath(),repo.supplierInvoiceAttachmentBytes(profile.tenantId,invoice.id,event.invoice.version),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                                    message="Historical evidence copy saved. Invoice history remains unchanged."
                                } else message="Attachment export cancelled."
                                error=null
                            } catch(e:Exception) {error=e.message ?: "Historical evidence could not be saved. Existing files are not overwritten."}}) {Text("Save revision ${event.invoice.version} file copy")}
                        }
                    }
                    NeutralButton("Save revision history CSV",{try {
                        val file=CsvService.save(SupplierInvoiceCsv.history(profile.tenantId,history.getOrThrow(),records!!.second),"supplier-invoice-history-${invoice.id.takeLast(8)}.csv")
                        message=if(file==null) "Export cancelled." else "Invoice history CSV saved to ${file.name}.";error=null
                    } catch(e:Exception) {error=e.message ?: "History CSV could not be saved."}},enabled=history.isSuccess)
                }
            }}}
        }
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        error?.let {InlineNotice("Could not finish",it,NoticeTone.Danger)}
    }},confirmButton={NeutralButton("Save invoice metadata CSV",{try {
        val current=loaded.getOrThrow()
        val file=CsvService.save(SupplierInvoiceCsv.current(profile.tenantId,current.first,current.second),"supplier-invoices-${profile.id.takeLast(8)}.csv")
        message=if(file==null) "Export cancelled." else "Invoice metadata CSV saved to ${file.name}.";error=null
    } catch(e:Exception) {error=e.message ?: "Invoice CSV could not be saved."}},enabled=loaded.isSuccess)},dismissButton={TextButton(dismiss) {Text("Close")}})
}

@Composable
private fun SupplierInvoiceEditor(repo:PosRepository,profile:SupplierProfile,invoice:SupplierInvoice?,receipts:List<StockReceipt>,allInvoices:List<SupplierInvoice>,dismiss:()->Unit,saved:()->Unit) {
    var number by remember {mutableStateOf(invoice?.details?.number.orEmpty())}
    var date by remember {mutableStateOf(invoice?.details?.invoiceDate ?: LocalDate.now(ZoneId.of("Africa/Lagos")).toString())}
    var reference by remember {mutableStateOf(invoice?.details?.reference.orEmpty())}
    var notes by remember {mutableStateOf(invoice?.details?.notes.orEmpty())}
    var duplicateReason by remember {mutableStateOf(invoice?.details?.duplicateReviewReason.orEmpty())}
    var reason by remember {mutableStateOf("")}
    var voided by remember {mutableStateOf(invoice?.status==SupplierInvoiceStatus.VOIDED)}
    val selected=remember {mutableStateListOf<String>().also {it.addAll(invoice?.receiptIds.orEmpty())}}
    var attachment by remember {mutableStateOf(invoice?.attachment)}
    var upload by remember {mutableStateOf<SupplierInvoiceAttachmentUpload?>(null)}
    var receiptSearch by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val operation=remember(number,date,reference,notes,duplicateReason,reason,voided,selected.toList(),attachment,upload) {UUID.randomUUID().toString()}
    val claimed=allInvoices.filter {it.id!=invoice?.id && it.status==SupplierInvoiceStatus.ACTIVE}.flatMap {it.receiptIds}.toSet()
    AlertDialog(onDismissRequest=dismiss,title={Text(if(invoice==null) "Record supplier invoice" else "Correct supplier invoice metadata")},text={Column(Modifier.widthIn(max=600.dp).heightIn(max=570.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Copy the number and date from the supplier's document. Select the existing purchases it covers. This record does not change their costs, stock, cash or debt.",style=MaterialTheme.typography.bodySmall)
        PosTextField(number,{number=it;error=null},"Supplier invoice number")
        PosTextField(date,{date=it;error=null},"Date written on invoice (YYYY-MM-DD)")
        PosTextField(reference,{reference=it},"Other reference (optional)")
        PosTextField(notes,{notes=it},"Invoice notes (optional)")
        Text("Duplicate checks ignore number case and extra spaces within the same supplier. The same number/date cannot identify two active records. Reused numbers on different dates require review.",style=MaterialTheme.typography.bodySmall)
        PosTextField(duplicateReason,{duplicateReason=it},"Why is a repeated invoice number different? (if needed)")
        if(invoice!=null) {
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(voided,{voided=it});Text("Void this metadata record; keep its history")}
            Text("Voiding releases its current purchase associations. It does not cancel purchases, supplier debt or payments.",style=MaterialTheme.typography.bodySmall)
            PosTextField(reason,{reason=it},"Why is this invoice record changing?")
        }
        if(!voided) {
            PosTextField(receiptSearch,{receiptSearch=it},"Find purchases to include",hint="Item, historical supplier name or receipt ID")
            val visible=receipts.filter {it.id !in claimed && listOf(it.id,it.productName,it.supplierName).any {field ->field.contains(receiptSearch,true)}}
            if(visible.isEmpty()) Text("No available linked purchases. Link receipts to this supplier account first. Purchases assigned to another active invoice are excluded.")
            Column(Modifier.heightIn(max=190.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {visible.forEach {receipt ->Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(receipt.id in selected,{checked ->if(checked)selected.add(receipt.id) else selected.remove(receipt.id)})
                Column {Text("${receipt.productName} · ${money(receipt.totalCostMinor)}",fontWeight=FontWeight.Medium);Text("${receipt.id.takeLast(8)} · Name saved: ${receipt.supplierName}",style=MaterialTheme.typography.bodySmall)}
            }}}
            Text("${selected.size} receipt(s) selected",style=MaterialTheme.typography.bodySmall)
        }
        Text("Local evidence attachment (optional)",fontWeight=FontWeight.Medium)
        attachment?.let {Text("${it.fileName} · ${it.byteSize} bytes · ${it.mediaType}",style=MaterialTheme.typography.bodySmall)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            NeutralButton("Choose PDF or image",{try {
                val chooser=JFileChooser().apply {dialogTitle="Choose supplier invoice evidence (up to 1 MB)";fileFilter=FileNameExtensionFilter("PDF, PNG or JPEG","pdf","png","jpg","jpeg")}
                if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {upload=SupplierInvoiceAttachmentUpload.fromFile(chooser.selectedFile.toPath());attachment=upload!!.reference;error=null}
            } catch(e:Exception) {error=e.message ?: "Attachment could not be read. Choose a supported file up to 1 MB."}})
            if(attachment!=null) TextButton({attachment=null;upload=null}) {Text("Remove from this revision")}
        }
        Text("PDFs: 1–100 unencrypted pages. Images: up to 12 million pixels. Maximum file size: 1 MB. Real bytes are kept on this computer and in encrypted business backups; older evidence stays in history. No online delivery is connected.",style=MaterialTheme.typography.bodySmall)
        error?.let {InlineNotice("Invoice record not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton(if(invoice==null) "Save invoice metadata" else "Save correction",{try {
        fun optional(value:String)=value.trim().ifEmpty {null}
        val details=SupplierInvoiceDetails(number.trim(),date.trim(),optional(reference),optional(notes),optional(duplicateReason))
        if(invoice==null) repo.recordSupplierInvoice(profile.tenantId,profile.id,details,selected.toList(),upload,operation)
        else repo.correctSupplierInvoice(profile.tenantId,invoice.id,details,if(voided)emptyList() else selected.toList(),if(voided)SupplierInvoiceStatus.VOIDED else SupplierInvoiceStatus.ACTIVE,attachment,upload,reason.trim(),invoice.version,operation)
        saved()
    } catch(e:Exception) {error=e.message ?: "Invoice could not be saved. Your entries remain here."}})},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}


/** Bounded evidence preview uses the saved revision's bytes; never opens a remote URL. */
@Composable
private fun InvoiceImagePreview(repo:PosRepository,tenantId:String,invoiceId:String,version:Long,ref:SupplierInvoiceAttachmentRef) {
    var showing by remember(tenantId,invoiceId,version,ref.hash) {mutableStateOf(false)}
    TextButton({showing=true}) {Text("View image · revision $version")}
    if(showing) {
        val loaded=remember(tenantId,invoiceId,version,ref.hash) {runCatching {repo.supplierInvoiceAttachmentBytes(tenantId,invoiceId,version)}}
        AlertDialog(onDismissRequest={showing=false},title={Text("Invoice image · revision $version")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(ref.fileName)
            loaded.getOrNull()?.let {LocalImage(it,"Invoice evidence ${ref.fileName}",Modifier.fillMaxWidth().height(300.dp))}
            loaded.exceptionOrNull()?.let {InlineNotice("Image unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
            Text("Saved evidence for this revision. Viewing it does not change stock, debt or payment status.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton({showing=false}) {Text("Close")}})
    }
}
