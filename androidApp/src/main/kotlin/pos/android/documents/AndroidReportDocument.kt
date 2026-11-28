package pos.android.documents

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import pos.data.PosRepository
import pos.domain.*
import pos.output.*
import pos.screens.ReportDateRange
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** Local snapshot only: never implies that other devices have finished sharing records. */
data class AndroidReportDocument(val tenantId: String, val from: String, val through: String, val generatedAt: String, val text: String)

fun PosRepository.prepareAndroidReportDocument(tenantId: String, range: ReportDateRange): AndroidReportDocument =
    featureRead(tenantId, StaffPermission.VIEW_REPORTS) {
        val sales = sales(tenantId); val returns = saleReturns(tenantId)
        val products = products(tenantId, true); val events = auditEvents(tenantId)
        val selected = sales.filter { range.contains(it.createdAt) }
        fun total(values: List<Long>) = values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }.longValueExact()
        val generated = Instant.now().toString()
        val text = buildString {
            appendLine("Saved local records report")
            appendLine("Shop: ${tenants().single { it.id == tenantId }.name}")
            appendLine("${range.from} through ${range.through} · Inclusive dates in West Africa Time")
            appendLine("Generated UTC: $generated")
            appendLine("Includes only this shop's records currently saved on this device. Other devices may have unshared work. Cloud completeness is not established by this report.")
            appendLine()
            appendLine("SALES AND CASH")
            appendLine("Original sales total: ${money(total(selected.map { it.totalMinor }))}")
            appendLine("Cash applied at sale: ${money(total(selected.map { it.cashMinor }))}")
            appendLine("Customer credit granted at sale: ${money(total(selected.map { it.creditMinor }))}")
            appendLine("External amounts recorded · not confirmed: ${money(total(selected.map { it.externalRecordedMinor }))}")
            appendLine("Cash sale refunds in range: ${money(total(returns.filter { range.contains(it.createdAt) }.map { it.refundMinor }))}")
            appendLine("Cash expenses recorded: ${money(total(expenses(tenantId).filter { range.contains(it.createdAt) }.map { it.amountMinor }))}")
            appendLine("Net signed cash movements: ${money(total(cashEntries(tenantId).filter { range.contains(it.createdAt) }.map { it.amountMinor }))}")
            appendLine("Net cash movements exclude opening counts. They are not the current drawer balance or profit.")
            appendLine("Current customer debt · all dates: ${money(total(customers(tenantId).map { it.balanceMinor }))}")
            appendLine("Current debt is a present balance, not debt as of the selected end date.")
            appendLine("Profit is unavailable: complete cost and expense recognition is still required.")
            appendLine()
            appendLine("PRODUCT SALES AND RETURNS")
            val productRows = ProductPerformanceService.build(tenantId, sales, returns, range)
            if (productRows.isEmpty()) appendLine("No product activity in this period.")
            productRows.forEach { row ->
                appendLine(row.name)
                appendLine("Sales less returned goods value: ${money(row.netSalesMinor)}")
                appendLine("Sold ${quantityLabel(row.soldBaseQuantity, row.quantityPrecision)} ${row.baseUnit} · Returned ${quantityLabel(row.returnedBaseQuantity, row.quantityPrecision)} ${row.baseUnit}")
            }
            appendLine()
            appendLine("CURRENT-CATEGORY COMPARISON")
            val comparison = CategoryPerformanceService.build(tenantId, products, sales, returns, range)
            appendLine("Compared with ${comparison.previous.from} through ${comparison.previous.through} · WAT")
            appendLine("Both periods use current catalogue categories. Historical category changes are not reconstructed.")
            if (comparison.rows.isEmpty()) appendLine("No category activity in these periods.")
            comparison.rows.forEach { row ->
                appendLine(row.category)
                appendLine("Sales less returned goods value: ${money(row.netMinor)}")
                appendLine("Previous period: ${money(row.previousNetMinor)} · Change: ${money(row.changeMinor)}")
            }
            appendLine()
            appendLine("RECORDED STAFF ACTIVITY")
            appendLine("This is recorded activity, not commission or cash custody. Older records may have no staff identity.")
            val staffRows = StaffPerformanceService.build(tenantId, sales, returns, events, range)
            if (staffRows.isEmpty()) appendLine("No recorded staff activity in this period.")
            staffRows.forEach { row ->
                appendLine(row.actor?.name ?: "Staff identity not recorded")
                appendLine("Sales recorded: ${row.salesCount}")
                appendLine("Sales after discounts: ${money(row.salesMinor)}")
                appendLine("Cash refunds recorded: ${money(row.refundsMinor)} · Customer debt reduced: ${money(row.debtReducedMinor)}")
            }
        }
        require(text.length <= 1_000_000) { "Report is too large. Choose a smaller period." }
        AndroidReportDocument(tenantId, range.from.toString(), range.through.toString(), generated, text)
    }

fun PosRepository.authorizeAndroidReportSave(tenantId: String, report: AndroidReportDocument) {
    require(report.tenantId == tenantId) { "This report belongs to another shop. Generate it again." }
    featureRead(tenantId, StaffPermission.VIEW_REPORTS) { Unit }
}

/** A4 output uses wrapping and bounded pagination; an interrupted write leaves no cache artifact. */
fun writeAndroidReportPdf(context: Context, report: AndroidReportDocument): File {
    require(report.text.isNotBlank() && report.text.length <= 1_000_000) { "Generate a bounded report again." }
    val folder = File(context.cacheDir, "reports").apply { check(mkdirs() || isDirectory) }
    val file = File(folder, "report-${UUID.randomUUID()}.pdf")
    val pdf = PdfDocument()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(24, 34, 48); typeface = Typeface.DEFAULT }
    var page: PdfDocument.Page? = null
    var count = 0
    var y = 0f
    fun finish() {
        page?.let {
            val footer = Paint(paint).apply { textSize = 9f; color = Color.DKGRAY; typeface = Typeface.DEFAULT }
            it.canvas.drawText("Local report · Page $count", 32f, 814f, footer)
            pdf.finishPage(it)
        }
        page = null
    }
    fun start() {
        require(count < 200) { "Report exceeds 200 pages. Choose a smaller period." }
        page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++count).create())
        val heading = Paint(paint).apply { textSize = 10f; color = Color.rgb(8, 127, 115); typeface = Typeface.DEFAULT_BOLD }
        page!!.canvas.drawText("RETAIL POS · LOCAL REPORT", 32f, 35f, heading)
        y = 61f
    }
    try {
        start()
        for (paragraph in report.text.split('\n')) {
            if (paragraph.isEmpty()) { y += 8f; continue }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                if (y > 785f) { finish(); start() }
                val fit = paint.breakText(remaining, true, 531f, null).coerceAtLeast(1)
                val end = if (fit < remaining.length) remaining.lastIndexOf(' ', fit).takeIf { it > 0 } ?: fit else fit
                page!!.canvas.drawText(remaining.substring(0, end), 32f, y, paint)
                y += 16f
                remaining = remaining.substring(end).trimStart()
            }
        }
        finish()
        FileOutputStream(file).use { pdf.writeTo(it); it.fd.sync() }
        return file
    } catch (failure: Throwable) { file.delete(); throw failure }
    finally { pdf.close() }
}
