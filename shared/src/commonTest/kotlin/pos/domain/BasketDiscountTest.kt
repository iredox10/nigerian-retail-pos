package pos.domain

import kotlin.test.*

class BasketDiscountTest {
    private fun line(id:String,price:Long)=CartLine(Product(id,"shop",id,"piece",price,100.0),1)
    @Test fun largestRemaindersTieByStableKeyAndIgnoreInputOrdering() {
        val lines=listOf(line("c",1),line("a",1),line("b",1))
        val rule=BasketDiscount("fixed",2,"Agreed total")
        val expected=mapOf("a" to 1L,"b" to 1L,"c" to 0L)
        assertEquals(expected,allocateBasketDiscount(lines,rule).associate {it.key to it.basketDiscountMinor})
        assertEquals(expected,allocateBasketDiscount(lines.reversed(),rule).associate {it.key to it.basketDiscountMinor})
    }
    @Test fun allocationNearLongMaximumDoesNotOverflowIntermediateProducts() {
        val lines=listOf(line("large",Long.MAX_VALUE-1),line("small",1))
        val allocated=allocateBasketDiscount(lines,BasketDiscount("fixed",Long.MAX_VALUE-1,"Large exact reduction"))
        assertEquals(Long.MAX_VALUE-2,allocated.first().basketDiscountMinor)
        assertEquals(1L,allocated.last().basketDiscountMinor)
        assertEquals(1L,cartTotal(allocated))
        assertEquals(Long.MAX_VALUE,allocateBasketDiscount(lines,BasketDiscount("percentage",10_000,"Full reduction")).sumOf {it.basketDiscountMinor})
    }
    @Test fun zeroPriceLinesNeverReceiveDiscountAndFullDiscountIsSupported() {
        val allocated=allocateBasketDiscount(listOf(line("free",0),line("paid",135)),BasketDiscount("percentage",10_000,"Complimentary sale"))
        assertEquals(0L,allocated.first().basketDiscountMinor)
        assertEquals(0L,cartTotal(allocated))
        assertEquals(135L,allocated.last().basketDiscountMinor)
    }
    @Test fun fractionalPartialRefundsConserveTheActuallyPaidLine() {
        val item=Product("rice","shop","Rice","kg",101,10.0,quantityPrecision=3)
        val original=allocateBasketDiscount(listOf(CartLine(item,1375)),BasketDiscount("fixed",5,"Agreed reduction")).single()
        assertEquals(139L,original.beforeBasketMinor)
        assertEquals(134L,original.totalMinor)
        val cumulative=listOf(0,1,375,500,1375).map(original::cumulativeRefundMinor)
        assertEquals(original.totalMinor,cumulative.zipWithNext().sumOf {(before,after) -> after-before})
        assertEquals(0L,cumulative.first())
        assertEquals(134L,cumulative.last())
    }
    @Test fun percentagesRoundOnceOnBasketAndApplyAfterItemDiscounts() {
        val source=listOf(line("a",101).copy(discountPerUnitMinor=1,discountReason="Agreed item price"),line("b",101))
        val allocated=allocateBasketDiscount(source,BasketDiscount("percentage",1250,"Whole sale reduction"))
        assertEquals(25L,allocated.sumOf {it.basketDiscountMinor})
        assertEquals(176L,cartTotal(allocated))
        assertEquals(26L,cartDiscountTotal(allocated))
    }
    @Test fun oversizedUnallocatedDraftCanReachTheExistingTotalValidation() {
        val source=line("a",Long.MAX_VALUE).copy(quantity=2)
        assertFails {cartTotal(listOf(source))}
        assertFails {allocateBasketDiscount(listOf(source),BasketDiscount("fixed",1,"Review amount"))}
    }
    @Test fun invalidRulesAndOverAllocationAreRejected() {
        assertFails {BasketDiscount("fixed",0,"Reason")}
        assertFails {BasketDiscount("percentage",10001,"Reason")}
        assertFails {allocateBasketDiscount(listOf(line("a",1)),BasketDiscount("fixed",2,"Reason"))}
        assertFails {allocateBasketDiscount(listOf(line("a",0)),BasketDiscount("percentage",100,"Reason"))}
        assertFails {line("a",1).copy(basketDiscountMinor=2)}
    }
    @Test fun saleRequiresItsRuleAndExactDeterministicShares() {
        val lines=allocateBasketDiscount(listOf(line("a",100),line("b",100)),BasketDiscount("fixed",1,"Agreed reduction"))
        assertFails {Sale("s","shop",lines,199,199,0,null,"2026-11-13T00:00:00Z")}
        assertFails {Sale("s","shop",lines,199,199,0,null,"2026-11-13T00:00:00Z",basketDiscount=BasketDiscount("fixed",2,"Different reduction"))}
    }
    @Test fun reviewedBasisUsesUnambiguousFieldsWithUnusualMerchantText() {
        val source=line("a",100)
        val first=source.copy(product=source.product.copy(name="Name|with\nslash\\"),discountPerUnitMinor=1,discountReason="Reason|with slash\\")
        val second=first.copy(discountReason="Reason||with slash\\")
        assertNotEquals(basketDiscountBasis(listOf(first)),basketDiscountBasis(listOf(second)))
        assertEquals(basketDiscountBasis(listOf(first, line("b",1))),basketDiscountBasis(listOf(line("b",1),first)))
    }
    @Test fun reviewedBasisChangesWithQuantityPriceOrItemDiscount() {
        val line=line("a",100)
        assertNotEquals(basketDiscountBasis(listOf(line)),basketDiscountBasis(listOf(line.copy(quantity=2))))
        assertNotEquals(basketDiscountBasis(listOf(line)),basketDiscountBasis(listOf(line.copy(discountPerUnitMinor=1,discountReason="Item reduction"))))
    }
}
