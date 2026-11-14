package pos.domain

/** A source-linked view of an existing stock event; quantity is in the product's fixed selling unit. */
data class InventoryMovement(
    val id:String, val tenantId:String, val productId:String, val kind:String,
    val quantityDelta:Double, val sourceId:String, val createdAt:String, val description:String,
) {
    init {
        require(listOf(id,tenantId,productId,sourceId,createdAt,description).all { it.isNotBlank() })
        require(kind in setOf("opening","purchase","sale","return","count","disposition"))
        require(quantityDelta.isFinite() && quantityDelta in -9_007_199_254_740_991.0..9_007_199_254_740_991.0)
        require(when(kind) { "opening","purchase","return" -> quantityDelta>=0; "sale","disposition" -> quantityDelta<0; else -> true })
    }
}

/** Changing a reorder reminder does not change stock or create a purchase. */
data class StockSettingsChange(
    val id:String, val tenantId:String, val productId:String,
    val previousReorderLevel:Int?, val reorderLevel:Int?, val createdAt:String,
) {
    init {
        require(listOf(id,tenantId,productId,createdAt).all { it.isNotBlank() })
        require(previousReorderLevel==null || previousReorderLevel>=0)
        require(reorderLevel==null || reorderLevel>=0)
    }
}
