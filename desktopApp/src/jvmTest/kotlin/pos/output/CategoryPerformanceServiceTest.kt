package pos.output

import java.time.LocalDate
import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange

class CategoryPerformanceServiceTest {
    @Test fun comparesEqualWatPeriodsAndUsesCurrentCategoriesWithoutMixingUnits() {
        val bottle = Product("bottle", "shop", "Malt", "bottle", 100, 10.0).copy(category = "Groceries")
        val bag = Product("bag", "shop", "Rice", "bag", 500, 10.0).copy(category = "Groceries")
        val old = Sale("old", "shop", listOf(CartLine(bottle, 2)), 200, 200, 0, null, "2026-11-01T10:00:00Z")
        val current = Sale("current", "shop", listOf(CartLine(bag, 1)), 500, 500, 0, null, "2026-11-03T10:00:00Z")
        val range = ReportDateRange(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04"))
        val review = CategoryPerformanceService.build("shop", listOf(bottle, bag), listOf(old, current), emptyList(), range)
        assertEquals(LocalDate.parse("2026-11-01"), review.previous.from)
        assertEquals(LocalDate.parse("2026-11-02"), review.previous.through)
        val row = review.rows.single()
        assertEquals(500L, row.netMinor)
        assertEquals(200L, row.previousNetMinor)
        assertEquals(300L, row.changeMinor)
        assertFails { CategoryPerformanceService.build("other", listOf(bottle), listOf(old), emptyList(), range) }
    }

    @Test fun returnsCanMakeACategoryNegativeAndCsvEscapesCategoryNames() {
        val item = Product("item", "shop", "Soap", "piece", 100, 10.0).copy(category = "=Goods")
        val sale = Sale("sale", "shop", listOf(CartLine(item, 1)), 100, 100, 0, null, "2026-11-01T10:00:00Z")
        val returned = SaleReturn("return", "shop", "sale", listOf(ReturnLine(item.id, item.name, item.unit, 1, 100)),
            100, true, "Unopened", "2026-11-03T10:00:00Z")
        val range = ReportDateRange(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-03"))
        val review = CategoryPerformanceService.build("shop", listOf(item), listOf(sale), listOf(returned), range)
        assertEquals(-100L, review.rows.single().netMinor)
        assertContains(CategoryPerformanceService.csv(review), "'=Goods")
        assertContains(CategoryPerformanceService.csv(review), "-1.00")
    }
}
