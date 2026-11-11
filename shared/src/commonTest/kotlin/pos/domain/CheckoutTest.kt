package pos.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckoutTest {
    private val bottle = Product("bottle", "shop-a", "Malt drink", "bottle", 50_000, 240.0)
    private val customer = Customer("customer", "shop-a", "Amina", 0)
    private fun finish(lines: List<CartLine> = listOf(CartLine(bottle, 2)), cash: Long = 200_000,
                       buyer: Customer? = null, credit: Boolean = false) = checkout(
        "shop-a", "sale-1", lines, cash, buyer, credit, "2026-11-11T12:00:00Z",
    )

    @Test fun cashGivenIsNotRevenue() {
        val result = finish()
        assertEquals(100_000L, result.sale.totalMinor)
        assertEquals(100_000L, result.sale.cashMinor)
        assertEquals(100_000L, result.changeMinor)
        assertEquals(0L, result.sale.creditMinor)
    }

    @Test fun shortPaymentDoesNotSilentlyCreateDebt() {
        assertFailsWith<IllegalArgumentException> { finish(cash = 50_000, buyer = customer) }
        assertFailsWith<IllegalArgumentException> { finish(cash = 50_000, credit = true) }
        val sale = finish(cash = 50_000, buyer = customer, credit = true).sale
        assertEquals(50_000L, sale.creditMinor)
        assertEquals(customer.id, sale.customerId)
    }

    @Test fun crossTenantProductsAndCustomersAreRejected() {
        assertFailsWith<IllegalArgumentException> { finish(listOf(CartLine(bottle.copy(tenantId = "shop-b"), 1))) }
        assertFailsWith<IllegalArgumentException> { finish(buyer = customer.copy(tenantId = "shop-b")) }
    }

    @Test fun stockAndRepeatedLinesCannotBypassValidation() {
        assertFailsWith<IllegalArgumentException> { finish(listOf(CartLine(bottle.copy(stock = 1.0), 2))) }
        assertFailsWith<IllegalArgumentException> { finish(listOf(CartLine(bottle, 1), CartLine(bottle, 1))) }
    }

    @Test fun invalidAndOverflowAmountsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CartLine(bottle, 0) }
        assertFailsWith<IllegalArgumentException> { finish(cash = -1) }
        assertFailsWith<IllegalArgumentException> { finish(emptyList()) }
        assertFailsWith<IllegalArgumentException> { finish(listOf(CartLine(bottle.copy(priceMinor = Long.MAX_VALUE), 2))) }
        assertFailsWith<IllegalArgumentException> { Product("p", "shop-a", "Item", "piece", 1, Double.NaN) }
    }

    @Test fun exactKoboFormattingHandlesAllLongValues() {
        assertEquals("₦0.00", money(0))
        assertEquals("₦0.01", money(1))
        assertEquals("₦12,345,678.90", money(1_234_567_890))
        assertEquals("−₦1.05", money(-105))
        assertEquals("−₦92,233,720,368,547,758.08", money(Long.MIN_VALUE))
    }
}
