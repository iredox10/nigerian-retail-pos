package pos.output

import pos.domain.Customer
import pos.domain.ReceiptProfileSnapshot
import pos.domain.Sale
import pos.domain.SaleReturn
import pos.domain.Tenant
import pos.domain.cartDiscountTotal
import pos.domain.cartSubtotal
import pos.domain.money
import pos.domain.quantityLabel

/** Local copies of saved sales and returns. Formatting never posts another transaction. */
object SaleDocumentService {
    fun invoice(tenant: Tenant, sale: Sale, profile: ReceiptProfileSnapshot?, customer: Customer? = null): String =
        buildString {
            appendHeader(tenant, sale, profile, customer, PdfDocumentKind.SALES_INVOICE, "INV-${sale.id}")
            sale.lines.forEach { line ->
                appendLine(line.product.name)
                appendLine("  ${line.quantityLabel} ${line.sellingUnit} × ${money(line.unitPriceMinor)}")
                if (line.baseUnitsPerUnit > 1) appendLine("  1 ${line.sellingUnit} = ${line.baseUnitsPerUnit} ${line.product.unit}")
                if (line.discountTotalMinor > 0) appendLine("  Discount: ${money(line.discountTotalMinor)}")
                appendLine("  Line total: ${money(line.totalMinor)}")
            }
            appendLine()
            val discount = cartDiscountTotal(sale.lines)
            if (discount > 0) {
                appendLine("Before discounts: ${money(cartSubtotal(sale.lines))}")
                appendLine("Total discounts: ${money(discount)}")
            }
            appendLine("Invoice total: ${money(sale.totalMinor)}")
            appendLine("Cash applied at sale: ${money(sale.cashMinor)}")
            sale.exchange?.let { exchange ->
                appendLine("Value from the exchange: ${money(exchange.amountMinor)}")
                appendLine("Original receipt: ${exchange.linkedReceiptId}")
            }
            sale.externalPayments.forEach { payment ->
                appendLine("${payment.label}: ${money(payment.amountMinor)}")
                appendLine("  Payment reference: ${payment.reference}")
            }
            if (sale.creditMinor > 0) {
                appendLine("Customer owed at sale: ${money(sale.creditMinor)}")
                sale.dueDate?.let { appendLine("Repayment due date agreed at sale: $it (WAT)") }
            }
            sale.note?.let { appendLine("Sale note: $it") }
            appendLine()
            appendLine("Original sale amounts. See the saved receipt or customer statement for later activity.")
        }

    fun deliveryNote(
        tenant: Tenant,
        sale: Sale,
        returns: List<SaleReturn>,
        profile: ReceiptProfileSnapshot?,
        customer: Customer? = null,
    ): String {
        val returned = returnedQuantities(sale, returns)
        require(sale.lines.any { it.quantity > returned.getOrDefault(it.key, 0) }) {
            "All items from this sale have been returned."
        }
        return buildString {
            appendHeader(tenant, sale, profile, customer, PdfDocumentKind.DELIVERY_NOTE, "DN-${sale.id}")
            appendLine("Goods to hand over, based on the sale and saved returns.")
            sale.lines.forEach { line ->
                val quantity = line.quantity - returned.getOrDefault(line.key, 0)
                if (quantity > 0) {
                    appendLine("${quantityLabel(quantity.toLong(), line.quantityPrecision)} ${line.sellingUnit} · ${line.product.name}")
                    if (line.baseUnitsPerUnit > 1) appendLine("  1 ${line.sellingUnit} = ${line.baseUnitsPerUnit} ${line.product.unit}")
                }
            }
            if (returns.isNotEmpty()) {
                appendLine()
                appendLine("Saved returns included in this copy:")
                returns.map { it.id }.sorted().forEach { appendLine("  $it") }
            }
            appendLine()
            appendLine("Complete at handover:")
            appendLine("Received by: _______________________________________")
            appendLine("Date and time: _______________________________________")
            appendLine("Signature: __________________________________________")
            appendLine("Goods received / differences: ________________________")
        }
    }

