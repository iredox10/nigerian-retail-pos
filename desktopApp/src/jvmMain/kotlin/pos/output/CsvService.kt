package pos.output

import pos.domain.*
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

sealed interface CsvCell {
    data class Text(val value: String) : CsvCell
    data class Number(val value: String) : CsvCell {
        init { require(value.matches(Regex("-?\\d+(\\.\\d+)?"))) { "CSV number must be a plain decimal." } }
    }
}

/** Pure RFC-style quoted CSV. Text is formula-neutralized; signed numeric data is not changed. */
object CsvService {
    fun encode(headers: List<String>, rows: List<List<CsvCell>>): String {
        require(headers.isNotEmpty())
        require(rows.all { it.size == headers.size }) { "CSV rows must match the header columns." }
        fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
        fun safeText(value: String): String {
            val first = value.dropWhile { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
            val dangerous = first in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')
            return if (dangerous) "'$value" else value
        }
        return buildString {
            append(headers.joinToString(",") { quote(safeText(it)) }); append("\r\n")
            rows.forEach { row ->
                append(row.joinToString(",") { cell -> when(cell) { is CsvCell.Text -> quote(safeText(cell.value)); is CsvCell.Number -> quote(cell.value) } })
                append("\r\n")
            }
        }
    }

    fun sales(tenantId: String, values: List<Sale>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's sales." }
        return encode(listOf("Sale ID", "Recorded at UTC", "Sale total NGN", "Cash applied at sale NGN", "Credit granted at sale NGN", "Customer ID"), values.map {
            listOf(text(it.id), text(it.createdAt), naira(it.totalMinor), naira(it.cashMinor), naira(it.creditMinor), text(it.customerId.orEmpty()))
        })
    }

    fun products(tenantId: String, values: List<Product>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's stock." }
        return encode(listOf("Product ID", "Name", "Selling unit", "Selling price NGN", "Current stock quantity"), values.map {
            listOf(text(it.id), text(it.name), text(it.unit), naira(it.priceMinor), CsvCell.Number(BigDecimal.valueOf(it.stock).toPlainString()))
        })
    }

    fun customers(tenantId: String, values: List<Customer>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's customers." }
        return encode(listOf("Customer ID", "Name", "Current debt NGN"), values.map { listOf(text(it.id), text(it.name), naira(it.balanceMinor)) })
    }

    fun expenses(tenantId: String, values: List<Expense>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's expenses." }
        return encode(listOf("Expense ID", "Recorded at UTC", "Category", "Description", "Expense paid NGN"), values.map {
            listOf(text(it.id), text(it.createdAt), text(it.category), text(it.description), naira(it.amountMinor))
        })
    }

    fun cashEntries(tenantId: String, values: List<CashEntry>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's cash activity." }
        return encode(listOf("Entry ID", "Recorded at UTC", "Shift ID", "Kind", "Description", "Signed cash movement NGN"), values.map {
            listOf(text(it.id), text(it.createdAt), text(it.shiftId.orEmpty()), text(it.kind), text(it.description), naira(it.amountMinor))
        })
    }

    fun stockReceipts(tenantId: String, values: List<StockReceipt>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's purchases." }
        return encode(listOf("Receipt ID", "Recorded at UTC", "Product ID", "Item", "Buying unit", "Quantity", "Buying price per unit NGN", "Purchase total NGN", "Cash paid at purchase NGN", "Credit at purchase NGN", "Supplier"), values.map {
            listOf(text(it.id), text(it.createdAt), text(it.productId), text(it.productName), text(it.unit), CsvCell.Number(it.quantity.toString()), naira(it.unitCostMinor), naira(it.totalCostMinor), naira(it.paidCashMinor), naira(it.unpaidMinor), text(it.supplierName))
        })
    }

    /** Explicit local export; cancellation returns null. Never changes the underlying business data. */
    fun save(text: String, suggestedName: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save this shop's CSV export"
            fileFilter = FileNameExtensionFilter("CSV spreadsheet (*.csv)", "csv")
            selectedFile = File(suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val chosen = chooser.selectedFile
        val file = if (chosen.name.endsWith(".csv", true)) chosen else File(chosen.parentFile, chosen.name + ".csv")
        if (file.exists() && JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Replace export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null
        val target = file.toPath().toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".csv-", ".tmp")
        try {
            java.io.FileOutputStream(temporary.toFile()).use { output -> output.write(("\uFEFF" + text).toByteArray(Charsets.UTF_8)); output.fd.sync() }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        return target.toFile()
    }

    private fun text(value: String) = CsvCell.Text(value)
    private fun naira(minor: Long) = CsvCell.Number(BigDecimal.valueOf(minor, 2).toPlainString())
}
