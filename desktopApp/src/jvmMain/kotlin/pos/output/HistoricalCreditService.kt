package pos.output

import pos.data.DebtAdjustment
import pos.data.netWrittenOff
import pos.domain.*
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A recorded-date reconstruction, not a claim about when a merchant physically received money. */
data class HistoricalCreditReview(val tenantId: String, val through: LocalDate, val rows: List<CreditReviewRow>)

object HistoricalCreditService {
    fun build(
        tenantId: String, customers: List<Customer>, sales: List<Sale>, payments: List<CustomerRepayment>,
        assignments: List<RepaymentAllocation>, corrections: List<AllocationReversal>, through: LocalDate,
        creditReturns:List<CreditReturnReduction> = emptyList(),
        debtAdjustments: List<DebtAdjustment> = emptyList(),
    ): HistoricalCreditReview {
        require(assignments.all {it.tenantId==tenantId} && assignments.map {it.id}.distinct().size==assignments.size) { "Foreign or repeated assignment." }
        require(corrections.all { it.tenantId==tenantId }) { "Correction belongs to another shop." }
        require(corrections.map {it.id}.distinct().size==corrections.size && corrections.map {it.allocationId}.distinct().size==corrections.size) { "Repeated assignment correction." }
        val byAssignment=assignments.associateBy {it.id}
        val bySale=sales.associateBy {it.id}
        val byPayment=payments.associateBy {it.id}
        assignments.forEach { assignment ->
            val sale=requireNotNull(bySale[assignment.saleId]) { "Assignment sale is missing." }
            val payment=requireNotNull(byPayment[assignment.repaymentId]) { "Assignment payment is missing." }
            require(assignment.customerId==sale.customerId && assignment.customerId==payment.customerId) { "Assignment belongs to another customer." }
            val recorded=Instant.parse(assignment.createdAt)
            require(recorded>=Instant.parse(sale.createdAt) && recorded>=Instant.parse(payment.createdAt)) { "Assignment predates its source records." }
        }
        corrections.forEach { correction ->
            val original=requireNotNull(byAssignment[correction.allocationId]) { "Correction assignment is missing." }
            require(correction.customerId==original.customerId && Instant.parse(correction.createdAt)>=Instant.parse(original.createdAt)) { "Correction does not match its assignment history." }
        }
        val undone=corrections.map {it.allocationId}.toSet()
        // Validate the complete current history before reconstructing a subset of it.
        DebtAgeingService.build(tenantId,customers,sales,payments,through,assignments.filter {it.id !in undone},creditReturns,debtAdjustments)
        val cutoff=through.plusDays(1).atStartOfDay(ZoneId.of("Africa/Lagos")).toInstant()
        fun included(time:String)=Instant.parse(time)<cutoff
        val datedSales=sales.filter {included(it.createdAt)}
        val datedPayments=payments.filter {included(it.createdAt)}
        val datedReturns=creditReturns.filter {included(it.createdAt)}
        val datedAdjustments=debtAdjustments.filter { included(it.createdAt) }
        val datedUndone=corrections.filter {included(it.createdAt)}.map {it.allocationId}.toSet()
        val datedAssignments=assignments.filter {included(it.createdAt) && it.id !in datedUndone}
        val historicalCustomers=customers.map { customer ->
            val credit=datedSales.filter {it.customerId==customer.id}.fold(BigInteger.ZERO) {sum,sale -> sum+BigInteger.valueOf(sale.creditMinor)}
            val repaid=datedPayments.filter {it.customerId==customer.id}.fold(BigInteger.ZERO) {sum,payment -> sum+BigInteger.valueOf(payment.amountMinor)}
            val reduced=datedReturns.filter {it.customerId==customer.id}.fold(BigInteger.ZERO) {sum,row ->sum+BigInteger.valueOf(row.amountMinor)}
            val writtenOff = datedAdjustments.filter { it.review.customerId == customer.id }.netWrittenOff()
            customer.copy(balanceMinor=(credit-repaid-reduced-BigInteger.valueOf(writtenOff)).longValueExact())
        }
        return HistoricalCreditReview(tenantId,through,DebtAgeingService.build(tenantId,historicalCustomers,datedSales,datedPayments,through,datedAssignments,datedReturns,datedAdjustments))
    }

    fun csv(review:HistoricalCreditReview):String {
        // Keep the existing ageing validation, formula protection and exact money formatting.
        return DebtAgeingService.csvWithBasis(review.tenantId,review.rows,review.through,
            balanceBasis="Recorded history through ${review.through} WAT; customer profile details are current",
            debtColumn="Debt at selected date NGN")
    }
}
