package pos.domain

/** Exact nonnegative base-unit quantity. Precision is a product policy, never inferred from input. */
data class Quantity(val scaledUnits: Long, val precision: Int) {
    init {
        require(scaledUnits >= 0) { "Quantity cannot be negative." }
        require(precision in 0..6) { "Quantity precision must be from zero to six." }
    }

    private val scale: Long get() = quantityScale(precision)

    /** Reducing precision is allowed only when it preserves the exact quantity. */
    fun rescale(newPrecision: Int): Quantity {
        val newScale = quantityScale(newPrecision)
        return if (newScale >= scale) Quantity(quantityProduct(scaledUnits, newScale / scale), newPrecision)
        else {
            val divisor = scale / newScale
            require(scaledUnits % divisor == 0L) { "Quantity cannot be represented at the selected precision." }
            Quantity(scaledUnits / divisor, newPrecision)
        }
    }

    operator fun plus(other: Quantity): Quantity {
        require(precision == other.precision) { "Explicitly rescale quantities before adding." }
        return copy(scaledUnits = quantitySum(scaledUnits, other.scaledUnits))
    }

    operator fun minus(other: Quantity): Quantity {
        require(precision == other.precision) { "Explicitly rescale quantities before subtracting." }
        require(scaledUnits >= other.scaledUnits) { "Quantity cannot become negative." }
        return copy(scaledUnits = scaledUnits - other.scaledUnits)
    }

    /** A whole count of identical packs; the saved pack definition supplies this base quantity. */
    fun timesWhole(count: Long): Quantity = copy(scaledUnits = quantityProduct(scaledUnits, count))

    fun wholePackageCount(baseQuantityPerPackage: Quantity): Long {
        require(precision == baseQuantityPerPackage.precision) { "Explicitly rescale the package definition first." }
        require(baseQuantityPerPackage.scaledUnits > 0) { "A package must contain a positive quantity." }
        require(scaledUnits % baseQuantityPerPackage.scaledUnits == 0L) { "Quantity does not contain a whole number of packages." }
        return scaledUnits / baseQuantityPerPackage.scaledUnits
    }

    /** Price per base unit in kobo; round the complete line once, HALF_UP to one kobo. */
    fun lineTotalMinor(unitPriceMinor: Long): Long {
        require(unitPriceMinor >= 0) { "Unit price cannot be negative." }
        val whole = quantityProduct(unitPriceMinor, scaledUnits / scale)
        val fraction = scaledUnits % scale
        // Splitting both operands keeps intermediates representable even when their
        // undivided product exceeds Long.MAX_VALUE but the rounded result fits.
        val fractionWhole = quantityProduct(unitPriceMinor / scale, fraction)
        val remainderProduct = (unitPriceMinor % scale) * fraction // At most (10^6 - 1)^2.
        val rounded = remainderProduct / scale + if (remainderProduct % scale >= (scale + 1) / 2) 1 else 0
        return quantitySum(whole, quantitySum(fractionWhole, rounded))
    }

    override fun toString(): String {
        if (precision == 0) return scaledUnits.toString()
        val digits = scaledUnits.toString().padStart(precision + 1, '0')
        return digits.dropLast(precision) + "." + digits.takeLast(precision)
    }
}

/** ASCII decimal only; outer whitespace is allowed, excess precision is rejected, never rounded. */
fun parseQuantity(text: String, precision: Int): Quantity? {
    val scale = quantityScale(precision)
    val input = text.trim()
    val point = input.indexOf('.')
    val wholeEnd = if (point < 0) input.length else point
    val fractionalDigits = if (point < 0) 0 else input.length - point - 1
    if (wholeEnd == 0 || (point >= 0 && fractionalDigits !in 1..precision)) return null
    var value = 0L
    for (character in input) {
        if (character == '.' && point >= 0) continue
        if (character !in '0'..'9') return null
        val digit = character - '0'
        if (value > (Long.MAX_VALUE - digit) / 10) return null
        value = value * 10 + digit
    }
    if (input.count { it == '.' } > 1) return null
    val multiplier = scale / quantityScale(fractionalDigits)
    if (value > Long.MAX_VALUE / multiplier) return null
    return Quantity(value * multiplier, precision)
}

private fun quantityScale(precision: Int): Long {
    require(precision in 0..6) { "Quantity precision must be from zero to six." }
    var scale = 1L
    repeat(precision) { scale *= 10 }
    return scale
}

private fun quantityProduct(value: Long, factor: Long): Long {
    require(value >= 0 && factor >= 0 && (factor == 0L || value <= Long.MAX_VALUE / factor)) { "Quantity or amount exceeds supported range." }
    return value * factor
}

private fun quantitySum(left: Long, right: Long): Long {
    require(left <= Long.MAX_VALUE - right) { "Quantity or amount exceeds supported range." }
    return left + right
}

/** Legacy Double fields are display projections; scaled integers are authoritative for new goods. */
fun quantityScaleFactor(precision: Int): Long {
    require(precision in 0..6)
    var scale = 1L
    repeat(precision) { scale *= 10 }
    return scale
}
fun quantityLabel(scaled: Long, precision: Int): String = Quantity(scaled, precision).toString().let {
    if (precision == 0) it else it.trimEnd('0').trimEnd('.')
}
fun quantityCount(text: String, precision: Int): Int? = parseQuantity(text, precision)?.scaledUnits
    ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
fun projectedQuantity(scaled: Long, precision: Int): Double = scaled.toDouble() / quantityScaleFactor(precision)
