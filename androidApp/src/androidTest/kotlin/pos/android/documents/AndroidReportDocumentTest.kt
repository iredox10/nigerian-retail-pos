package pos.android.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import pos.screens.ReportDateRange
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AndroidReportDocumentTest {
    @Test fun reportUsesExactLocalDatedRecordsKeepsLimitationsAndRechecksExportAuthority() {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Report shop").id
            val item = repo.addProduct(shop, "Soap", "piece", 12345, 10.0)
            val customer = repo.addCustomer(shop, "Ada").id
            repo.openCashShift(shop, 0)
            repo.checkout(shop, listOf(CartLine(item, 2)), 20000, customer, "report-sale", cashGivenMinor = 20000)
            val other = repo.createTenant("Foreign shop").id
            val foreign = repo.addProduct(other, "Foreign goods", "piece", 99999, 1.0)
            repo.openCashShift(other, 0)
            repo.checkout(other, listOf(CartLine(foreign, 1)), 99999, operationId = "foreign-sale")
            val before = repo.exportTenantSnapshot(shop)
            val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
            val report = repo.prepareAndroidReportDocument(shop, ReportDateRange(today, today))
            assertTrue(report.text.contains("Shop: Report shop"))
            assertTrue(report.text.contains("Original sales total: ${money(24690)}"))
            assertTrue(report.text.contains("Cash applied at sale: ${money(20000)}"))
            assertTrue(report.text.contains("Current customer debt · all dates: ${money(4690)}"))
            assertTrue(report.text.contains("not confirmed")); assertTrue(report.text.contains("not profit"))
            assertTrue(report.text.contains("not debt as of")); assertTrue(report.text.contains("Other devices may have unshared work"))
            assertFalse(report.text.contains("Foreign goods")); assertFalse(report.text.contains("Foreign shop"))
            val empty = repo.prepareAndroidReportDocument(shop, ReportDateRange(today.minusDays(2), today.minusDays(1)))
            assertTrue(empty.text.contains("Original sales total: ${money(0)}"))
            assertTrue(empty.text.contains("Current customer debt · all dates: ${money(4690)}"))
            repo.authorizeAndroidReportSave(shop, report)
            assertThrows(IllegalArgumentException::class.java) { repo.authorizeAndroidReportSave(other, report) }
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.VIEW_REPORTS) { "Reports revoked" } }
            assertThrows(IllegalArgumentException::class.java) { repo.authorizeAndroidReportSave(shop, report) }
            assertThrows(IllegalArgumentException::class.java) { repo.prepareAndroidReportDocument(shop, ReportDateRange(today, today)) }
            repo.setPermissionCheck(null)
            assertEquals(before, repo.exportTenantSnapshot(shop))
        }
    }

    @Test fun nativePdfWrapsAndPaginatesWithinMarginsAndCleansRejectedOversizedOutput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = "Long product " + "W".repeat(4000) + "\n" + (1..130).joinToString("\n") { "Exact amount: ${money(Long.MAX_VALUE)}" }
        val snapshot = AndroidReportDocument("QA", "2026-11-01", "2026-11-28", "2026-11-28T10:00:00Z", text)
        val file = writeAndroidReportPdf(context, snapshot)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue(renderer.pageCount > 1)
                    for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                        assertEquals(595, page.width); assertEquals(842, page.height)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            for (y in 0 until page.height) {
                                assertEquals(Color.WHITE, bitmap.getPixel(10, y))
                                assertEquals(Color.WHITE, bitmap.getPixel(585, y))
                            }
                            assertTrue((25..800).any { y -> (30..565).any { x -> bitmap.getPixel(x, y) != Color.WHITE } })
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { file.delete() }
        val folder = File(context.cacheDir, "reports")
        val before = folder.list()?.toSet().orEmpty()
        assertThrows(IllegalArgumentException::class.java) {
            writeAndroidReportPdf(context, snapshot.copy(text = "too many lines\n".repeat(12000)))
        }
        assertEquals(before, folder.list()?.toSet().orEmpty())
    }
}
