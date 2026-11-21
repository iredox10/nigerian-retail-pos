package pos.output

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import pos.data.PosRepository
import pos.data.receiptProfile
import pos.domain.CartLine
import pos.domain.Customer
import pos.domain.ExternalPayment
import pos.domain.Product
import pos.domain.ReceiptProfileSnapshot
import pos.domain.ReturnLine
import pos.domain.Sale
import pos.domain.SaleReturn
import pos.domain.ShopDetails
import pos.domain.Tenant
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaleDocumentServiceTest {
    private val shop = Tenant("shop-a", "Current shop name")
    private val malt = Product("malt", shop.id, "Malt drink", "bottle", 50_000, 120.0)
    private val soldOn = "2026-11-20T09:00:00Z"
    private val returnedOn = "2026-11-21T09:00:00Z"

    @Test fun invoicePreservesSoldPackTermsAndUnconfirmedPayment() {
        val pack = CartLine(
            malt, 2, sellingUnitId = "carton-id", sellingUnit = "carton",
            unitPriceMinor = 1_100_000, baseUnitsPerUnit = 24,
            discountPerUnitMinor = 100_000, discountReason = "Agreed wholesale price",
        )
        val sale = Sale(
            "split-sale", shop.id, listOf(pack), 2_000_000, 500_000, 300_000, "customer-a", soldOn,
            dueDate = "2026-11-25", externalPayments = listOf(ExternalPayment("transfer", 1_200_000, "bank-ref", "Till")),
        )
        val profile = ReceiptProfileSnapshot(shop.id, sale.id, ShopDetails("Saved shop name", address = "Saved address"), 2, soldOn)
        val customer = Customer("customer-a", shop.id, "Ada", 0)
        val document = SaleDocumentService.invoice(shop, sale, profile, customer)
        assertContains(document, "SALES INVOICE")
        assertContains(document, "Invoice: INV-split-sale")
        assertContains(document, "2 carton × ₦11,000.00")
        assertContains(document, "1 carton = 24 bottle")
        assertContains(document, "Total discounts: ₦2,000.00")
        assertContains(document, "Invoice total: ₦20,000.00")
        assertContains(document, "Transfer not confirmed: ₦12,000.00")
        assertContains(document, "Cash applied at sale: ₦5,000.00")
        assertContains(document, "Customer owed at sale: ₦3,000.00")
        assertContains(document, "Repayment due date agreed at sale: 2026-11-25 (WAT)")
        assertContains(document, "Shop address: Saved address")
        assertContains(document, "Current customer name: Ada")
        assertFalse(document.contains("Current shop name"))
        assertFalse(document.contains("Paid in cash"))
        assertFailsWith<IllegalArgumentException> { SaleDocumentService.invoice(shop, sale, profile.copy(saleId = "another")) }
        assertFailsWith<IllegalArgumentException> { SaleDocumentService.invoice(shop, sale, profile, customer.copy(tenantId = "other")) }
    }

    @Test fun deliveryNoteExcludesRecordedReturnsAndLeavesPaperAcknowledgementBlank() {
        val rice = Product("rice", shop.id, "Loose rice", "kg", 1_000, 10.0, quantityPrecision = 3)
        val sale = Sale("rice-sale", shop.id, listOf(CartLine(rice, 1_500)), 1_500, 1_500, 0, null, soldOn)
        val returned = SaleReturn(
            "a-return", shop.id, sale.id, listOf(ReturnLine(rice.id, rice.name, rice.unit, 250, 1_000, quantityPrecision = 3)),
            250, false, "Customer returned sealed portion", returnedOn,
        )
        val document = SaleDocumentService.deliveryNote(shop, sale, listOf(returned), null)
        assertContains(document, "Delivery note: DN-rice-sale")
        assertContains(document, "1.25 kg · Loose rice")
        assertContains(document, "a-return")
        assertContains(document, "Received by: ___")
        assertFalse(document.contains("₦"))
        assertFailsWith<IllegalArgumentException> {
            SaleDocumentService.deliveryNote(shop, sale, listOf(returned.copy(saleId = "another")), null)
        }
        assertFailsWith<IllegalArgumentException> {
            SaleDocumentService.deliveryNote(shop, sale, listOf(returned, returned), null)
        }
        assertFailsWith<IllegalArgumentException> {
            val fullReturn = returned.copy(lines = listOf(returned.lines.single().copy(quantity = 1_500)), refundMinor = 1_500)
            SaleDocumentService.deliveryNote(shop, sale, listOf(fullReturn), null)
        }
    }

    @Test fun creditNoteCopiesARealReturnWithoutPostingAnotherCashOrDebtMovement() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Sample shop")
            val product = repo.addProduct(tenant.id, "Soap", "piece", 100, 12.0)
            val customer = repo.addCustomer(tenant.id, "Ada")
            repo.openCashShift(tenant.id, 0)
            val sale = repo.checkout(tenant.id, listOf(CartLine(product, 10)), 500, customer.id, "credit-sale")
            val returned = repo.returnItems(tenant.id, sale.id, mapOf(product.id to 6), true, "Wrong variety", "partial-return")
            val before = repo.exportTenantSnapshot(tenant.id)
            val document = SaleDocumentService.creditNote(tenant, sale, returned, repo.receiptProfile(tenant.id, sale.id), customer)
            assertContains(document, "Credit note: CN-partial-return")
            assertContains(document, "Original invoice: INV-credit-sale")
            assertContains(document, "Returned goods value: ₦6.00")
            assertContains(document, "Customer debt reduced: ₦5.00")
            assertContains(document, "Cash refunded: ₦1.00")
            assertEquals(before, repo.exportTenantSnapshot(tenant.id))
        }
    }

    @Test fun brandedPdfsRepeatTheRightIdentityOnContinuationPages() {
        val lines = List(55) { CartLine(malt.copy(id = "malt-$it", name = "Malt drink $it"), 2) }
        val sale = Sale("sale-123", shop.id, lines, 5_500_000, 5_500_000, 0, null, soldOn)
        val returned = SaleReturn(
            "return-456", shop.id, sale.id,
            lines.map { ReturnLine(it.product.id, it.product.name, it.sellingUnit, 1, it.unitPriceMinor) },
            2_750_000, false, "Returned items", returnedOn,
        )
        val logo = ByteArrayOutputStream().use { buffer ->
            ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "PNG", buffer)
            buffer.toByteArray()
        }
        val documents = listOf(
            Triple(PdfDocumentKind.SALES_INVOICE, "INV-sale-123", SaleDocumentService.invoice(shop, sale, null)),
            Triple(PdfDocumentKind.DELIVERY_NOTE, "DN-sale-123", SaleDocumentService.deliveryNote(shop, sale, listOf(returned), null)),
            Triple(PdfDocumentKind.CREDIT_NOTE, "CN-return-456", SaleDocumentService.creditNote(shop, sale, returned, null)),
        )
        for ((kind, reference, text) in documents) {
            Loader.loadPDF(PdfDocumentService.createPdf(text, kind, logoPng = logo)).use { pdf ->
                assertTrue(pdf.numberOfPages > 1)
                val first = pdf.getPage(0).resources
                assertTrue(first.xObjectNames.any { first.getXObject(it) is PDImageXObject })
                for (page in 2..pdf.numberOfPages) {
                    val extracted = PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
                    assertContains(extracted, "${kind.heading} - continued")
                    assertContains(extracted, "${kind.referencePrefix}$reference")
                }
            }
        }
    }
}
