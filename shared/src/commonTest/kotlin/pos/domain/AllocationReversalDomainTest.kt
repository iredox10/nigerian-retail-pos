package pos.domain

import kotlin.test.*

class AllocationReversalDomainTest {
    private fun reversal(reason:String="Wrong receipt")=AllocationReversal("undo","shop","buyer","assignment",reason,"2026-11-13T10:00:00Z")

    @Test fun correctionKeepsOriginalAssignmentReferenceAndReason() {
        val value=reversal()
        assertEquals("assignment",value.allocationId)
        assertEquals("Wrong receipt",value.reason)
        assertEquals("shop",value.tenantId)
    }

    @Test fun reasonMustBeBoundedTrimmedAndPrintable() {
        listOf("","ab"," wrong receipt ","wrong\nreceipt","wrong\treceipt","x".repeat(251)).forEach { reason ->
            assertFails { reversal(reason) }
        }
        assertEquals("x".repeat(250),reversal("x".repeat(250)).reason)
    }

    @Test fun everySourceIdentifierAndTimestampIsRequired() {
        val value=reversal()
        assertFails {value.copy(id="")}
        assertFails {value.copy(tenantId="")}
        assertFails {value.copy(customerId="")}
        assertFails {value.copy(allocationId="")}
        assertFails {value.copy(createdAt="")}
    }
}
