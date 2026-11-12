package pos.output

import pos.domain.Customer
import pos.domain.CustomerRepayment
import pos.domain.Sale
import java.math.BigInteger
import java.time.LocalDate

/**
 * Due dates classify original credit sales, not outstanding invoice balances.
 * Repayments are customer-level and have no invoice allocation in this application.
 */
data class CreditReviewRow(
    val customer: Customer,
    val pastDueSales: List<Sale>,
    val dueTodaySales: List<Sale>,
    val futureDueSales: List<Sale>,
    val undatedSales: List<Sale>,
    val repaymentsTotalMinor: Long,
) {
    val currentDebtMinor: Long get() = customer.balanceMinor
    val pastDueOriginalCreditMinor: Long get() = exactTotal(pastDueSales.map { it.creditMinor })
    val earliestRecordedDueDate: LocalDate? get() = (pastDueSales + dueTodaySales + futureDueSales)
        .map { LocalDate.parse(requireNotNull(it.dueDate)) }.minOrNull()
}

private fun exactTotal(values: List<Long>): Long = values.fold(BigInteger.ZERO) { sum, value ->
    sum.add(BigInteger.valueOf(value))
}.longValueExact()

object DebtAgeingService {
    const val ALLOCATION_NOTICE = "Payments are not allocated to individual receipts. Review original due dates alongside repayments. Current customer debt includes all saved dates."

    /**
     * Supply complete local history. [asOf] classifies due dates only: balances remain current,
     * even when the review date differs from today. No FIFO or other allocation is inferred.
     */
    fun build(
        tenantId: String,
        customers: List<Customer>,
        sales: List<Sale>,
        repayments: List<CustomerRepayment>,
        asOf: LocalDate,
    ): List<CreditReviewRow> {
        require(tenantId.isNotBlank()) { "Choose a shop before reviewing credit." }
        require(customers.all { it.tenantId == tenantId } && sales.all { it.tenantId == tenantId } && repayments.all { it.tenantId == tenantId }) {
            "Credit history includes another shop's records."
        }
        require(customers.map { it.id }.distinct().size == customers.size &&
            sales.map { it.id }.distinct().size == sales.size &&
            repayments.map { it.id }.distinct().size == repayments.size) { "Credit history contains repeated records." }
        val ids = customers.map { it.id }.toSet()
        require(sales.all { it.customerId == null || it.customerId in ids } && repayments.all { it.customerId in ids }) {
            "Credit history refers to an unavailable customer. Refresh the complete customer list."
        }
        // Validate dates even on settled customers; malformed saved data must not quietly disappear.
        val dueDates = sales.associate { sale ->
            val due = sale.dueDate?.let { value ->
                require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "A saved due date is invalid." }
                LocalDate.parse(value)
            }
            require(due == null || sale.creditMinor > 0) { "A due date must belong to an original credit sale." }
            sale.id to due
        }
        return customers.mapNotNull { customer ->
            val statement = CustomerStatementService.build(tenantId, customer, sales, repayments)
            if (customer.balanceMinor == 0L) return@mapNotNull null
            val creditSales = statement.events.mapNotNull { it.sale }.filter { it.creditMinor > 0 }
            fun sorted(values: List<Sale>) = values.sortedWith(compareBy<Sale> { dueDates[it.id] }.thenBy { it.createdAt }.thenBy { it.id })
            val past = sorted(creditSales.filter { dueDates[it.id]?.isBefore(asOf) == true })
            // Force exact totals now rather than deferring possible overflow until UI rendering.
            exactTotal(past.map { it.creditMinor })
            CreditReviewRow(
                customer,
                past,
                sorted(creditSales.filter { dueDates[it.id] == asOf }),
                sorted(creditSales.filter { dueDates[it.id]?.isAfter(asOf) == true }),
                sorted(creditSales.filter { dueDates[it.id] == null }),
                statement.repaidMinor,
            )
        }.sortedWith(compareBy<CreditReviewRow> { if (it.pastDueSales.isNotEmpty()) 0 else 1 }
            .thenBy(nullsLast()) { it.earliestRecordedDueDate }.thenBy { it.customer.name }.thenBy { it.customer.id })
    }
}
