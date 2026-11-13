package pos.domain

import kotlin.test.*

class DiscountTest {
    private val item=Product("p","shop","Rice","bag",100_000,10.0)

    @Test fun discountUsesIntegerKoboAndDoesNotChangeStockUnits() {
        val line=CartLine(item,3,discountPerUnitMinor=10_001,discountReason="Agreed price")
        assertEquals(89_999L,line.netUnitPriceMinor)
        assertEquals(300_000L,line.grossTotalMinor)
        assertEquals(30_003L,line.discountTotalMinor)
        assertEquals(269_997L,cartTotal(listOf(line)))
        assertEquals(3L,line.baseQuantity)
    }

    @Test fun invalidAmountsMissingReasonsAndControlCharactersAreRejected() {
        listOf(-1L,100_001L).forEach { amount -> assertFails { CartLine(item,1,discountPerUnitMinor=amount,discountReason="Agreed price") } }
        listOf(null,"","ab"," padded ","new\nline","x".repeat(251)).forEach { reason ->
            assertFails { CartLine(item,1,discountPerUnitMinor=1,discountReason=reason) }
        }
        assertFails { CartLine(item,1,discountReason="Unused reason") }
    }

    @Test fun receiptGrossCannotOverflowEvenWhenDiscountWouldMakeNetFit() {
        val expensive=item.copy(priceMinor=Long.MAX_VALUE)
        val line=CartLine(expensive,2,discountPerUnitMinor=Long.MAX_VALUE,discountReason="Owner sample")
        assertFails { cartTotal(listOf(line)) }
        val half=item.copy(priceMinor=Long.MAX_VALUE/2+1)
        assertFails { cartTotal(listOf(CartLine(half,1),CartLine(half.copy(id="q"),1))) }
    }
}
