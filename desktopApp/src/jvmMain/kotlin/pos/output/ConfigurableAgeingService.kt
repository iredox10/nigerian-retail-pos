package pos.output

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Inclusive upper limits of late-day bands; due today/future and undated remain separate. */
data class AgeingSchedule(val upperDays:List<Int>) {
    init {
        require(upperDays.size in 1..8 && upperDays.all {it in 1..36500} && upperDays.zipWithNext().all {(a,b)->a<b}) {
            "Enter 1 to 8 increasing day limits from 1 to 36500, separated by commas."
        }
    }
    val labels:List<String> get()=listOf("Due today or later")+upperDays.mapIndexed {index,end -> "${if(index==0) 1 else upperDays[index-1]+1}–$end days late"}+listOf("Over ${upperDays.last()} days late","No agreed due date")
    fun index(due:String?,asOf:LocalDate):Int {
        if(due==null) return labels.lastIndex
        val late=ChronoUnit.DAYS.between(LocalDate.parse(due),asOf)
        if(late<=0) return 0
        return upperDays.indexOfFirst {late<=it}.let {if(it<0) upperDays.size+1 else it+1}
    }
    companion object {
        val DEFAULT=AgeingSchedule(listOf(7,30,60,90))
        fun parse(text:String):AgeingSchedule {
            val pieces=text.split(',').map {it.trim()}
            require(pieces.all {it.matches(Regex("[0-9]{1,5}"))}) { "Use whole day limits separated by commas, for example 7,30,60,90." }
            return AgeingSchedule(pieces.map {it.toInt()})
        }
    }
}

object ConfigurableAgeingService {
    fun amounts(row:CreditReviewRow,asOf:LocalDate,schedule:AgeingSchedule):List<Long>? {
        val receiptTotal=row.invoiceBalances.fold(0L) {sum,balance -> Math.addExact(sum,balance.remainingMinor)}
        require(row.unallocatedRepaymentsMinor>=0 && receiptTotal>=row.unallocatedRepaymentsMinor && receiptTotal-row.unallocatedRepaymentsMinor==row.currentDebtMinor) { "Receipt balances do not reconcile with customer debt." }
        if(row.unallocatedRepaymentsMinor>0) return null
        val values=MutableList(schedule.labels.size) {0L}
        row.invoiceBalances.forEach {balance ->
            val index=schedule.index(balance.sale.dueDate,asOf)
            values[index]=Math.addExact(values[index],balance.remainingMinor)
        }
        return values
    }

    fun csv(tenantId:String,rows:List<CreditReviewRow>,asOf:LocalDate,schedule:AgeingSchedule,historical:Boolean):String {
        require(rows.all {it.customer.tenantId==tenantId} && rows.map {it.customer.id}.distinct().size==rows.size) { "Foreign or duplicate customer in ageing export." }
        fun amount(value:Long)=CsvCell.Number(BigDecimal.valueOf(value,2).toPlainString())
        val basis=if(historical) "Recorded history through $asOf WAT; customer profile details are current" else "Current saved balances; not a historical balance"
        return CsvService.encode(listOf("Customer ID","Customer","Classification date WAT","Balance basis",if(historical) "Debt at selected date NGN" else "Current debt NGN","Unassigned repayments NGN","Exact ageing available")+schedule.labels.map {"$it NGN"},rows.map {row ->
            val values=amounts(row,asOf,schedule)
            listOf(CsvCell.Text(row.customer.id),CsvCell.Text(row.customer.name),CsvCell.Text(asOf.toString()),CsvCell.Text(basis),amount(row.currentDebtMinor),amount(row.unallocatedRepaymentsMinor),CsvCell.Text((values!=null).toString()))+
                schedule.labels.indices.map {index -> values?.get(index)?.let(::amount) ?: CsvCell.Text("")}
        })
    }
}
