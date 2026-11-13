package pos.domain

/** A fixed reduction on each sold unit. Keeping the original price avoids rounding refunds. */
data class UnitDiscount(val cataloguePriceMinor: Long, val amountMinor: Long, val reason: String) {
    init {
        require(cataloguePriceMinor >= 0 && amountMinor > 0 && amountMinor <= cataloguePriceMinor) { "Enter a discount greater than zero and no greater than the unit price." }
        require(reason == reason.trim() && reason.length in 3..250 && reason.none { it.isISOControl() }) { "Enter a reason from 3 to 250 characters." }
    }
}
