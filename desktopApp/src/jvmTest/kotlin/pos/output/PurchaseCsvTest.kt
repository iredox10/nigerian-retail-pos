package pos.output

import kotlin.test.*
import pos.domain.StockReceipt

class PurchaseCsvTest {
    @Test fun exportedPurchaseKeepsBuyingCostAndBaseStockSeparate() {
        val receipt=StockReceipt("receipt","shop","malt","Malt","carton",2,1050000,2100000,500000,"Wholesaler","2026-11-12T10:00:00Z","bottle",24,"carton-definition")
        val csv=CsvService.stockReceipts("shop",listOf(receipt))
        assertTrue(csv.contains("\"Buying unit\""))
        assertTrue(csv.contains("\"Base quantity added\""))
        assertTrue(csv.contains("\"carton\",\"2\",\"10500.00\",\"21000.00\",\"5000.00\",\"16000.00\",\"Wholesaler\",\"bottle\",\"24\",\"48\",\"carton-definition\""))
    }
    @Test fun legacyBaseUnitReceiptExportsOneToOneAndRejectsAnotherShop() {
        val receipt=StockReceipt("r","shop","soap","Soap","piece",5,12000,60000,60000,"Supplier","2026-11-12T10:00:00Z")
        val csv=CsvService.stockReceipts("shop",listOf(receipt))
        assertTrue(csv.contains("\"piece\",\"1\",\"5\",\"\""))
        assertFailsWith<IllegalArgumentException> {CsvService.stockReceipts("other-shop",listOf(receipt))}
    }
}
