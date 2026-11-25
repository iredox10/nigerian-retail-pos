package pos.cloud.client

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource

object CloudSetup {
    const val server="https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com"
    /** QR contains only the short review code. Neither device nor account tokens enter it. */
    fun pairingCode(value:String):String {
        val code=if(value.startsWith("retailpos:pair:"))value.removePrefix("retailpos:pair:") else value
        return code.trim().uppercase(java.util.Locale.ROOT).replace("-","").replace(" ","").also {
            require(it.matches(Regex("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}"))) {"Enter the 10-character code shown on the new device."}
        }.chunked(5).joinToString("-")
    }
    fun qr(code:String):Array<BooleanArray> {
        val bits=MultiFormatWriter().encode("retailpos:pair:"+pairingCode(code),BarcodeFormat.QR_CODE,45,45)
        return Array(bits.height) {y ->BooleanArray(bits.width) {x ->bits[x,y]}}
    }
    fun readQr(width:Int,height:Int,pixels:IntArray):String {
        require(width in 1..4096 && height in 1..4096 && pixels.size==width*height)
        return pairingCode(MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,height,pixels)))).text)
    }
}
