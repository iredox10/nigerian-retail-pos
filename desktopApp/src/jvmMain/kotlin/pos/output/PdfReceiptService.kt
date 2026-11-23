package pos.output

import pos.domain.ReceiptAppearance
import java.io.File
import java.nio.file.Path

/** Offline file output only. A PDF receipt is not a fiscal invoice or a payment verification. */
object PdfReceiptService {
    fun createPdf(text:String,paper:String="A4",logoPng:ByteArray?=null,appearance:ReceiptAppearance=ReceiptAppearance(),artworkPng:ByteArray?=null):ByteArray = PdfDocumentService.createPdf(text,PdfDocumentKind.RECEIPT,paper,logoPng,appearance,artworkPng)
    fun writePdf(text:String,paper:String,destination:Path,replaceExisting:Boolean=false,logoPng:ByteArray?=null,appearance:ReceiptAppearance=ReceiptAppearance(),artworkPng:ByteArray?=null):Path =
        PdfDocumentService.writePdf(text,PdfDocumentKind.RECEIPT,paper,destination,replaceExisting,logoPng,appearance,artworkPng)
    fun exportReceipt(text:String,paper:String="A4",logoPng:ByteArray?=null,appearance:ReceiptAppearance=ReceiptAppearance(),artworkPng:ByteArray?=null):File? = PdfDocumentService.export(text,PdfDocumentKind.RECEIPT,paper,logoPng,appearance,artworkPng)
}
