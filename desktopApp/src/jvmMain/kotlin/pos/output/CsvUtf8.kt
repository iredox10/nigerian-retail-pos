package pos.output

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Portable bounded reader shared by Android document providers and desktop CSV files. */
object CsvUtf8 {
    const val maximumBytes=5*1024*1024
    fun read(input:InputStream):String {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer,0,minOf(buffer.size,maximumBytes+1-out.size()))
            if(count<0)break
            if(count==0)continue
            out.write(buffer,0,count)
            require(out.size()<=maximumBytes) {"Choose a CSV file no larger than 5 MB."}
        }
        return try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()}
        catch(_:java.nio.charset.CharacterCodingException) {throw IllegalArgumentException("Save this CSV as UTF-8 and choose it again. No items were imported.")}
    }
}
