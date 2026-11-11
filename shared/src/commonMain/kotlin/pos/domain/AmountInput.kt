package pos.domain

/**
 * Parses an entered naira amount into integer kobo, without rounding.
 *
 * Accepts surrounding whitespace, ASCII digits, and an optional decimal point
 * followed by one or two digits. Currency symbols, grouping commas, signs,
 * exponents, internal spaces, and amounts outside [Long.MAX_VALUE] are rejected.
 * Zero is valid here; each business operation decides whether zero is allowed.
 */
fun parseNaira(text: String): Long? {
    val input = text.trim()
    if (input.isEmpty()) return null
    val point = input.indexOf('.')
    val wholeEnd = if (point < 0) input.length else point
    if (wholeEnd == 0) return null
    val fractionLength = if (point < 0) 0 else input.length - point - 1
    if (point >= 0 && fractionLength !in 1..2) return null

    var value = 0L
    fun append(character: Char): Boolean {
        if (character !in '0'..'9') return false
        val digit = character - '0'
        if (value > (Long.MAX_VALUE - digit) / 10) return false
        value = value * 10 + digit
        return true
    }

    for (index in 0 until wholeEnd) {
        if (!append(input[index])) return null
    }
    if (!append(if (fractionLength >= 1) input[point + 1] else '0')) return null
    if (!append(if (fractionLength == 2) input[point + 2] else '0')) return null
    return value
}
