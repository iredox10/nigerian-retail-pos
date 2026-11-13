package pos.output

import kotlin.test.*
import pos.domain.Product

class CatalogueMetadataExportTest {
    @Test fun metadataExportsPreserveTextAndNeutralizeSpreadsheetFormulas() {
        val item=Product("item","shop","Rice","bag",100,5.0,
            sku="=SKU",category="Food",brand="@Brand",aliases=listOf("Local rice","Bag, small"),favourite=true)
        val csv=CsvService.products("shop",listOf(item))
        assertContains(csv,"\"SKU\",\"Category\",\"Brand\",\"Aliases\",\"Favourite\"")
        assertContains(csv,"\"'=SKU\",\"Food\",\"'@Brand\",\"Local rice | Bag, small\",\"true\"")
        assertFails {CsvService.products("other",listOf(item))}
    }
}
