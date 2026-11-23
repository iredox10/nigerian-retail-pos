package pos.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.data.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AndroidImageUploadTest {
    @Test fun allEightExifTransformsPreserveTheirDistinctPixelMappings() {
        val colors=intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.CYAN,Color.MAGENTA,Color.YELLOW)
        val source=Bitmap.createBitmap(colors,3,2,Bitmap.Config.ARGB_8888)
        val expected=listOf(
            listOf(0,1,2,3,4,5),listOf(2,1,0,5,4,3),listOf(5,4,3,2,1,0),listOf(3,4,5,0,1,2),
            listOf(0,3,1,4,2,5),listOf(3,0,4,1,5,2),listOf(5,2,4,1,3,0),listOf(2,5,1,4,0,3))
        try {for(orientation in 1..8){val result=orientAndroidPhoto(source,orientation)
            try {assertEquals(if(orientation>=5)2 else 3,result.width);assertEquals(if(orientation>=5)3 else 2,result.height)
                expected[orientation-1].forEachIndexed {i,index->assertEquals("Orientation $orientation pixel $i",colors[index],result.getPixel(i%result.width,i/result.width))}
            }finally {if(result!==source)result.recycle()}
        }}finally {source.recycle()}
    }
    @Test fun jpegExifAppliesBeforeNormalizationAndMetadataIsRemoved() {
        val image=Bitmap.createBitmap(24,12,Bitmap.Config.ARGB_8888)
        for(y in 0..11)for(x in 0..23)image.setPixel(x,y,if(x<12)Color.RED else Color.BLUE)
        val jpeg=ByteArrayOutputStream().also {image.compress(Bitmap.CompressFormat.JPEG,100,it)}.toByteArray();image.recycle()
        for(order in listOf(ByteOrder.BIG_ENDIAN,ByteOrder.LITTLE_ENDIAN)) {
            val oriented=withExif(jpeg,order,6,8)
            val upload=ImageUpload.fromBytes(oriented)
            val decoded=requireNotNull(BitmapFactory.decodeByteArray(upload.bytes,0,upload.bytes.size))
            try {assertEquals(12,decoded.width);assertEquals(24,decoded.height);assertTrue(Color.red(decoded.getPixel(6,4))>200);assertTrue(Color.blue(decoded.getPixel(6,20))>200);assertEquals(1,jpegOrientation(upload.bytes))}finally {decoded.recycle()}
            val invalid=ImageUpload.fromBytes(withExif(jpeg,order,6,Int.MAX_VALUE))
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(invalid.bytes,0,invalid.bytes.size,bounds)
            assertEquals(24,bounds.outWidth);assertEquals(12,bounds.outHeight)
        }
    }
    @Test fun limitsAndAdjustmentsKeepStoredAssetsBoundedAndDefensive() {
        val source=Bitmap.createBitmap(1600,800,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        val bytes=ByteArrayOutputStream().also {source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();source.recycle()
        val upload=ImageUpload.fromBytes(bytes);val copy=upload.bytes;copy.fill(0)
        validateStoredImage(upload.bytes)
        val normalized=requireNotNull(BitmapFactory.decodeByteArray(upload.bytes,0,upload.bytes.size))
        assertEquals(768,normalized.width);assertEquals(384,normalized.height);normalized.recycle()
        val adjusted=upload.adjust(1,ImageCrop(left=25,right=25))
        validateStoredImage(adjusted.bytes)
        val output=requireNotNull(BitmapFactory.decodeByteArray(adjusted.bytes,0,adjusted.bytes.size))
        assertEquals(192,output.width);assertEquals(768,output.height);output.recycle()
        try {ImageUpload.fromBytes(ByteArray(8_388_609));fail("Oversize input accepted")}catch(_:IllegalArgumentException){}
        try {ImageUpload.fromBytes(byteArrayOf(1,2,3));fail("Invalid image accepted")}catch(_:IllegalArgumentException){}
        try {upload.adjust(4);fail("Invalid turn accepted")}catch(_:IllegalArgumentException){}
    }
    private fun withExif(jpeg:ByteArray,order:ByteOrder,orientation:Int,directory:Int):ByteArray {
        val payload=ByteBuffer.allocate(32).order(order)
        payload.put(byteArrayOf(69,120,105,102,0,0));payload.putShort(if(order==ByteOrder.BIG_ENDIAN)0x4d4d else 0x4949)
        payload.putShort(42).putInt(directory).putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0)
        return jpeg.copyOfRange(0,2)+byteArrayOf(0xff.toByte(),0xe1.toByte(),0,34)+payload.array()+jpeg.copyOfRange(2,jpeg.size)
    }
}
