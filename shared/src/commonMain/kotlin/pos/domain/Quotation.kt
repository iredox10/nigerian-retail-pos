package pos.domain

enum class QuotationStatus { ISSUED, ACCEPTED, CANCELLED, CONVERTED }

data class QuotationCustomer(val customerId:String?,val name:String,val phone:String?=null,val address:String?=null) {
    init {
        require(customerId==null || customerId.isNotBlank())
        require(name==name.trim() && name.length in 1..150 && name.none {it.isISOControl()}) {"Enter a customer or contact name from 1 to 150 printable characters."}
        require(phone==null || normalizeNigerianPhone(phone)==phone) {"Use a valid Nigerian contact phone number."}
        require(address==null || (address==address.trim() && address.length in 1..1000 && address.none {it.isISOControl()})) {"Use an address of at most 1000 printable characters."}
    }
}

data class QuotationLineInput(val productId:String,val sellingUnitId:String?,val quantity:Int,val agreedUnitPriceMinor:Long) {
    init {require(productId.isNotBlank() && (sellingUnitId==null || sellingUnitId.isNotBlank()) && quantity>0 && agreedUnitPriceMinor>=0)}
    val key:String get()=sellingUnitId?.let {"$productId~$it"} ?: productId
}

/** Immutable agreed goods, quantity precision, pack conversion and price for one revision. */
data class QuotationLine(
    val productId:String,val productName:String,val baseUnit:String,val basePrecision:Int,
    val sellingUnitId:String?,val sellingUnit:String,val baseUnitsPerUnit:Int,val quantityPrecision:Int,
    val quantity:Int,val agreedUnitPriceMinor:Long,
) {
    init {
        require(productId.isNotBlank() && productName.isNotBlank() && baseUnit.isNotBlank() && sellingUnit.isNotBlank())
        require(basePrecision in 0..6 && quantityPrecision==(if(sellingUnitId==null) basePrecision else 0))
        require(sellingUnitId==null || sellingUnitId.isNotBlank())
        require(baseUnitsPerUnit>0 && quantity>0 && agreedUnitPriceMinor>=0)
        require(sellingUnitId!=null || (sellingUnit==baseUnit && baseUnitsPerUnit==1))
        Quantity(quantity.toLong(),quantityPrecision).rescale(basePrecision).timesWhole(baseUnitsPerUnit.toLong())
        Quantity(quantity.toLong(),quantityPrecision).lineTotalMinor(agreedUnitPriceMinor)
    }
    val key:String get()=sellingUnitId?.let {"$productId~$it"} ?: productId
    val quantityLabel:String get()=quantityLabel(quantity.toLong(),quantityPrecision)
    val totalMinor:Long get()=Quantity(quantity.toLong(),quantityPrecision).lineTotalMinor(agreedUnitPriceMinor)
    fun input()=QuotationLineInput(productId,sellingUnitId,quantity,agreedUnitPriceMinor)
}

data class Quotation(
    val id:String,val tenantId:String,val status:QuotationStatus,val version:Long,
    val createdAt:String,val updatedAt:String,val expiresOn:String,val customer:QuotationCustomer?,
    val notes:String,val lines:List<QuotationLine>,val saleId:String?=null,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && version>0 && createdAt.isNotBlank() && updatedAt.isNotBlank())
        require(isValidCreditDueDate(expiresOn)) {"Use a valid quotation expiry date as YYYY-MM-DD."}
        require(notes==notes.trim() && notes.length<=2000 && notes.none {it.isISOControl()}) {"Use quotation notes of at most 2000 printable characters."}
        require(lines.size in 1..100 && lines.map {it.key}.distinct().size==lines.size) {"Choose 1–100 distinct goods and selling units."}
        require((status==QuotationStatus.CONVERTED)==(saleId!=null) && (saleId==null || saleId.isNotBlank()))
        lines.fold(0L) {sum,line ->addMoney(sum,line.totalMinor)}
    }
    val totalMinor:Long get()=lines.fold(0L) {sum,line ->addMoney(sum,line.totalMinor)}
}

data class QuotationEvent(val id:String,val quotationId:String,val kind:String,val reason:String,val createdAt:String,val snapshot:Quotation,val requestFingerprint:String?=null) {
    init {
        require(id.isNotBlank() && quotationId==snapshot.id && createdAt.isNotBlank())
        require(kind in setOf("issued","revised","accepted","cancelled","converted"))
        require((kind=="converted")== (requestFingerprint!=null) && (requestFingerprint==null || requestFingerprint.matches(Regex("[a-f0-9]{64}"))))
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 250 printable characters."}
    }
}
