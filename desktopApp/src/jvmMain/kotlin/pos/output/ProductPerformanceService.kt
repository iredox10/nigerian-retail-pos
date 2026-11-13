package pos.output

import pos.domain.*
import pos.screens.ReportDateRange
import java.math.BigDecimal
import java.time.Instant

data class ProductPerformanceRow(
    val productId:String, val name:String, val baseUnit:String,
    val soldBaseQuantity:Long, val returnedBaseQuantity:Long,
    val salesMinor:Long, val refundsMinor:Long, val quantityPrecision:Int=0,
) {
    val netSalesMinor:Long get()=Math.subtractExact(salesMinor,refundsMinor)
    val netBaseQuantity:Long get()=Math.subtractExact(soldBaseQuantity,returnedBaseQuantity)
}

/** Sales and refunds use their own recorded dates; this is revenue, never profit or cash received. */
object ProductPerformanceService {
    fun build(tenantId:String,sales:List<Sale>,returns:List<SaleReturn>,range:ReportDateRange):List<ProductPerformanceRow> {
        require(tenantId.isNotBlank() && sales.all {it.tenantId==tenantId} && returns.all {it.tenantId==tenantId}) { "Product report includes another shop." }
        require(sales.map {it.id}.distinct().size==sales.size && returns.map {it.id}.distinct().size==returns.size) { "Product report contains repeated records." }
        val source=sales.associateBy {it.id}
        val returned=mutableMapOf<Pair<String,String>,Long>()
        val refunded=mutableMapOf<Pair<String,String>,Long>()
        returns.forEach { refund ->
            val sale=requireNotNull(source[refund.saleId]) { "Return sale is missing from the complete history." }
            require(sale.creditMinor==0L && Instant.parse(refund.createdAt)>=Instant.parse(sale.createdAt)) { "Return has an unsupported sale or recorded date." }
            refund.lines.forEach { line ->
                val original=requireNotNull(sale.lines.singleOrNull {it.key==line.key}) { "Returned item is missing from its sale." }
                require(line.unitPriceMinor==original.netUnitPriceMinor && line.baseUnitsPerUnit==original.baseUnitsPerUnit && line.baseUnit==original.product.unit && line.unit==original.sellingUnit && line.quantityPrecision==original.quantityPrecision && line.basePrecision==original.product.quantityPrecision) { "Return does not match the original selling unit or price." }
                val key=sale.id to line.key
                val quantity=Math.addExact(returned[key] ?: 0,line.quantity.toLong())
                require(quantity<=original.quantity) { "Returned quantity exceeds the original sale." }
                returned[key]=quantity
                refunded[key]=Math.addExact(refunded[key] ?: 0,line.totalMinor)
            }
        }
        sales.forEach {sale -> sale.lines.forEach {line ->
            val key=sale.id to line.key
            require((refunded[key] ?: 0)==line.cumulativeRefundMinor(Math.toIntExact(returned[key] ?: 0))) { "Refund amounts do not match the original saved line amount." }
        }}
        val rows=mutableMapOf<Pair<String,String>,ProductPerformanceRow>()
        val names=mutableMapOf<Pair<String,String>,Pair<Instant,String>>()
        fun append(id:String,name:String,unit:String,time:String,sourceId:String,sold:Long=0,returned:Long=0,amount:Long=0,refund:Long=0,precision:Int=0) {
            val key=id to unit
            val old=rows[key] ?: ProductPerformanceRow(id,name,unit,0,0,0,0,precision)
            val order=Instant.parse(time) to sourceId
            val previous=names[key]
            val useName=previous==null || order.first>previous.first || (order.first==previous.first && order.second>previous.second)
            if(useName) names[key]=order
            rows[key]=old.copy(name=if(useName) name else old.name,
                soldBaseQuantity=Math.addExact(old.soldBaseQuantity,sold),returnedBaseQuantity=Math.addExact(old.returnedBaseQuantity,returned),
                salesMinor=Math.addExact(old.salesMinor,amount),refundsMinor=Math.addExact(old.refundsMinor,refund))
        }
        sales.filter {range.contains(it.createdAt)}.forEach { sale -> sale.lines.forEach {line ->
            append(line.product.id,line.product.name,line.product.unit,sale.createdAt,sale.id,sold=line.baseQuantity,amount=line.totalMinor,precision=line.product.quantityPrecision)
        } }
        returns.filter {range.contains(it.createdAt)}.forEach { refund -> refund.lines.forEach {line ->
            append(line.productId,line.name,line.baseUnit,refund.createdAt,refund.id,returned=line.baseQuantity,refund=line.totalMinor,precision=line.basePrecision)
        } }
        return rows.values.sortedWith(compareByDescending<ProductPerformanceRow> {it.netSalesMinor}.thenBy {it.productId}.thenBy {it.baseUnit})
    }

    fun csv(tenantId:String,sales:List<Sale>,returns:List<SaleReturn>,range:ReportDateRange):String {
        fun text(value:String)=CsvCell.Text(value)
        fun number(value:Long)=CsvCell.Number(value.toString())
        fun amount(value:Long)=CsvCell.Number(BigDecimal.valueOf(value,2).toPlainString())
        return CsvService.encode(listOf("Product ID","Latest recorded item name in range","Base unit","From WAT","Through WAT","Sold base quantity","Returned base quantity","Net sold base quantity","Sales after discounts NGN","Refunds recorded in range NGN","Net sales NGN"),
            build(tenantId,sales,returns,range).map { row -> listOf(text(row.productId),text(row.name),text(row.baseUnit),text(range.from.toString()),text(range.through.toString()),CsvCell.Number(quantityLabel(row.soldBaseQuantity,row.quantityPrecision)),CsvCell.Number(quantityLabel(row.returnedBaseQuantity,row.quantityPrecision)),CsvCell.Number(if(row.netBaseQuantity<0) "-"+quantityLabel(-row.netBaseQuantity,row.quantityPrecision) else quantityLabel(row.netBaseQuantity,row.quantityPrecision)),amount(row.salesMinor),amount(row.refundsMinor),amount(row.netSalesMinor)) })
    }
}
