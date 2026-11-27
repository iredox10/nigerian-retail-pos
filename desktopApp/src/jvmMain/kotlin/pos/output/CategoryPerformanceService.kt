package pos.output

import java.math.BigDecimal
import java.time.temporal.ChronoUnit
import pos.domain.*
import pos.screens.ReportDateRange

data class CategoryPerformanceRow(val category: String, val salesMinor: Long, val returnsMinor: Long, val previousNetMinor: Long) {
    val netMinor: Long get() = Math.subtractExact(salesMinor, returnsMinor)
    val changeMinor: Long get() = Math.subtractExact(netMinor, previousNetMinor)
}

data class CategoryComparison(val range: ReportDateRange, val previous: ReportDateRange, val rows: List<CategoryPerformanceRow>)

/** Current catalogue categories label the recorded sales and returns in both periods. */
object CategoryPerformanceService {
    fun build(tenantId: String, products: List<Product>, sales: List<Sale>, returns: List<SaleReturn>, range: ReportDateRange): CategoryComparison {
        require(products.all { it.tenantId == tenantId } && products.map { it.id }.distinct().size == products.size) {
            "Category report catalogue belongs to another shop or contains duplicates."
        }
        val days = Math.addExact(ChronoUnit.DAYS.between(range.from, range.through), 1)
        val previous = ReportDateRange(range.from.minusDays(days), range.from.minusDays(1))
        val categories = products.associate { it.id to (it.category ?: "Uncategorised") }
        fun grouped(period: ReportDateRange) = ProductPerformanceService.build(tenantId, sales, returns, period)
            .groupBy { categories[it.productId] ?: "Uncategorised" }
        val current = grouped(range)
        val before = grouped(previous)
        fun sum(rows: List<ProductPerformanceRow>, amount: (ProductPerformanceRow) -> Long) =
            rows.fold(0L) { total, row -> Math.addExact(total, amount(row)) }
        val rows = (current.keys + before.keys).map { category ->
            CategoryPerformanceRow(category, sum(current[category].orEmpty()) { it.salesMinor },
                sum(current[category].orEmpty()) { it.refundsMinor }, sum(before[category].orEmpty()) { it.netSalesMinor })
        }.sortedWith(compareByDescending<CategoryPerformanceRow> { it.netMinor }.thenBy { it.category })
        return CategoryComparison(range, previous, rows)
    }

    fun csv(value: CategoryComparison): String {
        fun amount(v: Long) = CsvCell.Number(BigDecimal.valueOf(v, 2).toPlainString())
        return CsvData.encode(listOf("Current catalogue category", "From WAT", "Through WAT", "Sales", "Returned goods value",
            "Net sales", "Previous from WAT", "Previous through WAT", "Previous net sales", "Change in net sales"),
            value.rows.map { row -> listOf(CsvCell.Text(row.category), CsvCell.Text(value.range.from.toString()),
                CsvCell.Text(value.range.through.toString()), amount(row.salesMinor), amount(row.returnsMinor), amount(row.netMinor),
                CsvCell.Text(value.previous.from.toString()), CsvCell.Text(value.previous.through.toString()),
                amount(row.previousNetMinor), amount(row.changeMinor)) })
    }
}
