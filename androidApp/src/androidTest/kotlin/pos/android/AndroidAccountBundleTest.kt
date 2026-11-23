package pos.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.platform.AndroidPrivateFiles
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import java.util.UUID

/** These exercise the actual Android SQLite binding rather than the desktop JDBC driver. */
@RunWith(AndroidJUnit4::class)
class AndroidAccountBundleTest {
    private fun fixture(run: (PosRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.cacheDir, "account-bundle-${UUID.randomUUID()}").apply { mkdirs() }
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        AndroidPrivateFiles.install()
        try { PosRepository(directory.resolve("pos.db").path).use(run) } finally { directory.deleteRecursively() }
    }

    @Test fun advanceApplicationFundsDebtOnceDisputesDoNotMoveMoneyAndAllSourcesRestore() = fixture { repo ->
        val shop = repo.createTenant("Android account shop").id
        val customer = repo.addCustomer(shop, "Ada")
        val item = repo.addProduct(shop, "Soap", "piece", 1000, 10.0)
        val shift = repo.openCashShift(shop, 10000)
        val sale = repo.checkout(shop, listOf(CartLine(item, 2)), 0, customer.id, "credit-sale")
        val cashBefore = repo.cashEntries(shop)
        val claim = repo.openCustomerDispute(shop, customer.id, sale.id, 600, 2000, 2000, "Questioned receipt quantity", operationId = "dispute")
        assertEquals(cashBefore, repo.cashEntries(shop))
        assertEquals(CustomerDisputeBalance(2000, 600), repo.customerDisputeBalance(shop, customer.id))
        repo.receiveCustomerAdvance(shop, customer.id, 1500, 0, 0, "Funds received for later use", "receive")
        val beforeApplication = repo.cashEntries(shop)
        val application = repo.applyCustomerAdvance(shop, customer.id, sale.id, 700, 1500, 1, 2000, 2000, "Customer chose this receipt", "application")
        assertEquals(application, repo.applyCustomerAdvance(shop, customer.id, sale.id, 700, 1500, 1, 2000, 2000, "Customer chose this receipt", "application"))
        assertEquals(beforeApplication, repo.cashEntries(shop))
        assertEquals(1300L, repo.customers(shop).single().balanceMinor)
        assertEquals(800L, repo.customerAdvanceBalance(shop, customer.id))
        repo.refundCustomerAdvance(shop, customer.id, 300, 800, 2, "Unused cash returned to customer", "refund")
        assertEquals(500L, repo.customerAdvanceBalance(shop, customer.id))
        assertEquals(11200L, repo.expectedCash(shop, shift.id))
        val beforeResolution = repo.cashEntries(shop)
        repo.resolveCustomerDispute(shop, claim.disputeId, 1, "Customer accepted the original receipt", "resolution")
        assertEquals(beforeResolution, repo.cashEntries(shop))
        assertEquals(CustomerDisputeBalance(1300, 0), repo.customerDisputeBalance(shop, customer.id))
        val unchanged = repo.exportTenantSnapshot(shop)
        assertThrows(IllegalArgumentException::class.java) { repo.refundCustomerAdvance(shop, customer.id, 100, 800, 2, "Stale refund attempt", "stale") }
        assertEquals(unchanged, repo.exportTenantSnapshot(shop))
        val restored = repo.restoreTenantSnapshot(unchanged, "Recovered Android account shop")
        assertEquals(500L, repo.customerAdvanceBalance(restored.id, customer.id))
        assertEquals(1300L, repo.customers(restored.id).single().balanceMinor)
        assertEquals(repo.customerDisputes(shop), repo.customerDisputes(restored.id))
        assertEquals(11200L, repo.expectedCash(restored.id, shift.id))
    }

    @Test fun nativeBundleSaleDeductsExactMeasuredComponentsOnceAndPreservesCashChange() = fixture { repo ->
        val shop = repo.createTenant("Android bundle shop").id
        val rice = repo.addProduct(shop, "Loose rice", "kg", 100000, 10.0, quantityPrecision = 3)
        val soap = repo.addProduct(shop, "Soap", "piece", 45000, 10.0)
        val shift = repo.openCashShift(shop, 50000)
        repo.saveBundle(shop, "pair", "Rice and soap", true, 170001,
            listOf(BundleComponentInput(rice.id, null, 1375), BundleComponentInput(soap.id, null, 1)), 0, "Agreed bundle price", "definition")
        val review = repo.reviewBundleSale(shop, "pair", 1)
        assertEquals(170001L, review.totalMinor)
        val sale = repo.completeBundleCashSale(shop, review, 200000, "bundle-sale")
        assertEquals(sale, repo.completeBundleCashSale(shop, review, 200000, "bundle-sale"))
        assertEquals(29999L, sale.changeMinor)
        assertEquals(220001L, repo.expectedCash(shop, shift.id))
        assertEquals(8625L, repo.products(shop).single { it.id == rice.id }.exactStockScaled)
        assertEquals(9L, repo.products(shop).single { it.id == soap.id }.exactStockScaled)
        val stale = repo.reviewBundleSale(shop, "pair", 1)
        repo.adjustStock(shop, soap.id, 8, "Counted another missing piece", "count", expectedStock = 9.0)
        val beforeRejected = repo.exportTenantSnapshot(shop)
        assertThrows(IllegalArgumentException::class.java) { repo.completeBundleCashSale(shop, stale, 200000, "stale-sale") }
        assertEquals(beforeRejected, repo.exportTenantSnapshot(shop))
        val restored = repo.restoreTenantSnapshot(beforeRejected, "Recovered bundles")
        assertEquals(1, repo.bundleSales(restored.id).size)
        assertEquals(170001L, repo.sales(restored.id).single().totalMinor)
        assertEquals(220001L, repo.expectedCash(restored.id, shift.id))
    }
}
