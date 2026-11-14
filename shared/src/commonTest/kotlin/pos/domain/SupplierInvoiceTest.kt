package pos.domain

import kotlin.test.*

class SupplierInvoiceTest {
    @Test fun duplicateKeyIgnoresCaseAndExtraWhitespaceButKeepsPunctuation() {
        assertEquals("INV 42",SupplierInvoiceDetails("inv  42","2026-11-14").canonicalNumber)
        assertNotEquals(canonicalSupplierInvoiceNumber("INV-42"),canonicalSupplierInvoiceNumber("INV 42"))
        assertFails {SupplierInvoiceDetails(" invoice","2026-11-14")}
        assertFails {SupplierInvoiceDetails("invoice","2026-02-30")}
        assertFails {SupplierInvoiceDetails("invoice","2026-11-14",duplicateReviewReason="ok")}
    }
    @Test fun evidenceNamesAndInvoiceStatesAreBounded() {
        assertFails {SupplierInvoiceAttachmentRef("a".repeat(64),"image/png",1,"../invoice.png")}
        assertFails {SupplierInvoiceAttachmentRef("a".repeat(64),"text/html",1,"invoice.html")}
        assertFails {SupplierInvoice("i","t","s",SupplierInvoiceDetails("1","2026-11-14"),SupplierInvoiceStatus.ACTIVE,1,"now","now",emptyList())}
        assertFails {SupplierInvoice("i","t","s",SupplierInvoiceDetails("1","2026-11-14"),SupplierInvoiceStatus.VOIDED,1,"now","now",listOf("r"))}
    }
}