    fun creditNote(
        tenant: Tenant,
        sale: Sale,
        returned: SaleReturn,
        profile: ReceiptProfileSnapshot?,
        customer: Customer? = null,
    ): String {
        returnedQuantities(sale, listOf(returned))
        return buildString {
            appendHeader(tenant, sale, profile, customer, PdfDocumentKind.CREDIT_NOTE, "CN-${returned.id}")
            appendLine("Original invoice: INV-${sale.id}")
            appendLine("Return recorded: ${receiptDate(returned.createdAt)}")
            appendLine("Reason: ${returned.reason}")
            appendLine()
            returned.lines.forEach { line ->
                appendLine("${line.quantityLabel} ${line.unit} · ${line.name}")
                appendLine("  Credit for these goods: ${money(line.totalMinor)}")
            }
            appendLine()
            appendLine("Returned goods value: ${money(returned.returnedValueMinor)}")
            appendLine("Customer debt reduced: ${money(returned.creditReductionMinor)}")
            appendLine("Cash refunded: ${money(returned.refundMinor)}")
            returned.exchange?.let { exchange ->
                appendLine("Value applied to replacement: ${money(exchange.amountMinor)}")
                appendLine("Replacement receipt: ${exchange.linkedReceiptId}")
            }
            appendLine()
            appendLine("Copy of saved return ${returned.id}.")
        }
    }

    private fun StringBuilder.appendHeader(
        tenant: Tenant,
        sale: Sale,
        profile: ReceiptProfileSnapshot?,
        customer: Customer?,
        kind: PdfDocumentKind,
        reference: String,
    ) {
        require(tenant.id == sale.tenantId) { "This sale belongs to another shop." }
        require(profile == null || (profile.tenantId == tenant.id && profile.saleId == sale.id)) {
            "Contact details belong to another sale."
        }
        require(customer == null || (customer.tenantId == tenant.id && customer.id == sale.customerId)) {
            "This customer does not match the sale."
        }
        appendLine(profile?.details?.displayName ?: tenant.name)
        appendLine(kind.heading)
        appendLine("${kind.referencePrefix}$reference")
        appendLine("Sale receipt: ${sale.id}")
        appendLine("Sale recorded: ${receiptDate(sale.createdAt)}")
        profile?.details?.let { details ->
            details.address?.let { appendLine("Shop address: $it") }
            details.phone?.let { appendLine("Shop phone: $it") }
        }
        if (profile == null) appendLine("Current shop name shown. Saved contact details were unavailable.")
        customer?.let { appendLine("Current customer name: ${it.name}") }
        sale.customerId?.let { appendLine("Customer ID: $it") }
        appendLine()
    }

    private fun returnedQuantities(sale: Sale, returns: List<SaleReturn>): Map<String, Int> {
        require(returns.map { it.id }.distinct().size == returns.size) { "A saved return was included twice." }
        val originalLines = sale.lines.associateBy { it.key }
        val quantities = mutableMapOf<String, Int>()
        val values = mutableMapOf<String, Long>()
        returns.forEach { returned ->
            require(returned.saleId == sale.id && returned.tenantId == sale.tenantId) { "Return belongs to another sale." }
            returned.lines.forEach { line ->
                val original = requireNotNull(originalLines[line.key]) { "Return includes an item that was not sold." }
                require(
                    original.product.name == line.name && original.sellingUnit == line.unit &&
                        original.product.unit == line.baseUnit && original.baseUnitsPerUnit == line.baseUnitsPerUnit &&
                        original.product.quantityPrecision == line.basePrecision && original.quantityPrecision == line.quantityPrecision &&
                        original.netUnitPriceMinor == line.unitPriceMinor
                ) { "Return details do not match the saved item and unit." }
                val quantity = Math.addExact(quantities.getOrDefault(line.key, 0), line.quantity)
                val value = Math.addExact(values.getOrDefault(line.key, 0), line.totalMinor)
                require(quantity <= original.quantity && value <= original.totalMinor) { "Return exceeds the original sale line." }
                quantities[line.key] = quantity
                values[line.key] = value
            }
        }
        return quantities
    }
}
