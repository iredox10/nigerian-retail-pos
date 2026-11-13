package pos.output

import kotlin.test.*
import pos.domain.*

class InventoryCsvTest {
    @Test fun movementExportKeepsSignedQuantitiesAndSourceAndOpeningCertainty() {
        val product=Product("p","shop","=Soap","piece",10000,8.0,reorderLevel=10,openingStock=10.0,createdAt="2026-11-13T10:00:00Z")
        val movement=InventoryMovement("sale:s","shop","p","sale",-2.0,"s","2026-11-13T11:00:00Z","Sold goods")
        val csv=CsvService.inventoryMovements("shop",listOf(product),listOf(movement))
        assertContains(csv,"\"'=Soap\",\"piece\",\"-2.0\",\"sale\",\"s\"")
        assertContains(csv,"\"true\"")
        assertFalse(csv.contains("\"'-2.0\""))
        assertContains(CsvService.products("shop",listOf(product)),"\"10\",\"10.0\",\"2026-11-13T10:00:00Z\"")
    }
    @Test fun unknownOpeningIsNotExportedAsZeroAndForeignMovementsAreRejected() {
        val p=Product("p","shop","Soap","piece",10000,8.0)
        val cells=CsvService.products("shop",listOf(p)).lines()[1].split(',')
        assertEquals("\"\"",cells[7],"Reorder level remains unset")
        assertEquals("\"\"",cells[8],"Unknown opening quantity is blank, not zero")
        assertEquals("\"\"",cells[9],"Unknown opening date remains blank")
        val m=InventoryMovement("s","other","p","sale",-1.0,"source","2026-11-13T11:00:00Z","Sold")
        assertFailsWith<IllegalArgumentException> {CsvService.inventoryMovements("shop",listOf(p),listOf(m))}
        assertFailsWith<IllegalArgumentException> {CsvService.inventoryMovements("shop",emptyList(),listOf(m.copy(tenantId="shop")))}
    }
}
