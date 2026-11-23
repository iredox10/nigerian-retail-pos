package pos.data

import kotlin.test.*
import pos.domain.QuotationLineInput
import pos.domain.StaffPermission

class OrderDispatchIntegrityTest {
    private val details = OrderDispatchDetails("Amina", "Shop 12, Main Market, Lagos", "Local delivery person", "LOCAL-123", "Handed over three packed pieces")

    @Test fun dispatchUsesFulfilledGoodsAndRecipientProofWithoutAdditionalFinancialMovements() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val item = repo.addProduct(shop, "Soap", "piece", 1000, 10.0)
            val shift = repo.openCashShift(shop, 0)
            val order = repo.createCustomerOrder(shop, customer.id, "Phone", null, "Delivery requested",
                listOf(QuotationLineInput(item.id, null, 3, 1000)), "order")
            assertFails { repo.reviewOrderDispatch(shop, order.id, "unfulfilled") }
            val collection = repo.collectCustomerOrder(shop,
                repo.reviewCustomerOrderCollection(shop, order.id, mapOf(item.id to 3)), "collection")
            val sale = requireNotNull(collection.saleId)
            val cash = repo.expectedCash(shop, shift.id)
            val stock = repo.products(shop).single().exactStockScaled
            val review = repo.reviewOrderDispatch(shop, order.id, sale)
            val first = repo.createOrderDispatch(shop, review, mapOf(item.id to 2), details, "dispatch-one")
            assertEquals(first, repo.createOrderDispatch(shop, review, mapOf(item.id to 2), details, "dispatch-one"))
            assertFails { repo.createOrderDispatch(shop, review, mapOf(item.id to 1), details, "stale") }
            val next = repo.reviewOrderDispatch(shop, order.id, sale)
            assertEquals(1, next.available[item.id])
            assertFails { repo.createOrderDispatch(shop, next, mapOf(item.id to 2), details, "over-dispatch") }
            repo.createOrderDispatch(shop, next, mapOf(item.id to 1), details, "dispatch-two")
            val failed = repo.recordOrderDeliveryStatus(shop, first.id, 1, "failed", null, "Recipient was away", "failed")
            assertEquals(failed, repo.recordOrderDeliveryStatus(shop, first.id, 1, "failed", null, failed.note, "failed"))
            assertFails { repo.recordOrderDeliveryStatus(shop, first.id, 1, "delivered", "Amina", "Recipient signed", "stale-status") }
            repo.recordOrderDeliveryStatus(shop, first.id, 2, "dispatched", null, "Recipient requested delivery again", "sent-again")
            assertFails { repo.recordOrderDeliveryStatus(shop, first.id, 3, "delivered", null, "Received goods", "missing-proof") }
            repo.recordOrderDeliveryStatus(shop, first.id, 3, "delivered", "Amina Bello", "Acknowledged both pieces by phone", "delivered")
            assertFails { repo.recordOrderDeliveryStatus(shop, first.id, 4, "returned", null, "Change previous event", "rewrite") }
            assertEquals("delivered", repo.orderDispatches(shop, order.id).first { it.id == first.id }.status)
            assertEquals(cash, repo.expectedCash(shop, shift.id))
            assertEquals(stock, repo.products(shop).single().exactStockScaled)
            assertEquals(1, repo.sales(shop).size)

            val backup = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(backup, "Restored")
            assertEquals(repo.orderDispatches(shop), repo.orderDispatches(restored.id))
            assertFails { repo.restoreTenantSnapshot(backup.copy(tables = backup.tables - "order_dispatch_events"), "Missing evidence") }
            val lines = backup.tables.getValue("order_dispatch_lines").map { it + ("quantity" to "4") }
            assertFails { repo.restoreTenantSnapshot(backup.copy(tables = backup.tables + ("order_dispatch_lines" to lines)), "Over-dispatch") }
        }
    }

    @Test fun returnedGoodsReduceAvailabilityAndOtherShopsOrCashiersCannotRecordDispatch() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val other = repo.createTenant("Other").id
            val customer = repo.addCustomer(shop, "Amina")
            val item = repo.addProduct(shop, "Rice", "kg", 1000, 5.0, quantityPrecision = 2)
            repo.openCashShift(shop, 0)
            val order = repo.createCustomerOrder(shop, customer.id, "Phone", null, "Delivery requested",
                listOf(QuotationLineInput(item.id, null, 250, 1000)), "order")
            val collection = repo.collectCustomerOrder(shop,
                repo.reviewCustomerOrderCollection(shop, order.id, mapOf(item.id to 250)), "collection")
            val sale = requireNotNull(collection.saleId)
            repo.returnItems(shop, sale, mapOf(item.id to 50), true, "Half kilogram returned", "return-before-dispatch")
            val review = repo.reviewOrderDispatch(shop, order.id, sale)
            assertEquals(200, review.available[item.id])
            assertFails { repo.reviewOrderDispatch(other, order.id, sale) }
            val original = repo.exportTenantSnapshot(shop)
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.SELL) }
            assertFails { repo.createOrderDispatch(shop, review, mapOf(item.id to 200), details, "cashier") }
            repo.setPermissionCheck(null)
            assertEquals(original, repo.exportTenantSnapshot(shop))
            repo.createOrderDispatch(shop, review, mapOf(item.id to 200), details, "dispatch")
            repo.recordOrderDeliveryStatus(shop, "dispatch", 1, "returned", null, "Goods came back unopened", "returned")
            assertEquals(0, repo.reviewOrderDispatch(shop, order.id, sale).available[item.id])
            assertEquals("returned", repo.orderDispatches(shop).single().status)
            assertEquals(300L, repo.products(shop).single().exactStockScaled)
        }
    }
}
