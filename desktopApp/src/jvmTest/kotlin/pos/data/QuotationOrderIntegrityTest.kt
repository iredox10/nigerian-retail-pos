package pos.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*
import pos.domain.*

class QuotationOrderIntegrityTest {
    private val expiry get() = LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(3).toString()

    @Test fun orderRetainsQuotedPriceAndDepositExactlyOnceThroughCollectionAndRestore() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val item = repo.addProduct(shop, "Soap", "piece", 1000, 0.0)
            val shift = repo.openCashShift(shop, 0)
            val issued = repo.issueQuotation(shop, listOf(QuotationLineInput(item.id, null, 4, 900)), expiry,
                QuotationCustomer(customer.id, customer.name), "Accepted delivery terms", "quote")
            val quote = repo.acceptQuotation(shop, issued.id, issued.version, "accept")
            val oldReview = repo.reviewQuotationOrder(shop, quote.id, quote.version, customer.id, null, "Deliver together")
            repo.updateProduct(shop, item.id, item.name, item.unit, 1100, null)
            assertFails { repo.convertQuotationToOrder(shop, oldReview, 2000, "stale") }
            val review = repo.reviewQuotationOrder(shop, quote.id, quote.version, customer.id, null, "Deliver together")
            val order = repo.convertQuotationToOrder(shop, review, 2000, "order")
            assertEquals(order, repo.convertQuotationToOrder(shop, review, 2000, "order"))
            assertEquals(900L, order.lines.single().agreedUnitPriceMinor)
            assertEquals(3600L, order.totalMinor)
            assertEquals(0L, repo.products(shop).single().exactStockScaled)
            assertTrue(repo.sales(shop).isEmpty())
            assertEquals(2000L, repo.expectedCash(shop, shift.id))
            assertEquals(0L, repo.customers(shop).single().balanceMinor)
            assertEquals(2000L, repo.customerOrders(shop).single().depositLeftMinor)
            assertFails { repo.convertQuotationToOrder(shop, review, 1000, "order") }
            assertFails { repo.cancelQuotation(shop, quote.id, "Change quotation after order", quote.version, "cancel") }
            assertFails { repo.quotationConversionReview(shop, quote.id, quote.version) }
            assertFails { repo.convertQuotationToOrder(shop, review, 0, "duplicate") }
            repo.receiveStock(shop, item.id, 4, 500, 0, "Wholesaler", "stock")
            val collection = repo.reviewCustomerOrderCollection(shop, order.id, mapOf(item.id to 4))
            assertEquals(3600L, cartTotal(collection.lines))
            assertEquals(2000L, collection.depositMinor)
            assertEquals(1600L, collection.cashMinor)
            repo.collectCustomerOrder(shop, collection, "collection")
            assertEquals(3600L, repo.expectedCash(shop, shift.id))
            assertEquals(900L, repo.sales(shop).single().lines.single().netUnitPriceMinor)
            val snapshot = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored")
            assertEquals(repo.quotationOrderLinks(shop), repo.quotationOrderLinks(restored.id))
            assertEquals(900L, repo.customerOrders(restored.id).single().order.lines.single().agreedUnitPriceMinor)
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "quotation_order_links"), "Lost link") }
        }
    }

    @Test fun depositFailureRollsBackTheOrderAndUnlinkedQuotesRequireAnExistingShopCustomer() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val other = repo.createTenant("Other").id
            val customer = repo.addCustomer(shop, "Amina")
            val foreign = repo.addCustomer(other, "Other customer")
            val item = repo.addProduct(shop, "Soap", "piece", 1000, 2.0)
            val issued = repo.issueQuotation(shop, listOf(QuotationLineInput(item.id, null, 1, 1000)), expiry,
                QuotationCustomer(null, "Phone contact"), operationId = "quote")
            val quote = repo.acceptQuotation(shop, issued.id, issued.version, "accept")
            assertFails { repo.reviewQuotationOrder(shop, quote.id, quote.version, foreign.id, null, "") }
            assertFails { repo.reviewQuotationOrder(shop, quote.id, quote.version, customer.id,
                LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(1).toString(), "") }
            val review = repo.reviewQuotationOrder(shop, quote.id, quote.version, customer.id, null, "")
            val before = repo.exportTenantSnapshot(shop)
            assertFails { repo.convertQuotationToOrder(shop, review, 500, "needs-drawer") }
            assertEquals(before, repo.exportTenantSnapshot(shop))
            repo.setPermissionCheck { _, permission -> require(permission == StaffPermission.SELL) }
            assertFails { repo.convertQuotationToOrder(shop, review, 0, "cashier") }
            repo.setPermissionCheck(null)
            val order = repo.convertQuotationToOrder(shop, review, 0, "no-deposit")
            assertEquals(customer.id, order.customerId)
            assertEquals(0L, repo.customerOrders(shop).single().depositLeftMinor)
            assertTrue(repo.sales(shop).isEmpty())
            assertTrue(repo.cashEntries(shop).isEmpty())
        }
    }

    @Test fun changedDefinitionsAndLowerCataloguePricesBlockConversionWithoutChangingAcceptedTerms() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val other = repo.addCustomer(shop, "Other")
            val item = repo.addProduct(shop, "Rice", "kg", 1000, 5.0, quantityPrecision = 2)
            val issued = repo.issueQuotation(shop, listOf(QuotationLineInput(item.id, null, 125, 900)), expiry,
                QuotationCustomer(customer.id, customer.name), operationId = "quote")
            val quote = repo.acceptQuotation(shop, issued.id, issued.version, "accept")
            assertFails { repo.reviewQuotationOrder(shop, quote.id, quote.version, other.id, null, "") }
            repo.updateProduct(shop, item.id, item.name, item.unit, 800, null)
            assertFails { repo.reviewQuotationOrder(shop, quote.id, quote.version, customer.id, null, "") }
            repo.updateProduct(shop, item.id, "Renamed rice", item.unit, 1000, null)
            assertFails { repo.reviewQuotationOrder(shop, quote.id, quote.version, customer.id, null, "") }
            assertEquals(quote, repo.quotations(shop).single())
            assertTrue(repo.quotationOrderLinks(shop).isEmpty())
            assertTrue(repo.customerOrders(shop).isEmpty())
        }
    }
}
