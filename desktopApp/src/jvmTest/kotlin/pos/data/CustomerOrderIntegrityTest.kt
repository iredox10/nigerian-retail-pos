package pos.data

import java.nio.file.Files
import kotlin.test.*
import pos.domain.QuotationLineInput
import pos.domain.StaffPermission
import pos.output.BackupService
import pos.output.ReceiptService

class CustomerOrderIntegrityTest {
    @Test fun depositFundsPartialCollectionsExactlyOnceAndCancellationRefundsOnlyUnspentCash() {
        val directory = Files.createTempDirectory("customer-order-test-")
        val file = directory.resolve("order.backup")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val customer = repo.addCustomer(shop.id, "Amina")
                val item = repo.addProduct(shop.id, "Soap", "piece", 1_000, 4.0)
                val shift = repo.openCashShift(shop.id, 0)
                val input = listOf(QuotationLineInput(item.id, null, 3, 1_000))
                val order = repo.createCustomerOrder(shop.id, customer.id, "Phone", null, "Collection at the counter", input, "order")
                assertTrue(repo.sales(shop.id).isEmpty())
                assertEquals(order, repo.createCustomerOrder(shop.id, customer.id, "Phone", null, order.note, input, "order"))
                repo.recordCustomerOrderDeposit(shop.id, order.id, 1_500, 0, "deposit")
                assertEquals(4L, repo.products(shop.id).single().exactStockScaled)
                assertEquals(0L, repo.customers(shop.id).single().balanceMinor)
                assertEquals(1_500L, repo.expectedCash(shop.id, shift.id))

                val first = repo.reviewCustomerOrderCollection(shop.id, order.id, mapOf(item.id to 1))
                assertEquals(1_000L, first.depositMinor)
                assertEquals(0L, first.cashMinor)
                val collection = repo.collectCustomerOrder(shop.id, first, "first")
                assertEquals(collection, repo.collectCustomerOrder(shop.id, first, "first"))
                val second = repo.reviewCustomerOrderCollection(shop.id, order.id, mapOf(item.id to 1))
                assertEquals(500L, second.depositMinor)
                assertEquals(500L, second.cashMinor)
                repo.collectCustomerOrder(shop.id, second, "second")
                val sold = repo.sales(shop.id).single { it.id == collection.saleId }
                assertEquals(1_000L, sold.orderPayment?.depositMinor)
                assertContains(ReceiptService.format(shop, sold), "Order deposit applied")

                repo.recordCustomerOrderDeposit(shop.id, order.id, 700, 3, "another")
                assertEquals(2_700L, repo.expectedCash(shop.id, shift.id))
                repo.cancelCustomerOrder(shop.id, order.id, 4, 700, "Customer cancelled the last item", "cancel")
                assertEquals(2_000L, repo.expectedCash(shop.id, shift.id))
                assertEquals(2L, repo.products(shop.id).single().exactStockScaled)
                assertTrue(repo.customerOrders(shop.id).single().cancelled)
                assertFails { repo.reviewCustomerOrderCollection(shop.id, order.id, mapOf(item.id to 1)) }

                BackupService.create(repo, shop.id, "customer-order-test-password", file)
                val snapshot = BackupService.read(file, "customer-order-test-password")
                val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
                assertEquals(2_000L, repo.expectedCash(restored.id, shift.id))
                assertTrue(repo.customerOrders(restored.id).single().cancelled)
                assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "customer_order_events"), "Incomplete") }
            }
        } finally {
            Files.deleteIfExists(file)
            Files.delete(directory)
        }
    }

    @Test fun collectionIsAtomicAndStaleOrUnauthorizedRequestsLeaveTheDepositIntact() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val customer = repo.addCustomer(shop.id, "Amina")
            val item = repo.addProduct(shop.id, "Soap", "piece", 1_000, 2.0)
            repo.openCashShift(shop.id, 0)
            val order = repo.createCustomerOrder(shop.id, customer.id, "In shop", null, "",
                listOf(QuotationLineInput(item.id, null, 2, 1_000)), "order")
            val stale = repo.reviewCustomerOrderCollection(shop.id, order.id, mapOf(item.id to 1))
            repo.recordCustomerOrderDeposit(shop.id, order.id, 2_000, 0, "deposit")
            assertFails { repo.collectCustomerOrder(shop.id, stale, "stale") }
            val fresh = repo.reviewCustomerOrderCollection(shop.id, order.id, mapOf(item.id to 2))
            assertEquals(0L, fresh.cashMinor)
            val before = repo.exportTenantSnapshot(shop.id)
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.SELL) }
            assertFails { repo.collectCustomerOrder(shop.id, fresh, "denied") }
            repo.setPermissionCheck(null)
            var events = 0
            repo.setActorProvider {
                if (++events > 1) error("Actor revoked")
                null
            }
            assertFails { repo.collectCustomerOrder(shop.id, fresh, "rollback") }
            repo.setActorProvider(null)
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            repo.collectCustomerOrder(shop.id, fresh, "success")
            assertTrue(repo.customerOrders(shop.id).single().completed)
            assertEquals(0L, repo.products(shop.id).single().exactStockScaled)
        }
    }
}
