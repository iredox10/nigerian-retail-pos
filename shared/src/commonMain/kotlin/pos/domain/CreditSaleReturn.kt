package pos.domain

/** A linked reduction of unpaid customer credit, never money received or a cash refund. */
data class CreditReturnReduction(
    val returnId:String,val tenantId:String,val saleId:String,val customerId:String,
    val amountMinor:Long,val createdAt:String,val cashRefundMinor:Long=0,
) {
    init {require(listOf(returnId,tenantId,saleId,customerId,createdAt).all {it.isNotBlank()} && amountMinor>=0 && cashRefundMinor>=0);addMoney(amountMinor,cashRefundMinor)}
}

data class CreditReturnAllowance(val saleId:String,val customerId:String,val outstandingCreditMinor:Long,val customerDebtMinor:Long) {
    init {require(saleId.isNotBlank() && customerId.isNotBlank() && outstandingCreditMinor>=0 && customerDebtMinor>=outstandingCreditMinor)}
}
