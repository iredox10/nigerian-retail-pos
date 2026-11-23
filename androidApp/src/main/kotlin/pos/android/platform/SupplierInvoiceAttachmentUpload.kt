package pos.data

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import pos.domain.SupplierInvoiceAttachmentRef
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class SupplierInvoiceAttachmentUpload private constructor(val reference:SupplierInvoiceAttachmentRef,private val content:ByteArray) {
    fun bytes()=content.copyOf()
    companion object {
        fun fromFile(path:Path):SupplierInvoiceAttachmentUpload {require(Files.size(path) in 1..1_048_576);return fromBytes(path.fileName.toString(),Files.readAllBytes(path))}
        fun fromBytes(fileName:String,bytes:ByteArray):SupplierInvoiceAttachmentUpload {
            require(bytes.size in 1..1_048_576) {"Choose a document up to 1 MB."}
            val type=if(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
                val file=Files.createTempFile(Path.of(System.getProperty("pos.dataDir")),"invoice-check-",".pdf")
                try {Files.write(file,bytes);ParcelFileDescriptor.open(file.toFile(),ParcelFileDescriptor.MODE_READ_ONLY).use {fd ->PdfRenderer(fd).use {require(it.pageCount in 1..100)}}} finally {Files.deleteIfExists(file)}
                "application/pdf"
            } else {
                val b=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,b)
                require(b.outMimeType in listOf("image/png","image/jpeg") && b.outWidth in 1..10_000 && b.outHeight in 1..10_000 && b.outWidth.toLong()*b.outHeight<=12_000_000) {"Choose a valid PDF, PNG or JPEG."}
                b.outMimeType
            }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            return SupplierInvoiceAttachmentUpload(SupplierInvoiceAttachmentRef(hash,type,bytes.size,fileName),bytes.copyOf())
        }
    }
}
