package pos.domain

import kotlin.test.*

class QuotationTest {
    private val line=QuotationLine("rice","Rice","kg",3,null,"kg",1,3,1375,12345)
    private fun quote()=Quotation("quotation","shop",QuotationStatus.ISSUED,1,"2026-11-14T10:00:00Z","2026-11-14T10:00:00Z","2026-11-21",null,"",listOf(line))
    @Test fun frozenFractionalAndPackTermsHaveExactKoboTotals() {
        assertEquals("1.375",line.quantityLabel)
        assertEquals(16974L,line.totalMinor)
        assertEquals(16974L,quote().totalMinor)
        val pack=QuotationLine("rice","Rice","kg",3,"bag","bag",25,0,2,40000)
        assertEquals(80000L,pack.totalMinor)
        assertEquals(96974L,quote().copy(lines=listOf(line,pack)).totalMinor)
    }
    @Test fun onlyConversionEventsCarryAnExactReviewFingerprint() {
        val original=quote()
        assertFails {QuotationEvent("event",original.id,"issued","Issued terms",original.createdAt,original,"a".repeat(64))}
        val converted=original.copy(status=QuotationStatus.CONVERTED,saleId="sale")
        assertFails {QuotationEvent("sale",converted.id,"converted","Converted sale",converted.updatedAt,converted)}
        assertFails {QuotationEvent("sale",converted.id,"converted","Converted sale",converted.updatedAt,converted,"not-a-fingerprint")}
        assertEquals("a".repeat(64),QuotationEvent("sale",converted.id,"converted","Converted sale",converted.updatedAt,converted,"a".repeat(64)).requestFingerprint)
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
