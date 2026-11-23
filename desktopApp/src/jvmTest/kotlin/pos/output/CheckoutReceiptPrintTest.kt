package pos.output

import kotlin.test.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import pos.data.*
import pos.domain.*

class CheckoutReceiptPrintTest {
    @Test fun printCopyUsesCompletedSaleAndCapturedBrandingWithoutRecordingAnotherSale() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Workspace")
            repo.saveShopProfile(shop.id, ShopDetails("Original shop", appearance = ReceiptAppearance(style = "modern")),
                0, "Set up receipts", "profile-one")
            val item = repo.addProduct(shop.id, "Soap", "piece", 45000, 10.0)
            val sale = repo.checkout(shop.id, listOf(CartLine(item, 2)), 90000, operationId = "saved-checkout")
            repo.saveShopProfile(shop.id, ShopDetails("Changed shop"), 1, "Change shop branding", "profile-two")
            val before = repo.exportTenantSnapshot(shop.id)
            repeat(2) {
                Loader.loadPDF(savedReceiptPdf(repo, shop.id, sale.id)).use { pdf ->
                    val text = PDFTextStripper().getText(pdf)
                    assertContains(text, "Original shop")
                    assertContains(text, "₦900.00")
                    assertContains(text, "saved-checkout")
                    assertFalse(text.contains("Changed shop"))
                }
            }
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            assertEquals(1, repo.sales(shop.id).size)
        }
    }
}
