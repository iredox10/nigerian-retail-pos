package pos.domain

enum class PriceListKind(val label:String) { RETAIL("Retail"), WHOLESALE("Wholesale"), CUSTOMER("Customer") }

/** Threshold quantities use the selected selling unit's fixed decimal precision. */
data class PriceListTier(val productId:String,val sellingUnitId:String?,val minimumQuantity:Int,val quantityPrecision:Int,val unitPriceMinor:Long) {
    init {
        require(productId.isNotBlank() && (sellingUnitId==null || sellingUnitId.isNotBlank()))
        require(minimumQuantity>0 && quantityPrecision in 0..6 && (sellingUnitId==null || quantityPrecision==0))
        require(unitPriceMinor>=0)
    }
    val key:String get()=sellingUnitId?.let {"$productId~$it"} ?: productId
    val minimumLabel:String get()=quantityLabel(minimumQuantity.toLong(),quantityPrecision)
}

data class PriceListDetails(val name:String,val kind:PriceListKind,val customerId:String?=null,val tiers:List<PriceListTier>,val archived:Boolean=false) {
    init {
        require(name==name.trim() && name.length in 1..100 && name.none {it.isISOControl()}) {"Enter a price-list name from 1 to 100 printable characters."}
        require((kind==PriceListKind.CUSTOMER)==(customerId!=null)) {"Choose a customer only for a customer price list."}
        require(customerId==null || customerId.isNotBlank())
        require(tiers.size in 1..500 && tiers.map {Triple(it.productId,it.sellingUnitId,it.minimumQuantity)}.distinct().size==tiers.size) {"Use 1–500 distinct item/unit/quantity thresholds."}
        tiers.groupBy {it.productId to it.sellingUnitId}.values.forEach {group ->
            require(group.map {it.quantityPrecision}.distinct().size==1) {"Quantity precision must agree within an item/unit."}
            require(group.sortedBy {it.minimumQuantity}.zipWithNext().all {(a,b) ->b.unitPriceMinor<=a.unitPriceMinor}) {"A larger quantity break cannot have a higher unit price."}
        }
    }
    fun tierFor(line:CartLine):PriceListTier?=tiers.filter {it.productId==line.product.id && it.sellingUnitId==line.sellingUnitId && it.quantityPrecision==line.quantityPrecision && it.minimumQuantity<=line.quantity}.maxByOrNull {it.minimumQuantity}
}

data class PriceList(val id:String,val tenantId:String,val details:PriceListDetails,val version:Long,val updatedAt:String) {
    init {require(id.isNotBlank() && tenantId.isNotBlank() && version>0 && updatedAt.isNotBlank())}
}

data class PriceListChange(val id:String,val tenantId:String,val listId:String,val previous:PriceListDetails?,val current:PriceListDetails,val version:Long,val reason:String,val createdAt:String) {
    init {
        require(listOf(id,tenantId,listId,createdAt).all {it.isNotBlank()} && version>0 && ((previous==null)==(version==1L)))
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 250 printable characters."}
    }
}

/** Explicit review result. It neither changes a basket nor creates a sale by itself. */
data class PriceListQuote(val listId:String,val listName:String,val listVersion:Long,val kind:PriceListKind,val customerId:String?,val lineKey:String,val quantity:Int,val tier:PriceListTier,val catalogueUnitPriceMinor:Long) {
    init {
        require(listId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && listOf(listName,lineKey).all {it.isNotBlank()} && listVersion>0 && quantity>=tier.minimumQuantity && catalogueUnitPriceMinor>=tier.unitPriceMinor)
        require((kind==PriceListKind.CUSTOMER)==(customerId!=null))
    }
    val unitPriceMinor:Long get()=tier.unitPriceMinor
    val sourceReason:String get()=PriceListSource(listId,listVersion,tier.minimumQuantity,quantity).encode()
    fun discount():UnitDiscount?=if(unitPriceMinor==catalogueUnitPriceMinor) null else UnitDiscount(catalogueUnitPriceMinor,catalogueUnitPriceMinor-unitPriceMinor,sourceReason)
}

/** Reserved, bounded provenance stored in the existing immutable line discount snapshot. */
data class PriceListSource(val listId:String,val version:Long,val minimumQuantity:Int,val quantity:Int) {
    init {require(listId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && version>0 && minimumQuantity>0 && quantity>=minimumQuantity)}
    fun encode():String="@price-list/1/$listId/$version/$minimumQuantity/$quantity"
    companion object {
        fun decode(reason:String):PriceListSource? {
            if(!reason.startsWith("@price-list/")) return null
            val fields=reason.split('/')
            require(fields.size==6 && fields[0]=="@price-list" && fields[1]=="1") {"Price-list source is unsupported. Review this line price."}
            return PriceListSource(fields[2],fields[3].toLong(),fields[4].toInt(),fields[5].toInt()).also {require(it.encode()==reason) {"Price-list source is invalid."}}
        }
    }
}
