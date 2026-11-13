package pos.domain

import kotlin.test.*

class SupplierProfileTest {
    @Test fun contactDetailsValidateWithoutChangingBusinessIdentity() {
        val details=SupplierDetails("Amina supplies",normalizeNigerianPhone("08012345678"),"Amina","Lagos","14 days","Call before delivery")
        assertEquals("+2348012345678",details.phone)
        assertFails {details.copy(name=" Amina supplies")}
        assertFails {details.copy(phone="08012345678")}
        assertFails {details.copy(paymentTerms="")}
        assertFails {details.copy(notes="Hidden\ncontrol")}
    }
    @Test fun profileHistoryRequiresAReasonAndARealCreationBoundary() {
        val details=SupplierDetails("Supplier")
        SupplierProfileChange("create","shop","supplier",null,details,null,false,1,"Supplier added","2026-11-13T00:00:00Z")
        assertFails {SupplierProfileChange("edit","shop","supplier",details,details,false,false,1,"Edited details","2026-11-13T00:00:00Z")}
        assertFails {SupplierProfileChange("edit","shop","supplier",details,details,false,false,2,"","2026-11-13T00:00:00Z")}
    }
}
