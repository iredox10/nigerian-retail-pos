package pos.domain

import kotlin.test.*

class PriceListTest {
    @Test fun highestEligibleBreakKeepsMeasuredUnitPrecision() {
        val item=Product("rice","shop","Rice","kg",1000,10.0,quantityPrecision=3)
        val small=PriceListTier("rice",null,500,3,950)
        val large=PriceListTier("rice",null,2000,3,900)
        val list=PriceListDetails("Wholesale",PriceListKind.WHOLESALE,tiers=listOf(large,small))
        assertNull(list.tierFor(CartLine(item,499)))
        assertEquals(small,list.tierFor(CartLine(item,1500)))
        assertEquals(large,list.tierFor(CartLine(item,2000)))
        val quote=PriceListQuote("list","Wholesale",2,PriceListKind.WHOLESALE,null,"rice",2000,large,1000)
        assertEquals(100L,quote.discount()?.amountMinor)
        assertEquals(PriceListSource("list",2,2000,2000),PriceListSource.decode(quote.sourceReason))
        assertTrue(quote.sourceReason.length<=250)
        assertFails {PriceListSource.decode("@price-list/1/list/02/2000/2000")}
    }
    @Test fun invalidScopesDuplicateAndIncreasingBreaksAreRejected() {
        val tier=PriceListTier("soap",null,1,0,100)
        assertFails {PriceListDetails("Retail",PriceListKind.RETAIL,"customer",listOf(tier))}
        assertFails {PriceListDetails("Customer",PriceListKind.CUSTOMER,tiers=listOf(tier))}
        assertFails {PriceListDetails("Retail",PriceListKind.RETAIL,tiers=listOf(tier,tier))}
        assertFails {PriceListDetails("Retail",PriceListKind.RETAIL,tiers=listOf(tier,tier.copy(minimumQuantity=10,unitPriceMinor=101)))}
        assertFails {PriceListQuote("list","Retail",1,PriceListKind.RETAIL,null,"soap",1,tier,99)}
    }
    @Test fun packBreaksAndBaseBreaksNeverMix() {
        val item=Product("drink","shop","Drink","bottle",100,100.0)
        val base=PriceListTier("drink",null,12,0,90)
        val pack=PriceListTier("drink","carton",2,0,1000)
        val details=PriceListDetails("Wholesale",PriceListKind.WHOLESALE,tiers=listOf(base,pack))
        assertNull(details.tierFor(CartLine(item,2)))
        assertEquals(pack,details.tierFor(CartLine(item,2,"carton","carton",1200,12)))
        assertFails {pack.copy(quantityPrecision=3)}
    }
}
