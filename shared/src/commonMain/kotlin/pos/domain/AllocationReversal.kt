package pos.domain

/** Immutable correction of a receipt assignment; the received repayment itself remains unchanged. */
data class AllocationReversal(
    val id: String,
    val tenantId: String,
    val customerId: String,
    val allocationId: String,
    val reason: String,
    val createdAt: String,
) {
    init {
        require(listOf(id, tenantId, customerId, allocationId, createdAt).all { it.isNotBlank() })
        require(reason == reason.trim() && reason.length in 3..250 && reason.none { it.isISOControl() }) {
            "Enter a correction reason from 3 to 250 characters."
        }
    }
}
