package pos.domain

/** A purchase receipt is not a complete supplier ledger. */
data class StockReceipt(
    val id: String, val tenantId: String, val productId: String, val productName: String,
    val unit: String, val quantity: Int, val unitCostMinor: Long, val totalCostMinor: Long,
    val paidCashMinor: Long, val supplierName: String, val createdAt: String,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && productId.isNotBlank())
        require(productName.isNotBlank() && unit.isNotBlank() && quantity > 0)
        require(unitCostMinor >= 0 && totalCostMinor == multiplyMoney(unitCostMinor, quantity))
        require(paidCashMinor in 0..totalCostMinor)
        require((paidCashMinor == totalCostMinor || supplierName.isNotBlank()) && createdAt.isNotBlank())
    }
    val unpaidMinor: Long get() = totalCostMinor - paidCashMinor
}

data class Expense(val id: String, val tenantId: String, val category: String, val description: String, val amountMinor: Long, val createdAt: String) {
    init { require(id.isNotBlank() && tenantId.isNotBlank() && category.isNotBlank() && description.isNotBlank() && amountMinor > 0 && createdAt.isNotBlank()) }
}

data class CashShift(val id: String, val tenantId: String, val openingMinor: Long, val openedAt: String, val closedAt: String?, val expectedMinor: Long?, val countedMinor: Long?) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && openingMinor >= 0 && openedAt.isNotBlank())
        require((closedAt == null && expectedMinor == null && countedMinor == null) || (closedAt != null && expectedMinor != null && expectedMinor >= 0 && countedMinor != null && countedMinor >= 0))
    }
    val differenceMinor: Long? get() = if (countedMinor != null && expectedMinor != null) countedMinor - expectedMinor else null
}

/** amountMinor is signed: money into drawer positive, money taken out negative. */
data class CashEntry(val id: String, val tenantId: String, val shiftId: String?, val kind: String, val amountMinor: Long, val description: String, val createdAt: String) {
    init { require(id.isNotBlank() && tenantId.isNotBlank() && kind.isNotBlank() && amountMinor != 0L && amountMinor != Long.MIN_VALUE && description.isNotBlank() && createdAt.isNotBlank()) }
}
