package pos.data

import pos.domain.CartLine
import pos.output.ReceiptService
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SaleTenderIntegrityTest {
    @Test fun changeSurvivesRetryAndRestoreWhileOnlyAppliedCashEntersDrawer() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Rice", "bag", 150_000, 4.0)
            val shift = repo.openCashShift(shop.id, 100_000)
            val lines = listOf(CartLine(item, 1))
            val sale = repo.checkout(shop.id, lines, 150_000, operationId = "sale", cashGivenMinor = 200_000)
            val persisted = repo.sales(shop.id).single()
            assertEquals(200_000L, persisted.cashGivenMinor)
            assertEquals(50_000L, persisted.changeMinor)
            assertEquals(250_000L, repo.expectedCash(shop.id, shift.id))
            assertEquals(persisted, repo.checkout(shop.id, lines, 150_000, operationId = sale.id, cashGivenMinor = 200_000))
            assertFails { repo.checkout(shop.id, lines, 150_000, operationId = sale.id, cashGivenMinor = 210_000) }
            assertEquals(3.0, repo.products(shop.id).single().stock)
            val backup = repo.exportTenantSnapshot(shop.id)
            val restored = repo.restoreTenantSnapshot(backup, "Restored")
            val receipt = repo.sales(restored.id).single()
            assertEquals(50_000L, receipt.changeMinor)
            assertContains(ReceiptService.format(restored, receipt), "Change at sale: ₦500.00")
            assertFails { repo.restoreTenantSnapshot(backup.copy(tables = backup.tables - saleTenderSnapshotTables.toSet()), "Missing") }
        }
    }

    @Test fun cashCannotBeReturnedAsChangeWithDebtAndOldReceiptsRemainUnknown() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Soap", "piece", 500, 5.0)
            val customer = repo.addCustomer(shop.id, "Customer")
            val before = repo.exportTenantSnapshot(shop.id)
            assertFails {
                repo.checkout(shop.id, listOf(CartLine(item, 1)), 400, customer.id, cashGivenMinor = 500)
            }
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            val old = repo.checkout(shop.id, listOf(CartLine(item, 1)), 500)
            assertNull(repo.sales(shop.id).single().cashGivenMinor)
            assertFalse(ReceiptService.format(shop, old).contains("Change at sale"))
        }
    }
}
