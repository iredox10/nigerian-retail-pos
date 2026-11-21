package pos.output

import pos.data.DebtAdjustment
import pos.data.netWrittenOff
import pos.domain.Customer
import pos.domain.CustomerRepayment
import pos.domain.RepaymentAllocation
import pos.domain.Sale
import pos.domain.CreditReturnReduction
import java.math.BigInteger
import java.time.LocalDate

/** Balance after explicitly assigned repayments, before any unassigned customer payments. */
data class CreditReceiptBalance(val sale: Sale, val allocatedMinor: Long, val remainingMinor: Long,val returnedCreditMinor:Long=0,
    val writtenOffMinor: Long = 0) {
    init {
        require(sale.creditMinor > 0 && allocatedMinor in 0..sale.creditMinor)
        require(returnedCreditMinor in 0..(sale.creditMinor - allocatedMinor) &&
            writtenOffMinor in 0..(sale.creditMinor - allocatedMinor - returnedCreditMinor) &&
            remainingMinor == sale.creditMinor - allocatedMinor - returnedCreditMinor - writtenOffMinor)
    }
}

enum class DebtAgeBand(val label: String) {
    NOT_LATE("Due today or later"),
    LATE_1_7("1–7 days late"),
    LATE_8_30("8–30 days late"),
    LATE_31_60("31–60 days late"),
    LATE_61_90("61–90 days late"),
    LATE_OVER_90("Over 90 days late"),
    UNDATED("No agreed due date");

    companion object {
        fun classify(dueDate: String?, asOf: LocalDate): DebtAgeBand {
            if (dueDate == null) return UNDATED
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(dueDate), asOf)
            return when {
                days <= 0 -> NOT_LATE
                days <= 7 -> LATE_1_7
                days <= 30 -> LATE_8_30
                days <= 60 -> LATE_31_60
                days <= 90 -> LATE_61_90
                else -> LATE_OVER_90
            }
        }
    }
}

/** Null means exact ageing is unavailable, rather than a zero debt in every band. */
data class DebtAgeingAmounts(val amounts: Map<DebtAgeBand, Long>) {
    init {
        require(amounts.keys == DebtAgeBand.entries.toSet() && amounts.values.all { it >= 0 })
        exactTotal(amounts.values.toList())
    }
    val totalMinor: Long get() = exactTotal(amounts.values.toList())
    val overdueMinor: Long get() = exactTotal(amounts.filterKeys { it != DebtAgeBand.NOT_LATE && it != DebtAgeBand.UNDATED }.values.toList())
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
    val ageing: DebtAgeingAmounts? = null,
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

    /** Export every customer in the supplied review, with blanks when ageing is unknown. */
    fun csv(tenantId: String, rows: List<CreditReviewRow>, asOf: LocalDate): String =
        csvWithBasis(tenantId,rows,asOf,"Current saved balances; not a historical balance","Current debt NGN")

