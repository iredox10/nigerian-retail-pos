package pos.output

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import pos.data.*
import pos.domain.*

class SupplierCreditStatementIntegrationTest {
    private val supplier = "Historical wholesaler"
    private val receipt = StockReceipt("purchase", "shop", "soap", "Soap", "piece", 10, 1000, 10000, 0,
        supplier, "2026-11-01T09:00:00Z")
    private val apply = SupplierCreditAllocation("credit-apply", "credit-note", "supplier", receipt.id, 2000,
        "Apply supplier allowance", "2026-11-02T22:59:59.999999999Z", null, "shop")
    private val reverse = apply.copy(id = "credit-undo", reason = "Supplier corrected the allowance",
        createdAt = "2026-11-02T23:00:00Z", reversalOf = apply.id)
    private val returned = PurchaseReturn("return", "shop", receipt.id, 1, 1000, 10000, 10, 9,
        "Return one unopened piece", "2026-11-03T09:00:00Z")
    private val paid = SupplierPayment("payment", "shop", receipt.id, supplier, 3000, "2026-11-03T10:00:00Z")
    private val date = LocalDate.parse("2026-11-03")

    @Test fun datedStatementsAndPdfSeparateCreditReversalCashAndReturnAtWatBoundary() {
        val statement = SupplierStatementService.build("shop", supplier, listOf(receipt), listOf(paid), date, date,
            listOf(returned), listOf(reverse, apply))
        assertEquals(8000L, statement.openingMinor)
        assertEquals(-2000L, statement.creditNotesMinor)
        assertEquals(1000L, statement.returnCreditsMinor)
        assertEquals(3000L, statement.laterPaymentsMinor)
        assertEquals(6000L, statement.closingMinor)
        assertEquals(6000L, statement.currentOutstandingMinor)
        assertEquals(listOf(reverse.id, returned.id, paid.id), statement.events.map { it.id })
        assertEquals(listOf(10000L, 9000L, 6000L), statement.events.map { it.balanceAfterMinor })
        val csv = SupplierStatementService.csv(statement)
        assertContains(csv, "Credit note application reversed")
        assertContains(csv, "\"-20.00\"")
        assertContains(csv, "Credit note adjustment NGN")

        val profile = SupplierProfile("supplier", "shop", SupplierDetails("Current wholesaler name"), false, 1, "2026-11-01T08:00:00Z")
        val link = SupplierReceiptLink("shop", receipt.id, profile.id, "link", "2026-11-01T10:00:00Z")
        val account = SupplierAccountStatementService.build(profile, listOf(link), listOf(receipt), listOf(paid), date, date,
            listOf(returned), listOf(apply, reverse))
        assertEquals(statement.events, account.statement.events)
        assertEquals(6000L, account.statement.closingMinor)
        val accountCsv = SupplierAccountStatementService.csv(account)
        assertContains(accountCsv, "Current wholesaler name")
        assertContains(accountCsv, supplier)
        val pdfText = StatementPdfService.supplier(Tenant("shop", "Shop"), account)
        assertContains(pdfText, "Credit note adjustment: −₦20.00")
        assertContains(pdfText, "Purchase return credit: ₦10.00")
        Loader.loadPDF(PdfDocumentService.createPdf(pdfText, PdfDocumentKind.SUPPLIER_STATEMENT)).use { pdf ->
            val text = PDFTextStripper().getText(pdf).replace(Regex("\\s+"), " ")
            assertContains(text, "Credit note application reversed")
            assertContains(text, "−₦20.00")
            assertContains(text, "₦60.00")
            assertContains(text, supplier)
        }
    }

    @Test fun actualCreditReturnPaymentAndUndoHistoryRestoresToTheSameStatementBalance() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val profile = repo.addSupplierProfile(shop.id, SupplierDetails("Wholesaler"))
            val item = repo.addProduct(shop.id, "Soap", "piece", 1500, 0.0)
            repo.openCashShift(shop.id, 10000)
            val purchase = repo.receiveStockForSupplier(shop.id, item.id, 4, 1000, 0, profile.id, "purchase")
            val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
            val note = repo.recordSupplierCredit(shop.id, profile.id, "CN-001", today.toString(), 1500, "Confirmed supplier allowance", "note")
            val allocation = repo.applySupplierCredit(shop.id, repo.reviewSupplierCredit(shop.id, note.id, purchase.id, 1500), "Apply allowance to purchase", "apply")
            repo.savePurchaseReturn(shop.id, repo.reviewPurchaseReturn(shop.id, purchase.id, 1), "First returned piece", "first-return")
            repo.paySupplierReceipt(shop.id, purchase.id, 500, "cash-payment")
            repo.reverseSupplierCreditApplication(shop.id, allocation.id, "Supplier changed the allowance", "undo")
            repo.savePurchaseReturn(shop.id, repo.reviewPurchaseReturn(shop.id, purchase.id, 1), "Second returned piece", "second-return")
            val result = SupplierStatementService.build(shop.id, profile.details.name, repo.stockReceipts(shop.id),
                repo.supplierPayments(shop.id), today, today, repo.purchaseReturns(shop.id), repo.supplierCreditAllocations(shop.id))
            assertEquals(1500L, result.closingMinor)
            assertEquals(repo.supplierOutstanding(shop.id, purchase.id), result.closingMinor)
            assertEquals(0L, result.creditNotesMinor)
            assertEquals(2000L, result.returnCreditsMinor)
            assertEquals(500L, result.laterPaymentsMinor)
            val snapshot = repo.exportTenantSnapshot(shop.id)
            val restored = repo.restoreTenantSnapshot(snapshot, "Recovered shop")
            val recovered = SupplierStatementService.build(restored.id, profile.details.name, repo.stockReceipts(restored.id),
                repo.supplierPayments(restored.id), today, today, repo.purchaseReturns(restored.id), repo.supplierCreditAllocations(restored.id))
            assertEquals(result.events, recovered.events)
            assertEquals(result.closingMinor, recovered.closingMinor)
            assertEquals(result.currentOutstandingMinor, recovered.currentOutstandingMinor)
            assertEquals(1, repo.supplierPayments(restored.id).size)
        }
    }

    @Test fun rejectsMissingForeignOrAmbiguousCreditAndReturnChronology() {
        fun build(credits: List<SupplierCreditAllocation>, returns: List<PurchaseReturn> = listOf(returned)) =
            SupplierStatementService.build("shop", supplier, listOf(receipt), listOf(paid), date, date, returns, credits)
        assertFails { build(listOf(reverse)) }
        assertFails { build(listOf(apply, reverse.copy(tenantId = "foreign"))) }
        assertFails { build(listOf(apply, reverse.copy(amountMinor = 1500))) }
        assertFails { build(listOf(apply, reverse.copy(createdAt = apply.createdAt))) }
        assertFails { build(listOf(apply, reverse, reverse.copy(id = "duplicate-undo"))) }
        assertFails { build(listOf(apply)) }
        val simultaneous = apply.copy(createdAt = returned.createdAt)
        assertFails { build(listOf(simultaneous)) }
        val profile = SupplierProfile("another-supplier", "shop", SupplierDetails("Different account"), false, 1, "2026-11-01T08:00:00Z")
        val link = SupplierReceiptLink("shop", receipt.id, profile.id, "link", "2026-11-01T10:00:00Z")
        assertFails { SupplierAccountStatementService.build(profile, listOf(link), listOf(receipt), listOf(paid), date, date,
            listOf(returned), listOf(apply, reverse)) }
    }
}
