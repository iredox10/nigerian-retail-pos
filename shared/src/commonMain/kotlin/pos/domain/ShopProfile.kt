package pos.domain

/** Business receipt details; this is neither a tax registration nor an externally verified identity. */
data class ShopDetails(
    val displayName:String, val phone:String?=null, val address:String?=null,
    val footer:String?=null, val receiptPaper:String="A4",
) {
    init {
        require(displayName==displayName.trim() && displayName.length in 1..150 && displayName.none {it.isISOControl()}) {"Enter a shop name from 1 to 150 printable characters."}
        require(phone==null || normalizeNigerianPhone(phone)==phone) {"Use a valid Nigerian shop phone number."}
        listOf(address to 1000,footer to 500).forEach { (value,maximum) ->
            require(value==null || (value==value.trim() && value.isNotEmpty() && value.length<=maximum && value.none {it.isISOControl()})) {"Receipt address and footer must be trimmed printable text within their field limit."}
        }
        require(receiptPaper in setOf("A4","58mm","80mm")) {"Choose A4, 58 mm or 80 mm receipt paper."}
    }
}

data class ShopProfile(val tenantId:String,val details:ShopDetails,val version:Long,val updatedAt:String) {
    init {require(tenantId.isNotBlank() && version>0 && updatedAt.isNotBlank())}
}

data class ShopProfileChange(
    val id:String,val tenantId:String,val previous:ShopDetails?,val current:ShopDetails,
    val version:Long,val reason:String,val createdAt:String,
) {
    init {
        require(id.isNotBlank() && tenantId.isNotBlank() && version>0 && createdAt.isNotBlank())
        require((previous==null)==(version==1L))
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a reason from 3 to 250 printable characters."}
    }
}

/** Copied at checkout, so later contact/branding edits never rewrite an issued receipt. */
data class ReceiptProfileSnapshot(val tenantId:String,val saleId:String,val details:ShopDetails,val profileVersion:Long,val capturedAt:String) {
    init {require(tenantId.isNotBlank() && saleId.isNotBlank() && profileVersion>=0 && capturedAt.isNotBlank())}
}
