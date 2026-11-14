package pos.domain

private const val REPACK_MAX=9_007_199_254_740_991L
/** Merchant-declared physical recipe, not an inferred equivalence or a costing allocation. */
data class RepackingRecipeDetails(
    val name:String,val sourceId:String,val sourceName:String,val sourceUnit:String,val sourcePrecision:Int,val sourcePerBatch:Long,
    val destinationId:String,val destinationName:String,val destinationUnit:String,val destinationPrecision:Int,val destinationPerBatch:Long,
    val archived:Boolean=false,
) {
    init {
        require(name==name.trim() && name.length in 1..150 && name.none {it.isISOControl()}) {"Enter a repacking name from 1 to 150 printable characters."}
        require(listOf(sourceId,sourceName,sourceUnit,destinationId,destinationName,destinationUnit).all {it.isNotBlank() && it.length<=500 && it.none {c ->c.isISOControl()}})
        require(sourceId!=destinationId) {"Choose two different catalogue goods. Selling packs of the same item already share stock."}
        require(sourcePrecision in 0..6 && destinationPrecision in 0..6 && sourcePerBatch in 1..REPACK_MAX && destinationPerBatch in 1..REPACK_MAX) {"Enter positive exact quantities for both sides of one batch."}
    }
    fun sourceQuantity(batches:Long):Long=Quantity(sourcePerBatch,sourcePrecision).timesWhole(batches.also {require(it>0) {"Enter a positive whole number of batches."}}).scaledUnits.also {require(it<=REPACK_MAX)}
    fun destinationQuantity(batches:Long):Long=Quantity(destinationPerBatch,destinationPrecision).timesWhole(batches.also {require(it>0)}).scaledUnits.also {require(it<=REPACK_MAX)}
}
data class RepackingRecipe(val id:String,val tenantId:String,val details:RepackingRecipeDetails,val version:Long,val updatedAt:String) {
    init {require(id.isNotBlank() && tenantId.isNotBlank() && version>0 && updatedAt.isNotBlank())}
}
data class RepackingRecipeChange(val id:String,val recipeId:String,val version:Long,val previous:RepackingRecipeDetails?,val current:RepackingRecipeDetails,val reason:String,val createdAt:String)
data class RepackingReview(val recipeId:String,val recipeVersion:Long,val batches:Long,val sourceBefore:Long,val destinationBefore:Long,val token:String) {
    init {require(recipeId.isNotBlank() && recipeVersion>0 && batches>0 && sourceBefore in 0..REPACK_MAX && destinationBefore in 0..REPACK_MAX && token.length==64 && token.all {it in '0'..'9' || it in 'a'..'f'})}
}
/** Both physical movements commit together. No money, value, purchase or sale is implied. */
data class RepackingRun(val id:String,val tenantId:String,val recipeId:String,val recipeVersion:Long,val details:RepackingRecipeDetails,val batches:Long,
    val sourceBefore:Long,val sourceAfter:Long,val destinationBefore:Long,val destinationAfter:Long,val reason:String,val createdAt:String,val reviewToken:String) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && recipeId.isNotBlank() && recipeVersion>0 && createdAt.isNotBlank())
        val used=details.sourceQuantity(batches);val made=details.destinationQuantity(batches)
        require(sourceBefore in used..REPACK_MAX && sourceAfter==sourceBefore-used)
        require(destinationBefore in 0..REPACK_MAX && destinationAfter in destinationBefore..REPACK_MAX && destinationAfter-destinationBefore==made)
        require(reason==reason.trim() && reason.length in 3..500 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 500 printable characters."}
        require(reviewToken.length==64 && reviewToken.all {it in '0'..'9' || it in 'a'..'f'})
    }
}
