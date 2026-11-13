package pos.data

import kotlin.test.*
import pos.domain.CartLine

/** Independent checks for base-unit history and owner-configured stock alerts. */
class InventoryHistoryIntegrityTest {
    @Test fun movementsUseBaseQuantitiesAndPreserveSourcesWithoutCountingDamagedReturns() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Market shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(shop.id,item.id,"carton",24)
            repo.openCashShift(shop.id,1000000)
            val purchase=repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","receive",carton.id,24)
            val current=repo.products(shop.id).single()
            val sale=repo.checkout(shop.id,listOf(CartLine(current,3)),150000,operationId="sale")
            val returned=repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Unopened bottle","restocked-return")
            val damaged=repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),false,"Bottle broken","damaged-return")
            val correction=repo.adjustStock(shop.id,item.id,44,"Two damaged bottles removed","count",expectedStock=46.0)
            val movements=repo.inventoryMovements(shop.id,item.id)
            assertEquals(48.0,movements.single { it.kind=="purchase" && it.sourceId==purchase.id }.quantityDelta)
            assertEquals(-3.0,movements.single { it.kind=="sale" && it.sourceId==sale.id }.quantityDelta)
            assertEquals(1.0,movements.single { it.kind=="return" && it.sourceId==returned.id }.quantityDelta)
            assertTrue(movements.none { it.kind=="return" && it.sourceId==damaged.id })
            assertEquals(-2.0,movements.single { it.kind=="count" && it.sourceId==correction.id }.quantityDelta)
            assertEquals(0.0,movements.single { it.kind=="opening" }.quantityDelta)
            assertEquals(44.0,movements.sumOf { it.quantityDelta })
            assertEquals(44.0,repo.products(shop.id).single().stock)
            assertEquals(movements.size,movements.map { it.id }.distinct().size)
            assertTrue(movements.all {it.tenantId==shop.id && it.productId==item.id})
            val before=movements
            repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","receive",carton.id,24)
            repo.checkout(shop.id,listOf(CartLine(current,3)),150000,operationId="sale")
            repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Unopened bottle","restocked-return")
            repo.adjustStock(shop.id,item.id,44,"Two damaged bottles removed","count",expectedStock=46.0)
            assertEquals(before,repo.inventoryMovements(shop.id,item.id))
        }
    }

    @Test fun stockHistoryCannotBeReadByAnotherTenantAndArchivedHistoryRemainsAvailable() {
        PosRepository(":memory:").use { repo ->
            val a=repo.createTenant("A"); val b=repo.createTenant("B")
            val item=repo.addProduct(a.id,"Soap","piece",100,5.0)
            val other=repo.addProduct(b.id,"Other soap","piece",100,99.0)
            assertFails {repo.inventoryMovements(b.id,item.id)}
            assertFails {repo.setReorderLevel(b.id,item.id,10,"foreign")}
            repo.archiveProduct(a.id,item.id,true,"archive")
            assertEquals(5.0,repo.inventoryMovements(a.id,item.id).single().quantityDelta)
            assertEquals(other.id,repo.products(b.id).single().id)
            assertEquals(99.0,repo.inventoryMovements(b.id,other.id).single().quantityDelta)
        }
    }

    @Test fun reorderNullZeroAndPositiveLevelsHaveDistinctMeaningAndNeverChangeStockOrCash() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Soap","piece",100,0.0)
            assertTrue(repo.lowStockProducts(shop.id).isEmpty())
            val cash=repo.cashEntries(shop.id); val history=repo.inventoryMovements(shop.id,item.id)
            repo.setReorderLevel(shop.id,item.id,0,"zero")
            assertEquals(listOf(item.id),repo.lowStockProducts(shop.id).map {it.id})
            val pending=repo.pendingSyncCount(shop.id)
            repo.setReorderLevel(shop.id,item.id,0,"zero")
            assertEquals(pending,repo.pendingSyncCount(shop.id))
            assertEquals(cash,repo.cashEntries(shop.id));assertEquals(history,repo.inventoryMovements(shop.id,item.id))
            assertFails {repo.setReorderLevel(shop.id,item.id,1,"zero")}
            assertFails {repo.setReorderLevel(shop.id,item.id,-1,"negative")}
            repo.setReorderLevel(shop.id,item.id,null,"off")
            assertTrue(repo.lowStockProducts(shop.id).isEmpty())
            repo.setReorderLevel(shop.id,item.id,5,"five")
            repo.receiveStock(shop.id,item.id,6,0,0,"Supplier","six")
            assertTrue(repo.lowStockProducts(shop.id).isEmpty())
            repo.adjustStock(shop.id,item.id,5,"Count","count")
            assertEquals(listOf(item.id),repo.lowStockProducts(shop.id).map {it.id})
            repo.archiveProduct(shop.id,item.id,true,"archive")
            assertTrue(repo.lowStockProducts(shop.id).isEmpty())
            repo.archiveProduct(shop.id,item.id,false,"restore")
            assertEquals(5,repo.products(shop.id).single().reorderLevel)
            assertEquals(listOf(item.id),repo.lowStockProducts(shop.id).map {it.id})
        }
    }

    @Test fun aLateRetryCannotOverwriteALaterReorderDecision() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.setReorderLevel(shop.id,item.id,10,"first")
            repo.setReorderLevel(shop.id,item.id,2,"second")
            val pending=repo.pendingSyncCount(shop.id)
            repo.setReorderLevel(shop.id,item.id,10,"first")
            assertEquals(2,repo.products(shop.id).single().reorderLevel)
            assertEquals(pending,repo.pendingSyncCount(shop.id))
            assertEquals(5.0,repo.products(shop.id).single().stock)
        }
    }

    @Test fun legacyOpeningQuantityStaysUnknownInsteadOfBeingInferredFromCurrentStock() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");val item=repo.addProduct(shop.id,"Soap","piece",100,10.0)
            repo.checkout(shop.id,listOf(CartLine(item,2)),200,operationId="sale")
            val saved=repo.exportTenantSnapshot(shop.id)
            val legacy=saved.copy(tables=saved.tables.mapValues { (table,rows) ->
                if(table=="products")rows.map {it.filterKeys { key -> key !in setOf("opening_stock","created_at","reorder_level") }} else rows
            })
            val restored=repo.restoreTenantSnapshot(legacy,"Legacy")
            val product=repo.products(restored.id).single()
            assertNull(product.openingStock);assertNull(product.createdAt)
            assertEquals(8.0,product.stock)
            val history=repo.inventoryMovements(restored.id,product.id)
            assertTrue(history.none {it.kind=="opening"})
            assertEquals(-2.0,history.single {it.kind=="sale"}.quantityDelta)
        }
    }

    @Test fun restoredMetadataCannotInventAnOversizedOpeningOrNegativeAlertLevel() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop");repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val saved=repo.exportTenantSnapshot(shop.id)
            val before=repo.tenants().size
            listOf("opening_stock" to "9007199254740992", "reorder_level" to "-1", "reorder_level" to "4294967297", "created_at" to "not-an-instant").forEach { entry ->
                val forged=saved.copy(tables=saved.tables+("products" to saved.tables.getValue("products").map {it+entry}))
                assertFails {repo.restoreTenantSnapshot(forged,"Bad metadata")}
                assertEquals(before,repo.tenants().size)
            }
            val onlyOne=saved.copy(tables=saved.tables+("products" to saved.tables.getValue("products").map {it+("opening_stock" to null)}))
            assertFails {repo.restoreTenantSnapshot(onlyOne,"Incomplete opening")}
            assertEquals(before,repo.tenants().size)
        }
    }
}
