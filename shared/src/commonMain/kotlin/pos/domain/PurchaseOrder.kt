package pos.domain

enum class PurchaseOrderStatus { DRAFT, APPROVED, CANCELLED }

/** Quantities are explicit scaled increments in the chosen buying unit. Packs are whole counts. */
data class PurchaseOrderLineInput(val id:String,val productId:String,val quantity:Int,val unitCostMinor:Long,val purchaseUnitId:String?=null) {
    init {require(id.isNotBlank() && productId.isNotBlank() && quantity>0 && unitCostMinor>=0 && (purchaseUnitId==null || purchaseUnitId.isNotBlank()))}
}

data class PurchaseOrderLine(
    val id:String,val productId:String,val productName:String,val baseUnit:String,val buyingUnit:String,
    val quantity:Int,val quantityPrecision:Int,val basePrecision:Int,val baseUnitsPerUnit:Int,
    val unitCostMinor:Long,val purchaseUnitId:String?=null,
) {
    init {
        require(listOf(id,productId,productName,baseUnit,buyingUnit).all {it.isNotBlank()} && quantity>0 && unitCostMinor>=0)
        require(id.length<=200 && productName.length<=500 && baseUnit.length<=100 && buyingUnit.length<=100) {"Order item names support up to 500 characters and units up to 100."}
        require(basePrecision in 0..6 && quantityPrecision==(if(purchaseUnitId==null) basePrecision else 0))
        require(baseUnitsPerUnit>0 && (purchaseUnitId!=null || (buyingUnit==baseUnit && baseUnitsPerUnit==1)))
        require(purchaseUnitId==null || purchaseUnitId.isNotBlank())
        require(baseQuantity<=9_007_199_254_740_991L) {"Ordered quantity exceeds the supported exact range."}
        estimatedTotalMinor
    }
    val quantityLabel:String get()=quantityLabel(quantity.toLong(),quantityPrecision)
    val baseQuantity:Long get()=Quantity(quantity.toLong(),quantityPrecision).rescale(basePrecision).timesWhole(baseUnitsPerUnit.toLong()).scaledUnits
    val estimatedTotalMinor:Long get()=Quantity(quantity.toLong(),quantityPrecision).lineTotalMinor(unitCostMinor)
    fun input()=PurchaseOrderLineInput(id,productId,quantity,unitCostMinor,purchaseUnitId)
}

data class PurchaseOrder(
    val id:String,val tenantId:String,val supplierId:String,val supplierName:String,val supplierVersion:Long,
    val status:PurchaseOrderStatus,val version:Long,val createdAt:String,val approvedAt:String?,
    val cancelledAt:String?,val cancellationReason:String?,val notes:String,val lines:List<PurchaseOrderLine>,
) {
    init {
        require(listOf(id,tenantId,supplierId,supplierName,createdAt).all {it.isNotBlank()} && version>0 && supplierVersion>0)
        require(notes==notes.trim() && notes.length<=1000 && notes.none {it.isISOControl()}) {"Use up to 1,000 printable characters for order notes."}
        require(lines.size in 1..200 && lines.map {it.id}.distinct().size==lines.size && lines.map {it.productId to it.purchaseUnitId}.distinct().size==lines.size) {"Choose 1–200 different item/buying-unit combinations."}
        require(when(status) {
            PurchaseOrderStatus.DRAFT -> approvedAt==null && cancelledAt==null && cancellationReason==null
            PurchaseOrderStatus.APPROVED -> approvedAt!=null && cancelledAt==null && cancellationReason==null
            PurchaseOrderStatus.CANCELLED -> cancelledAt!=null && validPurchaseOrderReason(cancellationReason)
        })
        estimatedTotalMinor
    }
    val estimatedTotalMinor:Long get()=lines.fold(0L) {sum,line -> addMoney(sum,line.estimatedTotalMinor)}
}

/** Each delivery has explicit cash applied and a stock preview; no payment is inferred from order approval. */
data class PurchaseDeliveryInput(val lineId:String,val quantity:Int,val paidCashMinor:Long,val expectedStockScaled:Long) {
    init {require(lineId.isNotBlank() && quantity>0 && paidCashMinor>=0 && expectedStockScaled>=0)}
}

data class PurchaseOrderDeliveryLine(val lineId:String,val receiptId:String,val quantity:Int,val paidCashMinor:Long,val expectedStockScaled:Long) {
    init {require(lineId.isNotBlank() && receiptId.isNotBlank() && quantity>0 && paidCashMinor>=0 && expectedStockScaled>=0)}
    fun input()=PurchaseDeliveryInput(lineId,quantity,paidCashMinor,expectedStockScaled)
}

data class PurchaseOrderDelivery(val id:String,val tenantId:String,val orderId:String,val orderVersion:Long,val createdAt:String,val lines:List<PurchaseOrderDeliveryLine>) {
    init {require(listOf(id,tenantId,orderId,createdAt).all {it.isNotBlank()} && orderVersion>0 && lines.isNotEmpty() && lines.size<=200 && lines.map {it.lineId}.distinct().size==lines.size && lines.map {it.receiptId}.distinct().size==lines.size)}
}

data class PurchaseOrderEvent(val id:String,val tenantId:String,val orderId:String,val version:Long,val kind:String,val reason:String,val createdAt:String,val snapshot:PurchaseOrder) {
    init {require(listOf(id,tenantId,orderId,createdAt).all {it.isNotBlank()} && version>0 && kind in setOf("created","edited","approved","cancelled") && validPurchaseOrderReason(reason));require(snapshot.id==orderId && snapshot.tenantId==tenantId && snapshot.version==version)}
}

fun validPurchaseOrderReason(value:String?):Boolean=value!=null && value==value.trim() && value.length in 3..250 && value.none {it.isISOControl()}
