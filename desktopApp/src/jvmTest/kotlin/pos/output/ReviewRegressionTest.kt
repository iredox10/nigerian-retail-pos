package pos.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import pos.data.PosRepository
import pos.domain.CartLine

class ReviewRegressionTest {
    @Test fun restoreRejectsCashEntryAmountThatDisagreesWithSaleWhileShiftIsOpen() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Original")
            val product = repo.addProduct(tenant.id, "Milk", "tin", 50_000, 10.0)
            val shift = repo.openCashShift(tenant.id, 100_000)
            repo.checkout(tenant.id, listOf(CartLine(product, 1)), 50_000)
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val tables = snapshot.tables.toMutableMap()
            tables["cash_entries"] = tables.getValue("cash_entries").map { row ->
                if (row["kind"] == "sale") row + ("amount_minor" to "1") else row
            }
            val before = repo.tenants()

            assertFailsWith<IllegalArgumentException> {
                repo.restoreTenantSnapshot(snapshot.copy(tables = tables), "Wrong cash entry")
            }

            assertEquals(before, repo.tenants())
            assertEquals(150_000L, repo.expectedCash(tenant.id, shift.id))
        }
    }

    @Test fun restoreCannotLoseCashLedgerEntryForAnExistingCashSale() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Original")
            val product = repo.addProduct(tenant.id, "Milk", "tin", 50_000, 10.0)
            repo.openCashShift(tenant.id, 100_000)
            repo.checkout(tenant.id, listOf(CartLine(product, 1)), 50_000)
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val tables = snapshot.tables + ("cash_entries" to emptyList())
            val before = repo.tenants()

            assertFailsWith<IllegalArgumentException> {
                repo.restoreTenantSnapshot(snapshot.copy(tables = tables), "Missing cash entry")
            }

            assertEquals(before, repo.tenants())
        }
    }

    @Test fun restoreRejectsCustomerBalanceThatDisagreesWithCreditAndRepayments() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Original")
            val product = repo.addProduct(tenant.id, "Milk", "tin", 50_000, 10.0)
            val customer = repo.addCustomer(tenant.id, "Buyer")
            repo.checkout(tenant.id, listOf(CartLine(product, 2)), 50_000, customer.id)
            repo.recordRepayment(tenant.id, customer.id, 20_000)
            assertEquals(30_000L, repo.customers(tenant.id).single().balanceMinor)
            val snapshot = repo.exportTenantSnapshot(tenant.id)
            val tables = snapshot.tables.toMutableMap()
            tables["customers"] = tables.getValue("customers").map { row -> row + ("balance_minor" to "0") }
            val existingTenants = repo.tenants()

            assertFailsWith<IllegalArgumentException> {
                repo.restoreTenantSnapshot(snapshot.copy(tables = tables), "Invalid debt restore")
            }

            assertEquals(existingTenants, repo.tenants(), "Failed restore must roll back the new workspace")
            assertEquals(30_000L, repo.customers(tenant.id).single().balanceMinor)
        }
    }
}
