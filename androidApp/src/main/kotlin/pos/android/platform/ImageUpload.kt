package pos.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Native Android raster adapter; shares immutable asset hashes and budgets with desktop. */
class ImageUpload private constructor(private val content: ByteArray) {
    val bytes get() = content.copyOf()
    val assetId get() = imageHash(content)
    fun adjust(clockwiseQuarterTurns: Int, crop: ImageCrop = ImageCrop()): ImageUpload {
        require(clockwiseQuarterTurns in 0..3)
        val source = requireNotNull(BitmapFactory.decodeByteArray(content,0,content.size))
        val rotated = Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply {postRotate(clockwiseQuarterTurns*90f)},true)
        val left=rotated.width*crop.left/100; val top=rotated.height*crop.top/100
        return encode(Bitmap.createBitmap(rotated,left,top,rotated.width-left-rotated.width*crop.right/100,rotated.height-top-rotated.height*crop.bottom/100))
    }
    companion object {
        fun fromFile(path: Path) = fromBytes(Files.readAllBytes(path))
        fun fromBytes(bytes: ByteArray): ImageUpload {
            require(bytes.size in 1..8_388_608) {"Choose a PNG or JPEG up to 8 MB."}
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outMimeType in listOf("image/png","image/jpeg") && bounds.outWidth in 1..10_000 && bounds.outHeight in 1..10_000 && bounds.outWidth.toLong()*bounds.outHeight<=12_000_000) {"Choose a bounded PNG or JPEG image."}
            return encode(requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)))
        }
        internal fun fromStoredBytes(bytes: ByteArray):ImageUpload {validateStoredImage(bytes);return ImageUpload(bytes.copyOf())}
        private fun encode(source: Bitmap): ImageUpload {
            var side=768
            while(side>=64) {
                val scale=minOf(1.0,side.toDouble()/maxOf(source.width,source.height))
                val bitmap=Bitmap.createScaledBitmap(source,maxOf(1,(source.width*scale).toInt()),maxOf(1,(source.height*scale).toInt()),true)
                val bytes=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
                if(bytes.size<=524_288)return ImageUpload(bytes)
                side=(side*.75).toInt()
            }
            error("Choose a smaller image.")
        }
    }
}
data class ImageCrop(val left:Int=0,val top:Int=0,val right:Int=0,val bottom:Int=0) {init {require(listOf(left,top,right,bottom).all {it in 0..99} && left+right<100 && top+bottom<100)}}
internal fun imageHash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
internal fun validateStoredImage(bytes:ByteArray) {
    require(bytes.size in 1..524_288)
    val b=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,b)
    require(b.outMimeType=="image/png" && b.outWidth in 1..768 && b.outHeight in 1..768) {"Stored image failed validation."}
}
