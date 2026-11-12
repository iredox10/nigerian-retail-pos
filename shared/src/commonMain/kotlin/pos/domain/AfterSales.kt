package pos.domain

data class ReturnLine(val productId: String, val name: String, val unit: String, val quantity: Int, val unitPriceMinor: Long) {
    init { require(productId.isNotBlank() && name.isNotBlank() && unit.isNotBlank() && quantity > 0 && unitPriceMinor >= 0) }
    val totalMinor: Long get() = multiplyMoney(unitPriceMinor, quantity)
}

data class SaleReturn(
    val id: String, val tenantId: String, val saleId: String, val lines: List<ReturnLine>,
    val refundMinor: Long, val restock: Boolean, val reason: String, val createdAt: String,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && saleId.isNotBlank() && reason.isNotBlank() && createdAt.isNotBlank())
        require(lines.isNotEmpty() && lines.map { it.productId }.distinct().size == lines.size)
        require(refundMinor >= 0 && refundMinor == lines.fold(0L) { total, line -> addMoney(total, line.totalMinor) })
    }
}

data class SupplierPayment(val id: String, val tenantId: String, val receiptId: String, val supplierName: String, val amountMinor: Long, val createdAt: String) {
    init { require(id.isNotBlank() && tenantId.isNotBlank() && receiptId.isNotBlank() && supplierName.isNotBlank() && amountMinor > 0 && createdAt.isNotBlank()) }
}
