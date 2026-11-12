package pos.data

import kotlin.test.*
import pos.domain.CartLine

/** Independent integrity checks for receiving packs into base selling stock. */
class PurchaseUnitIntegrityTest {
    @Test fun cartonsIncreaseBottleStockWhileCashAndSupplierDebtStayAtPurchaseValue() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Market shop")
            val bottle=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(shop.id,bottle.id,"carton",24,"define-carton")
            val drawer=repo.openCashShift(shop.id,5_000_000)
            val purchase=repo.receiveStock(shop.id,bottle.id,2,1_000_000,500_000,"Wholesaler","buy-cartons",purchaseUnitId=carton.id,expectedBaseUnitsPerUnit=24)
            assertEquals(48L,purchase.baseQuantity)
            assertEquals("bottle",purchase.baseUnit)
            assertEquals("carton",purchase.unit)
            assertEquals(2,purchase.quantity)
            assertEquals(2_000_000L,purchase.totalCostMinor)
            assertEquals(1_500_000L,repo.supplierOutstanding(shop.id,purchase.id))
            assertEquals(4_500_000L,repo.expectedCash(shop.id,drawer.id))
            val current=repo.products(shop.id).single()
            assertEquals(48.0,current.stock)
            repo.checkout(shop.id,listOf(CartLine(current,3)),150_000,operationId="three-bottles")
            assertEquals(45.0,repo.products(shop.id).single().stock)
            assertEquals(4_650_000L,repo.expectedCash(shop.id,drawer.id))
            assertEquals(1_500_000L,repo.supplierOutstanding(shop.id,purchase.id))
        }
    }

    @Test fun receivingWholePackagesDoesNotTruncateLegacyFractionalBaseStock() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Legacy measured stock","unit",100,0.5)
            val pack=repo.addPurchaseUnit(shop.id,item.id,"pack",12)
            repo.receiveStock(shop.id,item.id,2,0,0,"Supplier",purchaseUnitId=pack.id)
            assertEquals(24.5,repo.products(shop.id).single().stock)
            assertEquals(24L,repo.stockReceipts(shop.id).single().baseQuantity)
        }
    }

    @Test fun unsupportedConvertedQuantityRejectsTheEntireReceiptBeforeWritingCashOrDebt() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Screws","piece",1,0.0)
            val huge=repo.addPurchaseUnit(shop.id,item.id,"bulk lot",Int.MAX_VALUE)
            repo.openCashShift(shop.id,1000)
            val beforePending=repo.pendingSyncCount(shop.id)
            val beforeCash=repo.cashEntries(shop.id)
            assertFails {
                repo.receiveStock(shop.id,item.id,Int.MAX_VALUE,0,0,"Supplier","too-large",purchaseUnitId=huge.id)
            }
            assertEquals(0.0,repo.products(shop.id).single().stock)
            assertTrue(repo.stockReceipts(shop.id).isEmpty())
            assertEquals(beforeCash,repo.cashEntries(shop.id))
            assertEquals(beforePending,repo.pendingSyncCount(shop.id))
        }
    }

    @Test fun conversionCannotRoundAwayAFractionalLegacyStockBalance() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val originalStock=4_503_599_627_370_495.5
            val item=repo.addProduct(shop.id,"Legacy measured goods","unit",1,originalStock)
            val pair=repo.addPurchaseUnit(shop.id,item.id,"pair",2)
            val pending=repo.pendingSyncCount(shop.id)
            assertFails { repo.receiveStock(shop.id,item.id,1,0,0,"Supplier","precision-loss",purchaseUnitId=pair.id) }
            assertEquals(originalStock,repo.products(shop.id).single().stock)
            assertTrue(repo.stockReceipts(shop.id).isEmpty())
            assertEquals(pending,repo.pendingSyncCount(shop.id))
        }
    }

    @Test fun aPackageFromAnotherProductOrTenantCannotDetermineStockMeaning() {
        PosRepository(":memory:").use { repo ->
            val a=repo.createTenant("Shop A");val b=repo.createTenant("Shop B")
            val malt=repo.addProduct(a.id,"Malt","bottle",50000,0.0)
            val soap=repo.addProduct(a.id,"Soap","piece",10000,0.0)
            val other=repo.addProduct(b.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(a.id,malt.id,"carton",24)
            assertFails { repo.receiveStock(a.id,soap.id,1,0,0,"Supplier",purchaseUnitId=carton.id) }
            assertFails { repo.receiveStock(b.id,other.id,1,0,0,"Supplier",purchaseUnitId=carton.id) }
            assertFails { repo.purchaseUnits(b.id,malt.id) }
            assertFails { repo.archivePurchaseUnit(b.id,carton.id,true) }
            assertTrue(repo.stockReceipts(a.id).isEmpty());assertTrue(repo.stockReceipts(b.id).isEmpty())
            assertTrue(repo.products(a.id).all { it.stock==0.0 })
        }
    }

    @Test fun committedReceiptReplayWorksAfterPackageAndProductArchiveWithoutAddingStockTwice() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(shop.id,item.id,"carton",24)
            val original=repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","receipt",purchaseUnitId=carton.id,expectedBaseUnitsPerUnit=24)
            repo.archivePurchaseUnit(shop.id,carton.id,true)
            repo.archiveProduct(shop.id,item.id,true,"archive-product")
            val pending=repo.pendingSyncCount(shop.id)
            assertEquals(original,repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","receipt",purchaseUnitId=carton.id,expectedBaseUnitsPerUnit=24))
            assertEquals(48.0,repo.products(shop.id,true).single().stock)
            assertEquals(pending,repo.pendingSyncCount(shop.id))
            assertEquals(1,repo.stockReceipts(shop.id).size)
            assertFails { repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","receipt",purchaseUnitId=carton.id,expectedBaseUnitsPerUnit=12) }
            assertFails { repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","new-receipt",purchaseUnitId=carton.id) }
        }
    }

    @Test fun restoredReceiptCannotSilentlyAdoptAChangedPackageConversion() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(shop.id,item.id,"carton",24)
            repo.receiveStock(shop.id,item.id,2,1000000,0,"Supplier","receipt",purchaseUnitId=carton.id)
            val saved=repo.exportTenantSnapshot(shop.id)
            val unitTable=saved.tables.keys.single { it=="purchase_units" }
            val changedUnit=saved.copy(tables=saved.tables+(unitTable to saved.tables.getValue(unitTable).map { it+("base_units_per_unit" to "12") }))
            val before=repo.tenants().size
            assertFails { repo.restoreTenantSnapshot(changedUnit,"Tampered conversion") }
            assertEquals(before,repo.tenants().size)
            val changedReceipt=saved.copy(tables=saved.tables+("stock_receipts" to saved.tables.getValue("stock_receipts").map { it+("base_units_per_unit" to "12") }))
            assertFails { repo.restoreTenantSnapshot(changedReceipt,"Tampered receipt") }
            assertEquals(before,repo.tenants().size)
        }
    }

    @Test fun restoredConversionCannotTruncateFromLongIntoInt() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(shop.id,item.id,"carton",24)
            repo.receiveStock(shop.id,item.id,2,0,0,"Supplier","receipt",purchaseUnitId=carton.id)
            val saved=repo.exportTenantSnapshot(shop.id)
            val forged=saved.copy(tables=saved.tables.mapValues { (table,rows) ->
                if(table in setOf("purchase_units","stock_receipts")) rows.map { it+("base_units_per_unit" to "4294967297") } else rows
            })
            val before=repo.tenants().size
            assertFails { repo.restoreTenantSnapshot(forged,"Oversized factor") }
            assertEquals(before,repo.tenants().size)
        }
    }

    @Test fun restoredPackageCannotReuseTheBaseUnitNameWithADifferentMeaning() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            val carton=repo.addPurchaseUnit(shop.id,item.id,"carton",24)
            repo.receiveStock(shop.id,item.id,2,0,0,"Supplier","receipt",purchaseUnitId=carton.id)
            val saved=repo.exportTenantSnapshot(shop.id)
            val forged=saved.copy(tables=saved.tables.mapValues { (table,rows) ->
                when(table) {
                    "purchase_units" -> rows.map { it+("name" to "bottle") }
                    "stock_receipts" -> rows.map { it+("unit" to "bottle") }
                    else -> rows
                }
            })
            val before=repo.tenants().size
            assertFails { repo.restoreTenantSnapshot(forged,"Ambiguous buying unit") }
            assertEquals(before,repo.tenants().size)
        }
    }

    @Test fun baseUnitReceiptsCannotHideANontrivialConversionWithoutAPackageReference() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Malt","bottle",50000,0.0)
            repo.receiveStock(shop.id,item.id,2,0,0,"Supplier","base-receipt")
            val saved=repo.exportTenantSnapshot(shop.id)
            val forged=saved.copy(tables=saved.tables+("stock_receipts" to saved.tables.getValue("stock_receipts").map { it+("base_units_per_unit" to "24")+("purchase_unit_id" to null) }))
            val before=repo.tenants().size
            assertFails { repo.restoreTenantSnapshot(forged,"Unlinked conversion") }
            assertEquals(before,repo.tenants().size)
        }
    }
}
