package pos.domain

enum class InventoryDispositionCategory(val label:String) {
    DAMAGE("Damage"), SPOILAGE_EXPIRY("Spoilage or expiry"), THEFT_LOSS("Theft or loss"),
    SAMPLE("Samples"), DONATION("Donation"), OWNER_USE("Owner use")
}

data class InventoryDispositionBaseline(val stockScaled:Long,val token:String) {
    init {require(stockScaled in 0..9_007_199_254_740_991L && token.length==64 && token.all {it in '0'..'9' || it in 'a'..'f'})}
}

/** A physical stock removal, never a sale, cash movement, expense or profit calculation. */
data class InventoryDisposition(
    val id:String,val tenantId:String,val productId:String,val productName:String,val unit:String,
    val precision:Int,val quantityScaled:Long,val beforeScaled:Long,val afterScaled:Long,
    val category:InventoryDispositionCategory,val reason:String,val notes:String,val createdAt:String,
    val baselineToken:String,
) {
    init {
        require(listOf(id,tenantId,productId,productName,unit,createdAt).all {it.isNotBlank()})
        require(precision in 0..6 && beforeScaled in 0..9_007_199_254_740_991L && quantityScaled in 1..beforeScaled)
        require(afterScaled==beforeScaled-quantityScaled)
        require(reason==reason.trim() && reason.length in 3..500 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 500 printable characters."}
        require(notes==notes.trim() && notes.length<=1000 && notes.none {it.isISOControl()}) {"Use at most 1,000 printable characters for notes."}
        require(baselineToken.length==64 && baselineToken.all {it in '0'..'9' || it in 'a'..'f'})
    }
    val quantityLabel:String get()=quantityLabel(quantityScaled,precision)
    val beforeLabel:String get()=quantityLabel(beforeScaled,precision)
    val afterLabel:String get()=quantityLabel(afterScaled,precision)
}
