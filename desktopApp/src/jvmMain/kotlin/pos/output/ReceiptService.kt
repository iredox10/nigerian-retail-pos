package pos.output

import pos.domain.Sale
import pos.domain.SaleReturn
import pos.domain.Tenant
import pos.domain.money
import java.awt.Font
import java.awt.Graphics2D
import java.awt.print.Printable
import java.awt.print.PrinterJob
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import java.util.concurrent.FutureTask

/** Receipt creation does not mutate a sale, debt, cash drawer, or printer preference. */
object ReceiptService {
    fun format(tenant: Tenant, sale: Sale, returns: List<SaleReturn> = emptyList()): String {
        require(sale.tenantId == tenant.id) { "This receipt belongs to another shop." }
        require(returns.all { it.tenantId == tenant.id && it.saleId == sale.id }) { "Return records belong to another receipt or shop." }
        val refunded = returns.fold(0L) { total, item -> Math.addExact(total, item.refundMinor) }
        require(refunded in 0..sale.totalMinor) { "Refund total exceeds the original sale." }
        return buildString {
            appendLine(tenant.name)
            appendLine("SALES RECEIPT")
            appendLine("Receipt: ${sale.id}")
            appendLine("Date: ${receiptDate(sale.createdAt)}")
            appendLine("----------------------------------------")
            sale.lines.forEach { line ->
                appendLine(line.product.name)
                appendLine("  ${line.quantity} ${line.product.unit} x ${money(line.product.priceMinor)}")
                appendLine("  ${money(line.totalMinor)}")
            }
            appendLine("----------------------------------------")
            appendLine("Original sale total: ${money(sale.totalMinor)}")
            appendLine("Cash applied at sale: ${money(sale.cashMinor)}")
            if (sale.creditMinor > 0) {
                appendLine("Customer owed at sale: ${money(sale.creditMinor)}")
                appendLine("This is not the customer's current debt.")
                appendLine(sale.dueDate?.let { "Agreed repayment due date: $it (WAT)" } ?: "No repayment due date recorded")
            } else appendLine("Paid in cash")
            // Tender/change are not stored by the initial sale model; never invent them.
            if (returns.isNotEmpty()) {
                appendLine()
                appendLine("RETURNS AND CASH REFUNDS")
                returns.forEach { item ->
                    appendLine("Return: ${item.id}")
                    appendLine(receiptDate(item.createdAt))
                    item.lines.forEach { line -> appendLine("  ${line.quantity} ${line.unit} ${line.name}") }
                    appendLine("Cash refunded: ${money(item.refundMinor)}")
                    appendLine(if (item.restock) "Returned goods added back to stock" else "Returned goods not added to sellable stock")
                    appendLine("Reason: ${item.reason}")
                }
                appendLine("Total cash refunded: ${money(refunded)}")
                appendLine("Sale value after returns: ${money(sale.totalMinor - refunded)}")
            }
            appendLine()
            appendLine("Saved sale record. Printed/exported copies do")
            appendLine("not create another payment or sale.")
        }
    }

    /** Explicit user-initiated local export, UTF-8; cancellation has no side effect. */
    fun exportReceipt(text: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save receipt as text"
            fileFilter = FileNameExtensionFilter("Text receipt (*.txt)", "txt")
            selectedFile = File("receipt.txt")
        }
        if (onSwing { chooser.showSaveDialog(null) } != JFileChooser.APPROVE_OPTION) return null
        val chosen = chooser.selectedFile
        val file = if (chosen.name.endsWith(".txt", true)) chosen else File(chosen.parentFile, chosen.name + ".txt")
        if (file.exists() && onSwing { JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Replace receipt file",
                JOptionPane.YES_NO_OPTION) } != JOptionPane.YES_OPTION) return null
        val absolute = file.toPath().toAbsolutePath()
        val temp = Files.createTempFile(absolute.parent, ".receipt-", ".tmp")
        try {
            Files.writeString(temp, text, Charsets.UTF_8)
            try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
        return absolute.toFile()
    }

    /** Opens a system dialog every time. True means submitted to the print service, not paper delivery. */
    fun printReceipt(text: String): Boolean {
        check(PrinterJob.lookupPrintServices().isNotEmpty()) { "No printer is available. Export the receipt or connect a printer and try again." }
        val job = PrinterJob.getPrinterJob()
        job.jobName = "Retail POS receipt"
        job.setPrintable { graphics, page, index ->
            val g = graphics.create() as Graphics2D
            try {
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
            val metrics = g.fontMetrics
            val width = page.imageableWidth.toInt()
            val lineHeight = metrics.height + 3
            val linesPerPage = (page.imageableHeight.toInt() / lineHeight).coerceAtLeast(1)
            val lines = text.lines().flatMap { source ->
                if (source.isEmpty()) listOf("") else buildList {
                    var remaining = source
                    while (remaining.isNotEmpty()) {
                        var end = remaining.length
                        while (end > 1 && metrics.stringWidth(remaining.substring(0, end)) > width) end--
                        val space = remaining.lastIndexOf(' ', end - 1)
                        val split = if (end < remaining.length && space > 0) space else end
                        add(remaining.substring(0, split))
                        remaining = remaining.substring(split).trimStart()
                    }
                }
            }
            val start = index * linesPerPage
            if (start >= lines.size) Printable.NO_SUCH_PAGE else {
                g.translate(page.imageableX, page.imageableY)
                lines.drop(start).take(linesPerPage).forEachIndexed { row, line ->
                    g.drawString(line, 0, row * lineHeight + metrics.ascent)
                }
                Printable.PAGE_EXISTS
            }
            } finally { g.dispose() }
        }
        if (!onSwing { job.printDialog() }) return false
        job.print()
        return true
    }
}

private fun <T> onSwing(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val task = FutureTask<T> { block() }
    SwingUtilities.invokeAndWait(task)
    return task.get()
}

fun receiptDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm 'WAT'", Locale.UK)
        .withZone(ZoneId.of("Africa/Lagos")).format(Instant.parse(iso))
}.getOrDefault(iso)
