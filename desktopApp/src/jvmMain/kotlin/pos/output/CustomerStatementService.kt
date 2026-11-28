package pos.output

import pos.data.toLongExact
import pos.data.DebtAdjustment
import pos.data.netWrittenOff
import pos.data.validateDebtAdjustmentLinks
import pos.domain.Customer
import pos.domain.CustomerRepayment
import pos.domain.Sale
import pos.domain.CreditReturnReduction
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** Original sales and later debt payments are separate events; repayment is not another sale. */
data class CustomerStatementEvent(
    val id: String, val createdAt: String, val sale: Sale? = null, val repayment: CustomerRepayment? = null,
    val creditReturn:CreditReturnReduction? = null,
    val debtAdjustment: DebtAdjustment? = null,
    val advanceFunded: Boolean = false,
) {
    init { require(listOf(sale,repayment,creditReturn,debtAdjustment).count {it!=null}==1) }
    val debtDeltaMinor:Long get()=debtAdjustment?.review?.deltaMinor
        ?: Math.subtractExact(Math.subtractExact(sale?.creditMinor ?: 0L,repayment?.amountMinor ?: 0L),creditReturn?.amountMinor ?: 0L)
    val activity: String get() = when {
        sale != null -> "Original sale"
        repayment != null -> if (advanceFunded) "Customer advance applied" else "Cash debt repayment"
        creditReturn != null -> "Credit-sale return"
        else -> requireNotNull(debtAdjustment).activity
    }
}

data class CustomerStatement(
    val customer: Customer,
    val events: List<CustomerStatementEvent>,
    val originalSalesMinor: Long,
    val creditGrantedMinor: Long,
    val repaidMinor: Long,
    val creditReturnedMinor:Long=0,
)

object CustomerStatementService {
    /** Input is complete local history for a tenant, never an arbitrary date-filtered debt reconstruction. */
    fun build(tenantId: String, customer: Customer, sales: List<Sale>, repayments: List<CustomerRepayment>,
        creditReturns:List<CreditReturnReduction> = emptyList(), debtAdjustments: List<DebtAdjustment> = emptyList(), advanceFundedRepaymentIds:Set<String> = emptySet()): CustomerStatement {
        require(tenantId.isNotBlank() && customer.tenantId == tenantId) { "Customer belongs to another shop." }
        require(sales.all { it.tenantId == tenantId } && repayments.all { it.tenantId == tenantId } &&
            creditReturns.all {it.tenantId==tenantId} && debtAdjustments.all { it.tenantId == tenantId }) { "History includes another shop's records." }
        require(sales.map { it.id }.distinct().size == sales.size && repayments.map { it.id }.distinct().size == repayments.size && creditReturns.map {it.returnId}.distinct().size==creditReturns.size) { "History contains repeated records." }
        require(advanceFundedRepaymentIds.all { id -> repayments.any { it.id == id } }) { "Advance funding refers to an unavailable repayment." }
        val customerSales = sales.filter { it.customerId == customer.id }
        val customerPayments = repayments.filter { it.customerId == customer.id }
        val customerReturns=creditReturns.filter {it.customerId==customer.id}
        val adjustments = debtAdjustments.filter { it.review.customerId == customer.id }
        validateDebtAdjustmentLinks(adjustments)
        adjustments.forEach { adjustment ->
            val receipt = requireNotNull(customerSales.find { it.id == adjustment.review.saleId && it.creditMinor > 0 }) {
                "Debt adjustment has no original receipt for this customer."
            }
            require(Instant.parse(adjustment.createdAt) > Instant.parse(receipt.createdAt) &&
                adjustment.review.amountMinor > 0 && adjustment.review.receiptAfterMinor in 0..receipt.creditMinor) {
                "Debt adjustment conflicts with its original receipt."
            }
        }
        customerReturns.forEach {returned ->
            val original=requireNotNull(customerSales.singleOrNull {it.id==returned.saleId}) {"Credit return has no original sale for this customer."}
            require(original.creditMinor>0 && original.externalPayments.isEmpty() && !Instant.parse(returned.createdAt).isBefore(Instant.parse(original.createdAt))) {"Credit return does not match its original sale."}
        }
        customerReturns.groupBy {it.saleId}.forEach {(id,rows) ->require(rows.fold(BigInteger.ZERO) {sum,row ->sum+BigInteger.valueOf(row.amountMinor)}<=BigInteger.valueOf(customerSales.single {it.id==id}.creditMinor)) {"Returned credit exceeds the original credit sale."}}
        fun total(values: List<Long>): Long = values.fold(BigInteger.ZERO) { sum, value -> sum.add(BigInteger.valueOf(value)) }.toLongExact()
        val granted = total(customerSales.map { it.creditMinor })
        val paid = total(customerPayments.map { it.amountMinor })
        val returned=total(customerReturns.map {it.amountMinor})
        val writtenOff = adjustments.netWrittenOff()
        require(paid <= granted && returned<=granted-paid && writtenOff <= granted - paid - returned &&
            granted - paid - returned - writtenOff == customer.balanceMinor) { "Saved customer debt does not match the available history. Refresh before exporting a statement." }
        val events = (customerSales.map { CustomerStatementEvent(it.id, it.createdAt, sale = it) } +
            customerPayments.map { CustomerStatementEvent(it.id, it.createdAt, repayment = it, advanceFunded = it.id in advanceFundedRepaymentIds) } +
            customerReturns.map {CustomerStatementEvent(it.returnId,it.createdAt,creditReturn=it)} +
            adjustments.map { CustomerStatementEvent(it.id, it.createdAt, debtAdjustment = it) })
            .sortedWith(compareBy<CustomerStatementEvent> { Instant.parse(it.createdAt) }.thenBy { if (it.sale != null) 0 else if(it.repayment!=null) 1 else 2 }.thenBy { it.id })
        return CustomerStatement(customer, events, total(customerSales.map { it.totalMinor }), granted, paid,returned)
    }

