package pos.domain

/** An immutable owner-made link between an existing repayment and one original credit sale. */
data class RepaymentAllocation(
    val id:String, val tenantId:String, val customerId:String,
    val repaymentId:String, val saleId:String, val amountMinor:Long, val createdAt:String,
) {
    init {
        require(listOf(id,tenantId,customerId,repaymentId,saleId,createdAt).all { it.isNotBlank() })
        require(amountMinor>0) { "An allocation must be greater than zero." }
    }
}
