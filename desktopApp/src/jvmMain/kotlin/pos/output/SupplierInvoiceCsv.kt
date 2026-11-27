package pos.output

import pos.domain.*
import java.math.BigDecimal

/** Invoice annotations do not post purchases, payments, credits or tax validation. */
object SupplierInvoiceCsv {
    fun current(tenantId:String,invoices:List<SupplierInvoice>,receipts:List<StockReceipt>):String {
        require(tenantId.isNotBlank() && invoices.all {it.tenantId==tenantId} && receipts.all {it.tenantId==tenantId}) {"Invoice export includes another shop."}
        require(invoices.map {it.id}.distinct().size==invoices.size && receipts.map {it.id}.distinct().size==receipts.size) {"Invoice export contains repeated records."}
        return encode(invoices.map {SupplierInvoiceRevision("current:${it.id}",it,"Current metadata projection")},receipts)
    }
    fun history(tenantId:String,revisions:List<SupplierInvoiceRevision>,receipts:List<StockReceipt>):String {
        require(tenantId.isNotBlank() && revisions.all {it.invoice.tenantId==tenantId} && receipts.all {it.tenantId==tenantId}) {"Invoice history export includes another shop."}
        require(revisions.map {it.invoice.id to it.invoice.version}.distinct().size==revisions.size && receipts.map {it.id}.distinct().size==receipts.size) {"Invoice history export contains repeated records."}
        return encode(revisions,receipts)
    }
    private fun encode(revisions:List<SupplierInvoiceRevision>,receipts:List<StockReceipt>):String {
        val byId=receipts.associateBy {it.id}
        fun text(value:String)=CsvCell.Text(value)
        return CsvData.encode(listOf("Supplier invoice record ID","Supplier account ID","Invoice number as recorded","Invoice date written on document","Reference","Metadata state — not payment status","Revision","Recorded at UTC","Correction reason","Duplicate-number review reason","Associated stock receipt IDs","Associated receipt buying cost NGN — not a new invoice posting","Attachment filename","Attachment media type","Attachment SHA-256","Notes"),revisions.map {revision ->
            val i=revision.invoice
            val cost=i.receiptIds.fold(0L) {sum,id ->Math.addExact(sum,requireNotNull(byId[id]) {"An invoice's associated stock receipt is missing."}.totalCostMinor)}
            listOf(text(i.id),text(i.supplierId),text(i.details.number),text(i.details.invoiceDate),text(i.details.reference.orEmpty()),text(i.status.name),CsvCell.Number(i.version.toString()),text(i.updatedAt),text(revision.reason),text(i.details.duplicateReviewReason.orEmpty()),text(i.receiptIds.joinToString(" | ")),CsvCell.Number(BigDecimal.valueOf(cost,2).toPlainString()),text(i.attachment?.fileName.orEmpty()),text(i.attachment?.mediaType.orEmpty()),text(i.attachment?.hash.orEmpty()),text(i.details.notes.orEmpty()))
        })
    }
}
