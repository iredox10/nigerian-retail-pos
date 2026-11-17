package pos.output

import kotlin.test.*
import pos.domain.OwnerCashRecord
import pos.screens.ReportDateRange
import java.time.LocalDate

class OwnerCashCsvTest {
    private val original=OwnerCashRecord("a","shop","CONTRIBUTION",500,"=Owner","Working cash","shift","entry-a","2026-11-13T23:15:00Z",0)
    private val correction=OwnerCashRecord("b","shop","REVERSAL",-500,"=Owner","Cash returned","shift","entry-b","2026-11-14T10:00:00Z",500,"a")
    @Test fun exportKeepsSignedCashAndLinksWithoutClassifyingIncomeOrExpenses() {
        val csv=OwnerCashCsv.create("shop",listOf(original,correction))
        assertContains(csv,"Signed drawer change NGN")
        assertContains(csv,"\"5.00\"");assertContains(csv,"\"-5.00\"")
        assertContains(csv,"\"'=Owner\"");assertContains(csv,"Owner cash correction")
        assertContains(csv,"Correction of record ID")
    }
    @Test fun datesUseWatAndCorrectionCanShowWhenOriginalFallsOutsideSelectedPeriod() {
        val day=LocalDate.parse("2026-11-14")
        val csv=OwnerCashCsv.create("shop",listOf(original,correction),ReportDateRange(day,day))
        assertContains(csv,"Working cash") // 23:15 UTC is next day in WAT
        assertContains(csv,"Cash returned")
        val earlier=OwnerCashCsv.create("shop",listOf(original,correction),ReportDateRange(day.minusDays(1),day.minusDays(1)))
        assertFalse(earlier.contains("Working cash"));assertFalse(earlier.contains("Cash returned"))
    }
    @Test fun foreignDuplicateMissingOrInvalidCorrectionSourcesReject() {
        assertFails {OwnerCashCsv.create("other",listOf(original))}
        assertFails {OwnerCashCsv.create("shop",listOf(original,original))}
        assertFails {OwnerCashCsv.create("shop",listOf(correction))}
        assertFails {OwnerCashCsv.create("shop",listOf(original,correction.copy(amountMinor=-400)))}
    }
}
