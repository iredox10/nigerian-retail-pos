package pos.output

import pos.domain.*
import pos.screens.ReportDateRange
import java.math.BigDecimal

data class StaffPerformanceRow(val actor:AuditActor?,val salesCount:Long=0,val salesMinor:Long=0,val refundsCount:Long=0,val refundsMinor:Long=0,val returnedValueMinor:Long=0,val debtReducedMinor:Long=0)

/** Attribute each action to its recorded actor. A refund belongs to its recorder, not the salesperson. */
object StaffPerformanceService {
    fun build(tenantId:String,sales:List<Sale>,returns:List<SaleReturn>,events:List<AuditEvent>,range:ReportDateRange):List<StaffPerformanceRow> {
        require(events.all {it.tenantId==tenantId} && events.map {it.id}.distinct().size==events.size) { "Activity includes foreign or repeated records." }
        ProductPerformanceService.build(tenantId,sales,returns,range) // Validate complete business sources.
        val saleIds=sales.map {it.id}.toSet()
        val returnIds=returns.map {it.id}.toSet()
        val relevant=events.filter {it.kind in setOf("sale.completed","sale.returned")}
        require(relevant.map {it.kind to it.entityId}.distinct().size==relevant.size) { "An action has more than one recorded actor." }
        require(relevant.all {if(it.kind=="sale.completed") it.entityId in saleIds else it.entityId in returnIds}) { "Activity refers to an unavailable business record." }
        val actors=relevant.associate { (it.kind to it.entityId) to it.actor }
        val rows=mutableMapOf<AuditActor?,StaffPerformanceRow>()
        sales.filter {range.contains(it.createdAt)}.forEach {sale ->
            val actor=actors["sale.completed" to sale.id]
            val old=rows[actor] ?: StaffPerformanceRow(actor)
            rows[actor]=old.copy(salesCount=Math.addExact(old.salesCount,1),salesMinor=Math.addExact(old.salesMinor,sale.totalMinor))
        }
        returns.filter {range.contains(it.createdAt)}.forEach {refund ->
            val actor=actors["sale.returned" to refund.id]
            val old=rows[actor] ?: StaffPerformanceRow(actor)
            rows[actor]=old.copy(refundsCount=Math.addExact(old.refundsCount,1),refundsMinor=Math.addExact(old.refundsMinor,refund.refundMinor),returnedValueMinor=Math.addExact(old.returnedValueMinor,refund.returnedValueMinor),debtReducedMinor=Math.addExact(old.debtReducedMinor,refund.creditReductionMinor))
        }
        return rows.values.sortedWith(compareByDescending<StaffPerformanceRow> {it.salesMinor}.thenBy {it.actor?.name.orEmpty()}.thenBy {it.actor?.id.orEmpty()}.thenBy {it.actor?.role?.name.orEmpty()})
    }

    fun csv(tenantId:String,sales:List<Sale>,returns:List<SaleReturn>,events:List<AuditEvent>,range:ReportDateRange):String {
        fun text(value:String)=CsvCell.Text(value)
        fun count(value:Long)=CsvCell.Number(value.toString())
        fun amount(value:Long)=CsvCell.Number(BigDecimal.valueOf(value,2).toPlainString())
        return CsvData.encode(listOf("Actor recorded","Actor ID","Name at event","Role at event","From WAT","Through WAT","Sales recorded count","Sales after discounts NGN","Returns recorded count","Cash refunded NGN","Returned goods value NGN","Customer debt reduced NGN"),
            build(tenantId,sales,returns,events,range).map {row -> listOf(text((row.actor!=null).toString()),text(row.actor?.id.orEmpty()),text(row.actor?.name ?: "Actor not recorded"),text(row.actor?.role?.name.orEmpty()),text(range.from.toString()),text(range.through.toString()),count(row.salesCount),amount(row.salesMinor),count(row.refundsCount),amount(row.refundsMinor),amount(row.returnedValueMinor),amount(row.debtReducedMinor)) })
    }
}