    fun csv(statement: CustomerStatement): String {
        val verified = build(statement.customer.tenantId, statement.customer,
            statement.events.mapNotNull { it.sale }, statement.events.mapNotNull { it.repayment },
            statement.events.mapNotNull {it.creditReturn}, statement.events.mapNotNull { it.debtAdjustment },
            statement.events.filter { it.advanceFunded }.map { it.id }.toSet())
        require(verified == statement) { "Statement contains inconsistent customer history." }
        fun text(value: String) = CsvCell.Text(value)
        fun amount(value: Long) = CsvCell.Number(BigDecimal.valueOf(value, 2).toPlainString())
        val customer = statement.customer
        val rows = statement.events.map { event ->
            listOf(text(customer.id), text(customer.name), text(event.createdAt), text(event.activity), text(event.id),
                amount(event.sale?.totalMinor ?: 0), amount(event.sale?.cashMinor ?: 0), amount(event.sale?.creditMinor ?: 0), amount(event.repayment?.amountMinor ?: 0), text(""),text(event.sale?.dueDate.orEmpty()),amount(event.creditReturn?.amountMinor ?: 0),amount(event.creditReturn?.cashRefundMinor ?: 0),
                event.debtAdjustment?.review?.deltaMinor?.let(::amount) ?: text(""),
                text(event.debtAdjustment?.review?.saleId.orEmpty()), text(event.debtAdjustment?.reason.orEmpty()))
        } + listOf(listOf(text(customer.id), text(customer.name), text(""), text("Current debt — all dates"), text(""), text(""), text(""), text(""), text(""), amount(customer.balanceMinor),text(""),text(""),text(""),text(""),text(""),text("")))
        return CsvData.encode(listOf("Customer ID", "Customer name", "Recorded at UTC", "Activity", "Record ID", "Original sale total NGN", "Cash applied at sale NGN", "Credit added at sale NGN", "Later repayment NGN", "Current debt NGN", "Agreed repayment due date WAT","Debt reduced on return NGN","Cash refunded on credit return NGN",
            "Debt adjustment NGN", "Adjusted receipt ID", "Adjustment reason"), rows)
    }
}
