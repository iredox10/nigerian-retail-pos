package pos.domain

/** Contact details are current profile data; purchase receipts keep their historical supplier name. */
data class SupplierDetails(
    val name:String, val phone:String?=null, val contactPerson:String?=null,
    val address:String?=null, val paymentTerms:String?=null, val notes:String?=null,
) {
    init {
        require(name==name.trim() && name.length in 1..150 && name.none {it.isISOControl()}) { "Enter a supplier name from 1 to 150 printable characters." }
        listOf(contactPerson to 150,address to 1000,paymentTerms to 500,notes to 2000).forEach { (value,maximum) ->
            require(value==null || (value==value.trim() && value.isNotEmpty() && value.length<=maximum && value.none {it.isISOControl()})) { "Supplier details must be trimmed printable text within their field limit." }
        }
        require(phone==null || normalizeNigerianPhone(phone)==phone) { "Use a valid Nigerian supplier phone number." }
    }
}

data class SupplierProfile(val id:String,val tenantId:String,val details:SupplierDetails,val archived:Boolean,val version:Long,val createdAt:String) {
    init { require(id.isNotBlank() && tenantId.isNotBlank() && version>0 && createdAt.isNotBlank()) }
}

data class SupplierProfileChange(
    val id:String,val tenantId:String,val supplierId:String,val previous:SupplierDetails?,val current:SupplierDetails,
    val previousArchived:Boolean?,val currentArchived:Boolean,val version:Long,val reason:String,val createdAt:String,
) {
    init {
        require(listOf(id,tenantId,supplierId,createdAt).all {it.isNotBlank()} && version>0)
        require((previous==null)==(previousArchived==null) && (previous==null)==(version==1L))
        require(reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) { "Enter a reason from 3 to 250 printable characters." }
    }
}

data class SupplierReceiptLink(val tenantId:String,val receiptId:String,val supplierId:String,val operationId:String,val createdAt:String) {
    init { require(listOf(tenantId,receiptId,supplierId,operationId,createdAt).all {it.isNotBlank()}) }
}
