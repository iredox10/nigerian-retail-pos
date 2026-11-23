package pos.output

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import pos.domain.*

class ModernReceiptDesignTest {
    private val shop = Tenant("modern-shop", "Amina's Market")
    private val appearance = ReceiptAppearance(style = "modern", accentHex = "#087F73",
        headerAlignment = "left", tagline = "Everyday essentials, thoughtfully served",
        policy = "Keep this receipt for returns enquiries.")

    private fun receipt(): String {
        val rice = Product("rice", shop.id, "Premium Nigerian long-grain rice · 5 kg", "bag", 1_250_000, 30.0)
        val soap = Product("soap", shop.id, "Gentle laundry soap · 200 g", "piece", 45_000, 60.0)
        val malt = Product("malt", shop.id, "Malt drink · 330 ml", "bottle", 50_000, 100.0)
        val lines = listOf(CartLine(rice, 1), CartLine(soap, 2),
            CartLine(malt, 3, discountPerUnitMinor = 5_000, discountReason = "Agreed bundle price"))
        val total = cartTotal(lines)
        val sale = Sale("SAMPLE-MODERN-2026-001", shop.id, lines, total, 500_000,
            total - 1_000_000, "sample-customer", "2026-11-23T09:00:00Z",
            dueDate = "2026-11-30", externalPayments = listOf(
                ExternalPayment("transfer", 500_000, "SAMPLE-TRANSFER-001", "Sample shop account")))
        val details = ShopDetails(shop.name, "+2348012345678", "Shop 22, Main Market, Lagos",
            "Thank you for shopping with us.", appearance = appearance)
        return BrandedReceiptService.format(shop, sale,
            profile = ReceiptProfileSnapshot(shop.id, sale.id, details, 1, sale.createdAt))
    }

    @Test fun realisticModernReceiptPreservesItemsDiscountAndUnconfirmedPaymentAcrossPaperWidths() {
        val source = receipt()
        val directory = System.getenv("POS_MODERN_RECEIPT_QA_DIR")?.let(Path::of)
        directory?.let { Files.createDirectories(it) }
        for (paper in listOf("A4", "58mm", "80mm")) {
            val bytes = PdfReceiptService.createPdf(source, paper, appearance = appearance)
            directory?.let { Files.write(it.resolve("modern-realistic-$paper.pdf"), bytes) }
            Loader.loadPDF(bytes).use { document ->
                if (paper == "A4") assertEquals(1, document.numberOfPages, "A short sale and its policy should fit one A4 sheet")
                val normalized = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
                for (required in listOf("Amina's Market", "SAMPLE-MODERN-2026-001", "Premium Nigerian",
                    "Gentle laundry soap", "Malt drink", "₦14,750.00", "₦4,750.00",
                    "Transfer not confirmed", "Agreed bundle price", "This is not the customer's current debt.")) {
                    assertContains(normalized, required)
                }
                document.pages.forEachIndexed { index, page ->
                    object : PDFTextStripper() {
                        override fun processTextPosition(position: TextPosition) {
                            assertTrue(position.xDirAdj >= 5f, "Text starts inside the page")
                            assertTrue(position.xDirAdj + position.widthDirAdj <= page.mediaBox.width - 4f,
                                "Text ends inside the page")
                            assertTrue(position.yDirAdj in 5f..(page.mediaBox.height - 4f),
                                "Text stays above the page edge")
                            super.processTextPosition(position)
                        }
                    }.apply { startPage = index + 1; endPage = index + 1 }.getText(document)
                }
            }
        }
    }
}
