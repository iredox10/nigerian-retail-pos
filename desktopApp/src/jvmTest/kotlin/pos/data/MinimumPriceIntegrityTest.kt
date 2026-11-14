package pos.data

import kotlin.test.*
import pos.domain.*

class MinimumPriceIntegrityTest {
    @Test fun finalBasketDiscountCannotBypassBaseOrPackMinimum() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Malt","bottle",100,100.0)
            val carton=repo.addSellingUnit(shop.id,item.id,"carton",12,1200,null)
            repo.openCashShift(shop.id,0)
            repo.saveMinimumPrice(shop.id,item.id,null,90,0,"Base selling floor","base")
            repo.saveMinimumPrice(shop.id,item.id,carton.id,1000,0,"Pack selling floor","pack")
            val line=CartLine(item,2,carton.id,carton.name,carton.priceMinor,carton.baseUnitsPerUnit,100,"Agreed carton price")
            val basket=BasketDiscount("fixed",100,"Whole sale reduction")
            val allocated=allocateBasketDiscount(listOf(line),basket)
            assertEquals(2100L,cartTotal(allocated))
            val before=repo.exportTenantSnapshot(shop.id)
            val failure=assertFails {repo.checkout(shop.id,allocated,2100,operationId="under-floor",basketDiscount=basket)}
            assertContains(failure.message.orEmpty(),"must total at least")
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            val valid=repo.checkout(shop.id,listOf(line),2200,operationId="allowed")
            assertEquals(2200L,valid.totalMinor)
            assertEquals(76L,repo.products(shop.id).single().exactStockScaled)
            val checks=repo.exportTenantSnapshot(shop.id).tables.getValue("sale_minimum_price_checks")
            assertEquals(setOf("2160","2000"),checks.map {it["required_minor"]}.toSet())
        }
    }
    @Test fun measuredSubKoboFloorCannotRoundAwayAndAllowedExactAmountSaves() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val rice=repo.addProduct(shop.id,"Rice","kg",1000,2.0,quantityPrecision=3)
            repo.openCashShift(shop.id,0)
            repo.saveMinimumPrice(shop.id,rice.id,null,900,0,"Measured goods floor")
            val line=CartLine(rice,1,discountPerUnitMinor=900,discountReason="Rounded tiny sale")
            assertEquals(0L,line.totalMinor)
            assertContains(assertFails {repo.checkout(shop.id,listOf(line),0,operationId="tiny")}.message.orEmpty(),"must total at least")
            val sale=repo.checkout(shop.id,listOf(CartLine(rice,1375,discountPerUnitMinor=99,discountReason="Within floor")),1239,operationId="measured")
            assertEquals(1239L,sale.totalMinor)
            assertEquals("1238",repo.exportTenantSnapshot(shop.id).tables.getValue("sale_minimum_price_checks").single()["required_minor"])
        }
    }
    @Test fun staleRequestsChangedRetriesAndHistoryEditsCannotChangeSavedPolicy() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val saved=repo.saveMinimumPrice(shop.id,item.id,null,90,0,"Initial minimum","request")
            val before=repo.exportTenantSnapshot(shop.id)
            assertEquals(saved,repo.saveMinimumPrice(shop.id,item.id,null,90,0,"Initial minimum","request"))
            assertFails {repo.saveMinimumPrice(shop.id,item.id,null,80,0,"Initial minimum","request")}
            assertFails {repo.saveMinimumPrice(shop.id,item.id,null,80,0,"Stale minimum","stale")}
            assertFails {repo.featureExecute("UPDATE minimum_price_changes SET reason='Rewrite'")}
            assertFails {repo.featureExecute("DELETE FROM minimum_price_changes")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.saveMinimumPrice(shop.id,item.id,null,null,1,"Stop this minimum","remove")
            assertNull(repo.minimumPricePolicies(shop.id).single().terms.minimumUnitPriceMinor)
            assertEquals(listOf(90L,null),repo.minimumPriceHistory(shop.id,item.id,null).map {it.current.minimumUnitPriceMinor})
        }
    }
    @Test fun foreignProductsArchivedPacksPermissionsAndLateActorFailureAreDeniedAtomically() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val other=repo.createTenant("Other");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val pack=repo.addSellingUnit(shop.id,item.id,"pair",2,200,null)
            assertFails {repo.saveMinimumPrice(other.id,item.id,null,90,0,"Foreign item")}
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck {_,permission ->require(permission in setOf(StaffPermission.SELL,StaffPermission.BACKUP)) {"Settings denied"}}
            assertFails {repo.saveMinimumPrice(shop.id,item.id,null,90,0,"Cashier cannot change")}
            assertFails {repo.minimumPricePolicies(shop.id)}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.setPermissionCheck(null);repo.setActorProvider {error("Actor revoked")}
            assertContains(assertFails {repo.saveMinimumPrice(shop.id,item.id,null,90,0,"Late actor failure")}.message.orEmpty(),"Actor revoked")
            repo.setActorProvider(null);assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.archiveSellingUnit(shop.id,pack.id,true)
            val archived=repo.exportTenantSnapshot(shop.id)
            assertFails {repo.saveMinimumPrice(shop.id,item.id,pack.id,150,0,"Archived pair")}
            assertEquals(archived,repo.exportTenantSnapshot(shop.id))
        }
    }
    @Test fun historicalSaleKeepsItsFloorAfterPolicyChangesAndBackupRestore() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.openCashShift(shop.id,0)
            repo.saveMinimumPrice(shop.id,item.id,null,80,0,"Initial minimum","floor")
            val sale=repo.checkout(shop.id,listOf(CartLine(item,1,discountPerUnitMinor=20,discountReason="Agreed reduction")),80,operationId="sale")
            repo.saveMinimumPrice(shop.id,item.id,null,95,1,"Raise minimum","raise")
            repo.saveMinimumPrice(shop.id,item.id,null,null,2,"Remove minimum","remove")
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val restored=repo.restoreTenantSnapshot(snapshot,"Recovered")
            assertEquals(80L,repo.sales(restored.id).single().totalMinor)
            val checks=repo.exportTenantSnapshot(restored.id).tables.getValue("sale_minimum_price_checks")
            assertEquals("1",checks.single()["policy_version"]);assertEquals("80",checks.single()["required_minor"])
            assertEquals(sale.id,checks.single()["sale_id"])
        }
    }
    @Test fun missingChecksOrChangedProjectionRejectRestoreWithoutCreatingShop() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.openCashShift(shop.id,0);repo.saveMinimumPrice(shop.id,item.id,null,90,0,"Set minimum")
            repo.checkout(shop.id,listOf(CartLine(item,1)),100,operationId="sale")
            val snapshot=repo.exportTenantSnapshot(shop.id);val before=repo.tenants()
            val bad=listOf(
                snapshot.copy(tables=snapshot.tables+("sale_minimum_price_checks" to emptyList())),
                snapshot.copy(tables=snapshot.tables+("minimum_price_policies" to snapshot.tables.getValue("minimum_price_policies").map {it+("version" to "9")})),
                snapshot.copy(tables=snapshot.tables+("sale_minimum_price_checks" to snapshot.tables.getValue("sale_minimum_price_checks").map {it+("required_minor" to "89")})),
                snapshot.copy(tables=snapshot.tables-minimumPriceSnapshotTables.toSet()),
            )
            bad.forEach {assertFails {repo.restoreTenantSnapshot(it,"Invalid")};assertEquals(before,repo.tenants())}
        }
    }
    @Test fun noPolicyLegacyBackupMayOmitNewTablesAndCaptureMarker() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Legacy");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.openCashShift(shop.id,0);repo.checkout(shop.id,listOf(CartLine(item,1)),100)
            val snapshot=repo.exportTenantSnapshot(shop.id)
            val legacy=snapshot.copy(tables=snapshot.tables.filterKeys {it !in minimumPriceSnapshotTables}.mapValues {(table,rows) ->if(table=="sales") rows.map {it-"minimum_price_captured"} else rows})
            val restored=repo.restoreTenantSnapshot(legacy,"Recovered legacy")
            assertTrue(repo.minimumPricePolicies(restored.id).isEmpty())
        }
    }
}
