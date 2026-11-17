package pos.domain

/** Classifies drawer cash only; this is not a general ledger or a profit calculation. */
data class OwnerCashRecord(
    val id:String,val tenantId:String,val kind:String,val amountMinor:Long,val ownerName:String,
    val reason:String,val shiftId:String,val cashEntryId:String,val createdAt:String,
    val beforeCashMinor:Long,val reversesId:String?=null,
) {
    init {
        require(id.length in 1..200 && tenantId.isNotBlank() && shiftId.isNotBlank() && cashEntryId.isNotBlank() && createdAt.isNotBlank())
        require(kind in setOf("CONTRIBUTION","DRAWING","REVERSAL"))
        require(amountMinor!=0L && amountMinor!=Long.MIN_VALUE)
        require(when(kind) {"CONTRIBUTION" ->amountMinor>0;"DRAWING" ->amountMinor<0;else ->true})
        require((kind=="REVERSAL")== (reversesId!=null) && reversesId!=id)
        require(ownerName==ownerName.trim() && ownerName.length in 1..150 && ownerName.none {it.isISOControl()}) {"Enter the owner's name (up to 150 printable characters)."}
        require(reason==reason.trim() && reason.length in 3..500 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 500 printable characters."}
        require(beforeCashMinor>=0 && if(amountMinor>0) beforeCashMinor<=Long.MAX_VALUE-amountMinor else beforeCashMinor>=-amountMinor) {"Not enough recorded drawer cash."}
    }
}
