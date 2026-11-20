package pos.output

import pos.domain.PurchaseReturn
import pos.domain.StockReceipt
import pos.domain.SupplierPayment
import pos.domain.purchaseReturnBaseQuantity
import pos.domain.purchaseReturnCredit
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SupplierStatementEvent(
    val id:String, val receiptId:String, val createdAt:String,
    val purchaseMinor:Long, val paidAtReceiptMinor:Long, val laterPaymentMinor:Long,
    val balanceAfterMinor:Long, val returnCreditMinor:Long=0,
) {
    val activity: String get() = when {
        returnCreditMinor > 0 -> "Purchase return"
        laterPaymentMinor > 0 -> "Later cash payment"
        else -> "Stock received"
    }
}

/** Exact recorded-name grouping; this is not a supplier master account or a full accounting ledger. */
data class SupplierStatement(
    val tenantId:String, val supplierName:String, val from:LocalDate, val through:LocalDate,
    val receipts:List<StockReceipt>, val payments:List<SupplierPayment>,
    val events:List<SupplierStatementEvent>, val openingMinor:Long, val closingMinor:Long,
    val purchasesMinor:Long, val paidAtReceiptMinor:Long, val laterPaymentsMinor:Long,
    val currentOutstandingMinor:Long,
    val returns:List<PurchaseReturn> = emptyList(), val returnCreditsMinor:Long=0,
)

object SupplierStatementService {
    /** Supply complete local history, not rows filtered by the balances screen's search or unpaid toggle. */
    fun build(tenantId:String,supplierName:String,receipts:List<StockReceipt>,payments:List<SupplierPayment>,from:LocalDate,through:LocalDate,returns:List<PurchaseReturn> = emptyList()):SupplierStatement {
        require(tenantId.isNotBlank() && !through.isBefore(from)) { "Choose a shop and an end date on or after the start date." }
        require(receipts.all {it.tenantId==tenantId} && payments.all {it.tenantId==tenantId} && returns.all {it.tenantId==tenantId}) { "Supplier history includes another shop." }
        require(receipts.map {it.id}.distinct().size==receipts.size && payments.map {it.id}.distinct().size==payments.size &&
            returns.map {it.id}.distinct().size==returns.size) { "Supplier history contains repeated records." }
        val byReceipt=receipts.associateBy {it.id}
        val paidByReceipt=mutableMapOf<String,Long>()
        receipts.forEach {Instant.parse(it.createdAt)}
        payments.forEach {payment ->
            val receipt=requireNotNull(byReceipt[payment.receiptId]) { "A supplier payment refers to a missing stock receipt." }
            require(payment.supplierName==receipt.supplierName) { "A payment's supplier does not match its receipt." }
            require(Instant.parse(payment.createdAt)>=Instant.parse(receipt.createdAt)) { "A supplier payment predates its stock receipt." }
            val paid=Math.addExact(paidByReceipt[payment.receiptId] ?: 0,payment.amountMinor)
            require(paid<=receipt.unpaidMinor) { "Supplier payments exceed the original receipt balance." }
            paidByReceipt[payment.receiptId]=paid
        }
        returns.groupBy {it.receiptId}.forEach { (receiptId, returned) ->
            val receipt = requireNotNull(byReceipt[receiptId]) { "A purchase return refers to a missing stock receipt." }
            val receiptPayments = payments.filter {it.receiptId == receiptId}
            var latest = Instant.parse(receipt.createdAt)
            var quantity = 0
            var credits = 0L
            returned.sortedBy {Instant.parse(it.createdAt)}.forEach { event ->
                val time = Instant.parse(event.createdAt)
                require(time > latest && receiptPayments.none {Instant.parse(it.createdAt) == time}) {
                    "Purchase return dates are inconsistent."
                }
                val paidBefore = receiptPayments.filter {Instant.parse(it.createdAt) < time}.sumOf {it.amountMinor}
                require(event.owedBeforeMinor == receipt.unpaidMinor - credits - paidBefore &&
                    event.creditMinor == receipt.purchaseReturnCredit(quantity, event.quantity) &&
                    event.stockBefore - event.stockAfter == receipt.purchaseReturnBaseQuantity(event.quantity)) {
                    "A purchase return does not match its recorded receipt balance or quantity."
                }
                quantity += event.quantity
                credits = Math.addExact(credits, event.creditMinor)
                latest = time
            }
            require(credits <= receipt.unpaidMinor - (paidByReceipt[receiptId] ?: 0)) {
                "Supplier payments and returns exceed the original receipt balance."
            }
        }
        val selectedReceipts=receipts.filter {it.supplierName==supplierName}
        val selectedPayments=payments.filter {it.supplierName==supplierName}
        val selectedReturns=returns.filter {byReceipt.getValue(it.receiptId).supplierName==supplierName}
        val zone=ZoneId.of("Africa/Lagos")
        val start=from.atStartOfDay(zone).toInstant()
        val end=through.plusDays(1).atStartOfDay(zone).toInstant()
        val all=(selectedReceipts.map { SupplierStatementEvent(it.id,it.id,it.createdAt,it.totalCostMinor,it.paidCashMinor,0,0) }+
            selectedPayments.map { SupplierStatementEvent(it.id,it.receiptId,it.createdAt,0,0,it.amountMinor,0) }+
            selectedReturns.map { SupplierStatementEvent(it.id,it.receiptId,it.createdAt,0,0,0,0,it.creditMinor) })
            .sortedWith(compareBy<SupplierStatementEvent> {Instant.parse(it.createdAt)}
                .thenBy {if(it.laterPaymentMinor == 0L && it.returnCreditMinor == 0L) 0 else 1}.thenBy {it.id})
        var balance=0L
        var opening=0L
        var closing=0L
        val visible=mutableListOf<SupplierStatementEvent>()
        all.forEach {event ->
            balance=Math.subtractExact(Math.addExact(balance,event.purchaseMinor-event.paidAtReceiptMinor),event.laterPaymentMinor + event.returnCreditMinor)
            require(balance>=0) { "Supplier history has a negative recorded balance." }
            val recorded=Instant.parse(event.createdAt)
            if(recorded<start) opening=balance
            if(recorded<end) closing=balance
            if(recorded>=start && recorded<end) visible+=event.copy(balanceAfterMinor=balance)
        }
        fun total(values:List<Long>)=values.fold(0L) {sum,value -> Math.addExact(sum,value)}
        val purchased=total(visible.map {it.purchaseMinor})
        val initialPaid=total(visible.map {it.paidAtReceiptMinor})
        val laterPaid=total(visible.map {it.laterPaymentMinor})
        val credits=total(visible.map {it.returnCreditMinor})
        require(Math.subtractExact(Math.subtractExact(Math.addExact(opening,purchased-initialPaid),laterPaid),credits)==closing) { "Supplier statement does not reconcile." }
        return SupplierStatement(tenantId,supplierName,from,through,selectedReceipts,selectedPayments,visible,opening,closing,purchased,initialPaid,laterPaid,balance,selectedReturns,credits)
    }

