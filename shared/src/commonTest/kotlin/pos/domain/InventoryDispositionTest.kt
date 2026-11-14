package pos.domain

import kotlin.test.*

class InventoryDispositionTest {
    private val removal=InventoryDisposition("id","shop","rice","Rice","kg",3,125,10125,10000,InventoryDispositionCategory.DAMAGE,"Bag torn","During unloading","2026-11-14T00:00:00Z","a".repeat(64))
    @Test fun snapshotsRetainExactMeasuredQuantityAndClassification() {
        assertEquals("0.125",removal.quantityLabel)
        assertEquals("10.125",removal.beforeLabel)
        assertEquals("10",removal.afterLabel)
        assertEquals("Damage",removal.category.label)
    }
    @Test fun impossibleAmountsAndUntraceableReasonsAreRejected() {
        assertFails {removal.copy(quantityScaled=0)}
        assertFails {removal.copy(quantityScaled=10126,afterScaled=-1)}
        assertFails {removal.copy(afterScaled=10001)}
        assertFails {removal.copy(reason="  ")}
        assertFails {removal.copy(notes="x\nsecret")}
        assertFails {removal.copy(precision=7)}
    }
}
