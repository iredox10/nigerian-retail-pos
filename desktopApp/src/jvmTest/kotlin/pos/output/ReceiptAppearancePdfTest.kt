package pos.output

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import pos.domain.ReceiptAppearance

class ReceiptAppearancePdfTest {
    private val receipt = buildString {
        appendLine("Amina's sample shop")
        appendLine("Phone: +2348012345678")
        appendLine("SALES RECEIPT")
        appendLine("Receipt: DESIGN-SAMPLE-001")
        repeat(85) { appendLine("${it + 1}. Soap - 1 piece x ₦450.00") }
        appendLine("Original sale total: ₦38,250.00")
        appendLine("Cash applied at sale: ₦700.00")
        appendLine("External payment recorded - not confirmed")
        appendLine("Customer owes ₦37,550.00")
    }

    private fun artwork(): ByteArray {
        val image = BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB)
        val canvas = image.createGraphics()
        try {
            canvas.color = Color.WHITE
            canvas.fillRect(0, 0, 400, 100)
            canvas.color = Color(8, 127, 115)
            canvas.fillRect(20, 20, 360, 60)
        } finally { canvas.dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
    }

    @Test fun styledReceiptsRetainFinancialTextAndPaginateArtworkInsideEveryPaperWidth() {
        for (paper in listOf("A4", "58mm", "80mm")) {
            for (style in listOf("classic", "modern", "compact")) {
                val appearance = ReceiptAppearance(style = style, frame = "double", accentHex = "#175CD3",
                    tagline = "Everyday goods, close to home", policy = "Bring your receipt for return enquiries.")
                Loader.loadPDF(PdfReceiptService.createPdf(receipt, paper, artwork(), appearance, artwork())).use { document ->
                    assertTrue(document.numberOfPages > 1)
                    val text = PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
                    assertContains(text, "₦38,250.00")
                    assertContains(text, "External payment recorded - not confirmed")
                    assertContains(text, "Customer owes ₦37,550.00")
                    assertContains(text, "Everyday goods, close to home")
                    assertContains(text, "Bring your receipt for return enquiries.")
                    assertEquals(2, document.pages.sumOf { page ->
                        page.resources.xObjectNames.count { page.resources.getXObject(it) is PDImageXObject }
                    })
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
                    assertTrue(PDFRenderer(document).renderImageWithDPI(0, 72f).width > 100)
                }
            }
        }
    }

    @Test fun hiddenLogoAndVisualStylesProduceDistinctOutputWithoutDroppingReceiptIdentity() {
        val short = "Sample shop\nSALES RECEIPT\nReceipt: SAMPLE-1\nOriginal sale total: ₦450.00\nPaid in cash"
        val fingerprints = mutableSetOf<Int>()
        for (style in listOf("classic", "modern", "compact")) {
            Loader.loadPDF(PdfReceiptService.createPdf(short, logoPng = artwork(),
                appearance = ReceiptAppearance(style = style, showLogo = false, headerAlignment = "left", frame = "line"))).use { document ->
                assertTrue(document.getPage(0).resources.xObjectNames.none())
                assertContains(PDFTextStripper().getText(document), "Receipt: SAMPLE-1")
                val image = PDFRenderer(document).renderImageWithDPI(0, 72f)
                fingerprints += image.getRGB(0, 0, image.width, image.height, null, 0, image.width).contentHashCode()
            }
        }
        assertEquals(3, fingerprints.size)
    }

    @Test fun badArtworkPreservesExistingPdf() {
        val directory = Files.createTempDirectory("receipt-artwork-")
        try {
            val file = directory.resolve("receipt.pdf")
            Files.writeString(file, "Existing receipt")
            assertFails { PdfReceiptService.writePdf(receipt, "A4", file, true,
                appearance = ReceiptAppearance(style = "modern"), artworkPng = byteArrayOf(1, 2, 3)) }
            assertEquals("Existing receipt", Files.readString(file))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun optionalStyledReceiptFixturesForVisualReview() {
        val directory = System.getenv("POS_RECEIPT_APPEARANCE_QA_DIR")?.let(Path::of) ?: return
        Files.createDirectories(directory)
        for (paper in listOf("A4", "58mm", "80mm")) {
            for (style in listOf("classic", "modern", "compact")) {
                PdfReceiptService.writePdf(receipt, paper, directory.resolve("receipt-$style-$paper.pdf"), true,
                    artwork(), ReceiptAppearance(style = style, frame = "double", tagline = "Everyday goods, close to home",
                        policy = "Bring your receipt for return enquiries."), artwork())
            }
        }
    }
}
