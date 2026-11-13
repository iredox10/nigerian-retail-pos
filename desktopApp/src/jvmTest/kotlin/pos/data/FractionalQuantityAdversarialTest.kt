package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.ProductPerformanceService
import pos.screens.ReportDateRange
import java.time.LocalDate

class FractionalQuantityAdversarialTest {
    @Test fun decimalSubtractionsReachZeroAndRetryCannotDeductTwice() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Exact decimals")
            val item = repo.addProduct(shop.id, "Loose rice", "kg", 100_000, 0.3, quantityPrecision = 1)
            repo.checkout(shop.id, listOf(CartLine(item, 1)), 10_000, operationId = "first")
            val remaining = repo.products(shop.id).single()
            assertEquals("0.2", remaining.stockLabel)
            val second = repo.checkout(shop.id, listOf(CartLine(remaining, 2)), 20_000, operationId = "second")
            assertEquals(0L, repo.products(shop.id).single().exactStockScaled)
            assertEquals(second.id, repo.checkout(shop.id, listOf(CartLine(remaining, 2)), 20_000, operationId = "second").id)
            assertEquals(0L, repo.products(shop.id).single().exactStockScaled)
            assertFails { repo.checkout(shop.id, listOf(CartLine(remaining, 1)), 10_000, operationId = "oversell") }
            assertEquals(2, repo.sales(shop.id).size)
        }
    }

    @Test fun sixDecimalSaleAndReportUseMeasuredUnitsRatherThanScaledCounts() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Fine measures")
            val item = repo.addProduct(shop.id, "Concentrate", "litre", 1_000_000, 1.0, quantityPrecision = 6)
            val sale = repo.checkout(shop.id, listOf(CartLine(item, 1)), 1, operationId = "tiny")
            assertEquals(1L, sale.totalMinor)
            assertEquals("0.000001", sale.lines.single().quantityLabel)
            assertEquals("0.999999", repo.products(shop.id).single().stockLabel)
            val day = LocalDate.parse(sale.createdAt.substring(0, 10))
            val csv = ProductPerformanceService.csv(shop.id, repo.sales(shop.id), emptyList(), ReportDateRange(day.minusDays(1), day.plusDays(1)))
            assertTrue(csv.contains("0.000001"), csv)
        }
    }

    @Test fun corruptedPrecisionAndExactStockRejectRestoreWithoutCreatingAShop() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Original")
            val item = repo.addProduct(shop.id, "Fabric", "metre", 100, 2.25, quantityPrecision = 2)
            repo.checkout(shop.id, listOf(CartLine(item, 25)), 25, operationId = "fabric-sale")
            val snapshot = repo.exportTenantSnapshot(shop.id)
            fun corrupt(table: String, column: String, value: String) = snapshot.copy(tables = snapshot.tables +
                (table to snapshot.tables.getValue(table).map { it + (column to value) }))
            assertFails { repo.restoreTenantSnapshot(corrupt("products", "stock_scaled", "999999"), "Bad stock") }
            assertFails { repo.restoreTenantSnapshot(corrupt("sale_lines", "quantity_precision", "0"), "Bad sale") }
            assertEquals(listOf(shop), repo.tenants())
            assertEquals("2", repo.products(shop.id).single().stockLabel)
        }
    }

    @Test fun zeroKoboPartialRefundsStillPreserveStockAndSurviveBackup() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Rounding history")
            val item = repo.addProduct(shop.id, "Ribbon", "metre", 1, 1.0, quantityPrecision = 2)
            repo.openCashShift(shop.id, 10)
            val sale = repo.checkout(shop.id, listOf(CartLine(item, 100)), 1, operationId = "ribbon")
            repo.returnItems(shop.id, sale.id, mapOf(item.id to 49), true, "First piece", "ribbon-r1")
            repo.returnItems(shop.id, sale.id, mapOf(item.id to 51), true, "Remaining ribbon", "ribbon-r2")
            assertEquals(1L, repo.saleReturns(shop.id).sumOf { it.refundMinor })
            val restored = repo.restoreTenantSnapshot(repo.exportTenantSnapshot(shop.id), "Recovered ribbon")
            assertEquals("1", repo.products(restored.id).single().stockLabel)
            assertEquals(listOf(0L, 1L).sorted(), repo.saleReturns(restored.id).map { it.refundMinor }.sorted())
        }
    }
}
