package pos.domain

data class ReturnLine(
    val productId: String, val name: String, val unit: String, val quantity: Int, val unitPriceMinor: Long,
    val sellingUnitId: String? = null, val baseUnitsPerUnit: Int = 1, val baseUnit: String = unit,
    val quantityPrecision: Int = 0, val basePrecision: Int = quantityPrecision,
    val refundOverrideMinor: Long? = null,
) {
    init { require(quantityPrecision in 0..6 && basePrecision in 0..6 && quantityPrecision == (if (sellingUnitId == null) basePrecision else 0)); require(refundOverrideMinor == null || refundOverrideMinor >= 0) }
    init { require(productId.isNotBlank() && name.isNotBlank() && unit.isNotBlank() && quantity > 0 && unitPriceMinor >= 0 && baseUnitsPerUnit > 0 && baseUnit.isNotBlank() && (sellingUnitId == null || sellingUnitId.isNotBlank())) }
    init { require(sellingUnitId != null || (baseUnitsPerUnit == 1 && baseUnit == unit)) { "Base return units must match the item." } }
    val key: String get() = sellingUnitId?.let { productId + "~" + it } ?: productId
    val quantityLabel: String get() = quantityLabel(quantity.toLong(), quantityPrecision)
    val baseQuantity: Long get() = Quantity(quantity.toLong(), quantityPrecision).rescale(basePrecision).timesWhole(baseUnitsPerUnit.toLong()).scaledUnits
    val totalMinor: Long get() = refundOverrideMinor ?: Quantity(quantity.toLong(), quantityPrecision).lineTotalMinor(unitPriceMinor)
}

data class SaleReturn(
    val id: String, val tenantId: String, val saleId: String, val lines: List<ReturnLine>,
    val refundMinor: Long, val restock: Boolean, val reason: String, val createdAt: String,
    val creditReductionMinor: Long = 0,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && saleId.isNotBlank() && reason.isNotBlank() && createdAt.isNotBlank())
        require(lines.isNotEmpty() && lines.map { it.key }.distinct().size == lines.size)
        require(refundMinor >= 0 && creditReductionMinor >= 0 && addMoney(refundMinor,creditReductionMinor) == lines.fold(0L) { total, line -> addMoney(total, line.totalMinor) })
    }
    val returnedValueMinor:Long get()=addMoney(refundMinor,creditReductionMinor)
}

data class SupplierPayment(val id: String, val tenantId: String, val receiptId: String, val supplierName: String, val amountMinor: Long, val createdAt: String) {
    init { require(id.isNotBlank() && tenantId.isNotBlank() && receiptId.isNotBlank() && supplierName.isNotBlank() && amountMinor > 0 && createdAt.isNotBlank()) }
}
