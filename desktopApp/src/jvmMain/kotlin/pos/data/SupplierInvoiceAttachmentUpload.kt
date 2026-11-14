package pos.data

import pos.domain.SupplierInvoiceAttachmentRef
import org.apache.pdfbox.Loader
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Real bounded document bytes; no filesystem path is persisted and no external viewer is launched. */
class SupplierInvoiceAttachmentUpload private constructor(val reference:SupplierInvoiceAttachmentRef,private val content:ByteArray) {
    fun bytes():ByteArray=content.copyOf()
    companion object {
        fun fromFile(path:Path):SupplierInvoiceAttachmentUpload {
            require(Files.isRegularFile(path) && Files.size(path) in 1..1_048_576) {"Choose a regular PDF, PNG or JPEG file up to 1 MB."}
            val bytes=Files.newInputStream(path).use {it.readNBytes(1_048_577)}
            return fromBytes(path.fileName.toString(),bytes)
        }
        fun fromBytes(fileName:String,bytes:ByteArray):SupplierInvoiceAttachmentUpload {
            require(bytes.size in 1..1_048_576) {"Supplier invoice attachments support files up to 1 MB."}
            val type=when {
                bytes.size>=5 && bytes.copyOfRange(0,5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)) -> {
                    Loader.loadPDF(bytes).use {pdf -> require(!pdf.isEncrypted && pdf.numberOfPages in 1..100) {"Choose an unencrypted PDF with 1–100 pages."}}
                    "application/pdf"
                }
                bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) -> {validateImage(bytes,"png");"image/png"}
                bytes.size>=3 && bytes[0]==(-1).toByte() && bytes[1]==(-40).toByte() && bytes[2]==(-1).toByte() -> {validateImage(bytes,"jpeg");"image/jpeg"}
                else -> error("Choose a valid PDF, PNG or JPEG invoice file.")
            }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            return SupplierInvoiceAttachmentUpload(SupplierInvoiceAttachmentRef(hash,type,bytes.size,fileName),bytes.copyOf())
        }
        private fun validateImage(bytes:ByteArray,format:String) {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {input ->
                require(input!=null) {"Invoice image could not be read."}
                val readers=ImageIO.getImageReaders(input);require(readers.hasNext()) {"Invoice image format is invalid."}
                val reader=readers.next()
                try {
                    reader.input=input
                    require(reader.formatName.equals(format,true) || (format=="jpeg" && reader.formatName.equals("jpg",true))) {"Invoice image format does not match its bytes."}
                    val width=reader.getWidth(0);val height=reader.getHeight(0)
                    require(width in 1..10_000 && height in 1..10_000 && width.toLong()*height<=12_000_000) {"Invoice images support up to 12 million pixels and 10,000 pixels per side."}
                    require(reader.read(0)!=null) {"Invoice image could not be decoded."}
                } finally {reader.dispose()}
            }
        }
    }
}
