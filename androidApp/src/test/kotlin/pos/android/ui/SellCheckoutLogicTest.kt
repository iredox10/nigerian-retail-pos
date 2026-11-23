package pos.android.ui

import org.junit.Test
import org.junit.Assert.*
import pos.data.SaleDraft
import pos.domain.*

class SellCheckoutLogicTest {
    private val measured = Product("rice", "shop", "Rice", "kg", 12345, 10.0, quantityPrecision = 3)
    private val bottle = Product("drink", "shop", "Drink", "bottle", 50000, 48.0)
    private val pack = SellingUnit("carton", "shop", bottle.id, "carton", "bottle", 24, 1100000, null, false, "2026-11-23T12:00:00Z")
    private fun denied(block: () -> Unit) { try { block(); fail("Expected validation to reject the operation") } catch (_: IllegalArgumentException) { } }

    @Test fun measuredAndPackDraftsRetainExactUnitAndMoneySemantics() {
        val draft = SaleDraft("rice:1250;drink~carton:1", "sale", "12000")
        val lines = sellDraftLines(draft, listOf(measured, bottle), listOf(pack))
        assertEquals(1250L, lines[0].baseQuantity)
        assertEquals(24L, lines[1].baseQuantity)
        assertEquals("1.25", lines[0].quantityLabel)
        assertEquals(1115431L, cartTotal(lines))
        denied { sellDraftLines(draft, listOf(measured, bottle), emptyList()) }
        denied { sellDraftLines(draft.copy(cart = "rice:1;rice:2"), listOf(measured), emptyList()) }
    }

    @Test fun shortCashNeverCreatesDebtUnlessExplicitlySelectedWithNamedCustomer() {
        val lines = listOf(CartLine(bottle, 2))
        val cash = SaleDraft("drink:2", "sale", "500")
        denied { reviewSellPayment(cash, lines, false) }
        denied { reviewSellPayment(cash.copy(method = "credit"), lines, false) }
        val credit = reviewSellPayment(cash.copy(method = "credit", customerId = "amina"), lines, false)
        assertEquals(50000L, credit.creditMinor)
        assertEquals(50000L, credit.cashMinor)
        assertEquals(0L, credit.changeMinor)
        val tender = reviewSellPayment(cash.copy(cashText = "1200"), lines, false)
        assertEquals(0L, tender.creditMinor)
        assertEquals(20000L, tender.changeMinor)
        assertNull(tender.customerId)
    }

    @Test fun externalPaymentNeedsExplicitAcknowledgementAndNeverClaimsConfirmation() {
        val lines = listOf(CartLine(bottle, 2))
        val draft = SaleDraft("drink:2", "sale", "200", method = "split", externalAmountText = "800", externalReference = "BANK-1", externalAccount = "Shop bank")
        denied { reviewSellPayment(draft, lines, false) }
        val review = reviewSellPayment(draft, lines, true)
        assertEquals(20000L, review.cashMinor)
        assertEquals(80000L, review.external.single().amountMinor)
        assertEquals("Transfer not confirmed", review.external.single().label)
        assertEquals(0L, review.creditMinor)
        denied { reviewSellPayment(draft.copy(externalAmountText = "1200"), lines, true) }
    }

    @Test fun priceChangesAndStaleBasketDiscountCannotSilentlyRewriteSavedTerms() {
        val draft = SaleDraft("drink:2", "sale", discounts = mapOf("drink" to UnitDiscount(50000, 1000, "Agreed discount")))
        assertEquals(98000L, cartTotal(sellDraftLines(draft, listOf(bottle), emptyList())))
        denied { sellDraftLines(draft, listOf(bottle.copy(priceMinor = 60000)), emptyList()) }
        val basket = BasketDiscount("fixed", 1000, "Sale offer")
        denied { sellDraftLines(draft.copy(basketDiscount = basket, basketBasis = "stale"), listOf(bottle), emptyList()) }
    }

    @Test fun selectedCustomerSurvivesFullCashAndExternalPaymentWithoutCreatingDebt() {
        val lines = listOf(CartLine(bottle, 2))
        val cash = SaleDraft("drink:2", "cash-sale", "1200", customerId = "amina")
        val paid = reviewSellPayment(cash, lines, false)
        assertEquals("amina", paid.customerId)
        assertEquals(0L, paid.creditMinor)
        assertEquals(20000L, paid.changeMinor)
        val external = reviewSellPayment(cash.copy(cashText = "0", method = "split",
            externalAmountText = "1000", externalReference = "BANK-2", externalAccount = "Shop bank"), lines, true)
        assertEquals("amina", external.customerId)
        assertEquals(0L, external.creditMinor)
        assertEquals(0L, external.cashMinor)
    }

    @Test fun addingMatchingTermsConservesMeasuredQuantitiesAndRejectsConflictingPrices() {
        val first = CartLine(measured, 1250, discountPerUnitMinor = 345, discountReason = "Agreed market price")
        val other = CartLine(bottle, 1)
        val added = first.copy(quantity = 375)
        val merged = addSellLine(listOf(first, other), added)
        assertEquals(listOf(first.copy(quantity = 1625), other), merged)
        assertEquals(1625L, merged.first().baseQuantity)
        assertEquals("Agreed market price", merged.first().discountReason)
        denied { addSellLine(listOf(first), added.copy(discountPerUnitMinor = 445)) }
        denied { addSellLine(listOf(first), added.copy(discountReason = "Different agreement")) }
        denied { addSellLine(listOf(first), CartLine(measured, 375)) }
        assertEquals(listOf(other, added), addSellLine(listOf(other), added))
        try { addSellLine(listOf(first.copy(quantity = Int.MAX_VALUE)), added); fail("Quantity overflow accepted") }
        catch (_: ArithmeticException) { }
    }
}
