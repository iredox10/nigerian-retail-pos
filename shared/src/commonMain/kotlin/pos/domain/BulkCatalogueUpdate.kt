package pos.domain

data class BulkCatalogueValues(
    val name:String,val unit:String,val precision:Int,val priceMinor:Long,val barcode:String?,
    val metadata:ProductMetadata,val reorderLevel:Int?,
) {
    init {
        require(name==name.trim() && name.length in 1..1000 && name.none {it.isISOControl()}) {"Use an item name from 1 to 1,000 printable characters."}
        require(unit.isNotBlank() && precision in 0..6 && priceMinor>=0)
        require(barcode==null || (barcode==barcode.trim() && barcode.length in 1..128 && barcode.none {it.isISOControl()})) {"Use a barcode of at most 128 printable characters, or leave it blank."}
        require(reorderLevel==null || reorderLevel>=0) {"Reorder level must be a whole number, zero or more, or blank."}
    }
}
fun Product.bulkCatalogueValues()=BulkCatalogueValues(name,unit,quantityPrecision,priceMinor,barcode,metadata(),reorderLevel)

data class BulkCatalogueUpdateRow(val record:Int,val productId:String,val expectedVersion:String,val values:BulkCatalogueValues) {
    init {
        require(record>=2)
        require(productId==productId.trim() && productId.length in 1..200 && productId.none {it.isISOControl()}) {"Keep the exported product ID unchanged."}
        require(expectedVersion.length==64 && expectedVersion.all {it in '0'..'9' || it in 'a'..'f'}) {"Keep the exported review version unchanged."}
    }
}
data class BulkCatalogueIssue(val record:Int,val message:String)
data class BulkCatalogueCsvResult(val rows:List<BulkCatalogueUpdateRow>,val issues:List<BulkCatalogueIssue>)
data class BulkCataloguePreviewRow(val requested:BulkCatalogueUpdateRow,val before:BulkCatalogueValues?) {
    val changed:Boolean get()=before!=null && before!=requested.values
}
data class BulkCataloguePreview(val rows:List<BulkCataloguePreviewRow>,val issues:List<BulkCatalogueIssue>) {
    val changedCount:Int get()=rows.count {it.changed}
    val canApply:Boolean get()=issues.isEmpty() && changedCount>0 && rows.isNotEmpty()
}
data class BulkCatalogueBatchRow(val productId:String,val record:Int,val expectedVersion:String,val before:BulkCatalogueValues,val after:BulkCatalogueValues)
data class BulkCatalogueBatch(val id:String,val tenantId:String,val reason:String,val createdAt:String,val fingerprint:String,val rows:List<BulkCatalogueBatchRow>) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && createdAt.isNotBlank())
        require(reason==reason.trim() && reason.length in 3..500 && reason.none {it.isISOControl()}) {"Enter a batch reason from 3 to 500 printable characters."}
        require(fingerprint.length==64 && fingerprint.all {it in '0'..'9' || it in 'a'..'f'})
        require(rows.size in 1..10000 && rows.map {it.productId}.distinct().size==rows.size)
        rows.forEach {BulkCatalogueUpdateRow(it.record,it.productId,it.expectedVersion,it.after)}
    }
}
