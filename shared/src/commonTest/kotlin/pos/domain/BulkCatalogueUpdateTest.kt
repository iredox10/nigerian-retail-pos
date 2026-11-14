package pos.domain

import kotlin.test.*

class BulkCatalogueUpdateTest {
    private val values=BulkCatalogueValues("Rice","kg",3,50000,null,ProductMetadata(),null)
    @Test fun previewCountsRealChangesWithoutInventingMissingGoods() {
        val row=BulkCatalogueUpdateRow(2,"rice","a".repeat(64),values)
        assertFalse(BulkCataloguePreview(listOf(BulkCataloguePreviewRow(row,values)),emptyList()).canApply)
        assertTrue(BulkCataloguePreview(listOf(BulkCataloguePreviewRow(row,values.copy(priceMinor=40000))),emptyList()).canApply)
        assertFalse(BulkCataloguePreview(listOf(BulkCataloguePreviewRow(row,null)),listOf(BulkCatalogueIssue(2,"Missing"))).canApply)
    }
    @Test fun invalidPricesIdentifiersAndUnsafeTextAreRejected() {
        assertFails {values.copy(priceMinor=-1)}
        assertFails {values.copy(name="")}
        assertFails {values.copy(barcode="bad\ncode")}
        assertFails {BulkCatalogueUpdateRow(2,"rice","wrong",values)}
        assertFails {BulkCatalogueUpdateRow(1,"rice","a".repeat(64),values)}
    }
}
