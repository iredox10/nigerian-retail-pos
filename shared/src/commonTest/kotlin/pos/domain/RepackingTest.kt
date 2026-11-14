package pos.domain

import kotlin.test.*

class RepackingTest {
    private fun recipe()=RepackingRecipeDetails("Split bag","bag","Rice bag","bag",0,1,"loose","Loose rice","kg",3,50000)
    @Test fun declaredRatioMultipliesExactlyWithoutAssumingUnits() {
        val recipe=recipe()
        assertEquals(3L,recipe.sourceQuantity(3));assertEquals(150000L,recipe.destinationQuantity(3))
        assertFails {recipe.sourceQuantity(0)}
        assertFails {recipe.copy(sourceId="loose")}
        assertFails {recipe.copy(destinationPerBatch=Long.MAX_VALUE)}
        assertFails {recipe.destinationQuantity(Long.MAX_VALUE)}
    }
    @Test fun completedRunMustPreserveBothDeclaredMovements() {
        val run=RepackingRun("run","shop","recipe",1,recipe(),2,4,2,500,100500,"Split two bags","2026-11-14T10:00:00Z","a".repeat(64))
        assertEquals(100500L,run.destinationAfter)
        assertFails {run.copy(sourceAfter=3)}
        assertFails {run.copy(destinationAfter=100501)}
        assertFails {run.copy(sourceBefore=1)}
        assertFails {run.copy(reason="")}
    }
}
