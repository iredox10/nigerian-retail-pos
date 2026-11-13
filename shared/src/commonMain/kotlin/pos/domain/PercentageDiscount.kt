package pos.domain

/** Percentage with two decimal places: 1 basis point = 0.01%. */
data class DiscountPercentage(val basisPoints: Int) {
    init { require(basisPoints in 0..10_000) { "Enter a percentage from 0 to 100 with at most two decimal places." } }

    /** Round the reduction, once per selling unit, to the nearest kobo; half a kobo rounds up. */
    fun amountMinor(unitPriceMinor: Long): Long {
        require(unitPriceMinor >= 0) { "Selling price cannot be negative." }
        // Split before multiplying: Long.MAX_VALUE × 100% remains representable.
        val whole = (unitPriceMinor / 10_000) * basisPoints
        val remainder = (unitPriceMinor % 10_000) * basisPoints
        return whole + (remainder + 5_000) / 10_000
    }

    fun display(): String {
        val whole=basisPoints / 100
        val fraction=(basisPoints % 100).toString().padStart(2, '0').trimEnd('0')
        return if (fraction.isEmpty()) "$whole%" else "$whole.$fraction%"
    }

    /** Save the exact reduction and the requested percentage in existing immutable receipt/draft fields. */
    fun unitDiscount(unitPriceMinor: Long, reason: String): UnitDiscount {
        val amount=amountMinor(unitPriceMinor)
        require(amount > 0) { "This percentage saves less than half a kobo per unit. Choose a larger discount." }
        // Validate the merchant reason independently so a prefix cannot make a blank reason valid.
        UnitDiscount(unitPriceMinor, amount, reason)
        return UnitDiscount(unitPriceMinor, amount, "${display()} off: $reason")
    }
}

/** Strict decimal percentage, without signs, symbols, grouping or exponent notation. */
fun parseDiscountPercentage(text: String): DiscountPercentage? {
    val points=parseNaira(text) ?: return null
    if (points !in 0..10_000) return null
    return DiscountPercentage(points.toInt())
}