    fun csv(statement:SupplierStatement):String {
        require(build(statement.tenantId,statement.supplierName,statement.receipts,statement.payments,statement.from,statement.through,statement.returns)==statement) { "Supplier statement has inconsistent history or totals." }
        fun text(value:String)=CsvCell.Text(value)
        fun amount(value:Long)=CsvCell.Number(BigDecimal.valueOf(value,2).toPlainString())
        fun row(kind:String,id:String="",receipt:String="",date:String="",purchase:Long=0,paid:Long=0,later:Long=0,balance:Long,credit:Long=0)=
            listOf(text(statement.supplierName),text(statement.from.toString()),text(statement.through.toString()),text(kind),text(id),text(receipt),text(date),amount(purchase),amount(paid),amount(later),amount(balance),amount(credit))
        val rows=listOf(row("Opening balance before period",balance=statement.openingMinor))+
            statement.events.map {event -> row(event.activity,event.id,event.receiptId,event.createdAt,event.purchaseMinor,event.paidAtReceiptMinor,event.laterPaymentMinor,event.balanceAfterMinor,event.returnCreditMinor) }+
            listOf(row("Closing balance through selected WAT date",balance=statement.closingMinor),row("Current outstanding — all recorded dates",balance=statement.currentOutstandingMinor))
        return CsvService.encode(listOf("Supplier name exactly as recorded","From WAT","Through WAT","Activity","Source ID","Stock receipt ID","Recorded at UTC","Purchase total NGN","Cash paid at receipt NGN","Later cash payment NGN","Recorded balance NGN","Purchase return credit NGN"),rows)
    }
}
