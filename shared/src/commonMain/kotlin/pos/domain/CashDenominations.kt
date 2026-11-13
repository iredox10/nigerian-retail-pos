package pos.domain

/** Physical naira only. Values are kobo, independent of the design of a note or coin.
 * Source: https://www.cbn.gov.ng/currency/legaltender.html (checked 13 November 2026).
 */
val nairaBanknotesMinor: List<Long> = listOf(100000, 50000, 20000, 10000, 5000, 2000, 1000, 500)
val nairaCoinsMinor: List<Long> = listOf(200, 100, 50, 10, 1)

/** A defensive, exact snapshot of a physical count. Zero rows have a single canonical form. */
class CashCount(counts: Map<Long, Long>) {
    val counts: Map<Long, Long> = counts.toMap().also { entries ->
        require(entries.keys.all { it in nairaBanknotesMinor || it in nairaCoinsMinor }) { "Choose a supported naira note or coin." }
        require(entries.values.all { it >= 0 }) { "Enter a whole number of notes or coins, zero or more." }
    }.filterValues { it > 0 }

    val totalMinor: Long = this.counts.entries.fold(0L) { total, (denomination, count) ->
        require(count <= Long.MAX_VALUE / denomination) { "Cash count exceeds the supported amount." }
        val subtotal = denomination * count
        require(total <= Long.MAX_VALUE - subtotal) { "Cash count exceeds the supported amount." }
        total + subtotal
    }

    /** Versioned canonical representation for SQLite and encrypted business backups. */
    fun encode(): String = "v1|" + counts.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" }

    override fun equals(other: Any?): Boolean = other is CashCount && counts == other.counts
    override fun hashCode(): Int = counts.hashCode()
    override fun toString(): String = encode()

    companion object {
        fun decode(encoded: String): CashCount {
            require(encoded.length <= 1024 && encoded.startsWith("v1|")) { "Cash count format is not supported." }
            val body = encoded.removePrefix("v1|")
            if (body.isEmpty()) return CashCount(emptyMap())
            val rows = body.split(';').map { row ->
                val parts = row.split(':')
                require(parts.size == 2) { "Cash count row is invalid." }
                val denomination = requireNotNull(parseCashPieceCount(parts[0])) { "Cash denomination is invalid." }
                val count = requireNotNull(parseCashPieceCount(parts[1])) { "Cash count is invalid." }
                denomination to count
            }
            require(rows.map { it.first }.distinct().size == rows.size) { "Cash count contains a repeated denomination." }
            return CashCount(rows.toMap()).also { require(it.encode() == encoded) { "Cash count is not in its saved format." } }
        }
    }
}

/** Empty form rows mean none; signs, grouping, fractions, exponents and overflow are rejected. */
fun parseCashPieceCount(text: String): Long? {
    val input = text.trim()
    if (input.isEmpty()) return 0
    if (input.any { it !in '0'..'9' }) return null
    return input.toLongOrNull()
}

fun cashCountFromInputs(inputs: Map<Long, String>): CashCount? {
    // Selecting denomination entry is not evidence that an empty drawer was counted.
    // A merchant can explicitly enter 0 in any row to save a zero count.
    if (inputs.values.none { it.isNotBlank() }) return null
    val values = inputs.mapValues { (_, text) -> parseCashPieceCount(text) ?: return null }
    return try { CashCount(values) } catch (_: IllegalArgumentException) { null }
}
