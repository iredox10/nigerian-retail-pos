package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.SupplierInvoiceAttachmentRef
import pos.output.receiptDate
import pos.ui.components.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Contextual to an already-saved expense: evidence never changes its amount or cash movement. */
@Composable
fun ExpenseAttachmentsPanel(repo:PosRepository,tenantId:String,expenseId:String) {
    var showing by remember(tenantId,expenseId) {mutableStateOf(false)}
    NeutralButton("Receipt file & history",{showing=true})
    if(showing) ExpenseReceiptDialog(repo,tenantId,expenseId) {showing=false}
}

@Composable
private fun ExpenseReceiptDialog(repo:PosRepository,tenantId:String,expenseId:String,dismiss:()->Unit) {
    var revision by remember {mutableIntStateOf(0)}
    val loaded=remember(tenantId,expenseId,revision) {runCatching {repo.expenseAttachmentHistory(tenantId,expenseId)}}
    val history=loaded.getOrDefault(emptyList())
    val current=history.lastOrNull()
    var upload by remember {mutableStateOf<SupplierInvoiceAttachmentUpload?>(null)}
    var removing by remember {mutableStateOf(false)}
    var reason by remember {mutableStateOf("")}
    var message by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var showingHistory by remember {mutableStateOf(false)}
    var previewVersion by remember {mutableStateOf<Long?>(null)}
    val operationId=remember(upload,removing,reason,revision) {UUID.randomUUID().toString()}
    val changed=upload!=null || removing
    fun reset() {upload=null;removing=false;reason="";error=null;previewVersion=null}
    fun export(ref:SupplierInvoiceAttachmentRef,version:Long) {
        try {
            val bytes=repo.expenseAttachmentBytes(tenantId,expenseId,version)
            val chooser=JFileChooser().apply {dialogTitle="Save expense receipt copy";selectedFile=java.io.File(ref.fileName)}
            if(chooser.showSaveDialog(null)==JFileChooser.APPROVE_OPTION) {
                Files.write(chooser.selectedFile.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                message="Receipt copy saved. The expense and its original cash movement are unchanged."
            } else message="Receipt export cancelled."
            error=null
        } catch(e:Exception) {error=e.message ?: "No receipt copy was saved. Existing files are never overwritten."}
    }
    AlertDialog(onDismissRequest=dismiss,title={Text("Expense receipt")},text={Column(Modifier.widthIn(max=650.dp).heightIn(max=590.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Expense ${expenseId.takeLast(8)}",style=MaterialTheme.typography.bodySmall)
        Text("Attach a copy of the document for this saved expense. Replacing or removing it keeps earlier files and reasons in history. No amount, category or cash movement changes.",style=MaterialTheme.typography.bodySmall)
        loaded.exceptionOrNull()?.let {InlineNotice("Receipt could not be loaded",it.message ?: "Try again.",NoticeTone.Danger)}
        if(loaded.isSuccess) {
            if(current?.attachment==null) Text("No current receipt file",style=MaterialTheme.typography.titleMedium)
            current?.attachment?.let {ref ->
                Text("Saved: ${ref.fileName}",style=MaterialTheme.typography.titleMedium)
                Text("${ref.mediaType} · ${ref.byteSize} bytes · Revision ${current.version}",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(ref.mediaType.startsWith("image/")) NeutralButton("View saved image",{previewVersion=current.version})
                    NeutralButton("Save file copy",{export(ref,current.version)})
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                NeutralButton(if(current?.attachment==null) "Choose receipt file" else "Choose replacement file",{
                    try {
                        val chooser=JFileChooser().apply {dialogTitle="Choose expense receipt";fileFilter=FileNameExtensionFilter("PDF, PNG or JPEG receipt","pdf","png","jpg","jpeg")}
                        if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
                            upload=SupplierInvoiceAttachmentUpload.fromFile(chooser.selectedFile.toPath());removing=false;error=null;message=null
                        } else {message="File selection cancelled. Saved receipt is unchanged.";error=null}
                    } catch(e:Exception) {error=e.message ?: "The selected file could not be read."}
                })
                if(current?.attachment!=null) NeutralButton("Remove current file",{upload=null;removing=true;error=null;message=null},enabled=!removing)
            }
            Text("PDF, PNG or JPEG up to 1 MB. Files are copied into local shop records and encrypted backups; retained expense receipt history supports 8 MB per shop.",style=MaterialTheme.typography.bodySmall)
            if(changed) {
                if(removing) InlineNotice("Removal preview", "Save this change to remove the current file. Earlier evidence stays in history.",NoticeTone.Info)
                upload?.let {pending ->
                    Text("Selected: ${pending.reference.fileName} · ${pending.reference.byteSize} bytes")
                    if(pending.reference.mediaType.startsWith("image/")) {
                        val bytes=remember(pending) {pending.bytes()}
                        LocalImage(bytes,"Selected expense receipt image",Modifier.fillMaxWidth().height(220.dp))
                    } else Text("PDF checked and ready to save. A PDF viewer is not opened here.",style=MaterialTheme.typography.bodySmall)
                }
                PosTextField(reason,{reason=it;error=null},"Why is this receipt file changing?",hint="3–250 characters; kept in history")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PosButton(if(removing) "Save file removal" else "Save receipt file",{
                        try {
                            repo.saveExpenseAttachment(tenantId,expenseId,if(removing) null else requireNotNull(upload),current?.version ?: 0,reason.trim(),operationId)
                            reset();revision++;message="Expense receipt change saved on this computer."
                        } catch(e:Exception) {error=e.message ?: "Receipt file not saved. Your selection remains here."}
                    },enabled=reason.trim().length in 3..250)
                    NeutralButton("Discard file change",{reset();message=null})
                }
            }
            NeutralButton(if(showingHistory) "Hide receipt history" else "View receipt history",{showingHistory=!showingHistory})
            if(showingHistory) {
                if(history.isEmpty()) Text("No receipt file changes yet.",style=MaterialTheme.typography.bodySmall)
                history.asReversed().forEach {event ->
                    HorizontalDivider()
                    Text("Revision ${event.version} · ${receiptDate(event.createdAt)}",style=MaterialTheme.typography.titleSmall)
                    Text(event.reason)
                    event.attachment?.let {ref ->
                        Text(ref.fileName,style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(ref.mediaType.startsWith("image/")) NeutralButton("View revision image",{previewVersion=event.version})
                            NeutralButton("Save revision file copy",{export(ref,event.version)})
                        }
                    } ?: Text("Current file removed in this revision",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let {InlineNotice("Could not finish",it,NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Info)}
        NeutralButton("Reload saved receipt",{reset();revision++;message=null})
    }},confirmButton={TextButton(dismiss) {Text("Close")}})
    previewVersion?.let {version ->
        val saved=remember(tenantId,expenseId,version) {runCatching {repo.expenseAttachmentBytes(tenantId,expenseId,version)}}
        AlertDialog(onDismissRequest={previewVersion=null},title={Text("Expense receipt · revision $version")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            saved.getOrNull()?.let {LocalImage(it,"Saved expense receipt image",Modifier.fillMaxWidth().height(300.dp))}
            saved.exceptionOrNull()?.let {InlineNotice("Image unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
        }},confirmButton={TextButton({previewVersion=null}) {Text("Close")}})
    }
}
