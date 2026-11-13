package pos.data

import kotlin.test.*
import pos.domain.CartLine

/** Selling packs are priced independently, but all inventory remains in base units. */
class SellingUnitIntegrityTest {
    @Test fun mixedCartonAndLooseBottleSaleUsesEachPriceAndDeductsCombinedBaseStock() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Market shop")
            val bottle = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(shop.id, bottle.id, "carton", 24, 1_100_000)
            val drawer = repo.openCashShift(shop.id, 5_000_000)
            val pack = CartLine(bottle, 1, carton.id, carton.name, carton.priceMinor, carton.baseUnitsPerUnit)
            val loose = CartLine(bottle, 3)
            assertEquals(24L, pack.baseQuantity)
            assertEquals(1_100_000L, pack.totalMinor)
            assertNotEquals(pack.key, loose.key)
            val sale = repo.checkout(shop.id, listOf(pack, loose), 1_250_000, operationId = "mixed-sale")
            assertEquals(1_250_000L, sale.totalMinor)
            assertEquals(33.0, repo.products(shop.id).single().stock)
            assertEquals(6_250_000L, repo.expectedCash(shop.id, drawer.id))
            assertEquals(setOf(pack.key, loose.key), sale.lines.map { it.key }.toSet())
            assertEquals(sale.copy(lines = sale.lines.map { it.copy(product = it.product.copy(stock = 0.0, barcode = null, openingStock = null, createdAt = null)) }), repo.sales(shop.id).single())
            val movements = repo.inventoryMovements(shop.id, bottle.id).filter { it.sourceId == sale.id && it.kind == "sale" }
            assertEquals(1, movements.size)
            assertEquals(-27.0, movements.single().quantityDelta)
        }
    }

    @Test fun returnsKeepPackAndLooseLimitsSeparateAndAggregateBaseStockMovement() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(shop.id, item.id, "carton", 24, 1_100_000)
            repo.openCashShift(shop.id, 5_000_000)
            val pack = CartLine(item, 1, carton.id, carton.name, carton.priceMinor, carton.baseUnitsPerUnit)
            val sale = repo.checkout(shop.id, listOf(pack, CartLine(item, 3)), 1_250_000)
            assertEquals(mapOf(pack.key to 1, item.id to 3), repo.returnableQuantities(shop.id, sale.id))
            val returned = repo.returnItems(shop.id, sale.id, mapOf(pack.key to 1, item.id to 1), true, "Unopened goods", "mixed-return")
            assertEquals(1_150_000L, returned.refundMinor)
            assertEquals(58.0, repo.products(shop.id).single().stock)
            assertEquals(24L, returned.lines.single { it.key == pack.key }.baseQuantity)
            assertEquals("bottle", returned.lines.single { it.key == pack.key }.baseUnit)
            assertEquals("carton", returned.lines.single { it.key == pack.key }.unit)
            assertEquals(mapOf(pack.key to 0, item.id to 2), repo.returnableQuantities(shop.id, sale.id))
            val movements = repo.inventoryMovements(shop.id, item.id).filter { it.kind == "return" && it.sourceId == returned.id }
            assertEquals(1, movements.size)
            assertEquals(25.0, movements.single().quantityDelta)
            assertFails { repo.returnItems(shop.id, sale.id, mapOf(pack.key to 1), true, "Already returned") }
            assertEquals(58.0, repo.products(shop.id).single().stock)
        }
    }

    @Test fun archivedPackStillRefundsHistoricalPriceAndRetriesNeverRepeatCashOrStock() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(shop.id, item.id, "carton", 24, 1_100_000)
            repo.openCashShift(shop.id, 5_000_000)
            val line = CartLine(item, 1, carton.id, carton.name, carton.priceMinor, carton.baseUnitsPerUnit)
            val sale = repo.checkout(shop.id, listOf(line), 1_100_000, operationId = "pack-sale")
            repo.archiveSellingUnit(shop.id, carton.id, true)
            repo.archiveProduct(shop.id, item.id, true, "archive-product")
            val returned = repo.returnItems(shop.id, sale.id, mapOf(line.key to 1), true, "Unopened carton", "pack-return")
            assertEquals(1_100_000L, returned.refundMinor)
            assertEquals(60.0, repo.products(shop.id, true).single().stock)
            val pending = repo.pendingSyncCount(shop.id)
            val cash = repo.cashEntries(shop.id)
            val history = repo.inventoryMovements(shop.id, item.id)
            assertEquals(repo.sales(shop.id).single(), repo.checkout(shop.id, listOf(line), 1_100_000, operationId = "pack-sale"))
            assertEquals(returned, repo.returnItems(shop.id, sale.id, mapOf(line.key to 1), true, "Unopened carton", "pack-return"))
            assertEquals(pending, repo.pendingSyncCount(shop.id))
            assertEquals(cash, repo.cashEntries(shop.id))
            assertEquals(history, repo.inventoryMovements(shop.id, item.id))
            assertFails { repo.checkout(shop.id, listOf(line), 1_100_000, operationId = "new-sale") }
            assertFails { repo.returnItems(shop.id, sale.id, mapOf(item.id to 1), true, "Unopened carton", "pack-return") }
        }
    }

    @Test fun mixedLinesAndDuplicatePackLinesCannotBypassAvailableBaseStock() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 26.0)
            val carton = repo.addSellingUnit(shop.id, item.id, "carton", 24, 1_100_000)
            val pack = CartLine(item, 1, carton.id, carton.name, carton.priceMinor, carton.baseUnitsPerUnit)
            val pending = repo.pendingSyncCount(shop.id)
            val cash = repo.cashEntries(shop.id)
            assertFails { repo.checkout(shop.id, listOf(pack, CartLine(item, 3)), 1_250_000, operationId = "mixed-oversale") }
            assertFails { repo.checkout(shop.id, listOf(pack, pack), 2_200_000, operationId = "duplicate-oversale") }
            assertEquals(26.0, repo.products(shop.id).single().stock)
            assertTrue(repo.sales(shop.id).isEmpty())
            assertEquals(cash, repo.cashEntries(shop.id))
            assertEquals(pending, repo.pendingSyncCount(shop.id))
        }
    }

    @Test fun sellingUnitMustBelongToTheSameProductAndTenant() {
        PosRepository(":memory:").use { repo ->
            val a = repo.createTenant("A")
            val b = repo.createTenant("B")
            val malt = repo.addProduct(a.id, "Malt", "bottle", 50_000, 60.0)
            val soap = repo.addProduct(a.id, "Soap", "piece", 10_000, 60.0)
            val other = repo.addProduct(b.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(a.id, malt.id, "carton", 24, 1_100_000)
            assertFails { repo.sellingUnits(b.id, malt.id) }
            assertFails { repo.addSellingUnit(b.id, malt.id, "pack", 6, 100_000) }
            assertFails { repo.archiveSellingUnit(b.id, carton.id, true) }
            listOf(a.id to soap, b.id to other).forEach { (tenantId, product) ->
                assertFails { repo.checkout(tenantId, listOf(CartLine(product, 1, carton.id, carton.name, carton.priceMinor, 24)), 1_100_000) }
            }
            assertTrue(repo.sales(a.id).isEmpty())
            assertTrue(repo.sales(b.id).isEmpty())
            assertTrue(repo.products(a.id).all { it.stock == 60.0 })
        }
    }

    @Test fun barcodeNamespaceIncludesBaseItemsAndArchivedPacksButIsScopedToShop() {
        PosRepository(":memory:").use { repo ->
            val a = repo.createTenant("A")
            val b = repo.createTenant("B")
            val item = repo.addProduct(a.id, "Malt", "bottle", 50_000, 60.0, "BOTTLE-01")
            val soap = repo.addProduct(a.id, "Soap", "piece", 10_000, 60.0)
            assertFails { repo.addSellingUnit(a.id, item.id, "carton", 24, 1_100_000, "BOTTLE-01") }
            val carton = repo.addSellingUnit(a.id, item.id, "carton", 24, 1_100_000, "CARTON-01")
            assertFails { repo.addProduct(a.id, "Rice", "bag", 1, 1.0, "CARTON-01") }
            assertFails { repo.addSellingUnit(a.id, soap.id, "box", 12, 100_000, "CARTON-01") }
            repo.archiveSellingUnit(a.id, carton.id, true)
            assertTrue(repo.sellingUnits(a.id, item.id).isEmpty())
            assertTrue(repo.sellingUnits(a.id, item.id, true).single().archived)
            assertFails { repo.addSellingUnit(a.id, soap.id, "box", 12, 100_000, "CARTON-01") }
            val other = repo.addProduct(b.id, "Malt", "bottle", 50_000, 60.0, "BOTTLE-01")
            assertEquals("CARTON-01", repo.addSellingUnit(b.id, other.id, "carton", 24, 1_100_000, "CARTON-01").barcode)
        }
    }

    @Test fun staleOrForgedPackPriceNameAndFactorAreRejectedBeforeAnyLedgerWrites() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(shop.id, item.id, "carton", 24, 1_100_000)
            val valid = CartLine(item, 1, carton.id, carton.name, carton.priceMinor, 24)
            val pending = repo.pendingSyncCount(shop.id)
            listOf(valid.copy(unitPriceMinor = 1), valid.copy(baseUnitsPerUnit = 1), valid.copy(sellingUnit = "bottle")).forEach { forged ->
                assertFails { repo.checkout(shop.id, listOf(forged), 1_100_000) }
            }
            assertTrue(repo.sales(shop.id).isEmpty())
            assertEquals(60.0, repo.products(shop.id).single().stock)
            assertEquals(pending, repo.pendingSyncCount(shop.id))
            repo.archiveSellingUnit(shop.id, carton.id, true)
            assertFails { repo.checkout(shop.id, listOf(valid), 1_100_000) }
        }
    }

    @Test fun backupRoundTripPreservesMixedPricesReturnsAndArchivedDefinitions() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(shop.id, item.id, "carton", 24, 1_100_000, "PACK")
            repo.openCashShift(shop.id, 5_000_000)
            val pack = CartLine(item, 1, carton.id, carton.name, carton.priceMinor, 24)
            val sale = repo.checkout(shop.id, listOf(pack, CartLine(item, 3)), 1_250_000)
            val returned = repo.returnItems(shop.id, sale.id, mapOf(pack.key to 1), true, "Unopened")
            repo.archiveSellingUnit(shop.id, carton.id, true)
            val restored = repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id), "Restored")
            assertEquals(57.0, repo.products(restored.id).single().stock)
            assertEquals(24, repo.sellingUnits(restored.id, item.id, true).single().baseUnitsPerUnit)
            assertEquals(sale.lines.map { it.key to it.totalMinor }, repo.sales(restored.id).single().lines.map { it.key to it.totalMinor })
            assertEquals(returned.lines, repo.saleReturns(restored.id, sale.id).single().lines)
            assertEquals(mapOf(pack.key to 0, item.id to 3), repo.returnableQuantities(restored.id, sale.id))
            assertEquals(listOf(-27.0, 24.0).sorted(), repo.inventoryMovements(restored.id, item.id).filter { it.kind in setOf("sale", "return") }.map { it.quantityDelta }.sorted())
        }
    }

    @Test fun legacyBackupsDefaultSalesAndReturnsToOneBaseUnit() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Soap", "piece", 100, 5.0)
            repo.openCashShift(shop.id, 1_000)
            val sale = repo.checkout(shop.id, listOf(CartLine(item, 2)), 200)
            repo.returnItems(shop.id, sale.id, mapOf(item.id to 1), true, "Unopened")
            val saved = repo.exportTenantSnapshot(shop.id)
            val newFields = setOf("selling_unit_id", "selling_unit", "unit_price_minor", "base_units_per_unit", "base_unit")
            val legacy = saved.copy(tables = saved.tables.filterKeys { it != "selling_units" }.mapValues { (table, rows) ->
                if (table == "sale_lines") rows.map { it.filterKeys { key -> key !in newFields } }
                else if (table == "return_lines") rows.map { it.filterKeys { key -> key !in setOf("selling_unit_id", "base_units_per_unit", "base_unit") } }
                else rows
            })
            val restored = repo.restoreTenantSnapshot(legacy, "Legacy")
            val line = repo.sales(restored.id).single().lines.single()
            assertNull(line.sellingUnitId)
            assertEquals("piece", line.sellingUnit)
            assertEquals(1, line.baseUnitsPerUnit)
            assertEquals(100L, line.unitPriceMinor)
            assertEquals(2L, line.baseQuantity)
            val returned = repo.saleReturns(restored.id).single().lines.single()
            assertEquals(1L, returned.baseQuantity)
            assertEquals("piece", returned.baseUnit)
            assertEquals(4.0, repo.products(restored.id).single().stock)
        }
    }

    @Test fun backupRejectsTruncatedFactorsAndUnlinkedOrChangedSaleConversionsAtomically() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Malt", "bottle", 50_000, 60.0)
            val carton = repo.addSellingUnit(shop.id, item.id, "carton", 24, 1_100_000)
            val pack = CartLine(item, 1, carton.id, carton.name, carton.priceMinor, 24)
            repo.openCashShift(shop.id, 5_000_000)
            val sale = repo.checkout(shop.id, listOf(pack), 1_100_000)
            repo.returnItems(shop.id, sale.id, mapOf(pack.key to 1), true, "Unopened carton")
            val saved = repo.exportTenantSnapshot(shop.id)
            val before = repo.tenants().size
            listOf("selling_units", "sale_lines", "return_lines").forEach { table ->
                listOf("0", "-1", "4294967297").forEach { factor ->
                    val forged = saved.copy(tables = saved.tables + (table to saved.tables.getValue(table).map { it + ("base_units_per_unit" to factor) }))
                    assertFails { repo.restoreTenantSnapshot(forged, "Bad factor") }
                    assertEquals(before, repo.tenants().size)
                }
            }
            listOf(mapOf("base_units_per_unit" to "12"), mapOf("selling_unit_id" to null), mapOf("unit_price_minor" to "1")).forEach { changes ->
                val forged = saved.copy(tables = saved.tables + ("sale_lines" to saved.tables.getValue("sale_lines").map { it + changes }))
                assertFails { repo.restoreTenantSnapshot(forged, "Changed sale") }
                assertEquals(before, repo.tenants().size)
            }
            val forgedReturn = saved.copy(tables = saved.tables + ("return_lines" to saved.tables.getValue("return_lines").map { it + ("base_units_per_unit" to "12") }))
            assertFails { repo.restoreTenantSnapshot(forgedReturn, "Changed return") }
            assertEquals(before, repo.tenants().size)
        }
    }

    @Test fun hugeConvertedQuantitiesAndMoneyOverflowCannotWritePartialSales() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Screws", "piece", 1, 9_007_199_254_740_991.0)
            val bulk = repo.addSellingUnit(shop.id, item.id, "bulk lot", Int.MAX_VALUE, 1)
            val pending = repo.pendingSyncCount(shop.id)
            assertFails { repo.checkout(shop.id, listOf(CartLine(item, Int.MAX_VALUE, bulk.id, bulk.name, 1, Int.MAX_VALUE)), Int.MAX_VALUE.toLong()) }
            assertEquals(9_007_199_254_740_991.0, repo.products(shop.id).single().stock)
            assertEquals(pending, repo.pendingSyncCount(shop.id))
            val expensive = repo.addSellingUnit(shop.id, item.id, "costly pair", 2, Long.MAX_VALUE)
            val afterDefinition = repo.pendingSyncCount(shop.id)
            assertFails { repo.checkout(shop.id, listOf(CartLine(item, 2, expensive.id, expensive.name, expensive.priceMinor, 2)), Long.MAX_VALUE) }
            assertTrue(repo.sales(shop.id).isEmpty())
            assertEquals(afterDefinition, repo.pendingSyncCount(shop.id))
        }
    }
}
