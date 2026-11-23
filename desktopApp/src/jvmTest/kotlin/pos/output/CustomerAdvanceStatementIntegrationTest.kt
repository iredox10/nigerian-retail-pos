package pos.output

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.data.*
import pos.domain.*
import pos.screens.ReportDateRange

class CustomerAdvanceStatementIntegrationTest {
    private fun statement(repo: PosRepository, shop: String, customer: String) = CustomerStatementService.build(
        shop, repo.customers(shop).single { it.id == customer }, repo.sales(shop), repo.repayments(shop),
        repo.creditReturnReductions(shop), repo.debtAdjustments(shop,customer), repo.advanceFundedRepaymentIds(shop),
    )

    @Test fun statementSeparatesAdvanceFundingFromCashRepaymentAndReconcilesDrawerThroughEncryptedRecovery() {
        val directory = Files.createTempDirectory("advance-statement-test-")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val customer = repo.addCustomer(shop.id,"Amina")
                val shift = repo.openCashShift(shop.id,1000)
                repo.receiveCustomerAdvance(shop.id,customer.id,5000,0,0,"Cash held for later use","advance")
                val item = repo.addProduct(shop.id,"Soap","piece",1000,12.0)
                val sale = repo.checkout(shop.id,listOf(CartLine(item,6)),0,customer.id,"sale")
                repo.recordRepayment(shop.id,customer.id,1000,"cash-payment")
                val cashPayment = repo.repayments(shop.id,customer.id).single()
                repo.allocateRepayment(shop.id,cashPayment.id,sale.id,1000,"cash-assignment")
                val applied = repo.applyCustomerAdvance(shop.id,customer.id,sale.id,2000,5000,1,5000,5000,"Use held customer money","apply")
                repo.refundCustomerAdvance(shop.id,customer.id,500,3000,2,"Return unused advance cash","refund")

                val complete = statement(repo,shop.id,customer.id)
                assertEquals(6000L,complete.creditGrantedMinor)
                assertEquals(3000L,complete.repaidMinor)
                assertEquals(3000L,complete.customer.balanceMinor)
                assertEquals(3,complete.events.size)
                val advanceEvent = complete.events.single { it.id == applied.repaymentId }
                assertTrue(advanceEvent.advanceFunded)
                assertEquals("Customer advance applied",advanceEvent.activity)
                assertEquals(-2000L,advanceEvent.debtDeltaMinor)
                val cashEvent = complete.events.single { it.id == cashPayment.id }
                assertFalse(cashEvent.advanceFunded)
                assertEquals("Cash debt repayment",cashEvent.activity)
                assertEquals(2500L,repo.customerAdvanceBalance(shop.id,customer.id))
                assertEquals(6500L,repo.expectedCash(shop.id,shift.id))
                assertEquals(listOf(1000L),repo.cashEntries(shop.id).filter { it.kind == "repayment" }.map { it.amountMinor })
                assertEquals(listOf(5000L),repo.cashEntries(shop.id).filter { it.kind == "customer_advance" }.map { it.amountMinor })
                assertEquals(listOf(-500L),repo.cashEntries(shop.id).filter { it.kind == "customer_advance_refund" }.map { it.amountMinor })

                val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
                val range = ReportDateRange(today,today)
                val dated = DatedCustomerStatementService.build(complete,range)
                assertEquals(0L,dated.openingMinor)
                assertEquals(3000L,dated.closingMinor)
                assertEquals(listOf(6000L,5000L,3000L),dated.events.map { it.second })
                val csv = CustomerStatementService.csv(complete)
                assertContains(csv,"Customer advance applied")
                assertContains(csv,"Cash debt repayment")
                val datedCsv = DatedCustomerStatementService.csv(complete,range)
                assertContains(datedCsv,"Customer advance applied")
                assertEquals(1,Regex("Later cash repayment").findAll(datedCsv).count())
                val pdfText = StatementPdfService.customer(shop,dated)
                assertContains(pdfText,"Customer advance applied: ₦20.00")
                assertContains(pdfText,"Cash received against debt: ₦10.00")
                assertContains(pdfText,"Closing debt: ₦30.00")
                assertFalse(pdfText.contains("Cash received against debt: ₦20.00"))

                val file = directory.resolve("customer-advance.posbackup")
                val password = "advance-statement-test-password"
                BackupService.create(repo,shop.id,password,file)
                val restored = repo.restoreTenantSnapshot(BackupService.read(file,password),"Recovered")
                val recovered = statement(repo,restored.id,customer.id)
                assertEquals(complete.events.map { it.activity },recovered.events.map { it.activity })
                assertEquals(csv,CustomerStatementService.csv(recovered))
                assertEquals(datedCsv,DatedCustomerStatementService.csv(recovered,range))
                assertEquals(2500L,repo.customerAdvanceBalance(restored.id,customer.id))
                assertEquals(6500L,repo.expectedCash(restored.id,shift.id))
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun receivingAndRefundingHeldCashLeavesReceiptDebtStatementUnchanged() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val customer=repo.addCustomer(shop.id,"Amina")
            val item=repo.addProduct(shop.id,"Rice","bag",1000,8.0)
            val sale=repo.checkout(shop.id,listOf(CartLine(item,1)),0,customer.id,"sale")
            val before=statement(repo,shop.id,customer.id)
            val shift=repo.openCashShift(shop.id,0)
            repo.receiveCustomerAdvance(shop.id,customer.id,2000,0,0,"Customer funds held","advance")
            assertEquals(before,statement(repo,shop.id,customer.id))
            repo.refundCustomerAdvance(shop.id,customer.id,2000,2000,1,"Full unused amount refunded","refund")
            assertEquals(before,statement(repo,shop.id,customer.id))
            assertEquals(1000L,repo.unallocatedCredit(shop.id,sale.id))
            assertEquals(0L,repo.customerAdvanceBalance(shop.id,customer.id))
            assertEquals(0L,repo.expectedCash(shop.id,shift.id))
            assertTrue(repo.repayments(shop.id).isEmpty())
            assertTrue(repo.advanceFundedRepaymentIds(shop.id).isEmpty())
        }
    }
}
