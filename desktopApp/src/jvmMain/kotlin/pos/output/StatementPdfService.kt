package pos.output

import pos.domain.Tenant
import pos.domain.money
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Printable views of reconciled local history; exporting never records a financial event. */
object StatementPdfService {
    private val wat=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'WAT'").withZone(ZoneId.of("Africa/Lagos"))
    private fun date(value:String)=wat.format(Instant.parse(value))

    fun customer(tenant:Tenant,value:DatedCustomerStatement):String {
        val customer=value.complete.customer
        require(customer.tenantId==tenant.id) {"Customer statement belongs to another shop."}
        require(DatedCustomerStatementService.build(value.complete,value.range)==value) {"Customer statement has inconsistent history or totals."}
        return buildString {
            appendLine(tenant.name);appendLine("CUSTOMER STATEMENT")
            appendLine("Shop ID: ${tenant.id}");appendLine("Customer ID: ${customer.id}")
            appendLine("Current customer name: ${customer.name}")
            appendLine("Current phone: ${customer.phone ?: "Not recorded"}")
            appendLine("Current address: ${customer.address ?: "Not recorded"}")
            appendLine("Customer name and contact details above are current, not historical snapshots.")
            appendLine("Period: ${value.range.from} through ${value.range.through} (inclusive WAT)")
            appendLine("Opening debt: ${money(value.openingMinor)}");appendLine()
            if(value.events.isEmpty()) appendLine("No sales or debt repayments recorded in this period.")
            value.events.forEach { (event,balance) ->
                appendLine("${date(event.createdAt)} | ${if(event.sale!=null) "Original sale" else "Cash debt repayment"}")
                appendLine("Source ID: ${event.id}")
                event.sale?.let {sale ->
                    appendLine("Original sale total: ${money(sale.totalMinor)}")
                    appendLine("Cash applied at sale: ${money(sale.cashMinor)}")
                    appendLine("Credit added at sale: ${money(sale.creditMinor)}")
                }
                event.repayment?.let {appendLine("Cash received against debt: ${money(it.amountMinor)}")}
                appendLine("Debt after activity: ${money(balance)}");appendLine()
            }
            appendLine("Closing debt: ${money(value.closingMinor)}")
            appendLine("Closing debt is through the selected WAT end date.")
            appendLine("Current debt, all saved dates: ${money(customer.balanceMinor)}")
            appendLine("Original sales and later repayments remain separate. Cash returns are recorded under Sales.")
            appendLine("Statement of saved local records. Exporting does not record a sale or payment.")
        }
    }

    fun supplier(tenant:Tenant,value:SupplierAccountStatement):String {
        val profile=value.profile;val statement=value.statement
        require(profile.tenantId==tenant.id) {"Supplier statement belongs to another shop."}
        require(SupplierAccountStatementService.build(profile,value.links,value.receipts,value.payments,statement.from,statement.through)==value) {"Supplier statement has inconsistent history or totals."}
        val receipts=value.receipts.associateBy {it.id}
        return buildString {
            appendLine(tenant.name);appendLine("SUPPLIER STATEMENT")
            appendLine("Shop ID: ${tenant.id}");appendLine("Supplier account ID: ${profile.id}")
            appendLine("Current supplier name: ${profile.details.name}")
            appendLine("Current phone: ${profile.details.phone ?: "Not recorded"}")
            appendLine("Current contact person: ${profile.details.contactPerson ?: "Not recorded"}")
            appendLine("Current address: ${profile.details.address ?: "Not recorded"}")
            appendLine("Current payment terms: ${profile.details.paymentTerms ?: "Not recorded"}")
            appendLine("Contact details above are current. Each activity retains its receipt's historical supplier name.")
            appendLine("Only purchases explicitly linked to this supplier account are included.")
            appendLine("Period: ${statement.from} through ${statement.through} (inclusive WAT)")
            appendLine("Opening amount owed: ${money(statement.openingMinor)}");appendLine()
            if(statement.events.isEmpty()) appendLine("No linked purchases or payments recorded in this period.")
            statement.events.forEach {event ->
                appendLine("${date(event.createdAt)} | ${if(event.laterPaymentMinor>0) "Later cash payment" else "Stock received"}")
                appendLine("Source ID: ${event.id}");appendLine("Stock receipt ID: ${event.receiptId}")
                appendLine("Supplier name saved on receipt: ${receipts.getValue(event.receiptId).supplierName}")
                appendLine("Purchase total: ${money(event.purchaseMinor)}")
                appendLine("Cash paid at receipt: ${money(event.paidAtReceiptMinor)}")
                appendLine("Later cash payment: ${money(event.laterPaymentMinor)}")
                appendLine("Amount owed after activity: ${money(event.balanceAfterMinor)}");appendLine()
            }
            appendLine("Purchases in period: ${money(statement.purchasesMinor)}")
            appendLine("Cash paid at receipt in period: ${money(statement.paidAtReceiptMinor)}")
            appendLine("Later payments in period: ${money(statement.laterPaymentsMinor)}")
            appendLine("Closing amount owed: ${money(statement.closingMinor)}")
            appendLine("Closing amount owed is through the selected WAT end date.")
            appendLine("Current outstanding, all saved dates: ${money(statement.currentOutstandingMinor)}")
            appendLine("Statement of saved local records. Exporting does not record a purchase or payment.")
        }
    }

    fun exportCustomer(tenant:Tenant,value:DatedCustomerStatement):File? =
        PdfDocumentService.export(customer(tenant,value),PdfDocumentKind.CUSTOMER_STATEMENT)
    fun exportSupplier(tenant:Tenant,value:SupplierAccountStatement):File? =
        PdfDocumentService.export(supplier(tenant,value),PdfDocumentKind.SUPPLIER_STATEMENT)
}
