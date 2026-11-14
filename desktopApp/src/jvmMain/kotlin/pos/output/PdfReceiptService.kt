package pos.output

import java.io.File
import java.nio.file.Path

/** Offline file output only. A PDF receipt is not a fiscal invoice or a payment verification. */
object PdfReceiptService {
    fun createPdf(text:String,paper:String="A4"):ByteArray = PdfDocumentService.createPdf(text,PdfDocumentKind.RECEIPT,paper)
    fun writePdf(text:String,paper:String,destination:Path,replaceExisting:Boolean=false):Path =
        PdfDocumentService.writePdf(text,PdfDocumentKind.RECEIPT,paper,destination,replaceExisting)
    fun exportReceipt(text:String,paper:String="A4"):File? = PdfDocumentService.export(text,PdfDocumentKind.RECEIPT,paper)
}
