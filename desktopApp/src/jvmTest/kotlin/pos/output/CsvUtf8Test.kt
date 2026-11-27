package pos.output

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.*

class CsvUtf8Test {
    @Test fun unicodeAcrossReadBoundariesSurvivesAndMalformedInputIsRejected() {
        val original="x".repeat(8191)+"₦ soap 🧼"
        assertEquals(original,CsvUtf8.read(ByteArrayInputStream(original.toByteArray(Charsets.UTF_8))))
        assertFailsWith<IllegalArgumentException> {CsvUtf8.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)))}
    }
    @Test fun unboundedProviderIsStoppedBeforeReadingPastTheSizeLimit() {
        var consumed=0
        val stream=object:InputStream() {override fun read():Int {consumed++;return 65}}
        assertFailsWith<IllegalArgumentException> {CsvUtf8.read(stream)}
        assertEquals(CsvUtf8.maximumBytes+1,consumed)
    }
}
