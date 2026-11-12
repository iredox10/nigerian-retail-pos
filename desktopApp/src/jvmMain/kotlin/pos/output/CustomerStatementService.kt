package pos.output

import pos.domain.Customer
import pos.domain.CustomerRepayment
import pos.domain.Sale
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** Original sales and later debt payments are separate events; repayment is not another sale. */
data class CustomerStatementEvent(
    val id: String, val createdAt: String, val sale: Sale? = null, val repayment: CustomerRepayment? = null,
) {
    init { require((sale == null) != (repayment == null)) }
}

data class CustomerStatement(
    val customer: Customer,
    val events: List<CustomerStatementEvent>,
    val originalSalesMinor: Long,
    val creditGrantedMinor: Long,
    val repaidMinor: Long,
)

object CustomerStatementService {
    /** Input is complete local history for a tenant, never an arbitrary date-filtered debt reconstruction. */
    fun build(tenantId: String, customer: Customer, sales: List<Sale>, repayments: List<CustomerRepayment>): CustomerStatement {
        require(tenantId.isNotBlank() && customer.tenantId == tenantId) { "Customer belongs to another shop." }
        require(sales.all { it.tenantId == tenantId } && repayments.all { it.tenantId == tenantId }) { "History includes another shop's records." }
        require(sales.map { it.id }.distinct().size == sales.size && repayments.map { it.id }.distinct().size == repayments.size) { "History contains repeated records." }
        val customerSales = sales.filter { it.customerId == customer.id }
        val customerPayments = repayments.filter { it.customerId == customer.id }
        fun total(values: List<Long>): Long = values.fold(BigInteger.ZERO) { sum, value -> sum.add(BigInteger.valueOf(value)) }.longValueExact()
        val granted = total(customerSales.map { it.creditMinor })
        val paid = total(customerPayments.map { it.amountMinor })
        require(paid <= granted && granted - paid == customer.balanceMinor) { "Saved customer debt does not match the available history. Refresh before exporting a statement." }
        val events = (customerSales.map { CustomerStatementEvent(it.id, it.createdAt, sale = it) } +
            customerPayments.map { CustomerStatementEvent(it.id, it.createdAt, repayment = it) })
            .sortedWith(compareBy<CustomerStatementEvent> { Instant.parse(it.createdAt) }.thenBy { if (it.sale != null) 0 else 1 }.thenBy { it.id })
        return CustomerStatement(customer, events, total(customerSales.map { it.totalMinor }), granted, paid)
    }

    fun csv(statement: CustomerStatement): String {
        val verified = build(statement.customer.tenantId, statement.customer,
            statement.events.mapNotNull { it.sale }, statement.events.mapNotNull { it.repayment })
        require(verified == statement) { "Statement contains inconsistent customer history." }
        fun text(value: String) = CsvCell.Text(value)
        fun amount(value: Long) = CsvCell.Number(BigDecimal.valueOf(value, 2).toPlainString())
        val customer = statement.customer
        val rows = statement.events.map { event ->
            listOf(text(customer.id), text(customer.name), text(event.createdAt), text(if (event.sale != null) "Original sale" else "Cash debt repayment"), text(event.id),
                amount(event.sale?.totalMinor ?: 0), amount(event.sale?.cashMinor ?: 0), amount(event.sale?.creditMinor ?: 0), amount(event.repayment?.amountMinor ?: 0), text(""),text(event.sale?.dueDate.orEmpty()))
        } + listOf(listOf(text(customer.id), text(customer.name), text(""), text("Current debt — all dates"), text(""), text(""), text(""), text(""), text(""), amount(customer.balanceMinor),text("")))
        return CsvService.encode(listOf("Customer ID", "Customer name", "Recorded at UTC", "Activity", "Record ID", "Original sale total NGN", "Cash applied at sale NGN", "Credit added at sale NGN", "Later repayment NGN", "Current debt NGN", "Agreed repayment due date WAT"), rows)
    }
}
