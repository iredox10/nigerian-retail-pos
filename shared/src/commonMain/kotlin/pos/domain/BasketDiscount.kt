package pos.domain

/** A reviewed whole-sale reduction, applied after explicit item discounts. Percentage is basis points. */
data class BasketDiscount(val kind:String,val value:Long,val reason:String) {
    init {
        require(kind in setOf("fixed","percentage"))
        require(value>0 && (kind!="percentage" || value<=10_000)) { "Enter a discount greater than zero; percentages cannot exceed 100%." }
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) { "Enter a reason from 3 to 250 printable characters." }
    }
    fun amountMinor(subtotal:Long):Long {
        require(subtotal>=0)
        val amount=if(kind=="fixed") value else proportionalRounded(subtotal,value,10_000)
        require(amount in 1..subtotal) { "The basket discount must save at least one kobo and cannot exceed the sale subtotal." }
        return amount
    }
}

/** Exact floor/remainder of a*b/divisor, without multiplying two potentially large Longs. */
internal fun proportionalParts(a:Long,b:Long,divisor:Long):Pair<Long,Long> {
    require(a>=0 && b>=0 && divisor>0 && b<=divisor)
    var quotient=0L;var remainder=0L
    for(bit in 62 downTo 0) {
        quotient*=2
        if(remainder>=divisor-remainder) {remainder-=divisor-remainder;quotient++} else remainder+=remainder
        if(((a ushr bit) and 1L)!=0L) {
            if(remainder>=divisor-b) {remainder-=divisor-b;quotient++} else remainder+=b
        }
    }
    return quotient to remainder
}
internal fun proportionalRounded(a:Long,b:Long,divisor:Long):Long {
    val (whole,remainder)=proportionalParts(a,b,divisor)
    return whole+if(remainder>=divisor-remainder) 1 else 0
}

/** Largest remainders, with stable item-key ties; list order cannot change the saved allocation. */
fun allocateBasketDiscount(lines:List<CartLine>,discount:BasketDiscount):List<CartLine> {
    require(lines.isNotEmpty() && lines.map {it.key}.distinct().size==lines.size)
    val clean=lines.map {it.copy(basketDiscountMinor=0)}
    val total=clean.fold(0L) {sum,line -> addMoney(sum,line.beforeBasketMinor)}
    val amount=discount.amountMinor(total)
    val parts=clean.associate {it.key to proportionalParts(amount,it.beforeBasketMinor,total)}
    val allocated=parts.values.fold(0L) {sum,part -> addMoney(sum,part.first)}
    val extra=(amount-allocated).toInt()
    require(extra in 0..clean.size)
    val winners=clean.sortedWith(compareByDescending<CartLine> {parts.getValue(it.key).second}.thenBy {it.key}).take(extra).map {it.key}.toSet()
    return clean.map {it.copy(basketDiscountMinor=parts.getValue(it.key).first+if(it.key in winners) 1 else 0)}
}

/** Identity of all values which affect a reviewed allocation; descriptive values are included. */
fun basketDiscountBasis(lines:List<CartLine>):String = lines.sortedBy {it.key}.joinToString("") {
    val fields=listOf(it.key,it.quantity,it.quantityPrecision,it.unitPriceMinor,it.discountPerUnitMinor,it.discountReason.orEmpty(),it.sellingUnit,it.baseUnitsPerUnit,it.product.name)
    fields.joinToString("") {value -> value.toString().let {text -> "${text.length}:$text"}}
}
