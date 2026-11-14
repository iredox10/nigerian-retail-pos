package pos.domain

/** A cashier's record, never evidence of bank authorization or settlement. */
data class ExternalPayment(
    val method: String,
    val amountMinor: Long,
    val reference: String,
    val receivingAccount: String,
) {
    init {
        require(method in setOf("transfer", "card")) { "Choose bank transfer or card terminal." }
        require(amountMinor > 0) { "Enter an external payment amount greater than zero." }
        require(reference == reference.trim() && reference.length in 1..150 && reference.none { it.isISOControl() }) {
            "Enter a printable payment reference of at most 150 characters."
        }
        require(receivingAccount == receivingAccount.trim() && receivingAccount.length in 1..150 && receivingAccount.none { it.isISOControl() }) {
            "Enter the receiving account or terminal label."
        }
    }

    val label: String get() = if (method == "transfer") "Transfer not confirmed" else "Card payment not confirmed"
}

fun externalPaymentTotal(payments: List<ExternalPayment>): Long {
    require(payments.size <= 10) { "Use at most 10 external payment portions." }
    require(payments.map { it.method to it.reference }.distinct().size == payments.size) {
        "A payment reference cannot be repeated in the same sale."
    }
    return payments.fold(0L) { sum, payment -> addMoney(sum, payment.amountMinor) }
}
