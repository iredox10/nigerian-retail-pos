package pos.domain

import kotlin.test.*

class QuotationTest {
    private val line=QuotationLine("rice","Rice","kg",3,null,"kg",1,3,1375,12345)
    private fun quote()=Quotation("quotation","shop",QuotationStatus.ISSUED,1,"2026-11-14T10:00:00Z","2026-11-14T10:00:00Z","2026-11-21",null,"",listOf(line))
    @Test fun frozenFractionalAndPackTermsHaveExactKoboTotals() {
        assertEquals("1.375",line.quantityLabel)
        assertEquals(16974,line.totalMinor)
        assertEquals(16974,quote().totalMinor)
        val pack=QuotationLine("rice","Rice","kg",3,"bag","bag",25,0,2,40000)
        assertEquals(80000,pack.totalMinor)
        assertEquals(96974,quote().copy(lines=listOf(line,pack)).totalMinor)
    }
    @Test fun rejectsInvalidPrecisionRepeatedGoodsInvalidExpiryAndFalseConversion() {
        assertFails {line.copy(quantityPrecision=2)}
        assertFails {line.copy(baseUnitsPerUnit=25)}
        assertFails {quote().copy(lines=listOf(line,line))}
        assertFails {quote().copy(expiresOn="2026-02-30")}
        assertFails {quote().copy(status=QuotationStatus.CONVERTED)}
        assertFails {quote().copy(saleId="sale")}
        assertFails {QuotationCustomer(null,"Customer",phone="invalid")}
    }
}
