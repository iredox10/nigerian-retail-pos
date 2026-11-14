package pos.output

import kotlin.test.*
import pos.domain.*

class BulkCatalogueCsvServiceTest {
    private val row=BulkCatalogueUpdateRow(2,"rice","a".repeat(64),BulkCatalogueValues("=Rice, premium","kg",3,123456,"barcode",ProductMetadata("'=SKU","Food","Market",listOf("=Local rice","'Quoted name"),true),0))
    @Test fun editableExportRoundTripsQuotesAliasesAndReversibleSpreadsheetProtection() {
        val csv=BulkCatalogueCsvService.export(listOf(row))
        assertTrue(csv.contains("'=Rice, premium"))
        assertTrue(csv.contains("''=SKU"))
        val parsed=BulkCatalogueCsvService.parse(csv)
        assertTrue(parsed.issues.isEmpty(),parsed.issues.toString())
        assertEquals(listOf(row),parsed.rows)
    }
    @Test fun independentRecordErrorsAreRetainedRatherThanSilentlySkippingRows() {
        fun cells(id:String,price:String="500.00",favourite:String="false",reorder:String="")=listOf(id,"a".repeat(64),"Rice","kg","3",price,"","","","","",favourite,reorder).joinToString(",")
        val csv=BulkCatalogueCsvService.headers.joinToString(",")+"\n"+listOf(cells("one",price="-1"),cells("two",favourite="maybe"),cells("three",reorder="-2"),cells("four")).joinToString("\n")
        val parsed=BulkCatalogueCsvService.parse(csv)
        assertEquals(listOf(2,3,4),parsed.issues.map {it.record})
        assertEquals(listOf("four"),parsed.rows.map {it.productId})
    }
    @Test fun repeatedStableIdsAreReportedOnEveryAffectedRecord() {
        val csv=BulkCatalogueCsvService.export(listOf(row,row.copy(record=3)))
        assertEquals(listOf(2,3),BulkCatalogueCsvService.parse(csv).issues.map {it.record})
    }
    @Test fun malformedOrOversizedFilesCannotReachReview() {
        assertFails {BulkCatalogueCsvService.parse("wrong,header\n1,2\n")}
        assertFails {BulkCatalogueCsvService.parse(BulkCatalogueCsvService.headers.joinToString(",")+"\n\"unclosed")}
        assertFails {BulkCatalogueCsvService.parse("x".repeat(BulkCatalogueCsvService.maximumBytes+1))}
    }
}
