package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import java.nio.file.Files

class PriceListIntegrityTest {
    private fun details(product:Product,price:Long=80,minimum:Int=1,kind:PriceListKind=PriceListKind.WHOLESALE,customer:String?=null)=PriceListDetails("Wholesale",kind,customer,listOf(PriceListTier(product.id,null,minimum,product.quantityPrecision,price)))
    private fun apply(line:CartLine,quote:PriceListQuote):CartLine=quote.discount()?.let {line.copy(discountPerUnitMinor=it.amountMinor,discountReason=it.reason)} ?: line.copy(discountPerUnitMinor=0,discountReason=null)

    @Test fun listAppliesOnlyOnExplicitReviewAndSaleKeepsOriginalRevision() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,20.0)
            val list=repo.savePriceList(shop.id,"wholesale",details(item),0,"Initial wholesale setup","setup")
            assertEquals(100L,repo.products(shop.id).single().priceMinor)
            val quote=repo.quotePriceList(shop.id,list.id,listOf(CartLine(item,2)),null).single()
            val discounted=apply(CartLine(item,2),quote)
            val sale=repo.checkout(shop.id,listOf(discounted),160,operationId="sale")
            assertEquals(PriceListSource(list.id,1,1,2),PriceListSource.decode(sale.lines.single().discountReason!!))
            repo.savePriceList(shop.id,list.id,details(item,70),1,"New wholesale price","edit")
            assertEquals(80L,repo.sales(shop.id).single().lines.single().netUnitPriceMinor)
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id),"Restored")
            assertEquals(sale.lines.single().discountReason,repo.sales(restored.id).single().lines.single().discountReason)
        }
    }
    @Test fun quantityCustomerOrRevisionChangeRejectsStaleDiscountWithoutEffects() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,20.0)
            val customer=repo.addCustomer(shop.id,"Amina")
            val saved=repo.savePriceList(shop.id,"customer",details(item,80,2,PriceListKind.CUSTOMER,customer.id),0,"Customer price setup","setup")
            assertTrue(repo.checkoutPriceLists(shop.id,null).isEmpty())
            assertFails {repo.quotePriceList(shop.id,saved.id,listOf(CartLine(item,2)),null)}
            val line=apply(CartLine(item,2),repo.quotePriceList(shop.id,saved.id,listOf(CartLine(item,2)),customer.id).single())
            val before=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.checkout(shop.id,listOf(line),160,operationId="wrong-customer")}
            assertFails {repo.checkout(shop.id,listOf(line.copy(quantity=3)),240,customer.id,"wrong-quantity")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.savePriceList(shop.id,saved.id,saved.details.copy(archived=true),1,"Stop customer pricing","archive")
            val archived=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.checkout(shop.id,listOf(line),160,customer.id,"stale-revision")}
            assertEquals(archived,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun fractionalBreakAndMixedPackRulesChooseNamedUnitsExactly() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val rice=repo.addProduct(shop.id,"Rice","kg",1000,20.0,quantityPrecision=3)
            val drink=repo.addProduct(shop.id,"Drink","bottle",100,100.0)
            val unit=repo.addSellingUnit(shop.id,drink.id,"carton",12,1200,null)
            val details=PriceListDetails("Mixed wholesale",PriceListKind.WHOLESALE,tiers=listOf(PriceListTier(rice.id,null,1500,3,900),PriceListTier(drink.id,unit.id,2,0,1000)))
            repo.savePriceList(shop.id,"mixed",details,0,"Set measured prices","setup")
            val lines=listOf(CartLine(rice,1250),CartLine(drink,2),CartLine(drink,2,unit.id,unit.name,unit.priceMinor,unit.baseUnitsPerUnit))
            val quotes=repo.quotePriceList(shop.id,"mixed",lines,null)
            assertEquals(1,quotes.size);assertEquals(lines.last().key,quotes.single().lineKey)
            val larger=repo.quotePriceList(shop.id,"mixed",listOf(CartLine(rice,1500)),null).single()
            assertEquals(1350L,apply(CartLine(rice,1500),larger).totalMinor)
        }
    }
    @Test fun retriesStaleVersionsAndForgedRequestsAreSafe() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val first=repo.savePriceList(shop.id,"list",details(item),0,"Set prices","request")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            assertEquals(first,repo.savePriceList(shop.id,"list",details(item),0,"Set prices","request"))
            assertFails {repo.savePriceList(shop.id,"list",details(item,70),0,"Set prices","request")}
            assertFails {repo.savePriceList(shop.id,"list",details(item,70),0,"New prices","stale")}
            assertFails {repo.savePriceList(shop.id,"other",details(item,101).copy(name="Other"),0,"Bad markup","markup")}
            assertEquals(snapshot,repo.exportTenantSnapshot(shop.id))
            assertFails {repo.featureExecute("UPDATE price_list_changes SET reason='changed' WHERE tenant_id=?",shop.id)}
            assertFails {repo.featureExecute("DELETE FROM price_list_changes WHERE tenant_id=?",shop.id)}
            val forged=CartLine(item,1,discountPerUnitMinor=10,discountReason=PriceListSource("list",1,1,1).encode())
            assertFails {repo.checkout(shop.id,listOf(forged),90,operationId="forged")}
            assertEquals(snapshot,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun tenantPermissionsAndActorFailureProtectAllMutations() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val other=repo.createTenant("Other");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            assertFails {repo.savePriceList(other.id,"wrong",details(item),0,"Wrong tenant","wrong")}
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,p ->require(p==StaffPermission.BACKUP) {"Denied"}}
            assertFails {repo.savePriceList(shop.id,"list",details(item),0,"Set prices","new")}
            assertFails {repo.priceLists(shop.id)}
            assertFails {repo.checkoutPriceLists(shop.id,null)}
            repo.setPermissionCheck(null) // Trusted inspection after the denied operation.
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.setPermissionCheck {_,_->}
            repo.setActorProvider {throw IllegalStateException("Actor unavailable")}
            assertFails {repo.savePriceList(shop.id,"list",details(item),0,"Set prices","new")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun encryptedBackupReopenAndTamperDetectionRetainHistory() {
        val dir=Files.createTempDirectory("price-list-test-")
        try {
            val db=dir.resolve("shop.db");var tenant=""
            PosRepository(db.toString()).use {repo ->
                val shop=repo.createTenant("Shop");tenant=shop.id;val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
                repo.savePriceList(shop.id,"list",details(item),0,"Set prices","setup")
                val saleLine=apply(CartLine(item,1),repo.quotePriceList(shop.id,"list",listOf(CartLine(item,1)),null).single())
                repo.checkout(shop.id,listOf(saleLine),80,operationId="sale")
                val backup=dir.resolve("backup.posbackup");BackupService.create(repo,shop.id,"Backup-password-2026",backup)
                val recovered=repo.restoreTenantSnapshot(BackupService.read(backup,"Backup-password-2026"),"Recovered")
                assertEquals(repo.priceLists(shop.id).single().details,repo.priceLists(recovered.id).single().details)
                val snapshot=repo.exportTenantSnapshot(shop.id);val tenants=repo.tenants()
                assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-priceListSnapshotTables.toSet()),"Missing")}
                assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables+("price_lists" to snapshot.tables.getValue("price_lists").map {it+("version" to "999")})),"Tampered")}
                assertEquals(tenants,repo.tenants())
            }
            PosRepository(db.toString()).use {repo ->assertEquals(1L,repo.priceLists(tenant).single().version);assertEquals(1,repo.priceListHistory(tenant,"list").size)}
        } finally {dir.toFile().deleteRecursively()}
    }
}
