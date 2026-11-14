package pos.output

import pos.domain.*
import pos.data.quotationExpired
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.FutureTask
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

object QuotationOutputService {
    private val wat=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'WAT'").withZone(ZoneId.of("Africa/Lagos"))
    fun format(tenant:Tenant,quote:Quotation,now:Instant=Instant.now()):String {
        require(tenant.id==quote.tenantId) {"Quotation belongs to another shop."}
        return buildString {
            appendLine(tenant.name);appendLine("QUOTATION")
            appendLine("Quotation: ${quote.id} | revision ${quote.version}")
            appendLine("Shop ID: ${tenant.id}")
            appendLine("Status saved on this revision: ${quote.status.name.lowercase().replaceFirstChar {it.uppercase()}}")
            appendLine("First issued: ${wat.format(Instant.parse(quote.createdAt))}")
            appendLine("Revision recorded: ${wat.format(Instant.parse(quote.updatedAt))}")
            appendLine("Valid through: ${quote.expiresOn} (whole day, WAT)")
            if(quotationExpired(quote.expiresOn,now)) appendLine("This quotation's expiry date has passed.")
            appendLine("Terms and contact details below are frozen for this revision. The shop name above is current.")
            quote.customer?.let {customer ->
                appendLine("Quoted customer/contact: ${customer.name}")
                customer.customerId?.let {appendLine("Customer ID: $it")}
                customer.phone?.let {appendLine("Quoted phone: $it")}
                customer.address?.let {appendLine("Quoted address: $it")}
            } ?: appendLine("Customer/contact: Not recorded")
            appendLine()
            quote.lines.forEachIndexed {index,line ->
                appendLine("${index+1}. ${line.productName}")
                appendLine("Goods ID: ${line.productId}")
                appendLine("${line.quantityLabel} ${line.sellingUnit} x ${money(line.agreedUnitPriceMinor)} = ${money(line.totalMinor)}")
                if(line.sellingUnitId!=null) appendLine("Agreed pack: 1 ${line.sellingUnit} = ${line.baseUnitsPerUnit} ${line.baseUnit}")
            }
            appendLine();appendLine("Agreed quotation total: ${money(quote.totalMinor)}")
            if(quote.notes.isNotEmpty()) {appendLine();appendLine("Agreed notes: ${quote.notes}")}
            quote.saleId?.let {appendLine("Separate completed sale ID: $it")}
            appendLine();appendLine("Issuing or accepting this quotation does not reserve stock, take payment or create debt.")
            appendLine("Keep the quotation ID and revision when discussing these terms. Later revisions are separate saved records.")
        }
    }
    fun exportPdf(tenant:Tenant,quote:Quotation):File?=PdfDocumentService.export(format(tenant,quote),PdfDocumentKind.QUOTATION)
    fun writeText(text:String,destination:Path,replaceExisting:Boolean=false):Path {
        require(!Files.exists(destination) || replaceExisting) {"Choose a new filename; this quotation file already exists."}
        val absolute=destination.toAbsolutePath();val temporary=Files.createTempFile(absolute.parent,".quotation-",".tmp")
        try {
            FileOutputStream(temporary.toFile()).use {it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync()}
            if(replaceExisting) Files.move(temporary,absolute,ATOMIC_MOVE,REPLACE_EXISTING) else Files.move(temporary,absolute)
        } finally {Files.deleteIfExists(temporary)}
        return absolute
    }
    fun exportText(tenant:Tenant,quote:Quotation):File? {
        val text=format(tenant,quote)
        val chooser=JFileChooser().apply {dialogTitle="Save quotation text";selectedFile=File("quotation-${quote.id.takeLast(8)}-revision-${quote.version}.txt");fileFilter=FileNameExtensionFilter("Quotation text (*.txt)","txt")}
        if(onSwing {chooser.showSaveDialog(null)}!=JFileChooser.APPROVE_OPTION) return null
        val chosen=chooser.selectedFile;val file=if(chosen.name.endsWith(".txt",true)) chosen else File(chosen.parentFile,chosen.name+".txt")
        val replace=file.exists()
        if(replace && onSwing {JOptionPane.showConfirmDialog(null,"Replace ${file.name}?","Replace quotation text",JOptionPane.YES_NO_OPTION)}!=JOptionPane.YES_OPTION) return null
        return writeText(text,file.toPath(),replace).toFile()
    }
    private fun <T> onSwing(block:()->T):T {
        if(SwingUtilities.isEventDispatchThread()) return block()
        val task=FutureTask<T> {block()};SwingUtilities.invokeAndWait(task);return task.get()
    }
}
