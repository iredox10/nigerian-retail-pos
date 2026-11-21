package pos.data

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.domain.CartLine
import pos.domain.StaffPermission
import pos.output.BackupService
import pos.output.CustomerStatementService
import pos.output.DatedCustomerStatementService
import pos.output.DebtAgeingService
import pos.output.HistoricalCreditService
import pos.output.StatementPdfService
import pos.screens.ReportDateRange

class DebtAdjustmentIntegrityTest {
    @Test fun writeOffAndRestoreReconcileReceiptDebtAndStatementsWithoutCashOrStockMovement() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val customer = repo.addCustomer(shop.id, "Amina")
            val item = repo.addProduct(shop.id, "Rice", "bag", 2_000, 10.0)
            val sale = repo.checkout(shop.id, listOf(CartLine(item, 2)), 0, customer.id)
            repo.recordRepayment(shop.id, customer.id, 1_000)
            val repayment = repo.repayments(shop.id).single()
            assertFails { repo.reviewDebtAdjustment(shop.id, sale.id, 500) }
            repo.allocateRepayment(shop.id, repayment.id, sale.id, 1_000)
            assertFails { repo.reviewDebtAdjustment(shop.id, sale.id, 3_001) }
            val cash = repo.cashEntries(shop.id)
            val stock = repo.products(shop.id).single().exactStockScaled

            val review = repo.reviewDebtAdjustment(shop.id, sale.id, 1_200)
            val adjustment = repo.saveDebtAdjustment(shop.id, review, "Agreed to waive part of the balance", "first")
            assertEquals(1_800L, repo.customers(shop.id).single().balanceMinor)
            assertEquals(1_800L, repo.unallocatedCredit(shop.id, sale.id))
            assertEquals(adjustment, repo.saveDebtAdjustment(shop.id, review, adjustment.reason, adjustment.id))
            assertFails { repo.saveDebtAdjustment(shop.id, review, "Different reason", adjustment.id) }
            assertFails { repo.returnItems(shop.id, sale.id, mapOf(item.id to 1), true, "One bag returned") }

            val date = Instant.parse(adjustment.createdAt).atZone(ZoneId.of("Africa/Lagos")).toLocalDate()
            val credits = DebtAgeingService.build(shop.id, repo.customers(shop.id), repo.sales(shop.id),
                repo.repayments(shop.id), date, repo.repaymentAllocations(shop.id), debtAdjustments = repo.debtAdjustments(shop.id))
            assertEquals(1_800L, credits.single().ageing?.totalMinor)
            assertEquals(1_200L, credits.single().invoiceBalances.single().writtenOffMinor)
            val writeOffStatement = CustomerStatementService.build(shop.id, repo.customers(shop.id).single(), repo.sales(shop.id),
                repo.repayments(shop.id), debtAdjustments = repo.debtAdjustments(shop.id))
            assertContains(CustomerStatementService.csv(writeOffStatement), "Debt written off")
            assertContains(CustomerStatementService.csv(writeOffStatement), "-12.00")
            assertEquals(1_800L, DatedCustomerStatementService.build(writeOffStatement, ReportDateRange(date,date)).closingMinor)

