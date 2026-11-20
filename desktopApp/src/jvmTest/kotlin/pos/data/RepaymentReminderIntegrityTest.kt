package pos.data

import java.time.LocalDate
import java.time.ZoneId
import pos.domain.CartLine
import pos.domain.StaffPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RepaymentReminderIntegrityTest {
    @Test fun closureAndRetriesPersistWithoutChangingDebtOrCash() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val product = repo.addProduct(shop.id, "Soap", "bar", 500, 4.0)
            val customer = repo.addCustomer(shop.id, "Amina")
            repo.checkout(shop.id, listOf(CartLine(product, 1)), 0, customer.id)
            val due = LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(1)
            val opened = repo.addRepaymentReminder(shop.id, customer.id, due, "Discuss repayment", "open")
            assertEquals(opened, repo.addRepaymentReminder(shop.id, customer.id, due, "Discuss repayment", "open"))
            val closed = repo.finishRepaymentReminder(shop.id, opened.id, ReminderState.COMPLETED, "Customer contacted", "close")
            assertEquals(closed, repo.finishRepaymentReminder(shop.id, opened.id, ReminderState.COMPLETED, "Customer contacted", "close"))
            assertFails { repo.finishRepaymentReminder(shop.id, opened.id, ReminderState.CANCELLED, "Already closed") }
            assertEquals(500L, repo.customers(shop.id).single().balanceMinor)
            assertTrue(repo.cashEntries(shop.id).isEmpty())
            val backup = repo.exportTenantSnapshot(shop.id)
            val recovered = repo.restoreTenantSnapshot(backup, "Recovered")
            assertEquals(listOf(opened, closed), repo.repaymentReminderHistory(recovered.id))
            assertFails { repo.restoreTenantSnapshot(backup.copy(tables = backup.tables - reminderSnapshotTables.toSet()), "Missing") }
        }
    }

    @Test fun rejectsPastDatesCrossShopAndUnauthorizedChanges() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val other = repo.createTenant("Other")
            val product = repo.addProduct(shop.id, "Soap", "bar", 500, 4.0)
            val customer = repo.addCustomer(shop.id, "Amina")
            repo.checkout(shop.id, listOf(CartLine(product, 1)), 0, customer.id)
            val today = LocalDate.now(ZoneId.of("Africa/Lagos"))
            assertFails { repo.addRepaymentReminder(shop.id, customer.id, today.minusDays(1), "Past date") }
            assertFails { repo.addRepaymentReminder(other.id, customer.id, today, "Wrong shop") }
            repo.setPermissionCheck { _, required -> require(required != StaffPermission.MANAGE_CUSTOMERS) }
            assertFails { repo.addRepaymentReminder(shop.id, customer.id, today, "Forbidden") }
            repo.setPermissionCheck(null)
            assertTrue(repo.repaymentReminderHistory(shop.id).isEmpty())
        }
    }
}
