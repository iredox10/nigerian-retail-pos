package pos.output

import kotlin.test.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import pos.domain.ReceiptAppearance

class ModernReceiptLayoutTest {
    @Test fun longNamesLargeTotalsAndUnconfirmedPaymentRemainReadableInsideModernPages() {
        val source = """Amina and Sons Neighbourhood Grocery and Household Supplies
Phone: +2348012345678
SALES RECEIPT
Receipt: RECEIPT-WITH-A-LONG-IMMUTABLE-IDENTIFIER-2026-001
Date: 23 November 2026 - 10:30 WAT
----------------------------------------
A deliberately long product name for the stocked household goods
  2 pieces x ₦1,250.00
  ₦2,500.00
----------------------------------------
Original sale total: ₦92,233,720,368,547,758.07
Cash applied at sale: ₦2,500.00
External payment recorded - not confirmed
Customer owes ₦92,233,720,368,545,258.07
"""
        for (paper in listOf("A4", "58mm", "80mm")) {
            Loader.loadPDF(PdfReceiptService.createPdf(source, paper,
                appearance = ReceiptAppearance(style = "modern", frame = "double", headerAlignment = "center"))).use { document ->
                val text = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
                assertContains(text, "Original sale total:")
                assertContains(text, "₦92,233,720,368,547,758.07")
                assertContains(text, "External payment recorded - not confirmed")
                assertContains(text, "₦92,233,720,368,545,258.07")
                document.pages.forEachIndexed { index, page ->
                    object : PDFTextStripper() {
                        override fun processTextPosition(position: TextPosition) {
                            assertTrue(position.xDirAdj >= 8f)
                            assertTrue(position.xDirAdj + position.widthDirAdj <= page.mediaBox.width - 7f)
                            assertTrue(position.yDirAdj in 7f..(page.mediaBox.height - 7f))
                            super.processTextPosition(position)
                        }
                    }.apply { startPage = index + 1; endPage = index + 1 }.getText(document)
                }
            }
        }
    }

    @Test fun shortModernThermalReceiptsFitContentAndRetainPageIdentity() {
        val source = "Sample shop\nSALES RECEIPT\nReceipt: SHORT-001\nOriginal sale total: ₦450.00\nPaid in cash"
        Loader.loadPDF(PdfReceiptService.createPdf(source, "80mm",
            appearance = ReceiptAppearance(style = "modern"))).use { document ->
            assertEquals(1, document.numberOfPages)
            assertTrue(document.getPage(0).mediaBox.height < 200f * 72f / 25.4f)
            assertTrue(document.getPage(0).mediaBox.height >= 80f * 72f / 25.4f)
            assertContains(PDFTextStripper().getText(document), "Page 1 of 1")
        }
    }
}
