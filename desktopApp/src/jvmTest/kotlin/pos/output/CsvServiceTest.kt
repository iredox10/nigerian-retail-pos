package pos.output

import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange
import java.time.LocalDate

class CsvServiceTest {
    @Test fun quotesCommaDoubleQuoteAndMultilineText() {
        val csv = CsvService.encode(listOf("Description"), listOf(listOf(CsvCell.Text("Rice, \"premium\"\n50 kg"))))
        assertEquals("\"Description\"\r\n\"Rice, \"\"premium\"\"\n50 kg\"\r\n", csv)
    }

    @Test fun neutralizesFormulaTextButPreservesSignedNumbers() {
        val values = listOf("=SUM(1,2)", "+cmd", "-formula", "@SUM(A1)", "\t=1+1", "\n=1+1", "  =1+1", "\uFEFF=1+1")
        values.forEach { value ->
            assertEquals("\"Value\"\r\n\"'$value\"\r\n", CsvService.encode(listOf("Value"), listOf(listOf(CsvCell.Text(value)))))
        }
        assertEquals("\"Amount\"\r\n\"-1500.25\"\r\n", CsvService.encode(listOf("Amount"), listOf(listOf(CsvCell.Number("-1500.25")))))
        assertFailsWith<IllegalArgumentException> { CsvCell.Number("=1+1") }
    }

    @Test fun preservesUnicodeAndRejectsInconsistentColumns() {
        assertTrue(CsvService.encode(listOf("Item"), listOf(listOf(CsvCell.Text("Ọ̀rẹ́ · ₦")))).contains("Ọ̀rẹ́ · ₦"))
        assertFailsWith<IllegalArgumentException> { CsvService.encode(listOf("A", "B"), listOf(listOf(CsvCell.Text("one")))) }
    }

    @Test fun tenantExportsCannotIncludeAnotherShopsRecords() {
        val product = Product("p", "tenant-b", "Rice", "bag", 10000, 1.0)
        assertFailsWith<IllegalArgumentException> { CsvService.products("tenant-a", listOf(product)) }
        assertFailsWith<IllegalArgumentException> { CsvService.customers("tenant-a", listOf(Customer("c", "tenant-b", "Buyer", 0))) }
    }

    @Test fun cashExportRetainsExactSignedNairaWithoutFormulaPrefix() {
        val csv = CsvService.cashEntries("shop", listOf(CashEntry("e", "shop", null, "manual", -12345, "-owner withdrawal", "2026-11-11T10:00:00Z")))
        assertTrue(csv.contains("\"-123.45\""))
        assertTrue(csv.contains("\"'-owner withdrawal\""))
        assertFalse(csv.contains("\"'-123.45\""))
    }

    @Test fun inclusiveDatesUseWatMidnightInsteadOfUtcMidnight() {
        val range = ReportDateRange(LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-11"))
        assertFalse(range.contains("2026-11-10T22:59:59Z"))
        assertTrue(range.contains("2026-11-10T23:00:00Z"))
        assertTrue(range.contains("2026-11-11T22:59:59.999Z"))
        assertFalse(range.contains("2026-11-11T23:00:00Z"))
        assertFailsWith<IllegalArgumentException> { ReportDateRange(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-11")) }
    }
}
