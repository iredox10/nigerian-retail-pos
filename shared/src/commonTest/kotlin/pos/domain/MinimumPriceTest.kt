package pos.domain

import kotlin.test.*

class MinimumPriceTest {
    @Test fun measuredFloorRoundsUpOnceWithoutIntermediateOverflow() {
        assertEquals(1L,minimumPriceTotalMinor(Quantity(1,3),100))
        assertEquals(124L,minimumPriceTotalMinor(Quantity(1375,3),90))
        assertEquals(90L,minimumPriceTotalMinor(Quantity(1000,3),90))
        assertEquals(Long.MAX_VALUE,minimumPriceTotalMinor(Quantity(1000,3),Long.MAX_VALUE))
        assertEquals(9223372036854776L,minimumPriceTotalMinor(Quantity(1,3),Long.MAX_VALUE))
        assertFails {minimumPriceTotalMinor(Quantity(2,0),Long.MAX_VALUE)}
    }
    @Test fun baseAndPackFloorsAreIndependentAndFinalBasketNetIsCompared() {
        val item=Product("item","shop","Malt","bottle",100,100.0)
        val pack=CartLine(item,2,"carton","carton",1200,12,discountPerUnitMinor=100,discountReason="Agreed price",basketDiscountMinor=100)
        val base=MinimumPriceTerms(item.id,item.name,null,"bottle",0,0,1,90)
        val carton=MinimumPriceTerms(item.id,item.name,"carton","carton",0,0,12,1000)
        assertEquals(2160L,base.minimumFor(pack));assertEquals(2000L,carton.minimumFor(pack))
        assertEquals(2100L,pack.totalMinor)
        assertTrue(pack.totalMinor<base.minimumFor(pack)!!)
        assertNull(carton.minimumFor(CartLine(item,1)))
        assertNull(base.copy(minimumUnitPriceMinor=null).minimumFor(pack))
    }
    @Test fun staleUnitMeaningCannotReinterpretSavedFloor() {
        val item=Product("item","shop","Rice","kg",100,100.0,quantityPrecision=3)
        val floor=MinimumPriceTerms(item.id,item.name,null,"kg",3,3,1,90)
        assertFails {floor.minimumFor(CartLine(item.copy(unit="bag"),1))}
        assertFails {floor.minimumFor(CartLine(item.copy(quantityPrecision=2),1))}
    }
}
