package pos.output

import kotlin.test.*
import pos.domain.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.rendering.PDFRenderer
import java.nio.file.Files
import java.nio.file.Path

class PdfReceiptServiceTest {
    private val receipt=buildString {
        appendLine("Adewale Stores - Sample receipt")
        appendLine("Phone: +2348012345678")
        appendLine("Address: Main Market, shop 22, Lagos")
        appendLine("SALES RECEIPT")
        appendLine("Receipt: SAMPLE-2026-001")
        appendLine("Date: 14 November 2026 - Sample data")
        appendLine("----------------------------------------")
        repeat(65) {index ->
            appendLine("${index+1}. Premium Nigerian rice with a deliberately long item name")
            appendLine("  1.375 kg x ₦1,000.00")
            appendLine("  ₦1,375.00")
        }
        appendLine("Original sale total: ₦89,375.00")
        appendLine("Cash applied at sale: ₦89,375.00")
        appendLine("Paid in cash")
        appendLine("Thank you for shopping with us")
        appendLine("SAMPLE DATA - not a real merchant transaction")
    }
    @Test fun allPaperWidthsEmbedNairaFontWrapAndPaginateWithoutLosingAmounts() {
        for(paper in listOf("A4","58mm","80mm")) {
            val bytes=PdfReceiptService.createPdf(receipt,paper)
            Loader.loadPDF(bytes).use {document ->
                assertTrue(document.numberOfPages>1)
                val first=document.getPage(0)
                val expected=when(paper) {"58mm" -> 58f*72/25.4f;"80mm" ->80f*72/25.4f;else ->595.276f}
                assertEquals(expected,first.mediaBox.width,0.01f)
                val text=PDFTextStripper().getText(document)
                val normalized=text.replace(Regex("\\s+")," ")
                assertContains(text,"Adewale Stores")
                assertContains(text,"₦89,375.00")
                assertContains(text,"Page 1 of ${document.numberOfPages}")
                assertContains(text,"Page ${document.numberOfPages} of ${document.numberOfPages}")
                assertContains(normalized,"not a real merchant transaction")
                first.resources.fontNames.forEach {name ->assertNotNull(first.resources.getFont(name).fontDescriptor.fontFile2)}
                val image=PDFRenderer(document).renderImageWithDPI(0,96f)
                assertTrue(image.width>100 && image.height>500)
            }
        }
    }
    @Test fun unsupportedGlyphAndControlCharacterHaveClearErrors() {
        val unsupported=assertFailsWith<IllegalArgumentException> {PdfReceiptService.createPdf("Shop\nProduct 😀")}
        assertContains(unsupported.message.orEmpty(),"Export a text receipt instead")
        assertFails {PdfReceiptService.createPdf("Shop\nHidden\u0000data")}
        assertFails {PdfReceiptService.createPdf("Shop","Unknown")}
    }
    @Test fun failedOrUnapprovedReplacementPreservesExistingFiles() {
        val directory=Files.createTempDirectory("pdf-receipt-")
        try {
            val target=directory.resolve("receipt.pdf")
            Files.writeString(target,"Original file")
            assertFails {PdfReceiptService.writePdf(receipt,"A4",target)}
            assertEquals("Original file",Files.readString(target))
            assertFails {PdfReceiptService.writePdf("Shop\nUnsupported 😀","A4",target,true)}
            assertEquals("Original file",Files.readString(target))
            PdfReceiptService.writePdf("Sample shop\nOriginal sale total: ₦1,203.13\nPaid in cash","80mm",target,true)
            Loader.loadPDF(target.toFile()).use {assertContains(PDFTextStripper().getText(it),"₦1,203.13")}
            assertEquals(listOf("receipt.pdf"),Files.list(directory).use {it.map {path ->path.fileName.toString()}.toList()})
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun brandedReceiptUsesCapturedContactsAndRefusesForeignSnapshots() {
        val shop=Tenant("shop","Current workspace")
        val item=Product("soap",shop.id,"Soap","piece",100,10.0)
        val sale=Sale("sale",shop.id,listOf(CartLine(item,1)),100,100,0,null,"2026-11-14T00:00:00Z")
        val profile=ReceiptProfileSnapshot(shop.id,sale.id,ShopDetails("Captured name",address="Captured market",footer="Thank you",receiptPaper="58mm"),1,sale.createdAt)
        val text=BrandedReceiptService.format(shop,sale,profile=profile)
        assertTrue(text.startsWith("Captured name\nAddress: Captured market\nSALES RECEIPT"))
        assertContains(text,"Original sale total: ₦1.00");assertTrue(text.endsWith("Thank you\n"))
        assertFails {BrandedReceiptService.format(shop,sale,profile=profile.copy(saleId="different"))}
        assertContains(BrandedReceiptService.format(shop,sale),"not captured for this sale")
    }
    @Test fun optionalExplicitSampleFixtureForVisualReview() {
        val output=System.getenv("POS_RECEIPT_QA_PDF") ?: return
        val sample="""Adewale Stores - Sample data
Phone: +2348012345678
Address: Main Market, shop 22, Lagos
SALES RECEIPT
Receipt: SAMPLE-2026-001
Date: 14 November 2026 - Sample data
----------------------------------------
Loose rice with a long descriptive name
  1.375 kg x ₦1,000.00
  Basket discount share: ₦171.87
  ₦1,203.13
----------------------------------------
Before discounts: ₦1,375.00
Total discount: ₦171.87
Original sale total: ₦1,203.13
Cash applied at sale: ₦1,203.13
Paid in cash

Saved sale record. Copies do not create
another payment or sale.
Thank you for shopping with us.
SAMPLE DATA - not a real transaction
"""
        PdfReceiptService.writePdf(sample,"80mm",Path.of(output),replaceExisting=true)
    }
}
