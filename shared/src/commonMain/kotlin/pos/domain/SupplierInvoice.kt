package pos.domain

enum class SupplierInvoiceStatus { ACTIVE, VOIDED }

/** Case and repeated whitespace do not create a different invoice number. Punctuation remains meaningful. */
fun canonicalSupplierInvoiceNumber(value:String):String {
    require(value==value.trim() && value.length in 1..100 && value.none {it.isISOControl()}) {"Enter an invoice number from 1 to 100 printable characters."}
    return value.map {if(it.isWhitespace()) ' ' else it}.joinToString("").split(Regex(" +")).joinToString(" ").uppercase()
}

data class SupplierInvoiceDetails(val number:String,val invoiceDate:String,val reference:String?=null,val notes:String?=null,val duplicateReviewReason:String?=null) {
    init {
        canonicalSupplierInvoiceNumber(number)
        require(isValidCreditDueDate(invoiceDate)) {"Enter the date written on the supplier invoice as YYYY-MM-DD."}
        listOf(reference to 250,notes to 1000,duplicateReviewReason to 500).forEach {(value,max) ->require(value==null || (value==value.trim() && value.isNotEmpty() && value.length<=max && value.none {it.isISOControl()})) {"Invoice details must be trimmed printable text within the field limit."}}
        require(duplicateReviewReason==null || duplicateReviewReason.length>=3) {"Explain why the repeated number is a different invoice."}
    }
    val canonicalNumber:String get()=canonicalSupplierInvoiceNumber(number)
}

data class SupplierInvoiceAttachmentRef(val hash:String,val mediaType:String,val byteSize:Int,val fileName:String) {
    init {
        require(hash.matches(Regex("[0-9a-f]{64}")) && mediaType in setOf("application/pdf","image/png","image/jpeg") && byteSize in 1..1_048_576)
        require(fileName==fileName.trim() && fileName.length in 1..200 && fileName !in setOf(".","..") && fileName.none {it.isISOControl() || it=='/' || it=='\\'}) {"Use a filename without folders or control characters."}
    }
}

data class SupplierInvoice(
    val id:String,val tenantId:String,val supplierId:String,val details:SupplierInvoiceDetails,val status:SupplierInvoiceStatus,
    val version:Long,val createdAt:String,val updatedAt:String,val receiptIds:List<String>,val attachment:SupplierInvoiceAttachmentRef?=null,
) {
    init {
        require(listOf(id,tenantId,supplierId,createdAt,updatedAt).all {it.isNotBlank()} && version>0)
        require(receiptIds.size<=200 && receiptIds.all {it.isNotBlank()} && receiptIds.distinct().size==receiptIds.size)
        require(if(status==SupplierInvoiceStatus.ACTIVE) receiptIds.isNotEmpty() else receiptIds.isEmpty()) {"An active invoice needs linked purchases; a voided record releases its current receipt associations."}
    }
}

data class SupplierInvoiceRevision(val operationId:String,val invoice:SupplierInvoice,val reason:String) {
    init {require(operationId.isNotBlank() && reason==reason.trim() && reason.length in 3..250 && reason.none {it.isISOControl()}) {"Enter a correction reason from 3 to 250 printable characters."}}
}
