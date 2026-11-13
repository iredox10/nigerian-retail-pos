package pos.output

import kotlin.test.*
import pos.domain.RepaymentAllocation

class AllocationCsvTest {
    @Test fun exportsExactAmountAndBothSourceReferencesWithFormulaProtection() {
        val row=RepaymentAllocation("assignment","shop","=buyer","payment","receipt",12345,"2026-11-12T12:00:00Z")
        val csv=CsvService.repaymentAllocations("shop",listOf(row))
        assertContains(csv,"\"Assigned amount NGN\"")
        assertContains(csv,"\"'=buyer\",\"payment\",\"receipt\",\"123.45\"")
        assertContains(csv,"2026-11-12T12:00:00Z")
    }
    @Test fun rejectsAnotherShopsAssignmentAndDoesNotInventEmptyRecords() {
        val row=RepaymentAllocation("assignment","other","c","payment","receipt",1,"2026-11-12T12:00:00Z")
        assertFailsWith<IllegalArgumentException> {CsvService.repaymentAllocations("shop",listOf(row))}
        assertEquals(1,CsvService.repaymentAllocations("shop",emptyList()).split("\r\n").count {it.isNotEmpty()})
    }
}
