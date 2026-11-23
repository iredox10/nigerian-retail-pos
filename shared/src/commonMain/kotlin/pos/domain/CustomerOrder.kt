package pos.domain

data class OrderPayment(val orderId: String, val depositMinor: Long) {
    init { require(orderId.isNotBlank() && depositMinor >= 0) }
}

data class CustomerOrder(
    val id: String,
    val tenantId: String,
    val customerId: String,
    val customerName: String,
    val source: String,
    val collectOn: String?,
    val note: String,
    val createdAt: String,
    val lines: List<QuotationLine>,
) {
    init {
        require(listOf(id, tenantId, customerId, customerName, createdAt).all { it.isNotBlank() })
        require(source in setOf("In shop", "Phone", "WhatsApp", "Other"))
        require(collectOn == null || isValidCreditDueDate(collectOn))
        require(note == note.trim() && note.length <= 500 && note.none { it.isISOControl() })
        require(lines.size in 1..100 && lines.map { it.key }.distinct().size == lines.size)
        totalMinor
    }
    val totalMinor: Long get() = lines.fold(0L) { total, line -> addMoney(total, line.totalMinor) }
}
