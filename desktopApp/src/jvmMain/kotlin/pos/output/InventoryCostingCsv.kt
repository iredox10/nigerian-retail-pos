package pos.output

import java.math.BigDecimal
import pos.data.InventoryCostingReport

object InventoryCostingCsv {
    fun review(report: InventoryCostingReport): String {
        fun text(value: String) = CsvCell.Text(value)
        fun number(value: Long?, precision: Int = 2): CsvCell = value?.let {
            CsvCell.Number(BigDecimal.valueOf(it, precision).toPlainString())
        } ?: text("Unavailable")
        val rows = report.items.flatMap { row ->
            val product = row.product
            val common = listOf(text(report.through), text("FIFO purchase basis; latest opening-cost declaration"),
                text(product.id), text(product.name), text(product.unit))
            val summary = listOf(text("valuation")) + common + listOf(number(row.quantityScaled, product.quantityPrecision),
                number(row.stockValueMinor), text("stock on hand"), text(""), text(""), text(row.gaps.joinToString(" | ")))
            val cogs = listOf(text("original-sale COGS")) + common + listOf(text(""), number(row.originalSalesCogsMinor),
                text("sales through WAT date"), text(""), text(""), text(row.gaps.joinToString(" | ")))
            val netCogs = listOf(text("COGS after returns")) + common + listOf(text(""), number(row.netSalesCogsMinor),
                text("net retained sale-cost basis"), text(""), text(""), text(row.gaps.joinToString(" | ")))
            val returnLoss = listOf(text("non-restocked return loss")) + common + listOf(text(""), number(row.nonRestockedReturnCostMinor),
                text("original cost of goods returned outside sellable stock"), text(""), text(""), text(row.gaps.joinToString(" | ")))
            val ledger = row.ledger.map { event ->
                listOf(text("source movement")) + common + listOf(number(event.quantityScaled, product.quantityPrecision),
                    number(event.costMinor), text(event.kind), text(event.sourceId), text(event.createdAt), text(event.detail))
            }
            listOf(summary, cogs, netCogs, returnLoss) + ledger
        }
        return CsvService.encode(listOf("record_type", "through_wat", "method", "product_id", "product", "stock_unit",
            "quantity", "purchase_basis_cost_ngn", "source_kind", "source_id", "recorded_at", "coverage_or_detail"), rows)
    }
}
