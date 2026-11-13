package pos.domain

import kotlin.test.*

class PercentageDiscountTest {
    @Test fun parsesExactPercentagesAndRejectsUnsupportedInput() {
        mapOf("0" to 0," 12.5 " to 1250,"0.01" to 1,"99.99" to 9999,"100.00" to 10000).forEach { (text,expected) ->
            assertEquals(expected,parseDiscountPercentage(text)?.basisPoints)
        }
        listOf("", "-1", "+1", "10%", ".5", "1.", "100.01", "1.001", "1e1", "1,0", "NaN", "９").forEach {
            assertNull(parseDiscountPercentage(it),it)
        }
        assertEquals("12.5%",DiscountPercentage(1250).display())
        assertEquals("0.01%",DiscountPercentage(1).display())
        assertEquals("100%",DiscountPercentage(10000).display())
    }

    @Test fun roundsDiscountPerUnitHalfUpAndKeepsGrossAndNetReconciled() {
        assertEquals(0L,DiscountPercentage(4999).amountMinor(1))
        assertEquals(1L,DiscountPercentage(5000).amountMinor(1))
        assertEquals(2L,DiscountPercentage(5000).amountMinor(3))
        val discount=DiscountPercentage(1250).unitDiscount(101,"Agreed price")
        assertEquals(13L,discount.amountMinor)
        assertEquals("12.5% off: Agreed price",discount.reason)
        val product=Product("item","shop","Soap","piece",101,3.0)
        val line=CartLine(product,3,discountPerUnitMinor=discount.amountMinor,discountReason=discount.reason)
        assertEquals(264L,line.totalMinor)
        assertEquals(303L,line.grossTotalMinor)
        assertEquals(39L,line.discountTotalMinor)
    }

    @Test fun largePricesAndBoundaryPercentagesDoNotOverflow() {
        assertEquals(0L,DiscountPercentage(0).amountMinor(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE,DiscountPercentage(10000).amountMinor(Long.MAX_VALUE))
        assertEquals(4_611_686_018_427_387_904L,DiscountPercentage(5000).amountMinor(Long.MAX_VALUE))
        assertEquals(922_337_203_685_478L,DiscountPercentage(1).amountMinor(Long.MAX_VALUE))
        assertEquals(0L,DiscountPercentage(10000).amountMinor(0))
        assertFails {DiscountPercentage(-1)}
        assertFails {DiscountPercentage(10001)}
        assertFails {DiscountPercentage(100).amountMinor(-1)}
    }

    @Test fun zeroSavingsAndMissingOrOverlongReasonsCannotCreateDiscounts() {
        assertFails {DiscountPercentage(0).unitDiscount(100,"Agreed price")}
        assertFails {DiscountPercentage(1).unitDiscount(1,"Agreed price")}
        listOf("", "ab", " bad ", "bad\nreason", "x".repeat(250)).forEach {
            assertFails {DiscountPercentage(1000).unitDiscount(100,it)}
        }
        val maximum=250-"10% off: ".length
        assertEquals(250,DiscountPercentage(1000).unitDiscount(100,"x".repeat(maximum)).reason.length)
        assertFails {DiscountPercentage(1000).unitDiscount(100,"x".repeat(maximum+1))}
        val free=DiscountPercentage(10000).unitDiscount(100,"Owner sample")
        assertEquals(100L,free.amountMinor)
    }
}