    internal fun csvWithBasis(tenantId: String, rows: List<CreditReviewRow>, asOf: LocalDate, balanceBasis: String, debtColumn: String): String {
        require(rows.all { it.customer.tenantId == tenantId }) { "Credit export includes another shop." }
        require(rows.map { it.customer.id }.distinct().size == rows.size) { "Credit export has duplicate customers." }
        fun amount(value: Long) = CsvCell.Number(java.math.BigDecimal.valueOf(value, 2).toPlainString())
        return CsvService.encode(
            listOf("Customer ID", "Customer", "Due-date classification WAT", "Balance basis", debtColumn, "Unassigned repayments NGN", "Exact ageing available") + DebtAgeBand.entries.map { it.label + " NGN" },
            rows.map { row ->
                // Prevent a caller from relabeling a review with another classification date.
                val expected = if (row.unallocatedRepaymentsMinor == 0L) DebtAgeBand.entries.associateWith { band ->
                    exactTotal(row.invoiceBalances.filter { DebtAgeBand.classify(it.sale.dueDate, asOf) == band }.map { it.remainingMinor })
                } else null
                require(row.ageing?.amounts == expected) { "Refresh credit ageing for the export date." }
                listOf(CsvCell.Text(row.customer.id), CsvCell.Text(row.customer.name), CsvCell.Text(asOf.toString()), CsvCell.Text(balanceBasis), amount(row.currentDebtMinor), amount(row.unallocatedRepaymentsMinor), CsvCell.Text((row.ageing != null).toString())) +
                    DebtAgeBand.entries.map { band -> row.ageing?.amounts?.get(band)?.let(::amount) ?: CsvCell.Text("") }
            },
        )
    }

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
        creditReturns:List<CreditReturnReduction> = emptyList(),
        debtAdjustments: List<DebtAdjustment> = emptyList(),
    ): List<CreditReviewRow> {
        require(tenantId.isNotBlank()) { "Choose a shop before reviewing credit." }
        require(customers.all { it.tenantId == tenantId } && sales.all { it.tenantId == tenantId } && repayments.all { it.tenantId == tenantId } && allocations.all { it.tenantId == tenantId } && creditReturns.all {it.tenantId==tenantId} && debtAdjustments.all { it.tenantId==tenantId }) {
            "Credit history includes another shop's records."
        }
        require(customers.map { it.id }.distinct().size == customers.size &&
            sales.map { it.id }.distinct().size == sales.size &&
            repayments.map { it.id }.distinct().size == repayments.size &&
            allocations.map { it.id }.distinct().size == allocations.size && creditReturns.map {it.returnId}.distinct().size==creditReturns.size) { "Credit history contains repeated records." }
        val ids = customers.map { it.id }.toSet()
        require(sales.all { it.customerId == null || it.customerId in ids } && repayments.all { it.customerId in ids } && creditReturns.all {it.customerId in ids} && debtAdjustments.all { it.review.customerId in ids }) {
            "Credit history refers to an unavailable customer. Refresh the complete customer list."
        }
        val returnedBySale=creditReturns.groupBy {it.saleId}.mapValues {(_,rows) ->exactTotal(rows.map {it.amountMinor})}
        val writtenOffBySale = debtAdjustments.groupBy { it.review.saleId }.mapValues { (_, rows) -> rows.netWrittenOff() }
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
        allocatedBySale.forEach { (id,amount) -> require(amount<=Math.subtractExact(salesById.getValue(id).creditMinor,returnedBySale[id] ?: 0L)) { "Assigned payments exceed the original sale credit." } }
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
            val statement = CustomerStatementService.build(tenantId, customer, sales, repayments, creditReturns, debtAdjustments)
            val creditSales = statement.events.mapNotNull { it.sale }.filter { it.creditMinor > 0 }
            fun sorted(values: List<Sale>) = values.sortedWith(compareBy<Sale> { dueDates[it.id] }.thenBy { it.createdAt }.thenBy { it.id })
            val past = sorted(creditSales.filter { dueDates[it.id]?.isBefore(asOf) == true })
            exactTotal(past.map { it.creditMinor })
            val receiptBalances=sorted(creditSales).map { sale ->
                val allocated=allocatedBySale[sale.id] ?: 0
                val returned=returnedBySale[sale.id] ?: 0L
                val writtenOff = writtenOffBySale[sale.id] ?: 0L
                CreditReceiptBalance(sale, allocated,
                    Math.subtractExact(Math.subtractExact(Math.subtractExact(sale.creditMinor, allocated), returned), writtenOff),
                    returned, writtenOff)
            }
            val unassigned=exactTotal(repayments.filter { it.customerId==customer.id }.map { payment -> payment.amountMinor-(allocatedByPayment[payment.id] ?: 0) })
            val remaining=exactTotal(receiptBalances.map { it.remainingMinor })
            require(remaining>=unassigned && remaining-unassigned==customer.balanceMinor) { "Receipt allocations do not reconcile with current customer debt." }
            if (customer.balanceMinor == 0L) return@mapNotNull null
            val ageing = if (unassigned == 0L) DebtAgeingAmounts(DebtAgeBand.entries.associateWith { band ->
                exactTotal(receiptBalances.filter { DebtAgeBand.classify(it.sale.dueDate, asOf) == band }.map { it.remainingMinor })
            }) else null
            require(ageing == null || ageing.totalMinor == customer.balanceMinor) { "Debt ageing does not reconcile with current customer debt." }
            val overdue=ageing?.overdueMinor
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
                ageing,
            )
        }.sortedWith(compareBy<CreditReviewRow> { if (it.pastDueSales.isNotEmpty()) 0 else 1 }
            .thenBy(nullsLast()) { it.earliestRecordedDueDate }.thenBy { it.customer.name }.thenBy { it.customer.id })
    }
}
