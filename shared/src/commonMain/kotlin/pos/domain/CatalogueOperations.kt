package pos.domain

/** A counted correction preserves the quantity seen before the correction and the reason. */
data class StockAdjustment(
    val id:String, val tenantId:String, val productId:String, val productName:String,
    val unit:String, val previousStock:Double, val countedStock:Int, val reason:String,
    val createdAt:String,
) {
    init {
        require(listOf(id,tenantId,productId,productName,unit,reason,createdAt).all { it.isNotBlank() })
        require(previousStock.isFinite() && previousStock in 0.0..9_007_199_254_740_991.0 && countedStock>=0)
    }
    val difference:Double get() = countedStock.toDouble()-previousStock
}

data class PriceChange(val id:String,val tenantId:String,val productId:String,val previousPriceMinor:Long,val priceMinor:Long,val createdAt:String) {
    init { require(listOf(id,tenantId,productId,createdAt).all { it.isNotBlank() }); require(previousPriceMinor>=0 && priceMinor>=0) }
}

data class CustomerRepayment(val id:String,val tenantId:String,val customerId:String,val amountMinor:Long,val createdAt:String) {
    init { require(listOf(id,tenantId,customerId,createdAt).all { it.isNotBlank() }); require(amountMinor>0) }
}
