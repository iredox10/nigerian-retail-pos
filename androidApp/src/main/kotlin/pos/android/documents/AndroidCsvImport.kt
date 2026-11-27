package pos.android.documents

import pos.data.*
import pos.domain.*
import pos.output.ProductImportService
import pos.output.BulkCatalogueCsvService
import java.io.InputStream
import java.util.Locale

/** Android file APIs supply bytes; the same desktop parsers and transaction rules own their meaning. */
enum class AndroidCsvImportKind(val label:String) { NEW_ITEMS("Add new items"), UPDATE_ITEMS("Update existing items") }
data class AndroidCsvImportReview(
    val tenantId:String,val kind:AndroidCsvImportKind,val newItems:List<ProductImportRow> = emptyList(),
    val updates:List<BulkCatalogueUpdateRow> = emptyList(),val issues:List<BulkCatalogueIssue> = emptyList(),val changedCount:Int=0,
) {val rowCount:Int get()=if(kind==AndroidCsvImportKind.NEW_ITEMS)newItems.size else updates.size
    val canApply:Boolean get()=rowCount>0 && issues.isEmpty() && (kind==AndroidCsvImportKind.NEW_ITEMS || changedCount>0)
}
object AndroidCsvImport {
    const val maximumBytes=pos.output.CsvUtf8.maximumBytes
    const val template="name,unit,price_naira,stock,quantity_precision\r\nExample item,piece,500.00,24,0\r\n"
    fun readUtf8(input:InputStream):String = pos.output.CsvUtf8.read(input)
}
fun PosRepository.reviewAndroidCsvImport(tenantId:String,kind:AndroidCsvImportKind,text:String):AndroidCsvImportReview = featureRead(tenantId,StaffPermission.MANAGE_STOCK) {
    if(kind==AndroidCsvImportKind.NEW_ITEMS) {
        val rows=ProductImportService.parse(text)
        fun key(name:String,unit:String)=name.trim().lowercase(Locale.ROOT) to unit.trim().lowercase(Locale.ROOT)
        val existing=products(tenantId,true).map {key(it.name,it.unit)}.toSet()
        val counts=rows.groupingBy {key(it.name,it.unit)}.eachCount()
        val issues=rows.mapIndexedNotNull {index,row ->
            when {key(row.name,row.unit) in existing ->BulkCatalogueIssue(index+2,"An item with this name and unit already exists, including archived goods. Use Add stock or the update template.")
                counts.getValue(key(row.name,row.unit))>1 ->BulkCatalogueIssue(index+2,"This name and unit appears more than once in the CSV.")
                else ->null}
        }
        AndroidCsvImportReview(tenantId,kind,newItems=rows,issues=issues)
    } else {
        val parsed=BulkCatalogueCsvService.parse(text)
        val preview=if(parsed.rows.isEmpty())null else previewBulkCatalogueUpdates(tenantId,parsed.rows)
        AndroidCsvImportReview(tenantId,kind,updates=parsed.rows,issues=(parsed.issues+preview?.issues.orEmpty()).distinct().sortedBy {it.record},changedCount=preview?.changedCount ?: 0)
    }
}
fun PosRepository.androidCsvUpdateTemplate(tenantId:String):String = featureRead(tenantId,StaffPermission.MANAGE_STOCK) {BulkCatalogueCsvService.export(bulkCatalogueTemplateRows(tenantId))}
fun PosRepository.applyAndroidCsvImport(tenantId:String,review:AndroidCsvImportReview,reason:String,operationId:String):Int {
    require(review.tenantId==tenantId) {"This preview belongs to another shop. Choose and review the file again."}
    require(review.canApply) {"Fix every row error and review the CSV again before applying it."}
    return when(review.kind) {
        AndroidCsvImportKind.NEW_ITEMS ->importProducts(tenantId,review.newItems).size
        AndroidCsvImportKind.UPDATE_ITEMS ->applyBulkCatalogueUpdates(tenantId,review.updates,reason,operationId).rows.size
    }
}

fun PosRepository.authorizeAndroidCsvImport(tenantId:String) = featureRead(tenantId,StaffPermission.MANAGE_STOCK) {Unit}
