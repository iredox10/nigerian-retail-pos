package pos.domain

enum class PromotionReduction { FIXED, PERCENTAGE }

/** A promotion targets one saved selling unit, including the product's base unit. Dates are inclusive in WAT. */
data class PromotionTerms(
    val name: String,
    val productId: String,
    val sellingUnitId: String?,
    val minimumQuantity: Int,
    val quantityPrecision: Int,
    val from: String,
    val through: String,
    val reduction: PromotionReduction,
    val amount: Long,
    val maximumSales: Int? = null,
    val archived: Boolean = false,
) {
    init {
        require(validPromotionName(name)) { "Enter a promotion name from 1 to 80 printable characters." }
        require(productId.isNotBlank() && (sellingUnitId == null || sellingUnitId.isNotBlank()))
        require(minimumQuantity > 0 && quantityPrecision in 0..6 && (sellingUnitId == null || quantityPrecision == 0))
        require(isValidCreditDueDate(from) && isValidCreditDueDate(through) && from <= through) {
            "Enter a valid start and end date in YYYY-MM-DD order."
        }
        require(amount > 0 && (reduction == PromotionReduction.FIXED || amount <= 10_000)) {
            "Enter a positive amount off or a percentage up to 100."
        }
        require(maximumSales == null || maximumSales > 0) { "The sale limit must be a positive whole number." }
    }

    fun isScheduledOn(day: String): Boolean = !archived && day in from..through

    fun matches(line: CartLine): Boolean =
        line.product.id == productId && line.sellingUnitId == sellingUnitId &&
            line.quantityPrecision == quantityPrecision && line.quantity >= minimumQuantity

    fun discountMinor(priceMinor: Long): Long = when (reduction) {
        PromotionReduction.FIXED -> amount
        PromotionReduction.PERCENTAGE -> DiscountPercentage(amount.toInt()).amountMinor(priceMinor)
    }
}

private fun validPromotionName(value: String): Boolean =
    value == value.trim() && value.length in 1..80 && value.none { it.isISOControl() }

private val promotionId = Regex("[A-Za-z0-9_-]{1,64}")

fun validPromotionId(value: String): Boolean = promotionId.matches(value)

/** The existing draft and receipt discount fields carry this reference and the reviewed line price. */
data class PromotionSource(
    val id: String,
    val version: Long,
    val quantity: Int,
    val reviewedOn: String,
    val title: String,
) {
    init {
        require(validPromotionId(id) && version > 0 && quantity > 0 &&
            isValidCreditDueDate(reviewedOn) && validPromotionName(title)) {
            "Promotion details are invalid. Review the line price."
        }
    }

    fun encode(): String = "${prefix(id)}$version/$quantity/$reviewedOn/$title"

    companion object {
        fun prefix(id: String): String = "@promotion/1/$id/"

        fun decode(reason: String): PromotionSource? {
            if (!reason.startsWith("@promotion/")) return null
            val fields = reason.split('/', limit = 7)
            require(fields.size == 7 && fields[1] == "1") { "Review this promotion before taking payment." }
            val version = requireNotNull(fields[3].toLongOrNull()) { "The promotion revision is invalid." }
            val quantity = requireNotNull(fields[4].toIntOrNull()) { "The promotion quantity is invalid." }
            return PromotionSource(fields[2], version, quantity, fields[5], fields[6]).also {
                require(it.encode() == reason) { "The saved promotion reference is invalid." }
            }
        }
    }
}
