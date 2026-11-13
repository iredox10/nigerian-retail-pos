package pos.output

import kotlin.test.*
import pos.domain.AllocationReversal
import pos.domain.RepaymentAllocation

class AllocationCorrectionCsvTest {
    private val assignment=RepaymentAllocation("assignment","shop","customer","payment","receipt",250_000,"2026-11-01T10:00:00Z")
    private val correction=AllocationReversal("correction","shop","customer","assignment","=Wrong receipt","2026-11-13T10:00:00Z")

    @Test fun originalAssignmentIsPreservedAndCurrentUndoReasonIsFormulaSafe() {
        val csv=CsvService.repaymentAllocations("shop",listOf(assignment),listOf(correction))
        assertContains(csv,"\"2500.00\",\"2026-11-01T10:00:00Z\",\"Undone\"")
        assertContains(csv,"\"correction\",\"2026-11-13T10:00:00Z\",\"'=Wrong receipt\"")
        assertContains(CsvService.repaymentAllocations("shop",listOf(assignment)),"\"Active\",\"\",\"\",\"\"")
    }

    @Test fun correctionExportUsesItsOwnDateAndKeepsOriginalLink() {
        val csv=CsvService.allocationReversals("shop",listOf(correction))
        assertContains(csv,"\"correction\",\"assignment\",\"customer\",\"'=Wrong receipt\",\"2026-11-13T10:00:00Z\"")
        assertEquals(1,CsvService.allocationReversals("shop",emptyList()).split("\r\n").count {it.isNotEmpty()})
    }

    @Test fun exportsRejectForeignMismatchedAndDuplicateCorrections() {
        assertFails { CsvService.repaymentAllocations("other",listOf(assignment),listOf(correction)) }
        assertFails { CsvService.repaymentAllocations("shop",listOf(assignment),listOf(correction.copy(customerId="different"))) }
        assertFails { CsvService.repaymentAllocations("shop",emptyList(),listOf(correction)) }
        assertFails { CsvService.repaymentAllocations("shop",listOf(assignment),listOf(correction,correction.copy(id="second"))) }
        assertFails { CsvService.allocationReversals("shop",listOf(correction.copy(tenantId="other"))) }
        assertFails { CsvService.allocationReversals("shop",listOf(correction,correction)) }
    }
}
