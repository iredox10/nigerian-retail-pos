package pos.output

import java.math.BigDecimal
import pos.data.PurchaseCostRecord

object PurchaseCostCsv {
    fun encode(tenantId: String, records: List<PurchaseCostRecord>): String {
        require(records.all { it.receipt.tenantId == tenantId }) { "Export includes another shop's purchases." }
        fun text(value: String) = CsvCell.Text(value)
        fun number(value: String) = CsvCell.Number(value)
        fun naira(minor: Long) = number(BigDecimal.valueOf(minor, 2).toPlainString())
        return CsvService.encode(listOf(
            "Receipt ID", "Recorded at UTC", "Product ID", "Item on receipt", "Supplier on receipt",
            "Supplier account ID", "Supplier account name", "Buying unit", "Quantity received",
            "Price per buying unit NGN", "Purchase total NGN", "Stock unit", "Stock units per buying unit",
            "Price per stock unit NGN", "Price per stock unit rounded to kobo",
        ), records.map { row ->
            val receipt = row.receipt
            listOf(
                text(receipt.id), text(receipt.createdAt), text(receipt.productId), text(receipt.productName),
                text(receipt.supplierName), text(row.supplier?.id.orEmpty()), text(row.supplier?.details?.name.orEmpty()),
                text(receipt.unit), number(receipt.quantityLabel), naira(receipt.unitCostMinor),
                naira(receipt.totalCostMinor), text(receipt.baseUnit), number(receipt.baseUnitsPerUnit.toString()),
                naira(row.roundedBaseCostMinor), text(if (row.baseCostIsRounded) "Yes" else "No"),
            )
        })
    }
}
