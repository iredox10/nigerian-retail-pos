package pos.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.platform.AndroidPrivateFiles
import pos.android.storage.AndroidSqliteDriver
import pos.android.ui.archiveAndroidCatalogueItem
import pos.android.ui.saveAndroidCatalogueItem
import pos.data.*
import pos.domain.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCatalogueParityTest {
    private fun fixture(run:(PosRepository)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=java.io.File(context.cacheDir,"catalogue-${UUID.randomUUID()}").apply {mkdirs()}
        val previous=System.getProperty("pos.dataDir")
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        AndroidPrivateFiles.install()
        System.setProperty("pos.dataDir",directory.path)
        try {PosRepository(directory.resolve("pos.db").path).use(run)}
        finally {if(previous==null)System.clearProperty("pos.dataDir") else System.setProperty("pos.dataDir",previous);directory.deleteRecursively()}
    }

    @Test fun itemAndMetadataEditIsAtomicKeepsSavedReceiptsAndRestoresHistory()=fixture {repo ->
        val shop=repo.createTenant("Shop").id
        val original=repo.addProduct(shop,"Soap","piece",45000,10.0,"SOAP-1")
        val duplicate=repo.addProduct(shop,"Other","piece",10000,2.0)
        repo.updateProductMetadata(shop,duplicate.id,ProductMetadata(sku="TAKEN"),"other-sku")
        val shift=repo.openCashShift(shop,100000)
        val sale=repo.checkout(shop,listOf(CartLine(original,2)),90000,operationId="old-sale")
        val current=repo.products(shop).single {it.id==original.id}
        val before=repo.exportTenantSnapshot(shop)
        assertThrows(IllegalArgumentException::class.java) {
            repo.saveAndroidCatalogueItem(shop,current,"Partial edit must roll back",99000,"SOAP-NEW",ProductMetadata(sku="TAKEN"),"invalid-edit")
        }
        assertEquals(before,repo.exportTenantSnapshot(shop))
        val changed=repo.saveAndroidCatalogueItem(shop,current,"Laundry soap",50000,"SOAP-NEW",
            ProductMetadata("SOAP-SKU","Household","Local brand",listOf("washing soap"),true),"edit")
        assertEquals(8L,changed.exactStockScaled)
        assertEquals(190000L,repo.expectedCash(shop,shift.id))
        assertEquals(50000L,changed.priceMinor)
        assertEquals("Household",changed.category)
        assertTrue(changed.favourite)
        assertEquals(1,repo.productMetadataChanges(shop,original.id).size)
        val retained=repo.sales(shop).single()
        assertEquals(sale.totalMinor,retained.totalMinor)
        assertEquals("Soap",retained.lines.single().product.name)
        assertEquals(45000L,retained.lines.single().unitPriceMinor)
        val after=repo.exportTenantSnapshot(shop)
        assertEquals(changed,repo.saveAndroidCatalogueItem(shop,current,"Laundry soap",50000,"SOAP-NEW",changed.metadata(),"edit"))
        assertEquals(after,repo.exportTenantSnapshot(shop))
        val restored=repo.restoreTenantSnapshot(after,"Recovered")
        assertEquals(changed.metadata(),repo.products(restored.id).single {it.id==original.id}.metadata())
        assertEquals(1,repo.productMetadataChanges(restored.id,original.id).size)
        assertEquals("Soap",repo.sales(restored.id).single().lines.single().product.name)
    }

    @Test fun packageDefinitionsUseExactMeasuredStockAndArchivedPackagesPreserveReceipts()=fixture {repo ->
        val shop=repo.createTenant("Shop").id
        val item=repo.addProduct(shop,"Rice","kg",120000,10.0,quantityPrecision=3)
        val selling=repo.addSellingUnit(shop,item.id,"small sack",2,210000,"SACK-2","sell-pack")
        val buying=repo.addPurchaseUnit(shop,item.id,"delivery bag",5,"buy-pack")
        val shift=repo.openCashShift(shop,0)
        val loose=CartLine(item,1250)
        val pack=CartLine(item,1,selling.id,selling.name,selling.priceMinor,selling.baseUnitsPerUnit)
        val sale=repo.checkout(shop,listOf(loose,pack),360000,operationId="mixed-sale")
        assertEquals(360000L,sale.totalMinor)
        assertEquals(6750L,repo.products(shop).single().exactStockScaled)
        val receipt=repo.receiveStock(shop,item.id,2,500000,0,"Wholesaler","buy-stock",buying.id,5)
        assertEquals(10000L,receipt.baseQuantity)
        assertEquals(16750L,repo.products(shop).single().exactStockScaled)
        assertEquals(360000L,repo.expectedCash(shop,shift.id))
        repo.archiveSellingUnit(shop,selling.id,true)
        val freshItem=repo.products(shop).single()
        assertThrows(IllegalArgumentException::class.java) {
            repo.checkout(shop,listOf(pack.copy(product=freshItem)),210000,operationId="archived-pack-sale")
        }
        repo.archivePurchaseUnit(shop,buying.id,true)
        assertThrows(IllegalArgumentException::class.java) {repo.receiveStock(shop,item.id,1,500000,0,"Wholesaler","archived-buy",buying.id,5)}
        assertThrows(IllegalArgumentException::class.java) {repo.addPurchaseUnit(shop,item.id,"delivery bag",8,"reuse-name")}
        val snapshot=repo.exportTenantSnapshot(shop)
        val restored=repo.restoreTenantSnapshot(snapshot,"Recovered")
        assertEquals(16750L,repo.products(restored.id).single().exactStockScaled)
        assertEquals(2,repo.sales(restored.id).single().lines.size)
        assertEquals(210000L,repo.sales(restored.id).single().lines.single {it.sellingUnitId!=null}.unitPriceMinor)
        assertTrue(repo.sellingUnits(restored.id,includeArchived=true).single().archived)
        assertTrue(repo.purchaseUnits(restored.id,includeArchived=true).single().archived)
    }

    @Test fun staleCatalogueArchiveAndPermissionDenialLeaveHistoryUnchanged()=fixture {repo ->
        val shop=repo.createTenant("Shop").id
        val other=repo.createTenant("Other").id
        val original=repo.addProduct(shop,"Oil","bottle",20000,4.0)
        repo.updateProduct(shop,original.id,"Fresh oil",original.unit,25000,null,"outside-edit")
        val before=repo.exportTenantSnapshot(shop)
        assertThrows(IllegalArgumentException::class.java) {repo.saveAndroidCatalogueItem(shop,original,"Stale",30000,null,ProductMetadata(),"stale")}
        assertThrows(IllegalArgumentException::class.java) {repo.archiveAndroidCatalogueItem(shop,original,true,"stale-archive")}
        assertThrows(IllegalArgumentException::class.java) {repo.archiveAndroidCatalogueItem(other,original,true,"wrong-shop")}
        assertEquals(before,repo.exportTenantSnapshot(shop))
        val current=repo.products(shop).single()
        repo.archiveAndroidCatalogueItem(shop,current,true,"archive")
        assertTrue(repo.products(shop).isEmpty())
        val archived=repo.products(shop,true).single()
        assertThrows(IllegalArgumentException::class.java) {repo.saveAndroidCatalogueItem(shop,archived,"Cannot edit archived",30000,null,ProductMetadata(),"archived-edit")}
        repo.archiveAndroidCatalogueItem(shop,archived,false,"restore")
        assertEquals(4L,repo.products(shop).single().exactStockScaled)
        val beforeDenied=repo.exportTenantSnapshot(shop)
        repo.setPermissionCheck {_,permission ->require(permission!=StaffPermission.MANAGE_STOCK)}
        assertThrows(IllegalArgumentException::class.java) {repo.saveAndroidCatalogueItem(shop,repo.products(shop).single(),"Denied",100,null,ProductMetadata(),"denied")}
        repo.setPermissionCheck(null)
        assertEquals(beforeDenied,repo.exportTenantSnapshot(shop))
    }
}
