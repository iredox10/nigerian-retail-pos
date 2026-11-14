package pos.domain

enum class StockCountStatus { DRAFT, POSTED, CANCELLED }

data class StockCountLine(
    val productId:String, val productName:String, val unit:String, val precision:Int,
    val baselineScaled:Long, val baselineToken:String, val countedScaled:Int?, val position:Int,
) {
    init {
        require(productId.isNotBlank() && productName.isNotBlank() && unit.isNotBlank())
        require(precision in 0..6 && baselineScaled in 0..9_007_199_254_740_991L && position>=0)
        require(baselineToken.length==64 && baselineToken.all {it in '0'..'9' || it in 'a'..'f'})
        require(countedScaled==null || countedScaled>=0)
    }
    val baselineLabel:String get()=quantityLabel(baselineScaled,precision)
    val countedLabel:String? get()=countedScaled?.let {quantityLabel(it.toLong(),precision)}
    val differenceLabel:String? get()=countedScaled?.let {
        val difference=it.toLong()-baselineScaled
        (if(difference<0) "−" else if(difference>0) "+" else "")+quantityLabel(if(difference<0) -difference else difference,precision)
    }
}

data class StockCountSession(
    val id:String,val tenantId:String,val name:String,val status:StockCountStatus,
    val reason:String,val version:Long,val createdAt:String,val completedAt:String?,
    val lines:List<StockCountLine>,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && createdAt.isNotBlank() && version>0)
        require(name==name.trim() && name.length in 1..100 && name.none {it.isISOControl()})
        require(reason==reason.trim() && reason.length<=500 && reason.none {it.isISOControl()})
        require(lines.size in 1..500 && lines.map {it.productId}.distinct().size==lines.size)
        require(lines.map {it.position}==lines.indices.toList())
        require((status==StockCountStatus.DRAFT)==(completedAt==null))
        if(status!=StockCountStatus.DRAFT) require(reason.isNotBlank())
        if(status==StockCountStatus.POSTED) require(lines.all {it.countedScaled!=null})
    }
}
