package pos.domain

import kotlin.test.*

class ShopProfileTest {
    @Test fun receiptDetailsRequireExplicitSupportedPaperAndPrintableContactFields() {
        assertEquals("80mm",ShopDetails("Adewale Stores","+2348012345678","Main Market","Thank you","80mm").receiptPaper)
        assertFails {ShopDetails(" Shop ")}
        assertFails {ShopDetails("Shop",phone="08012345678")}
        assertFails {ShopDetails("Shop",address="Line one\nLine two")}
        assertFails {ShopDetails("Shop",receiptPaper="Thermal")}
        assertFails {ShopDetails("Shop",footer="x".repeat(501))}
    }
    @Test fun historyAndReceiptSnapshotsKeepTheirOwnIdentityAndVersion() {
        val details=ShopDetails("Adewale Stores")
        assertEquals(0L,ReceiptProfileSnapshot("shop","sale",details,0,"2026-11-14T00:00:00Z").profileVersion)
        assertFails {ShopProfileChange("change","shop",details,details,1,"Changed details","now")}
        assertFails {ShopProfileChange("change","shop",null,details,2,"Changed details","now")}
        assertFails {ShopProfileChange("change","shop",null,details,1,"x","now")}
    }
}
