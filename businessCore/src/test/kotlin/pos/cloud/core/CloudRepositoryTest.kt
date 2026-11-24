package pos.cloud.core

import pos.data.*
import pos.domain.*
import pos.cloud.protocol.*
import kotlin.test.*

class CloudRepositoryTest {
    @Test fun concurrentOfflineSalesMergeStockCashAndGeneratedLineIdsWithoutDuplicatingAfterCrash() {
        PosRepository(":memory:").use {a ->PosRepository(":memory:").use {b ->
            val shop=a.createTenant("Shop")
            val product=a.addProduct(shop.id,"Soap","piece",100,10.0)
            a.openCashShift(shop.id,1000)
            val other=b.restoreTenantSnapshot(a.exportTenantSnapshot(shop.id),"Shop")
            val schema=a.cloudBusinessSchema()
            val base=a.cloudBusinessSnapshot(shop.id,"business")
            assertEquals(base,b.cloudBusinessSnapshot(other.id,"business"))
            a.checkout(shop.id,listOf(CartLine(product,1)),100,operationId="sale-a")
            val sent=a.cloudBusinessSnapshot(shop.id,"business")
            b.checkout(other.id,listOf(CartLine(b.products(other.id).single(),2)),200,operationId="sale-b")
            val delta=CloudMerge.diff(base,b.cloudBusinessSnapshot(other.id,"business"),schema)
            val merged=requireNotNull(CloudMerge.merge(sent,delta,schema).snapshot)
            assertEquals(setOf(product.id),validateCloudBusinessMerge(sent,merged,delta,0,emptyMap()))
            a.recordExpense(shop.id,"Transport","After upload",50,"new-local")
            a.applyCloudBusinessSnapshot(shop.id,"checkpoint",sent,merged)
            assertEquals(7L,a.products(shop.id).single().exactStockScaled)
            assertEquals(2,a.sales(shop.id).size)
            assertEquals(1250L,a.expectedCash(shop.id,a.activeCashShift(shop.id)!!.id))
            a.applyCloudBusinessSnapshot(shop.id,"checkpoint",sent,merged)
            assertEquals(7L,a.products(shop.id).single().exactStockScaled)
            assertEquals(1250L,a.expectedCash(shop.id,a.activeCashShift(shop.id)!!.id))
            assertEquals(setOf("sale-a","sale-b"),a.sales(shop.id).map {it.id}.toSet())
        }}
    }
    @Test fun stockCannotChangeWithoutMatchingSourceEventsAndPermissionsRemainEnforced() {
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Shop");r.addProduct(t.id,"Rice","kg",100,10.0,quantityPrecision=3)
            val before=r.cloudBusinessSnapshot(t.id,"business")
            val changed=before.copy(tables=before.tables+("products" to before.tables.getValue("products").map {it+("stock" to "9")+("stock_scaled" to "9000")}))
            val delta=CloudMerge.diff(before,changed,r.cloudBusinessSchema())
            assertFailsWith<IllegalArgumentException> {validateCloudBusinessMerge(before,changed,delta,0,emptyMap())}
            val saved=r.exportTenantSnapshot(t.id)
            assertFails {r.applyCloudBusinessSnapshot(t.id,"bad",before,changed)}
            assertEquals(saved,r.exportTenantSnapshot(t.id))
            r.setPermissionCheck {_,p ->require(p!=StaffPermission.SELL)}
            assertFails {r.cloudBusinessSnapshot(t.id,"business")}
            assertFails {r.applyCloudBusinessSnapshot(t.id,"denied",before,before)}
        }
    }
    @Test fun countRejectsCloudInterveningMovementEvenWhenNetStockIsUnchanged() {
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Shop");val p=r.addProduct(t.id,"Soap","piece",100,10.0)
            val before=r.cloudBusinessSnapshot(t.id,"business")
            r.adjustStock(t.id,p.id,8,"Shelf count","count",10.0)
            val after=r.cloudBusinessSnapshot(t.id,"business")
            assertFails {validateCloudBusinessMerge(before,after,CloudMerge.diff(before,after,r.cloudBusinessSchema()),0,mapOf(p.id to 1L))}
        }
    }
    @Test fun deliveryFlagsStayDeviceLocalAndImportRetryDoesNotDuplicateShops() {
        PosRepository(":memory:").use {r ->
            val t=r.createTenant("Shop");r.addProduct(t.id,"Soap","piece",100,10.0)
            val before=r.cloudBusinessSnapshot(t.id,"business")
            r.applyCloudBusinessSnapshot(t.id,"first",before,before)
            assertEquals(before,r.cloudBusinessSnapshot(t.id,"business"))
            val copied=r.importCloudBusinessShop(t.id,"import-once",before,"Downloaded shop") {}
            assertEquals(copied,r.importCloudBusinessShop(t.id,"import-once",before,"Other name") {error("must not provision twice")})
            assertEquals(2,r.tenants().size)
            assertEquals(10L,r.products(copied.id).single().exactStockScaled)
        }
    }

}
