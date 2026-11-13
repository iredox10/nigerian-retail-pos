package pos.domain

data class ReturnLine(
    val productId: String, val name: String, val unit: String, val quantity: Int, val unitPriceMinor: Long,
    val sellingUnitId: String? = null, val baseUnitsPerUnit: Int = 1, val baseUnit: String = unit,
) {
    init { require(productId.isNotBlank() && name.isNotBlank() && unit.isNotBlank() && quantity > 0 && unitPriceMinor >= 0 && baseUnitsPerUnit > 0 && baseUnit.isNotBlank() && (sellingUnitId == null || sellingUnitId.isNotBlank())) }
    init { require(sellingUnitId != null || (baseUnitsPerUnit == 1 && baseUnit == unit)) { "Base return units must match the item." } }
    val key: String get() = sellingUnitId?.let { productId + "~" + it } ?: productId
    val baseQuantity: Long get() = quantity.toLong() * baseUnitsPerUnit
    val totalMinor: Long get() = multiplyMoney(unitPriceMinor, quantity)
}

data class SaleReturn(
    val id: String, val tenantId: String, val saleId: String, val lines: List<ReturnLine>,
    val refundMinor: Long, val restock: Boolean, val reason: String, val createdAt: String,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && saleId.isNotBlank() && reason.isNotBlank() && createdAt.isNotBlank())
        require(lines.isNotEmpty() && lines.map { it.key }.distinct().size == lines.size)
        require(refundMinor >= 0 && refundMinor == lines.fold(0L) { total, line -> addMoney(total, line.totalMinor) })
    }
}

data class SupplierPayment(val id: String, val tenantId: String, val receiptId: String, val supplierName: String, val amountMinor: Long, val createdAt: String) {
    init { require(id.isNotBlank() && tenantId.isNotBlank() && receiptId.isNotBlank() && supplierName.isNotBlank() && amountMinor > 0 && createdAt.isNotBlank()) }
}
