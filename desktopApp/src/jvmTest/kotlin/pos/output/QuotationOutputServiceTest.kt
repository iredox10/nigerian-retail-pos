package pos.output

import kotlin.test.*
import pos.domain.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class QuotationOutputServiceTest {
    private val shop=Tenant("shop","SAMPLE DATA - Quotation Shop")
    private val line=QuotationLine("rice","Long descriptive Nigerian rice name","kg",3,null,"kg",1,3,1375,12345)
    private val quote=Quotation("sample-quotation","shop",QuotationStatus.ISSUED,1,"2026-11-14T09:00:00Z","2026-11-14T09:00:00Z","2026-11-21",QuotationCustomer("customer","Frozen customer",address="Frozen contact address"),"Collect after a separate sale",listOf(line))
    @Test fun frozenTermsAreDistinctFromSaleAndIncludeWatExpiryAndExactMoney() {
        val text=QuotationOutputService.format(shop,quote,Instant.parse("2026-11-20T00:00:00Z"))
        assertContains(text,"QUOTATION")
        assertContains(text,"Quotation: sample-quotation | revision 1")
        assertContains(text,"Revision recorded: 2026-11-14 10:00:00 WAT")
        assertContains(text,"Valid through: 2026-11-21 (whole day, WAT)")
        assertContains(text,"Quoted address: Frozen contact address")
        assertContains(text,"1.375 kg x ₦123.45 = ₦169.74")
        assertContains(text,"Agreed quotation total: ₦169.74")
        assertFalse(text.contains("SALES RECEIPT"));assertFalse(text.contains("expiry date has passed"))
        assertContains(QuotationOutputService.format(shop,quote,Instant.parse("2026-11-21T23:00:00Z")),"expiry date has passed")
        assertFails {QuotationOutputService.format(shop.copy(id="foreign"),quote)}
    }
    @Test fun longQuotesPaginateWithQuotationRevisionIdentityAndEmbeddedNairaFont() {
        val quote=quote.copy(lines=(0 until 100).map {line.copy(productId="goods-$it",productName="Goods $it with a deliberately long descriptive name for wrapping in this quotation")})
        Loader.loadPDF(PdfDocumentService.createPdf(QuotationOutputService.format(shop,quote),PdfDocumentKind.QUOTATION)).use {document ->
            assertTrue(document.numberOfPages>3)
            assertEquals("Retail POS quotation",document.documentInformation.title)
            for(page in 2..document.numberOfPages) {
                val text=PDFTextStripper().apply {startPage=page;endPage=page}.getText(document)
                assertContains(text,"QUOTATION - continued")
                assertContains(text,"Quotation: sample-quotation | revision 1")
                assertFalse(text.contains("SALES RECEIPT"))
            }
            val text=PDFTextStripper().getText(document)
            assertContains(text,"₦16,974.00")
            assertContains(text,"Page ${document.numberOfPages} of ${document.numberOfPages}")
            document.getPage(0).resources.fontNames.forEach {assertNotNull(document.getPage(0).resources.getFont(it).fontDescriptor.fontFile2)}
        }
    }
    @Test fun unsupportedPdfGlyphSuggestsTextQuotationWithoutCreatingAFile() {
        val value=quote.copy(lines=listOf(line.copy(productName="Rice 😀")))
        val error=assertFailsWith<IllegalArgumentException> {PdfDocumentService.createPdf(QuotationOutputService.format(shop,value),PdfDocumentKind.QUOTATION)}
        assertContains(error.message.orEmpty(),"Export a text quotation")
    }
    @Test fun textExportIsUtf8GuardedAndTemporaryFilesDoNotRemain() {
        val folder=Files.createTempDirectory("quotation-text-")
        try {
            val path=folder.resolve("quotation.txt")
            QuotationOutputService.writeText("Frozen quotation ₦169.74",path)
            assertEquals("Frozen quotation ₦169.74",Files.readString(path))
            assertFails {QuotationOutputService.writeText("Unexpected replacement",path)}
            assertEquals("Frozen quotation ₦169.74",Files.readString(path))
            QuotationOutputService.writeText("Reviewed quotation ₦169.74",path,true)
            assertEquals("Reviewed quotation ₦169.74",Files.readString(path))
            assertEquals(listOf("quotation.txt"),Files.list(folder).use {it.map {p ->p.fileName.toString()}.toList()})
        } finally {folder.toFile().deleteRecursively()}
    }
    @Test fun optionalExplicitSampleForVisualReview() {
        val output=System.getenv("POS_QUOTATION_QA_PDF") ?: return
        val value=quote.copy(lines=listOf(line,QuotationLine("soap","Soap cartons","piece",0,"carton","carton",12,0,2,9000)))
        PdfDocumentService.writePdf(QuotationOutputService.format(shop,value),PdfDocumentKind.QUOTATION,"A4",Path.of(output),true)
    }
}
