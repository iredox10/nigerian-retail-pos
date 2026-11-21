package pos.data

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Bounded decoded raster import. Re-encoding strips source metadata and filesystem references. */
class ImageUpload private constructor(private val content:ByteArray) {
    val bytes:ByteArray get()=content.copyOf()
    val assetId:String get()=imageHash(content)

    /** Crop edges refer to the rotated preview, in percentages of its width or height. */
    fun adjust(clockwiseQuarterTurns:Int,crop:ImageCrop=ImageCrop()):ImageUpload {
        require(clockwiseQuarterTurns in 0..3)
        if(clockwiseQuarterTurns==0 && crop==ImageCrop()) return this
        val source=decodeBoundedImage(content,768,768*768,false)
        val rotated=rotatePhoto(source,clockwiseQuarterTurns)
        val left=rotated.width*crop.left/100
        val top=rotated.height*crop.top/100
        val right=rotated.width-rotated.width*crop.right/100
        val bottom=rotated.height-rotated.height*crop.bottom/100
        return encode(rotated.getSubimage(left,top,right-left,bottom-top))
    }

    companion object {
        fun fromFile(path:Path):ImageUpload {
            require(Files.isRegularFile(path) && Files.size(path) in 1..8_388_608) {"Choose a PNG or JPEG image up to 8 MB."}
            val bytes=Files.newInputStream(path).use {it.readNBytes(8_388_609)}
            return fromBytes(bytes)
        }
        fun fromBytes(bytes:ByteArray):ImageUpload {
            require(bytes.size in 1..8_388_608) {"Choose a PNG or JPEG image up to 8 MB."}
            val source=decodeBoundedImage(bytes,10_000,12_000_000,true)
            return encode(orientPhoto(source,jpegOrientation(bytes)))
        }
        internal fun fromStoredBytes(bytes:ByteArray):ImageUpload {
            validateStoredImage(bytes)
            return ImageUpload(bytes.copyOf())
        }
        private fun encode(source:BufferedImage):ImageUpload {
            var side=768
            while(true) {
                val scale=minOf(1.0,side.toDouble()/maxOf(source.width,source.height))
                val output=BufferedImage(maxOf(1,(source.width*scale).roundToInt()),maxOf(1,(source.height*scale).roundToInt()),BufferedImage.TYPE_INT_ARGB)
                output.createGraphics().let {g ->try {g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,output.width,output.height,null)} finally {g.dispose()}}
                val encoded=ByteArrayOutputStream().also {require(ImageIO.write(output,"png",it))}.toByteArray()
                if(encoded.size<=524_288) return ImageUpload(encoded)
                side=(side*.75).toInt()
                require(side>=64) {"This image cannot be stored within the supported size. Choose a smaller image."}
            }
        }
    }
}

data class ImageCrop(val left:Int=0,val top:Int=0,val right:Int=0,val bottom:Int=0) {
    init {
        require(listOf(left,top,right,bottom).all {it in 0..99} && left+right<100 && top+bottom<100) {
            "Leave at least 1% of the photo in each direction."
        }
    }
}

private fun rotatePhoto(source:BufferedImage,quarterTurns:Int):BufferedImage = orientPhoto(source,when(quarterTurns) {
    1 -> 6
    2 -> 3
    3 -> 8
    else -> 1
})

private fun orientPhoto(source:BufferedImage,orientation:Int):BufferedImage {
    if(orientation==1) return source
    val width=source.width
    val height=source.height
    val swapped=orientation>=5
    val transform=when(orientation) {
        2 -> java.awt.geom.AffineTransform(-1.0,0.0,0.0,1.0,width.toDouble(),0.0)
        3 -> java.awt.geom.AffineTransform(-1.0,0.0,0.0,-1.0,width.toDouble(),height.toDouble())
        4 -> java.awt.geom.AffineTransform(1.0,0.0,0.0,-1.0,0.0,height.toDouble())
        5 -> java.awt.geom.AffineTransform(0.0,1.0,1.0,0.0,0.0,0.0)
        6 -> java.awt.geom.AffineTransform(0.0,1.0,-1.0,0.0,height.toDouble(),0.0)
        7 -> java.awt.geom.AffineTransform(0.0,-1.0,-1.0,0.0,height.toDouble(),width.toDouble())
        8 -> java.awt.geom.AffineTransform(0.0,-1.0,1.0,0.0,0.0,width.toDouble())
        else -> return source
    }
    val result=BufferedImage(if(swapped) height else width,if(swapped) width else height,BufferedImage.TYPE_INT_ARGB)
    result.createGraphics().let {graphics ->
        try {graphics.drawImage(source,transform,null)} finally {graphics.dispose()}
    }
    return result
}
internal fun imageHash(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
internal fun validateStoredImage(bytes:ByteArray) {
    require(bytes.size in 1..524_288) {"Saved image exceeds 512 KB."}
    decodeBoundedImage(bytes,768,768*768,false)
}
private fun decodeBoundedImage(bytes:ByteArray,maxSide:Int,maxPixels:Int,allowJpeg:Boolean):BufferedImage =
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {input ->
        require(input!=null) {"Image could not be read."}
        val readers=ImageIO.getImageReaders(input);require(readers.hasNext()) {"Choose a valid PNG or JPEG image."}
        val reader=readers.next()
        try {
            reader.input=input
            val format=reader.formatName.lowercase()
            require(format=="png" || (allowJpeg && format in setOf("jpeg","jpg"))) {"Choose a PNG or JPEG image. Other formats are not supported."}
            val width=reader.getWidth(0);val height=reader.getHeight(0)
            require(width in 1..maxSide && height in 1..maxSide && width.toLong()*height<=maxPixels) {"Image dimensions exceed the supported limit (12 million source pixels)."}
            requireNotNull(reader.read(0)) {"Image could not be decoded."}
        } finally {reader.dispose()}
    }
