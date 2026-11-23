package pos.android.ui

import pos.data.SaleDraft
import pos.domain.*

/** Exact, platform-independent interpretation of a saved Android basket. Missing units fail closed. */
internal fun sellDraftLines(draft: SaleDraft, products: List<Product>, units: List<SellingUnit>): List<CartLine> {
    val choices = products.filterNot { it.archived }.flatMap { product ->
        listOf(CartLine(product, 1)) + units.filter { it.productId == product.id && !it.archived }.map {
            CartLine(product, 1, it.id, it.name, it.priceMinor, it.baseUnitsPerUnit)
        }
    }.associateBy { it.key }
    if (draft.cart.isEmpty()) return emptyList()
    val rows = draft.cart.split(';').map { encoded ->
        val separator = encoded.lastIndexOf(':')
        require(separator > 0) { "The saved basket contains an invalid item. It has not been replaced." }
        val key = encoded.substring(0, separator)
        val quantity = requireNotNull(encoded.substring(separator + 1).toIntOrNull()) { "Review the saved quantity." }
        val option = requireNotNull(choices[key]) { "An item or selling unit in this basket is unavailable. Remove it or restore its catalogue entry." }
        val discount = draft.discounts[key]
        require(discount == null || discount.cataloguePriceMinor == option.unitPriceMinor) { "A discounted item's price changed. Review or remove its discount." }
        option.copy(quantity = quantity, discountPerUnitMinor = discount?.amountMinor ?: 0,
            discountReason = discount?.reason)
    }
    require(rows.map { it.key }.distinct().size == rows.size) { "The saved basket contains duplicate lines." }
    return draft.basketDiscount?.let {
        require(draft.basketBasis == basketDiscountBasis(rows)) { "The basket changed after its discount was reviewed. Remove or review that discount." }
        allocateBasketDiscount(rows, it)
    } ?: rows
}

internal data class SellPaymentReview(
    val draft: SaleDraft, val lines: List<CartLine>, val totalMinor: Long,
    val cashMinor: Long, val cashGivenMinor: Long, val creditMinor: Long,
    val external: List<ExternalPayment>, val customerId: String?, val dueDate: String?,
) {
    val changeMinor: Long get() = cashGivenMinor - cashMinor
}

/** Adding another quantity must not silently choose between two agreed price terms. */
internal fun addSellLine(lines: List<CartLine>, added: CartLine): List<CartLine> {
    val previous = lines.firstOrNull { it.key == added.key } ?: return lines + added
    require(previous.unitPriceMinor == added.unitPriceMinor &&
        previous.discountPerUnitMinor == added.discountPerUnitMinor &&
        previous.discountReason == added.discountReason &&
        previous.sellingUnit == added.sellingUnit &&
        previous.baseUnitsPerUnit == added.baseUnitsPerUnit &&
        previous.quantityPrecision == added.quantityPrecision) {
        "This selling unit is already in the sale with different price terms. Change its existing quantity / price, or use the same agreed price and reason."
    }
    return lines.map { if (it.key == added.key) it.copy(quantity = Math.addExact(it.quantity, added.quantity)) else it }
}

internal fun reviewSellPayment(draft: SaleDraft, lines: List<CartLine>, externalAcknowledged: Boolean): SellPaymentReview {
    require(lines.isNotEmpty()) { "Add an item before taking payment." }
    val total = cartTotal(lines)
    val tender = requireNotNull(parseNaira(draft.cashText)) { "Enter a cash amount with up to two decimal places; use 0 when no cash was received." }
    val split = draft.method in setOf("split", "split_credit")
    val creditAllowed = draft.method in setOf("credit", "split_credit")
    val external = if (split) {
        require(externalAcknowledged) { "Acknowledge that this transfer or card payment is not confirmed by the app." }
        listOf(ExternalPayment(draft.externalMethod,
            requireNotNull(parseNaira(draft.externalAmountText)) { "Enter the recorded transfer or card amount." },
            draft.externalReference.trim(), draft.externalAccount.trim()))
    } else emptyList()
    val externalTotal = externalPaymentTotal(external)
    require(externalTotal <= total) { "The external portion exceeds this sale. Record advances separately." }
    val cashNeeded = total - externalTotal
    require(creditAllowed || tender >= cashNeeded) { "Still to pay ${money(cashNeeded - tender)}. Choose Pay later explicitly only if you are granting customer credit." }
    val cash = minOf(tender, cashNeeded)
    val debt = cashNeeded - cash
    require(debt == 0L || draft.customerId != null) { "Choose the customer who will pay the remaining balance." }
    require(cash > 0 || tender == 0L) { "Use 0 cash when this sale has no cash portion." }
    val due = draft.dueDateText.trim().ifEmpty { null }
    require(due == null || debt > 0) { "Remove the due date when no debt remains." }
    require(due == null || isValidCreditDueDate(due)) { "Use a valid YYYY-MM-DD due date." }
    return SellPaymentReview(draft, lines, total, cash, tender, debt, external,
        draft.customerId, due)
}
