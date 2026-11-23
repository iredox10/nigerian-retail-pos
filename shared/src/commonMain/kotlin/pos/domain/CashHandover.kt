package pos.domain

/** A handover closes the shop's single drawer and opens a new shift with the same counted cash. */
data class CashHandoverRequest(
    val tenantId:String,val operationId:String,val shiftId:String,val expectedMinor:Long,
    val countedMinor:Long,val countSheet:String?,val reason:String,
) {
    init {
        require(listOf(tenantId,operationId,shiftId).all {it.isNotBlank() && it.length<=150 && it.none {c->c.isISOControl()}})
        require(expectedMinor>=0 && countedMinor>=0)
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()})
        require(countSheet==null || CashCount.decode(countSheet).totalMinor==countedMinor)
    }
}
data class CashHandoverWitness(
    val id:String,val request:CashHandoverRequest,
    val senderId:String?,val senderName:String,val senderRole:StaffRole,
    val receiverId:String?,val receiverName:String,val receiverRole:StaffRole,
    val acceptedAt:String,val expiresAt:String,val issuerPublicKey:String,val signature:String,
) {
    init {
        require(id.isNotBlank() && id.length<=150)
        require(senderRole in setOf(StaffRole.OWNER,StaffRole.MANAGER) && (senderId==null)==(senderRole==StaffRole.OWNER))
        require((receiverId==null)==(receiverRole==StaffRole.OWNER) && senderId!=receiverId)
        require(listOf(senderName,receiverName).all {it==it.trim() && it.length in 1..100 && it.none {c->c.isISOControl()}})
        require(issuerPublicKey.length<=200 && signature.length<=200)
    }
}
data class CashHandover(val id:String,val tenantId:String,val closedShiftId:String,val openedShiftId:String,val witness:CashHandoverWitness,val createdAt:String)
