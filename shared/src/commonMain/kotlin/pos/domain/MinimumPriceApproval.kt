package pos.domain

/** Exact sale prices shown to the approving person, including every applicable floor version. */
data class MinimumPriceApprovalLine(
    val key: String, val productId: String, val name: String, val quantity: String,
    val unit: String, val totalMinor: Long,
)

data class MinimumPriceApprovalCheck(
    val lineKey: String, val productId: String, val unitKey: String, val policyVersion: Long, val requiredMinor: Long,
)

data class MinimumPriceApprovalRequest(
    val tenantId: String, val operationId: String, val fingerprint: String,
    val lines: List<MinimumPriceApprovalLine>, val checks: List<MinimumPriceApprovalCheck>,
    val totalMinor: Long, val hasDiscounts: Boolean,
) {
    init {
        require(tenantId.isNotBlank() && operationId.isNotBlank() && operationId.length <= 150 && operationId.none { it.isISOControl() })
        require(fingerprint.matches(Regex("[a-f0-9]{64}")) && lines.size in 1..200 && totalMinor >= 0)
        require(lines.map { it.key }.distinct().size == lines.size && lines.all { it.totalMinor >= 0 })
        require(checks.size <= 400 && checks.all { it.policyVersion > 0 && it.requiredMinor >= 0 &&
            lines.any { line -> line.key == it.lineKey && line.productId == it.productId } })
    }
    val belowFloor: Boolean get() = checks.any { check -> lines.single { it.key == check.lineKey }.totalMinor < check.requiredMinor }
    val requiresApproval: Boolean get() = hasDiscounts || belowFloor
}

/** Signed historical evidence is independent of the approver's later name, password or role. */
data class MinimumPriceApprovalWitness(
    val id: String, val request: MinimumPriceApprovalRequest,
    val requesterId: String?, val requesterName: String, val requesterRole: StaffRole,
    val approverId: String?, val approverName: String, val approverRole: StaffRole,
    val reason: String, val approvedAt: String, val expiresAt: String,
    val issuerPublicKey: String, val signature: String,
) {
    init {
        require(id.isNotBlank() && id.length <= 150 && id.none { it.isISOControl() })
        require((requesterRole == StaffRole.OWNER) == (requesterId == null))
        require(approverRole in setOf(StaffRole.OWNER, StaffRole.MANAGER) &&
            (approverRole == StaffRole.OWNER) == (approverId == null) && requesterId != approverId)
        require(listOf(requesterName, approverName).all { it == it.trim() && it.length in 1..100 && it.none { c -> c.isISOControl() } })
        require(reason == reason.trim() && reason.length in 3..250 && reason.none { it.isISOControl() })
        require(approvedAt.isNotBlank() && expiresAt.isNotBlank() && issuerPublicKey.length <= 200 && signature.length <= 200)
    }
}
