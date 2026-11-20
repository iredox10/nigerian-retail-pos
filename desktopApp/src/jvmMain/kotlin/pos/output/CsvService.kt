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
        return encode(listOf("Sale ID", "Recorded at UTC", "Sale total NGN", "Cash applied at sale NGN", "Credit granted at sale NGN", "Customer ID", "Agreed repayment due date WAT", "Before discounts NGN", "Discount NGN", "Basket discount NGN", "Basket rule", "Basket requested value (kobo or basis points)", "Basket reason", "External amount recorded NGN", "External recording status", "External payment references", "Cash given NGN", "Change at sale NGN"), values.map {
            listOf(text(it.id), text(it.createdAt), naira(it.totalMinor), naira(it.cashMinor), naira(it.creditMinor), text(it.customerId.orEmpty()), text(it.dueDate.orEmpty()), naira(cartSubtotal(it.lines)), naira(cartDiscountTotal(it.lines)),naira(it.lines.sumOf {line -> line.basketDiscountMinor}),text(it.basketDiscount?.kind.orEmpty()),text(it.basketDiscount?.value?.toString().orEmpty()),text(it.basketDiscount?.reason.orEmpty()),naira(it.externalRecordedMinor),text(if(it.externalPayments.isEmpty()) "" else "Manually recorded - unverified"),text(it.externalPayments.joinToString("; ") {p ->p.method+": "+p.reference+" @ "+p.receivingAccount+" = "+naira(p.amountMinor)}), it.cashGivenMinor?.let(::naira) ?: text(""), it.changeMinor?.let(::naira) ?: text(""))
        })
    }

    fun products(tenantId: String, values: List<Product>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's stock." }
        return encode(listOf("Product ID", "Name", "Selling unit", "Selling price NGN", "Current stock quantity", "Barcode", "Archived", "Reorder level (blank means disabled)", "Opening quantity (blank means unknown)", "Opening recorded at UTC", "SKU", "Category", "Brand", "Aliases", "Favourite", "Quantity decimal places"), values.map {
            listOf(text(it.id), text(it.name), text(it.unit), naira(it.priceMinor), CsvCell.Number(it.stockLabel), text(it.barcode.orEmpty()), text(it.archived.toString()),it.reorderLevel?.let { level -> CsvCell.Number(level.toString()) } ?: text(""),it.openingStock?.let { stock -> CsvCell.Number(BigDecimal.valueOf(stock).toPlainString()) } ?: text(""),text(it.createdAt.orEmpty()),text(it.sku.orEmpty()),text(it.category.orEmpty()),text(it.brand.orEmpty()),text(it.aliases.joinToString(" | ")),text(it.favourite.toString()),CsvCell.Number(it.quantityPrecision.toString()))
        })
    }

    fun customers(tenantId: String, values: List<Customer>): String {
        require(values.all { it.tenantId == tenantId }) { "Export includes another shop's customers." }
        return encode(listOf("Customer ID", "Name", "Current debt NGN", "Phone", "Address", "Credit limit NGN (blank means no limit)"), values.map { listOf(text(it.id), text(it.name), naira(it.balanceMinor), text(it.phone.orEmpty()), text(it.address.orEmpty()), it.creditLimitMinor?.let(::naira) ?: text("")) })
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
        return encode(listOf("Receipt ID", "Recorded at UTC", "Product ID", "Item", "Buying unit", "Quantity", "Buying price per unit NGN", "Purchase total NGN", "Cash paid at purchase NGN", "Credit at purchase NGN", "Supplier", "Base selling unit", "Base units per buying unit", "Base quantity added", "Buying unit ID"), values.map {
            listOf(text(it.id), text(it.createdAt), text(it.productId), text(it.productName), text(it.unit), CsvCell.Number(it.quantityLabel), naira(it.unitCostMinor), naira(it.totalCostMinor), naira(it.paidCashMinor), naira(it.unpaidMinor), text(it.supplierName), text(it.baseUnit), CsvCell.Number(it.baseUnitsPerUnit.toString()), CsvCell.Number(it.baseQuantityLabel), text(it.purchaseUnitId.orEmpty()))
        })
    }

    fun repaymentAllocations(tenantId:String,values:List<RepaymentAllocation>,reversals:List<AllocationReversal> = emptyList()):String {
        require(values.all {it.tenantId==tenantId} && reversals.all {it.tenantId==tenantId}) { "Export includes another shop's assignments or corrections." }
        require(values.map {it.id}.distinct().size==values.size && reversals.map {it.id}.distinct().size==reversals.size && reversals.map {it.allocationId}.distinct().size==reversals.size) { "Export contains repeated assignments or corrections." }
        val byId=values.associateBy {it.id}
        require(reversals.all { byId[it.allocationId]?.customerId==it.customerId }) { "Correction does not match an exported assignment." }
        val undone=reversals.associateBy {it.allocationId}
        return encode(listOf("Assignment ID","Customer ID","Payment ID","Credit receipt ID","Assigned amount NGN","Assigned at UTC","Current assignment status","Correction ID","Undone at UTC","Correction reason"),values.map {
            val reversal=undone[it.id]
            listOf(text(it.id),text(it.customerId),text(it.repaymentId),text(it.saleId),naira(it.amountMinor),text(it.createdAt),text(if(reversal==null) "Active" else "Undone"),text(reversal?.id.orEmpty()),text(reversal?.createdAt.orEmpty()),text(reversal?.reason.orEmpty()))
        })
    }

    fun allocationReversals(tenantId:String,values:List<AllocationReversal>):String {
        require(values.all {it.tenantId==tenantId}) { "Export includes another shop's assignment corrections." }
        require(values.map {it.id}.distinct().size==values.size && values.map {it.allocationId}.distinct().size==values.size) { "Export contains repeated corrections." }
        return encode(listOf("Correction ID","Assignment ID","Customer ID","Reason","Undone at UTC"),values.map {
            listOf(text(it.id),text(it.allocationId),text(it.customerId),text(it.reason),text(it.createdAt))
        })
    }

    fun auditEvents(tenantId:String,values:List<AuditEvent>):String {
        require(values.all {it.tenantId==tenantId}) { "Activity export contains another shop." }
        require(values.map {it.id}.distinct().size==values.size) { "Activity export has duplicate events." }
        return encode(listOf("Event ID","Recorded at UTC","Action","Source ID","Actor recorded","Actor ID","Actor name at event","Actor role at event","Description"),values.map {
            listOf(text(it.id),text(it.createdAt),text(it.kind),text(it.entityId),text((it.actor!=null).toString()),text(it.actor?.id.orEmpty()),text(it.actor?.name.orEmpty()),text(it.actor?.role?.name.orEmpty()),text(it.description.orEmpty()))
        })
    }

    fun inventoryMovements(tenantId:String,products:List<Product>,values:List<InventoryMovement>):String {
        require(products.all {it.tenantId==tenantId} && products.map {it.id}.distinct().size==products.size) { "Choose this shop's product records." }
        val byId=products.associateBy {it.id}
        require(values.all {it.tenantId==tenantId && it.productId in byId}) { "Movement export includes another shop's or unavailable items." }
        return encode(listOf("Movement ID","Product ID","Item","Base unit","Signed stock change","Kind","Source ID","Recorded at UTC","Description","Opening history known"),values.map { movement ->
            val product=byId.getValue(movement.productId)
            listOf(text(movement.id),text(product.id),text(product.name),text(product.unit),CsvCell.Number(BigDecimal.valueOf(movement.quantityDelta).toPlainString()),text(movement.kind),text(movement.sourceId),text(movement.createdAt),text(movement.description),text((product.openingStock!=null).toString()))
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
