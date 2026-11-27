package pos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import pos.android.documents.*
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import pos.output.BulkCatalogueCsvService
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class AndroidCsvImportTest {
    private fun fixture(work:(PosRepository,String)->Unit) {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use {repo ->work(repo,repo.createTenant("CSV test shop").id)}
    }
    @Test fun nativeAddOnlyImportKeepsExactMeasuredStockAndNeverDuplicatesOrCrossesShops()=fixture {r,t ->
        val text="name,unit,price_naira,stock,quantity_precision\r\n\"Rice, loose\",kg,1203.50,1.375,3\r\nSoap,piece,250.01,12,0\r\n"
        val review=r.reviewAndroidCsvImport(t,AndroidCsvImportKind.NEW_ITEMS,AndroidCsvImport.readUtf8(ByteArrayInputStream(text.toByteArray())))
        assertTrue(review.canApply);assertEquals(2,r.applyAndroidCsvImport(t,review,"","new-items"))
        val rice=r.products(t).single {it.name=="Rice, loose"}
        assertEquals(1375L,rice.exactStockScaled);assertEquals(120350L,rice.priceMinor)
        assertThrows(IllegalArgumentException::class.java) {r.applyAndroidCsvImport(t,review,"","retry")}
        assertEquals(2,r.products(t).size)
        val again=r.reviewAndroidCsvImport(t,AndroidCsvImportKind.NEW_ITEMS,text)
        assertFalse(again.canApply);assertEquals(2,again.issues.size)
        val other=r.createTenant("Other shop").id
        assertThrows(IllegalArgumentException::class.java) {r.applyAndroidCsvImport(other,review,"","cross-shop")}
        assertTrue(r.products(other).isEmpty())
    }
    @Test fun nativeUpdatePreviewRechecksVersionsAndRetriesOneHistoryBatchWithoutChangingStock()=fixture {r,t ->
        val a=r.addProduct(t,"Soap","piece",100,12.0);r.addProduct(t,"Rice","kg",200,5.0)
        val rows=r.bulkCatalogueTemplateRows(t).map {it.copy(values=it.values.copy(priceMinor=it.values.priceMinor+50))}
        val csv=BulkCatalogueCsvService.export(rows)
        val review=r.reviewAndroidCsvImport(t,AndroidCsvImportKind.UPDATE_ITEMS,csv)
        assertTrue(review.canApply)
        assertEquals(2,r.applyAndroidCsvImport(t,review,"New supplier prices","update-once"))
        assertEquals(2,r.applyAndroidCsvImport(t,review,"New supplier prices","update-once"))
        assertEquals(1,r.bulkCatalogueBatches(t).size);assertEquals(12L,r.products(t).single {it.id==a.id}.exactStockScaled)
        assertEquals(150L,r.products(t).single {it.id==a.id}.priceMinor)
        val stale=r.reviewAndroidCsvImport(t,AndroidCsvImportKind.UPDATE_ITEMS,r.androidCsvUpdateTemplate(t).replace("\"1.50\"","\"2.50\""))
        r.updateProduct(t,a.id,"Changed locally",a.unit,175,null,"local-edit")
        assertThrows(IllegalArgumentException::class.java) {r.applyAndroidCsvImport(t,stale,"Old review","stale-import")}
        assertEquals(175L,r.products(t).single {it.id==a.id}.priceMinor);assertEquals(1,r.bulkCatalogueBatches(t).size)
    }
    @Test fun importDenialAndDuplicateRowsDoNotPartiallyWrite()=fixture {r,t ->
        val duplicate="name,unit,price_naira,stock\nSoap,piece,1.00,1\nsoap,piece,2.00,2\n"
        val rejected=r.reviewAndroidCsvImport(t,AndroidCsvImportKind.NEW_ITEMS,duplicate)
        assertEquals(2,rejected.issues.size);assertFalse(rejected.canApply)
        val allowed=r.reviewAndroidCsvImport(t,AndroidCsvImportKind.NEW_ITEMS,"name,unit,price_naira,stock\nSoap,piece,1.00,1\n")
        r.setPermissionCheck {_,permission ->require(permission!=StaffPermission.MANAGE_STOCK) {"Stock authority revoked"}}
        assertThrows(IllegalArgumentException::class.java) {r.applyAndroidCsvImport(t,allowed,"","denied")}
        assertThrows(IllegalArgumentException::class.java) {r.reviewAndroidCsvImport(t,AndroidCsvImportKind.NEW_ITEMS,duplicate)}
        assertTrue(r.products(t).isEmpty())
    }
    @Test fun inputBoundsAndMalformedUtf8AreRejectedBeforeAnyParsing() {
        assertThrows(IllegalArgumentException::class.java) {AndroidCsvImport.readUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)))}
        assertThrows(IllegalArgumentException::class.java) {AndroidCsvImport.readUtf8(ByteArrayInputStream(ByteArray(AndroidCsvImport.maximumBytes+1) {65}))}
        assertEquals("name",AndroidCsvImport.readUtf8(ByteArrayInputStream("name".toByteArray())))
    }
}
