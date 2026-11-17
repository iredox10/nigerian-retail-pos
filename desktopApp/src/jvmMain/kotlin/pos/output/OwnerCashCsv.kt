package pos.output

import pos.domain.OwnerCashRecord
import pos.screens.ReportDateRange
import java.math.BigDecimal
import java.time.Instant

/** Owner cash classification has its own export; it is never included as sales or expenses. */
object OwnerCashCsv {
    fun create(tenantId:String,records:List<OwnerCashRecord>,range:ReportDateRange?=null):String {
        require(records.all {it.tenantId==tenantId} && records.map {it.id}.distinct().size==records.size) {"Owner money includes foreign or repeated records."}
        records.forEach {Instant.parse(it.createdAt)}
        val originals=records.associateBy {it.id}
        val corrections=records.filter {it.reversesId!=null}
        require(corrections.map {it.reversesId}.distinct().size==corrections.size) {"An owner movement has repeated corrections."}
        corrections.forEach {row ->
            val original=requireNotNull(originals[row.reversesId]) {"Correction source is unavailable."}
            require(original.kind!="REVERSAL" && original.amountMinor==Math.negateExact(row.amountMinor) && original.ownerName==row.ownerName && !Instant.parse(row.createdAt).isBefore(Instant.parse(original.createdAt))) {"Owner cash correction differs from its original."}
        }
        fun text(value:String)=CsvCell.Text(value)
        fun money(value:Long)=CsvCell.Number(BigDecimal.valueOf(value,2).toPlainString())
        return CsvService.encode(listOf("Record ID","Recorded at WAT","Recorded at UTC","Movement","Owner","Cash added NGN","Cash taken NGN","Signed drawer change NGN","Reason","Correction of record ID","Drawer shift ID","Cash ledger entry ID","From WAT","Through WAT"),
            records.filter {range==null || range.contains(it.createdAt)}.sortedWith(compareBy<OwnerCashRecord> {Instant.parse(it.createdAt)}.thenBy {it.id}).map {row ->
                listOf(text(row.id),text(receiptDate(row.createdAt)),text(row.createdAt),text(label(row.kind)),text(row.ownerName),money(if(row.amountMinor>0)row.amountMinor else 0),money(if(row.amountMinor<0)Math.negateExact(row.amountMinor) else 0),money(row.amountMinor),text(row.reason),text(row.reversesId.orEmpty()),text(row.shiftId),text(row.cashEntryId),text(range?.from?.toString() ?: "All dates"),text(range?.through?.toString() ?: "All dates"))
            })
    }
    fun label(kind:String)=when(kind) {"CONTRIBUTION" ->"Owner contribution";"DRAWING" ->"Owner drawing";"REVERSAL" ->"Owner cash correction";else ->error("Unknown owner cash type.")}
}
