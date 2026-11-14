package pos.domain

/** A floor is an explicit rule for a named unit, independent of the normal catalogue price. */
data class MinimumPriceTerms(
    val productId:String,val productName:String,val sellingUnitId:String?,val unitName:String,
    val quantityPrecision:Int,val basePrecision:Int,val baseUnitsPerUnit:Int,
    val minimumUnitPriceMinor:Long?,
) {
    init {
        require(productId.isNotBlank() && productName.isNotBlank() && (sellingUnitId==null || sellingUnitId.isNotBlank()) && unitName.isNotBlank())
        require(quantityPrecision in 0..6 && basePrecision in 0..6 && baseUnitsPerUnit>0)
        require(if(sellingUnitId==null) quantityPrecision==basePrecision && baseUnitsPerUnit==1 else quantityPrecision==0)
        require(minimumUnitPriceMinor==null || minimumUnitPriceMinor>=0) {"Minimum price cannot be negative."}
    }
    val key:String get()=sellingUnitId?.let {"$productId~$it"} ?: productId

    /** A base-unit floor also constrains named packs; final totals must satisfy both rules. */
    fun minimumFor(line:CartLine):Long? {
        if(line.product.id!=productId || minimumUnitPriceMinor==null) return null
        if(sellingUnitId!=null && sellingUnitId!=line.sellingUnitId) return null
        require(line.product.quantityPrecision==basePrecision) {"Item precision changed. Review its minimum price."}
        val quantity=if(sellingUnitId==null) {
            require(line.product.unit==unitName) {"Base unit changed. Review its minimum price."}
            Quantity(line.baseQuantity,basePrecision)
        } else {
            require(line.sellingUnit==unitName && line.baseUnitsPerUnit==baseUnitsPerUnit && line.quantityPrecision==quantityPrecision) {"Selling pack changed. Review its minimum price."}
            Quantity(line.quantity.toLong(),quantityPrecision)
        }
        return minimumPriceTotalMinor(quantity,minimumUnitPriceMinor)
    }
}

data class MinimumPricePolicy(val tenantId:String,val terms:MinimumPriceTerms,val version:Long,val updatedAt:String) {
    init {require(tenantId.isNotBlank() && version>0 && updatedAt.isNotBlank())}
}
data class MinimumPriceChange(val id:String,val tenantId:String,val previous:MinimumPriceTerms?,val current:MinimumPriceTerms,val version:Long,val reason:String,val createdAt:String) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && version>0 && createdAt.isNotBlank() && (previous==null)==(version==1L))
        require(previous==null || previous.key==current.key)
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 250 printable characters."}
    }
}

/** Round upward once, to a whole kobo. Rounding must never let a measured sale fall below its floor. */
fun minimumPriceTotalMinor(quantity:Quantity,unitPriceMinor:Long):Long {
    require(unitPriceMinor>=0)
    val scale=quantityScaleFactor(quantity.precision)
    val whole=quantity.scaledUnits/scale
    require(whole==0L || unitPriceMinor<=Long.MAX_VALUE/whole) {"Minimum-price amount exceeds the supported range."}
    val wholeAmount=whole*unitPriceMinor
    val fraction=quantity.scaledUnits%scale
    val parts=proportionalParts(unitPriceMinor,fraction,scale)
    return addMoney(wholeAmount,addMoney(parts.first,if(parts.second>0) 1 else 0))
}
