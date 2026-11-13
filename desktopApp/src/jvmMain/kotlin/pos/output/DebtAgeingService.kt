package pos.output

import pos.domain.Customer
import pos.domain.CustomerRepayment
import pos.domain.RepaymentAllocation
import pos.domain.Sale
import java.math.BigInteger
import java.time.LocalDate

/** Balance after explicitly assigned repayments, before any unassigned customer payments. */
data class CreditReceiptBalance(val sale: Sale, val allocatedMinor: Long, val remainingMinor: Long) {
    init {
        require(sale.creditMinor > 0 && allocatedMinor in 0..sale.creditMinor)
        require(remainingMinor == sale.creditMinor - allocatedMinor)
    }
}

/** Original due-date groups are historical; receipt balances use explicit allocations only. */
data class CreditReviewRow(
    val customer: Customer,
    val pastDueSales: List<Sale>,
    val dueTodaySales: List<Sale>,
    val futureDueSales: List<Sale>,
    val undatedSales: List<Sale>,
    val repaymentsTotalMinor: Long,
    val invoiceBalances: List<CreditReceiptBalance> = emptyList(),
    val unallocatedRepaymentsMinor: Long = 0,
    val preciseOverdueMinor: Long? = null,
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
    const val ALLOCATION_NOTICE = "Payments not allocated to receipts remain explicitly unassigned. Assign them before calculating an exact overdue invoice balance. Current customer debt includes all saved dates."

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
        allocations: List<RepaymentAllocation> = emptyList(),
    ): List<CreditReviewRow> {
        require(tenantId.isNotBlank()) { "Choose a shop before reviewing credit." }
        require(customers.all { it.tenantId == tenantId } && sales.all { it.tenantId == tenantId } && repayments.all { it.tenantId == tenantId } && allocations.all { it.tenantId == tenantId }) {
            "Credit history includes another shop's records."
        }
        require(customers.map { it.id }.distinct().size == customers.size &&
            sales.map { it.id }.distinct().size == sales.size &&
            repayments.map { it.id }.distinct().size == repayments.size &&
            allocations.map { it.id }.distinct().size == allocations.size) { "Credit history contains repeated records." }
        val ids = customers.map { it.id }.toSet()
        require(sales.all { it.customerId == null || it.customerId in ids } && repayments.all { it.customerId in ids }) {
            "Credit history refers to an unavailable customer. Refresh the complete customer list."
        }
        val salesById=sales.associateBy { it.id }
        val repaymentsById=repayments.associateBy { it.id }
        allocations.forEach { allocation ->
            val sale=requireNotNull(salesById[allocation.saleId]) { "An assigned payment refers to an unavailable sale." }
            val payment=requireNotNull(repaymentsById[allocation.repaymentId]) { "An assigned payment refers to an unavailable repayment." }
            require(allocation.customerId in ids && allocation.customerId==sale.customerId && allocation.customerId==payment.customerId && sale.creditMinor>0 && allocation.amountMinor>0) {
                "Assigned payments must belong to a credit sale and repayment for the same customer."
            }
        }
        val allocatedByPayment=allocations.groupBy { it.repaymentId }.mapValues { (_,rows) -> exactTotal(rows.map { it.amountMinor }) }
        val allocatedBySale=allocations.groupBy { it.saleId }.mapValues { (_,rows) -> exactTotal(rows.map { it.amountMinor }) }
        allocatedByPayment.forEach { (id,amount) -> require(amount<=repaymentsById.getValue(id).amountMinor) { "Assigned payments exceed the received repayment." } }
        allocatedBySale.forEach { (id,amount) -> require(amount<=salesById.getValue(id).creditMinor) { "Assigned payments exceed the original sale credit." } }
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
            val creditSales = statement.events.mapNotNull { it.sale }.filter { it.creditMinor > 0 }
            fun sorted(values: List<Sale>) = values.sortedWith(compareBy<Sale> { dueDates[it.id] }.thenBy { it.createdAt }.thenBy { it.id })
            val past = sorted(creditSales.filter { dueDates[it.id]?.isBefore(asOf) == true })
            exactTotal(past.map { it.creditMinor })
            val receiptBalances=sorted(creditSales).map { sale ->
                val allocated=allocatedBySale[sale.id] ?: 0
                CreditReceiptBalance(sale,allocated,sale.creditMinor-allocated)
            }
            val unassigned=exactTotal(repayments.filter { it.customerId==customer.id }.map { payment -> payment.amountMinor-(allocatedByPayment[payment.id] ?: 0) })
            val remaining=exactTotal(receiptBalances.map { it.remainingMinor })
            require(remaining>=unassigned && remaining-unassigned==customer.balanceMinor) { "Receipt allocations do not reconcile with current customer debt." }
            if (customer.balanceMinor == 0L) return@mapNotNull null
            val overdue=if(unassigned==0L) exactTotal(receiptBalances.filter { dueDates[it.sale.id]?.isBefore(asOf)==true }.map { it.remainingMinor }) else null
            CreditReviewRow(
                customer,
                past,
                sorted(creditSales.filter { dueDates[it.id] == asOf }),
                sorted(creditSales.filter { dueDates[it.id]?.isAfter(asOf) == true }),
                sorted(creditSales.filter { dueDates[it.id] == null }),
                statement.repaidMinor,
                receiptBalances,
                unassigned,
                overdue,
            )
        }.sortedWith(compareBy<CreditReviewRow> { if (it.pastDueSales.isNotEmpty()) 0 else 1 }
            .thenBy(nullsLast()) { it.earliestRecordedDueDate }.thenBy { it.customer.name }.thenBy { it.customer.id })
    }
}
