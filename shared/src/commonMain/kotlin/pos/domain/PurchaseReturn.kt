package pos.domain

/** Goods returned to a supplier against the original stock receipt and its outstanding debt. */
data class PurchaseReturn(
    val id: String,
    val tenantId: String,
    val receiptId: String,
    val quantity: Int,
    val creditMinor: Long,
    val owedBeforeMinor: Long,
    val stockBefore: Long,
    val stockAfter: Long,
    val reason: String,
    val createdAt: String,
    val cashReceivedMinor: Long = 0,
    val cashShiftId: String? = null,
) {
    init {
        require(validPurchaseReturnId(id) && tenantId.isNotBlank() && receiptId.isNotBlank())
        require(quantity > 0 && creditMinor >= 0 && owedBeforeMinor >= creditMinor && cashReceivedMinor >= 0 && returnedValueMinor > 0)
        require((cashReceivedMinor > 0) == (cashShiftId != null) && (cashShiftId == null || cashShiftId.isNotBlank()))
        require(stockAfter >= 0 && stockBefore > stockAfter && stockBefore <= 9_007_199_254_740_991L)
        require(reason == reason.trim() && reason.length in 3..500 && reason.none { it.isISOControl() })
        require(createdAt.isNotBlank())
    }
    val returnedValueMinor: Long get() = addMoney(creditMinor, cashReceivedMinor)
}

fun validPurchaseReturnId(id: String): Boolean = id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }

fun StockReceipt.purchaseReturnBaseQuantity(quantity: Int): Long =
    Quantity(quantity.toLong(), quantityPrecision).rescale(basePrecision).timesWhole(baseUnitsPerUnit.toLong()).scaledUnits

/** The marginal credit uses cumulative rounding so returning the entire receipt reverses its exact total. */
fun StockReceipt.purchaseReturnCredit(previousQuantity: Int, quantity: Int): Long {
    require(quantity > 0 && previousQuantity in 0..this.quantity && quantity <= this.quantity - previousQuantity) {
        "The return exceeds the quantity remaining on this receipt."
    }
    val before = Quantity(previousQuantity.toLong(), quantityPrecision).lineTotalMinor(unitCostMinor)
    val after = Quantity(previousQuantity.toLong() + quantity, quantityPrecision).lineTotalMinor(unitCostMinor)
    return after - before
}
