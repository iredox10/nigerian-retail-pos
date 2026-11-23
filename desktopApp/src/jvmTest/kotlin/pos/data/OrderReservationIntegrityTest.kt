package pos.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.domain.CartLine
import pos.domain.QuotationLineInput
import pos.domain.StaffPermission

class OrderReservationIntegrityTest {
    @Test fun heldPacksBlockOtherOrdersAndCashiersUntilCollectedOrReleased() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val item = repo.addProduct(shop, "Soap", "piece", 100, 13.0)
            val pack = repo.addSellingUnit(shop, item.id, "pack", 5, 500)
            repo.openCashShift(shop, 0)
            val today = orderReservationDate(Instant.now())
            val first = repo.createCustomerOrder(shop, customer.id, "Phone", null, "",
                listOf(QuotationLineInput(item.id, pack.id, 2, 500)), "first")
            val second = repo.createCustomerOrder(shop, customer.id, "In shop", null, "",
                listOf(QuotationLineInput(item.id, null, 4, 100)), "second")
            val hold = repo.holdCustomerOrderStock(shop, first.id, today, 0, 0, "hold")
            assertEquals(hold, repo.holdCustomerOrderStock(shop, first.id, today, 0, 0, "hold"))
            assertEquals(mapOf(item.id to 10L), repo.orderReservedQuantities(shop))
            assertEquals(13L, repo.products(shop).single().exactStockScaled)

            assertFails { repo.holdCustomerOrderStock(shop, second.id, today, 0, 0, "overcommit") }
            assertFails { repo.reviewCustomerOrderCollection(shop, second.id, mapOf(item.id to 4)) }
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.SELL) }
            assertFails { repo.checkout(shop, listOf(CartLine(item, 4)), 400, operationId = "blocked-cashier") }
            repo.checkout(shop, listOf(CartLine(item, 3)), 300, operationId = "available-cashier")
            repo.setPermissionCheck(null)

            val firstPickup = repo.reviewCustomerOrderCollection(shop, first.id, mapOf(first.lines.single().key to 1))
            repo.collectCustomerOrder(shop, firstPickup, "picked-up")
            assertEquals(5L, repo.reservedStock(shop, item.id))
            assertEquals(0L, repo.reservedStock(shop, item.id, excludingOrderId = first.id))
            assertFails { repo.releaseCustomerOrderStock(shop, first.id, "Changed pickup plans", 0, 1, "stale") }
            val release = repo.releaseCustomerOrderStock(shop, first.id, "Changed pickup plans", 1, 1, "release")
            assertEquals(release, repo.releaseCustomerOrderStock(shop, first.id, "Changed pickup plans", 1, 1, "release"))
            assertEquals(0L, repo.reservedStock(shop, item.id))

            repo.holdCustomerOrderStock(shop, second.id, today, 0, 0, "second-hold")
            val secondPickup = repo.reviewCustomerOrderCollection(shop, second.id, mapOf(item.id to 4))
            repo.collectCustomerOrder(shop, secondPickup, "second-completed")
            assertEquals(0L, repo.reservedStock(shop, item.id))
            assertEquals(1L, repo.products(shop).single().exactStockScaled)
        }
    }

    @Test fun holdsExpireAtWatMidnightAndRestoreWithoutExtendingTheDate() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val item = repo.addProduct(shop, "Rice", "kg", 200, 3.5, quantityPrecision = 2)
            val order = repo.createCustomerOrder(shop, customer.id, "In shop", null, "",
                listOf(QuotationLineInput(item.id, null, 275, 200)), "order")
            val zone = ZoneId.of("Africa/Lagos")
            val expiry = LocalDate.now(zone).plusDays(1)
            repo.holdCustomerOrderStock(shop, order.id, expiry.toString(), 0, 0, "hold")
            val midnight = expiry.plusDays(1).atStartOfDay(zone).toInstant()
            assertEquals(275L, repo.reservedStock(shop, item.id, now = midnight.minusNanos(1)))
            assertEquals(0L, repo.reservedStock(shop, item.id, now = midnight))

            val snapshot = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
            assertEquals(mapOf(item.id to 275L), repo.orderReservedQuantities(restored.id, midnight.minusNanos(1)))
            assertTrue(repo.orderReservedQuantities(restored.id, midnight).isEmpty())
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "order_reservation_events"), "Missing hold") }
            val hold = snapshot.tables.getValue("order_reservation_events").single()
            val invalid = listOf(hold + ("order_history_size" to "1"))
            assertFails {
                repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("order_reservation_events" to invalid)), "Wrong revision")
            }

            repo.cancelCustomerOrder(shop, order.id, 0, 0, "Customer cancelled the order", "cancel")
            assertEquals(0L, repo.reservedStock(shop, item.id))
            assertEquals(listOf("held"), repo.customerOrderReservationHistory(shop, order.id).map { it.kind })
        }
    }
}
