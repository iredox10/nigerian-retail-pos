package pos.output

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class ReceiptLogoPdfTest {
    private fun logo(width:Int=360,height:Int=120):ByteArray {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
        val graphics=image.createGraphics()
        try {
            graphics.color=Color(8,127,115);graphics.fillRoundRect(0,0,width,height,16,16)
            graphics.color=Color.WHITE;graphics.font=Font("SansSerif",Font.BOLD,30)
            graphics.drawString("SAMPLE SHOP",25,72)
        } finally {graphics.dispose()}
        return ByteArrayOutputStream().use {ImageIO.write(image,"png",it);it.toByteArray()}
    }
    private val receipt=buildString {
        appendLine("Sample shop")
        appendLine("SALES RECEIPT")
        appendLine("Receipt: LOGO-SAMPLE-001")
        repeat(90) {appendLine("${it+1}. Sample goods - 1 bottle x ₦500.00")}
        appendLine("Original sale total: ₦45,000.00")
        appendLine("External payment recorded - not confirmed")
    }

    @Test fun firstPageOnlyLogoReservesSpaceAndKeepsReceiptPaginationOnAllWidths() {
        listOf("A4","58mm","80mm").forEach {paper ->
            Loader.loadPDF(PdfReceiptService.createPdf(receipt,paper,logo())).use {document ->
                assertTrue(document.numberOfPages>1)
                document.pages.forEachIndexed {index,page ->
                    val images=page.resources.xObjectNames.mapNotNull {page.resources.getXObject(it) as? PDImageXObject}
                    assertEquals(if(index==0) 1 else 0,images.size,"Logo must appear on the first page only")
                    images.singleOrNull()?.let {assertEquals(360,it.width);assertEquals(120,it.height)}
                }
                val all=PDFTextStripper().getText(document)
                assertContains(all,"₦45,000.00")
                assertContains(all.replace(Regex("\\s+")," "),"External payment recorded - not confirmed")
                val firstBaselines=mutableListOf<Float>()
                object:PDFTextStripper() {
                    override fun processTextPosition(text:TextPosition) {firstBaselines+=text.yDirAdj;super.processTextPosition(text)}
                }.apply {startPage=1;endPage=1}.getText(document)
                assertTrue(firstBaselines.min()>=if(paper=="A4") 100f else 60f,"Receipt text must begin below the logo")
            }
        }
    }

    @Test fun invalidOrOversizedLogoCannotReplaceAnExistingReceiptOrEnterOtherDocuments() {
        val directory=Files.createTempDirectory("receipt-logo-rejection-")
        try {
            val target=directory.resolve("receipt.pdf")
            Files.writeString(target,"Existing receipt")
            for(bytes in listOf("Not an image".toByteArray(),logo().copyOf(20),logo(1025,5),ByteArray(1_048_577))) {
                assertFails {PdfReceiptService.writePdf(receipt,"A4",target,true,bytes)}
                assertEquals("Existing receipt",Files.readString(target))
            }
            assertFails {PdfDocumentService.createPdf("Statement",PdfDocumentKind.CUSTOMER_STATEMENT,logoPng=logo())}
            assertFails {PdfDocumentService.createPdf("Quotation",PdfDocumentKind.QUOTATION,logoPng=logo())}
            assertEquals(listOf("receipt.pdf"),Files.list(directory).use {it.map {p ->p.fileName.toString()}.toList()})
        } finally {directory.toFile().deleteRecursively()}
    }

    @Test fun optionalReceiptLogoFixturesForActualVisualReview() {
        val directory=System.getenv("POS_RECEIPT_LOGO_QA_DIR")?.let(Path::of) ?: return
        Files.createDirectories(directory)
        listOf("A4","58mm","80mm").forEach {paper ->
            PdfReceiptService.writePdf(receipt,paper,directory.resolve("receipt-logo-$paper.pdf"),replaceExisting=true,logoPng=logo())
        }
    }
}
