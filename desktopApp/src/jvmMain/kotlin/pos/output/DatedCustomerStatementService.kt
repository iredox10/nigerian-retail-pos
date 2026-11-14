package pos.output

import pos.screens.ReportDateRange
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

data class DatedCustomerStatement(
    val complete:CustomerStatement, val range:ReportDateRange, val openingMinor:Long,
    val events:List<Pair<CustomerStatementEvent,Long>>, val closingMinor:Long,
)

object DatedCustomerStatementService {
    fun build(statement:CustomerStatement,range:ReportDateRange):DatedCustomerStatement {
        val customer=statement.customer
        val validated=CustomerStatementService.build(customer.tenantId,customer,statement.events.mapNotNull {it.sale},statement.events.mapNotNull {it.repayment},statement.events.mapNotNull {it.creditReturn})
        require(validated==statement) { "Statement totals do not match the complete saved history." }
        val start=range.from.atStartOfDay(ZoneId.of("Africa/Lagos")).toInstant()
        val end=range.through.plusDays(1).atStartOfDay(ZoneId.of("Africa/Lagos")).toInstant()
        val events=statement.events.sortedWith(compareBy<CustomerStatementEvent> {Instant.parse(it.createdAt)}.thenBy {if(it.sale!=null) 0 else if(it.repayment!=null) 1 else 2}.thenBy {it.id})
        var balance=0L
        var opening=0L
        val rows=mutableListOf<Pair<CustomerStatementEvent,Long>>()
        events.forEach {event ->
            val time=Instant.parse(event.createdAt)
            if(time<end) {
                balance=Math.addExact(balance,event.debtDeltaMinor)
                require(balance>=0) { "Recorded payments precede or exceed the available credit history." }
                if(time<start) opening=balance else rows+=event to balance
            }
        }
        return DatedCustomerStatement(statement,range,opening,rows,balance)
    }

    fun csv(statement:CustomerStatement,range:ReportDateRange):String {
        val dated=build(statement,range)
        val customer=statement.customer
        fun text(value:String)=CsvCell.Text(value)
        fun amount(value:Long)=CsvCell.Number(BigDecimal.valueOf(value,2).toPlainString())
        fun summary(label:String,balance:Long)=listOf(text(customer.id),text(customer.name),text(range.from.toString()),text(range.through.toString()),text(""),text(label),text(""),text(""),text(""),text(""),amount(balance),text(""),text(""))
        val rows=listOf(summary("Opening debt before selected period",dated.openingMinor))+dated.events.map {(event,balance) ->
            listOf(text(customer.id),text(customer.name),text(range.from.toString()),text(range.through.toString()),text(event.createdAt),text(if(event.sale!=null) "Original sale" else if(event.repayment!=null) "Later cash repayment" else "Credit-sale return"),text(event.id),event.sale?.let {amount(it.totalMinor)} ?: text(""),event.sale?.let {amount(it.creditMinor)} ?: text(""),event.repayment?.let {amount(it.amountMinor)} ?: text(""),amount(balance),event.creditReturn?.let {amount(it.amountMinor)} ?: text(""),event.creditReturn?.let {amount(it.cashRefundMinor)} ?: text(""))
        }+listOf(summary("Closing debt through selected WAT date",dated.closingMinor),summary("Current debt — all saved dates",customer.balanceMinor))
        return CsvService.encode(listOf("Customer ID","Current customer name","From WAT","Through WAT","Recorded at UTC","Activity","Source ID","Original sale total NGN","Credit added NGN","Later repayment NGN","Debt balance NGN","Debt reduced on return NGN","Cash refunded on credit return NGN"),rows)
    }
}
