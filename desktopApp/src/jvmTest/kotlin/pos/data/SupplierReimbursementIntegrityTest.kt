package pos.data

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.domain.StaffPermission
import pos.output.BackupService
import pos.output.SupplierStatementService

class SupplierReimbursementIntegrityTest {
    @Test fun returningPartPaidGoodsReducesDebtAndRecordsTheCashReceivedSeparately() {
        val directory = Files.createTempDirectory("supplier-reimbursement-")
        val file = directory.resolve("shop.backup")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val item = repo.addProduct(shop.id, "Rice", "bag", 1_500, 0.0)
                val shift = repo.openCashShift(shop.id, 1_000)
                val receipt = repo.receiveStock(shop.id, item.id, 3, 1_000, 800, "Supplier")
                assertEquals(200L, repo.expectedCash(shop.id, shift.id))
                assertFails { repo.reviewPurchaseReturn(shop.id, receipt.id, 3) }
                val review = repo.reviewPurchaseReturn(shop.id, receipt.id, 3, receiveCash = true)
                assertEquals(2_200L, review.creditMinor)
                assertEquals(800L, review.cashReceivedMinor)
                assertEquals(shift.id, review.cashShiftId)
                val event = repo.savePurchaseReturn(shop.id, review, "Cash received for returned bags", "return")
                assertEquals(event, repo.savePurchaseReturn(shop.id, review, event.reason, "return"))
                assertEquals(0L, repo.supplierOutstanding(shop.id, receipt.id))
                assertEquals(0L, repo.products(shop.id).single().exactStockScaled)
                assertEquals(1_000L, repo.expectedCash(shop.id, shift.id))
                assertEquals(800L, repo.cashEntries(shop.id).single { it.kind == "supplier_reimbursement" }.amountMinor)
                assertFails { repo.reviewPurchaseReturn(shop.id, receipt.id, 1, receiveCash = true) }

                val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
                val statement = SupplierStatementService.build(shop.id, receipt.supplierName, listOf(receipt),
                    emptyList(), today, today, listOf(event))
                assertEquals(0L, statement.closingMinor)
                assertEquals(2_200L, statement.returnCreditsMinor)
                assertEquals(800L, statement.returnCashMinor)
                assertContains(SupplierStatementService.csv(statement), "Cash received on return NGN")
                BackupService.create(repo, shop.id, "supplier-return-test", file)
                val snapshot = BackupService.read(file, "supplier-return-test")
                val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
                assertEquals(event.copy(tenantId = restored.id), repo.purchaseReturns(restored.id).single())
                assertEquals(1_000L, repo.expectedCash(restored.id, shift.id))
                val missingReimbursement = snapshot.copy(tables = snapshot.tables - "purchase_cash_returns")
                assertFails { repo.restoreTenantSnapshot(missingReimbursement, "Missing return") }
                val missingCash = snapshot.tables.getValue("cash_entries").filterNot { it["kind"] == "supplier_reimbursement" }
                assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("cash_entries" to missingCash)), "Missing cash") }
            }
        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }

    @Test fun fullyPaidReturnRequiresCashPermissionAndRollsBackAfterAnActorFailure() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Rice", "bag", 1_500, 0.0)
            val shift = repo.openCashShift(shop.id, 3_000)
            val receipt = repo.receiveStock(shop.id, item.id, 2, 1_000, 2_000, "Supplier")
            val review = repo.reviewPurchaseReturn(shop.id, receipt.id, 1, receiveCash = true)
            assertEquals(0L, review.creditMinor)
            assertEquals(1_000L, review.cashReceivedMinor)
            val before = repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.MANAGE_MONEY) }
            assertFails { repo.savePurchaseReturn(shop.id, review, "Supplier returned cash", "forbidden") }
            repo.setPermissionCheck(null)
            repo.setActorProvider { error("Actor revoked") }
            assertFails { repo.savePurchaseReturn(shop.id, review, "Supplier returned cash", "rollback") }
            repo.setActorProvider(null)
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            val saved = repo.savePurchaseReturn(shop.id, review, "Supplier returned cash", "paid")
            assertEquals(1_000L, saved.returnedValueMinor)
            assertEquals(2_000L, repo.expectedCash(shop.id, shift.id))
            assertEquals(0L, repo.supplierOutstanding(shop.id, receipt.id))
        }
    }
}
