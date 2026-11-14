package pos.output

import kotlin.test.*
import pos.domain.*
import pos.screens.ReportDateRange
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class StatementPdfServiceTest {
    private val tenant=Tenant("shop","SAMPLE DATA - Adewale Stores")
    private val from=LocalDate.parse("2026-11-02")
    private val through=LocalDate.parse("2026-11-03")
    private val product=Product("rice","shop","Rice","bag",12345,100.0)
    private val sale=Sale("original-sale","shop",listOf(CartLine(product,1)),12345,2345,10000,"customer","2026-11-01T12:00:00Z")
    private val repayment=CustomerRepayment("debt-payment","shop","customer",3210,"2026-11-02T23:00:00Z")
    private fun customer():DatedCustomerStatement {
        val complete=CustomerStatementService.build("shop",Customer("customer","shop","Current customer name",6790,phone="+2348012345678",address="Current address"),listOf(sale),listOf(repayment))
        return DatedCustomerStatementService.build(complete,ReportDateRange(from,through))
    }
    private val profile=SupplierProfile("supplier-id","shop",SupplierDetails("Current renamed supplier",address="Current supplier address"),false,2,"2026-11-01T00:00:00Z")
    private fun supplier(count:Int=1):SupplierAccountStatement {
        val receipts=(0 until count).map {index ->StockReceipt("purchase-$index","shop","rice","Rice","bag",1,12345,12345,2345,"Historical supplier name $index","2026-11-02T09:00:00Z")}
        val links=receipts.map {SupplierReceiptLink("shop",it.id,profile.id,"link-${it.id}","2026-11-04T00:00:00Z")}
        val payments=listOf(SupplierPayment("payment","shop",receipts.first().id,receipts.first().supplierName,3210,"2026-11-03T09:00:00Z"))
        return SupplierAccountStatementService.build(profile,links,receipts,payments,from,through)
    }
    @Test fun customerShowsExactRecordedDebtChronologyAndWatWithCurrentContacts() {
        val text=StatementPdfService.customer(tenant,customer())
        assertContains(text,"CUSTOMER STATEMENT")
        assertContains(text,"Opening debt: ₦100.00")
        assertContains(text,"2026-11-03 00:00:00 WAT | Cash debt repayment")
        assertContains(text,"Cash received against debt: ₦32.10")
        assertContains(text,"Debt after activity: ₦67.90")
        assertContains(text,"Closing debt: ₦67.90")
        assertContains(text,"current, not historical snapshots")
        assertFalse(text.contains("SALES RECEIPT"))
        val noPayment=DatedCustomerStatementService.build(customer().complete,ReportDateRange(from,from))
        assertContains(StatementPdfService.customer(tenant,noPayment),"Closing debt: ₦100.00")
        assertContains(StatementPdfService.customer(tenant,noPayment),"Current debt, all saved dates: ₦67.90")
    }
    @Test fun supplierKeepsStableAccountAndHistoricNamesWithReconciledExactAmounts() {
        val text=StatementPdfService.supplier(tenant,supplier())
        assertContains(text,"SUPPLIER STATEMENT")
        assertContains(text,"Supplier account ID: supplier-id")
        assertContains(text,"Current supplier name: Current renamed supplier")
        assertContains(text,"Supplier name saved on receipt: Historical supplier name 0")
        assertContains(text,"Purchases in period: ₦123.45")
        assertContains(text,"Cash paid at receipt in period: ₦23.45")
        assertContains(text,"Later payments in period: ₦32.10")
        assertContains(text,"Closing amount owed: ₦67.90")
        assertFalse(text.contains("SALES RECEIPT"))
    }
    @Test fun foreignTenantOrForgedStatementsCannotExport() {
        assertFails {StatementPdfService.customer(tenant.copy(id="other"),customer())}
        assertFails {StatementPdfService.customer(tenant,customer().copy(closingMinor=0))}
        assertFails {StatementPdfService.supplier(tenant.copy(id="other"),supplier())}
        val source=supplier()
        assertFails {StatementPdfService.supplier(tenant,source.copy(statement=source.statement.copy(closingMinor=0)))}
    }
    @Test fun multiPageStatementsRepeatCorrectIdentityAndPreserveEmbeddedNaira() {
        val customer=customer()
        val repeatedSales=(0 until 70).map {sale.copy(id="sale-$it",createdAt="2026-11-02T09:00:00Z")}
        val complete=CustomerStatementService.build("shop",customer.complete.customer.copy(balanceMinor=700000),repeatedSales,emptyList())
        val dated=DatedCustomerStatementService.build(complete,ReportDateRange(from,through))
        val values=listOf(
            Triple(StatementPdfService.customer(tenant,dated),PdfDocumentKind.CUSTOMER_STATEMENT,"Customer ID: customer"),
            Triple(StatementPdfService.supplier(tenant,supplier(65)),PdfDocumentKind.SUPPLIER_STATEMENT,"Supplier account ID: supplier-id")
        )
        values.forEach {(text,kind,reference) ->
            Loader.loadPDF(PdfDocumentService.createPdf(text,kind)).use {document ->
                assertTrue(document.numberOfPages>2)
                assertEquals("Retail POS ${kind.label}",document.documentInformation.title)
                for(page in 2..document.numberOfPages) {
                    val extracted=PDFTextStripper().apply {startPage=page;endPage=page}.getText(document)
                    assertContains(extracted,"${kind.heading} - continued")
                    assertContains(extracted,reference)
                    assertContains(extracted,"Period: 2026-11-02 through 2026-11-03 (inclusive WAT)")
                    assertFalse(extracted.contains("SALES RECEIPT"))
                }
                val extracted=PDFTextStripper().getText(document)
                assertContains(extracted,"₦123.45")
                assertContains(extracted,"Page ${document.numberOfPages} of ${document.numberOfPages}")
                document.getPage(0).resources.fontNames.forEach {assertNotNull(document.getPage(0).resources.getFont(it).fontDescriptor.fontFile2)}
            }
        }
    }
    @Test fun longCurrentContactDetailsWrapWithoutLosingTheHistoricalNameOrClosingAmount() {
        val source=supplier()
        val longName="Long supplier name ".repeat(7).trim()
        val longAddress="Market road with a deliberately long address ".repeat(18).trim()
        val profile=source.profile.copy(details=source.profile.details.copy(name=longName,address=longAddress))
        val value=SupplierAccountStatementService.build(profile,source.links,source.receipts,source.payments,from,through)
        val text=StatementPdfService.supplier(tenant,value)
        Loader.loadPDF(PdfDocumentService.createPdf(text,PdfDocumentKind.SUPPLIER_STATEMENT)).use {document ->
            val extracted=PDFTextStripper().getText(document).replace(Regex("\\s+")," ")
            assertContains(extracted,longName)
            assertContains(extracted,longAddress)
            assertContains(extracted,"Supplier name saved on receipt: Historical supplier name 0")
            assertContains(extracted,"Closing amount owed: ₦67.90")
        }
    }
    @Test fun failedStatementExportPreservesDestinationAndCleansTemporaryFiles() {
        val directory=Files.createTempDirectory("statement-pdf-")
        try {
            val path=directory.resolve("statement.pdf")
            Files.writeString(path,"Existing document")
            val text=StatementPdfService.customer(tenant,customer())
            assertFails {PdfDocumentService.writePdf(text,PdfDocumentKind.CUSTOMER_STATEMENT,"A4",path)}
            val error=assertFailsWith<IllegalArgumentException> {PdfDocumentService.writePdf("Customer 😀",PdfDocumentKind.CUSTOMER_STATEMENT,"A4",path,true)}
            assertContains(error.message.orEmpty(),"Export a CSV statement")
            assertEquals("Existing document",Files.readString(path))
            PdfDocumentService.writePdf(text,PdfDocumentKind.CUSTOMER_STATEMENT,"A4",path,true)
            Loader.loadPDF(path.toFile()).use {assertContains(PDFTextStripper().getText(it),"Closing debt: ₦67.90")}
            assertEquals(listOf("statement.pdf"),Files.list(directory).use {it.map {p ->p.fileName.toString()}.toList()})
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun optionalExplicitSampleFixturesForVisualReview() {
        val directory=System.getenv("POS_STATEMENT_QA_DIR")?.let {Path.of(it)} ?: return
        Files.createDirectories(directory)
        PdfDocumentService.writePdf(StatementPdfService.customer(tenant,customer()),PdfDocumentKind.CUSTOMER_STATEMENT,"A4",directory.resolve("sample-customer-statement.pdf"),true)
        PdfDocumentService.writePdf(StatementPdfService.supplier(tenant,supplier(12)),PdfDocumentKind.SUPPLIER_STATEMENT,"A4",directory.resolve("sample-supplier-statement.pdf"),true)
    }
}
