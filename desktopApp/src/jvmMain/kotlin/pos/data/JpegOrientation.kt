package pos.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read the optional orientation tag from JPEG APP1 without following pointers outside the segment. */
internal fun jpegOrientation(bytes: ByteArray): Int {
    if (bytes.size < 4 || bytes[0].toInt() and 255 != 255 || bytes[1].toInt() and 255 != 216) return 1
    var cursor = 2
    while (cursor < bytes.size && bytes[cursor].toInt() and 255 == 255) {
        while (cursor < bytes.size && bytes[cursor].toInt() and 255 == 255) cursor++
        if (cursor >= bytes.size - 2) break
        val marker = bytes[cursor++].toInt() and 255
        if (marker == 218 || marker == 217) break
        val length = ((bytes[cursor].toInt() and 255) shl 8) or (bytes[cursor + 1].toInt() and 255)
        if (length < 2 || length > bytes.size - cursor) break
        if (marker == 225) orientationFromExif(bytes, cursor + 2, length - 2)?.let { return it }
        cursor += length
    }
    return 1
}

private fun orientationFromExif(bytes: ByteArray, start: Int, length: Int): Int? {
    if (length < 14 || !bytes.copyOfRange(start, start + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
        return null
    }
    val tiff = ByteBuffer.wrap(bytes, start + 6, length - 6).slice()
    val order = when (tiff.getShort(0).toInt()) {
        0x4949 -> ByteOrder.LITTLE_ENDIAN
        0x4D4D -> ByteOrder.BIG_ENDIAN
        else -> return null
    }
    tiff.order(order)
    if (tiff.getShort(2).toInt() != 42) return null
    val directory = tiff.getInt(4)
    if (directory !in 8..tiff.limit() - 2) return null
    val count = tiff.getShort(directory).toInt() and 0xffff
    val first = directory + 2
    if (count > (tiff.limit() - first) / 12) return null
    for (entry in 0 until count) {
        val offset = first + 12 * entry
        if (tiff.getShort(offset).toInt() == 0x0112 && tiff.getShort(offset + 2).toInt() == 3 &&
            tiff.getInt(offset + 4) == 1
        ) return tiff.getShort(offset + 8).toInt().takeIf { it in 1..8 }
    }
    return null
}
