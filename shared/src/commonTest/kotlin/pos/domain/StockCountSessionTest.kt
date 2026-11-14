package pos.domain

import kotlin.test.*

class StockCountSessionTest {
    private val line=StockCountLine("rice","Rice","kg",3,10250,"a".repeat(64),9750,0)
    @Test fun differencesKeepMeasuredUnitsAndExplicitZero() {
        assertEquals("10.25",line.baselineLabel)
        assertEquals("9.75",line.countedLabel)
        assertEquals("−0.5",line.differenceLabel)
        assertEquals("0",line.copy(countedScaled=0).countedLabel)
        assertNull(line.copy(countedScaled=null).differenceLabel)
    }
    @Test fun finalStatesRequireReasonTimeAndCompletePostedLines() {
        val draft=StockCountSession("count","shop","Shelf",StockCountStatus.DRAFT,"",1,"2026-11-14T00:00:00Z",null,listOf(line.copy(countedScaled=null)))
        assertFails {draft.copy(status=StockCountStatus.POSTED,completedAt="2026-11-14T01:00:00Z")}
        assertFails {draft.copy(status=StockCountStatus.CANCELLED)}
        assertFails {draft.copy(lines=listOf(line,line))}
        assertFails {line.copy(precision=7)}
        assertFails {line.copy(countedScaled=-1)}
    }
}
