package pos.domain

enum class PurchaseDiscrepancyKind { SHORTAGE, DAMAGED, EXTRA }

/** Quantities use the saved order buying unit or the explicit stock unit of an extra item. */
data class PurchaseDiscrepancy(
    val id: String,
    val tenantId: String,
    val orderId: String,
    val approvalVersion: Long,
    val kind: PurchaseDiscrepancyKind,
    val lineId: String?,
    val productId: String,
    val productName: String,
    val buyingUnit: String,
    val baseUnit: String,
    val quantity: Int,
    val quantityPrecision: Int,
    val basePrecision: Int,
    val baseUnitsPerUnit: Int,
    val remainingAtReport: Int?,
    val reason: String,
    val createdAt: String,
) {
    init {
        require(listOf(id, tenantId, orderId, productId, productName, buyingUnit, baseUnit, createdAt).all { it.isNotBlank() })
        require(approvalVersion > 0 && quantity > 0 && basePrecision in 0..6 && quantityPrecision in 0..6 && baseUnitsPerUnit > 0)
        require(validPurchaseOrderReason(reason)) { "Enter a reason from 3 to 250 printable characters." }
        require((kind == PurchaseDiscrepancyKind.EXTRA) == (remainingAtReport == null))
        require(remainingAtReport == null || (lineId != null && quantity <= remainingAtReport))
        require(lineId != null || (buyingUnit == baseUnit && baseUnitsPerUnit == 1 && quantityPrecision == basePrecision))
        require(baseQuantity <= 9_007_199_254_740_991L) { "The reported quantity exceeds the supported stock range." }
    }

    val baseQuantity: Long
        get() = Quantity(quantity.toLong(), quantityPrecision).rescale(basePrecision).timesWhole(baseUnitsPerUnit.toLong()).scaledUnits

    val quantityLabel: String get() = quantityLabel(quantity.toLong(), quantityPrecision)
}

data class PurchaseDiscrepancyResolution(
    val id: String,
    val discrepancyId: String,
    val reason: String,
    val receiptId: String?,
    val createdAt: String,
) {
    init {
        require(id.isNotBlank() && discrepancyId.isNotBlank() && createdAt.isNotBlank())
        require(receiptId == null || receiptId.isNotBlank())
        require(validPurchaseOrderReason(reason)) { "Enter a resolution from 3 to 250 printable characters." }
    }
}

data class PurchaseDiscrepancyHistory(
    val report: PurchaseDiscrepancy,
    val resolution: PurchaseDiscrepancyResolution?,
)
