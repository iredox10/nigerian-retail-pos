package pos.data

import pos.domain.UnitDiscount
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Length-bounded binary payload inside the atomically written draft properties file. */
internal object DraftDiscountCodec {
    fun encode(discounts: Map<String, UnitDiscount>): String {
        if (discounts.isEmpty()) return ""
        require(discounts.size <= 10_000)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(discounts.size)
            discounts.toSortedMap().forEach { (key, value) ->
                out.writeUTF(key); out.writeLong(value.cataloguePriceMinor)
                out.writeLong(value.amountMinor); out.writeUTF(value.reason)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): Map<String, UnitDiscount> {
        if (encoded.isEmpty()) return emptyMap()
        require(encoded.length <= 4 * 1024 * 1024) { "Saved discounts are too large." }
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val count = input.readInt()
            require(count in 1..10_000) { "Saved discount count is invalid." }
            val entries = (0 until count).map {
                input.readUTF() to UnitDiscount(input.readLong(), input.readLong(), input.readUTF())
            }
            require(input.read() == -1 && entries.map { it.first }.distinct().size == count) { "Saved discounts contain duplicate or unsupported data." }
            entries.toMap()
        }
    }
}