            val restore = repo.reviewDebtAdjustment(shop.id, sale.id, 1_200, adjustment.id)
            repo.saveDebtAdjustment(shop.id, restore, "Agreed original balance is payable", "second")
            assertFails { repo.reviewDebtAdjustment(shop.id, sale.id, 1_200, adjustment.id) }
            assertEquals(3_000L, repo.customers(shop.id).single().balanceMinor)
            assertEquals(cash, repo.cashEntries(shop.id))
            assertEquals(stock, repo.products(shop.id).single().exactStockScaled)
            val updatedStatement = CustomerStatementService.build(shop.id, repo.customers(shop.id).single(), repo.sales(shop.id),
                repo.repayments(shop.id), debtAdjustments = repo.debtAdjustments(shop.id))
            assertContains(StatementPdfService.customer(shop,
                DatedCustomerStatementService.build(updatedStatement, ReportDateRange(date.minusDays(1),date.plusDays(1)))),
                "Debt reinstated")
            val returned = repo.returnItems(shop.id, sale.id, mapOf(item.id to 1), true, "One bag returned")
            assertEquals(2_000L, returned.creditReductionMinor)
            assertEquals(0L, returned.refundMinor)
        }
    }

    @Test fun staleReviewPermissionFailureAndMissingOrChangedRecoveryEvidenceAreAtomic() {
        val directory = Files.createTempDirectory("debt-adjustment-backup-")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val customer = repo.addCustomer(shop.id, "Amina")
                val item = repo.addProduct(shop.id, "Soap", "bar", 500, 10.0)
                val sale = repo.checkout(shop.id, listOf(CartLine(item, 5)), 0, customer.id)
                val stale = repo.reviewDebtAdjustment(shop.id, sale.id, 200)
                repo.checkout(shop.id, listOf(CartLine(item, 1)), 0, customer.id)
                assertFails { repo.saveDebtAdjustment(shop.id, stale, "Waive balance") }
                val review = repo.reviewDebtAdjustment(shop.id, sale.id, 200)
                val before = repo.exportTenantSnapshot(shop.id)
                repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.MANAGE_STAFF) }
                assertFails { repo.saveDebtAdjustment(shop.id, review, "Waive balance") }
                repo.setPermissionCheck(null)
                repo.setActorProvider { error("Sign-in expired") }
                assertFails { repo.saveDebtAdjustment(shop.id, review, "Waive balance") }
                repo.setActorProvider(null)
                assertEquals(before, repo.exportTenantSnapshot(shop.id))

                val adjustment = repo.saveDebtAdjustment(shop.id, review, "Waive balance", "write-off")
                val snapshot = repo.exportTenantSnapshot(shop.id)
                val shops = repo.tenants()
                val missing = snapshot.copy(tables = snapshot.tables - "customer_debt_adjustments")
                assertFails { repo.restoreTenantSnapshot(missing, "Missing") }
                val rows = snapshot.tables.getValue("customer_debt_adjustments")
                val tampered = snapshot.copy(tables = snapshot.tables + ("customer_debt_adjustments" to
                    rows.map { it + ("receipt_before_minor" to "2000") }))
                assertFails { repo.restoreTenantSnapshot(tampered, "Tampered") }
                assertEquals(shops, repo.tenants())
                assertFails { repo.featureExecute("DELETE FROM customer_debt_adjustments WHERE tenant_id=?", shop.id) }

                val file = directory.resolve("debt.posbackup")
                BackupService.create(repo, shop.id, "Example-password-2026", file)
                val restored = repo.restoreTenantSnapshot(BackupService.read(file, "Example-password-2026"), "Recovered")
                assertEquals(adjustment.copy(tenantId = restored.id), repo.debtAdjustments(restored.id).single())
                assertEquals(2_800L, repo.customers(restored.id).single().balanceMinor)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun historicalAgeingSeparatesWriteOffAndReinstatementAcrossShopDates() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val customer = repo.addCustomer(shop.id, "Amina")
            val item = repo.addProduct(shop.id, "Soap", "bar", 1_000, 10.0)
            val sale = repo.checkout(shop.id, listOf(CartLine(item, 2)), 0, customer.id)
                .copy(createdAt = "2026-03-01T09:00:00Z")
            val writeOff = DebtAdjustment(shop.id, "waive",
                DebtAdjustmentReview(sale.id, customer.id, 500, 2_000, 2_000),
                "Waived after review", "2026-03-02T22:59:00Z")
            val restore = DebtAdjustment(shop.id, "restore",
                DebtAdjustmentReview(sale.id, customer.id, 500, 1_500, 1_500, writeOff.id),
                "Restored after agreement", "2026-03-02T23:01:00Z")
            fun debt(day: Int) = HistoricalCreditService.build(shop.id,
                listOf(customer.copy(balanceMinor = 2_000)), listOf(sale), emptyList(), emptyList(), emptyList(),
                LocalDate.of(2026,3,day), debtAdjustments = listOf(writeOff,restore)).rows.single().currentDebtMinor
            assertEquals(2_000L, debt(1))
            assertEquals(1_500L, debt(2))
            assertEquals(2_000L, debt(3))
        }
    }
}
