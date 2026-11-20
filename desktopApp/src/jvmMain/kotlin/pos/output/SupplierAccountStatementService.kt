package pos.output

import pos.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant

data class SupplierAccountStatement(
    val profile:SupplierProfile,val links:List<SupplierReceiptLink>,val receipts:List<StockReceipt>,
    val payments:List<SupplierPayment>,val statement:SupplierStatement,
    val returns:List<PurchaseReturn> = emptyList(),
)

/** Stable account identity groups explicitly linked receipts, never similar supplier names. */
object SupplierAccountStatementService {
    fun build(profile:SupplierProfile,links:List<SupplierReceiptLink>,receipts:List<StockReceipt>,payments:List<SupplierPayment>,from:LocalDate,through:LocalDate,returns:List<PurchaseReturn> = emptyList()):SupplierAccountStatement {
        val tenant=profile.tenantId
        require(links.all {it.tenantId==tenant} && receipts.all {it.tenantId==tenant} && payments.all {it.tenantId==tenant} &&
            returns.all {it.tenantId==tenant}) {"Supplier account includes another shop."}
        require(links.map {it.receiptId}.distinct().size==links.size && receipts.map {it.id}.distinct().size==receipts.size &&
            payments.map {it.id}.distinct().size==payments.size && returns.map {it.id}.distinct().size==returns.size) {"Supplier account contains duplicate records."}
        val byId=receipts.associateBy {it.id}
        require(links.all {it.receiptId in byId}) {"Supplier account link refers to an unavailable receipt."}
        require(returns.all {it.receiptId in byId}) {"A purchase return refers to an unavailable receipt."}
        links.forEach {Instant.parse(it.createdAt)}
        val selectedLinks=links.filter {it.supplierId==profile.id}
        val ids=selectedLinks.map {it.receiptId}.toSet()
        val selectedReceipts=receipts.filter {it.id in ids}
        val selectedPayments=payments.filter {it.receiptId in ids}
        val selectedReturns=returns.filter {it.receiptId in ids}
        require(selectedPayments.all {it.supplierName==byId.getValue(it.receiptId).supplierName}) {"A supplier payment does not match its receipt's historical name."}
        // Reuse the validated financial chronology with a private canonical grouping key.
        // Only the original receipt names are exposed in the account export below.
        val result=SupplierStatementService.build(tenant,profile.id,selectedReceipts.map {it.copy(supplierName=profile.id)},selectedPayments.map {it.copy(supplierName=profile.id)},from,through,selectedReturns)
        return SupplierAccountStatement(profile,selectedLinks,selectedReceipts,selectedPayments,result,selectedReturns)
    }
    fun csv(value:SupplierAccountStatement):String {
        val s=value.statement
        require(build(value.profile,value.links,value.receipts,value.payments,s.from,s.through,value.returns)==value) {"Supplier account statement does not reconcile."}
        fun text(v:String)=CsvCell.Text(v)
        fun money(v:Long)=CsvCell.Number(BigDecimal.valueOf(v,2).toPlainString())
        val receipts=value.receipts.associateBy {it.id}
        fun row(kind:String,source:String="",receiptId:String="",date:String="",purchase:Long=0,paid:Long=0,later:Long=0,balance:Long,credit:Long=0)=listOf(
            text(value.profile.id),text(value.profile.details.name),text(receipts[receiptId]?.supplierName.orEmpty()),text(s.from.toString()),text(s.through.toString()),text(kind),text(source),text(receiptId),text(date),money(purchase),money(paid),money(later),money(balance),money(credit))
        val rows=listOf(row("Opening balance before period",balance=s.openingMinor))+s.events.map {event ->
            row(event.activity,event.id,event.receiptId,event.createdAt,event.purchaseMinor,event.paidAtReceiptMinor,event.laterPaymentMinor,event.balanceAfterMinor,event.returnCreditMinor)
        }+listOf(row("Closing balance through selected WAT date",balance=s.closingMinor),row("Current outstanding — all recorded dates",balance=s.currentOutstandingMinor))
        return CsvService.encode(listOf("Supplier account ID","Current supplier profile name","Supplier name saved on receipt","From WAT","Through WAT","Activity","Source ID","Stock receipt ID","Recorded at UTC","Purchase total NGN","Cash paid at receipt NGN","Later cash payment NGN","Recorded balance NGN","Purchase return credit NGN"),rows)
    }
}
