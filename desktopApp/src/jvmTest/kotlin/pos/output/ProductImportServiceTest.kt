package pos.output

import kotlin.test.*
import pos.data.PosRepository
import pos.domain.ProductImportRow

class ProductImportServiceTest {
    @Test fun parsesTemplateAndQuotedUnicodeNamesWithoutLosingKobo() {
        val rows=ProductImportService.parse("\uFEFFname,unit,price_naira,stock\r\n\"Malt, \"\"large\"\"\",bottle,500.01,24\r\nÀkàrà,piece,0,0\r\n")
        assertEquals("Malt, \"large\"",rows[0].name)
        assertEquals(50001L,rows[0].priceMinor)
        assertEquals(24,rows[0].stock)
        assertEquals("Àkàrà",rows[1].name)
        assertEquals(1,ProductImportService.parse(ProductImportService.TEMPLATE).size)
    }
    @Test fun rejectsAmbiguousAmountsQuantitiesAndMalformedCsv() {
        val header="name,unit,price_naira,stock\n"
        listOf("Malt,bottle,1.001,2","Malt,bottle,1e3,2","Malt,bottle,10,-1","Malt,bottle,10,1.5","Malt,bottle,10,2147483648","Malt,bottle,,2","Malt,bottle,10","\"Malt,bottle,10,2","\"Malt\"x,bottle,10,2").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ProductImportService.parse(header+it) }
        }
        assertFailsWith<IllegalArgumentException> {ProductImportService.parse("unit,name,stock,price\nbottle,Malt,1,2")}
        assertFailsWith<IllegalArgumentException> {ProductImportService.parse(header)}
    }
    @Test fun importsOnlyIntoChosenTenantAndRepeatedFileDoesNotDuplicateStock() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("A");val b=db.createTenant("B")
            val rows=ProductImportService.parse(ProductImportService.TEMPLATE)
            db.importProducts(a.id,rows)
            assertEquals(1,db.products(a.id).size);assertTrue(db.products(b.id).isEmpty())
            assertFailsWith<IllegalArgumentException> {db.importProducts(a.id,rows)}
            assertEquals(24.0,db.products(a.id).single().stock)
            db.importProducts(b.id,rows)
            assertEquals(1,db.products(b.id).size)
        }
    }
    @Test fun duplicateInBatchOrExistingItemRejectsWholeImportBeforeWrites() {
        PosRepository(":memory:").use {db ->
            val t=db.createTenant("Shop")
            val row=ProductImportRow("Malt","bottle",50000,24)
            assertFailsWith<IllegalArgumentException> {db.importProducts(t.id,listOf(row,row.copy(name=" malt ",unit=" Bottle ")))}
            assertTrue(db.products(t.id).isEmpty());assertEquals(0,db.pendingSyncCount(t.id))
            db.addProduct(t.id,"Malt","bottle",50000,24.0)
            val count=db.pendingSyncCount(t.id)
            assertFailsWith<IllegalArgumentException> {db.importProducts(t.id,listOf(row.copy(name="New goods"),row))}
            assertEquals(1,db.products(t.id).size);assertEquals(count,db.pendingSyncCount(t.id))
        }
    }
}
