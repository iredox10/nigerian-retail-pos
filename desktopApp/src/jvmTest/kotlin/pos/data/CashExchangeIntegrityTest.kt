package pos.data

import java.nio.file.Files
import kotlin.test.*
import pos.domain.CartLine
import pos.domain.ExternalPayment
import pos.domain.StaffPermission
import pos.output.BackupService
import pos.output.ReceiptService

class CashExchangeIntegrityTest {
    @Test fun higherAndLowerPricesMoveOnlyTheCashDifferenceAndRetainBothReceipts() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val originalItem = repo.addProduct(shop.id, "Original shirt", "piece", 1_000, 2.0)
            val lowerPrice = repo.addProduct(shop.id, "Smaller shirt", "piece", 600, 1.0)
            val higherPrice = repo.addProduct(shop.id, "Larger shirt", "piece", 1_300, 1.0)
            val shift = repo.openCashShift(shop.id, 0)
            val original = repo.checkout(shop.id, listOf(CartLine(originalItem, 2)), 2_000)
            val cheaper = repo.reviewCashExchange(shop.id, original.id, mapOf(originalItem.id to 1),
                listOf(CartLine(lowerPrice, 1)), true, "Customer chose smaller shirt")
            assertEquals(400L, cheaper.cashToRefundMinor)
            val lower = repo.saveCashExchange(shop.id, cheaper, "lower-price")
            assertEquals(lower, repo.saveCashExchange(shop.id, cheaper, "lower-price"))

            val dearer = repo.reviewCashExchange(shop.id, original.id, mapOf(originalItem.id to 1),
                listOf(CartLine(higherPrice, 1)), false, "Customer chose larger shirt")
            assertEquals(300L, dearer.cashToCollectMinor)
            val higher = repo.saveCashExchange(shop.id, dearer, "higher-price")
            assertEquals(1_900L, repo.expectedCash(shop.id, shift.id))
            assertEquals(listOf(-400L, 300L, 2_000L), repo.cashEntries(shop.id).map { it.amountMinor }.sorted())
            assertEquals(1.0, repo.products(shop.id).single { it.id == originalItem.id }.stock)
            assertEquals(0, repo.returnableQuantities(shop.id, original.id).getValue(originalItem.id))
            val replacement = repo.sales(shop.id).single { it.id == higher.replacementReceiptId }
            assertEquals(1_000L, replacement.exchange?.amountMinor)
            assertEquals(300L, replacement.cashMinor)
            val originalReceipt = ReceiptService.format(shop, original, repo.saleReturns(shop.id, original.id))
            assertContains(originalReceipt, lower.replacementReceiptId)
            assertContains(originalReceipt, higher.replacementReceiptId)
            val replacementReceipt = ReceiptService.format(shop, replacement, emptyList())
            assertContains(replacementReceipt, original.id)
            assertContains(replacementReceipt, "Value from the exchange")
            assertFalse(replacementReceipt.contains("Paid in cash"))
            val refund = repo.returnItems(shop.id, replacement.id, mapOf(higherPrice.id to 1), false, "Returned replacement")
            assertEquals(1_300L, refund.refundMinor)
            assertEquals(600L, repo.expectedCash(shop.id, shift.id))
        }
    }

    @Test fun lastStepFailureRollsBackBothWritesAndReviewCannotSurviveStockOrPermissionChanges() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val originalItem = repo.addProduct(shop.id, "Original shirt", "piece", 1_000, 1.0)
            val replacement = repo.addProduct(shop.id, "Replacement shirt", "piece", 1_000, 2.0)
            val original = repo.checkout(shop.id, listOf(CartLine(originalItem, 1)), 1_000)
            val review = repo.reviewCashExchange(shop.id, original.id, mapOf(originalItem.id to 1),
                listOf(CartLine(replacement, 1)), true, "Swap the sample shirt")
            assertNull(review.shiftId)
            val before = repo.exportTenantSnapshot(shop.id)
            var events = 0
            repo.setActorProvider {
                if (++events == 3) error("Authorization revoked just before linking")
                null
            }
            assertFails { repo.saveCashExchange(shop.id, review, "rollback") }
            assertEquals(3, events)
            repo.setActorProvider(null)
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.SELL) }
            assertFails { repo.saveCashExchange(shop.id, review, "forbidden") }
            repo.setPermissionCheck(null)
            repo.checkout(shop.id, listOf(CartLine(replacement, 1)), 1_000)
            assertFails { repo.saveCashExchange(shop.id, review, "stale") }
            assertTrue(repo.saleReturns(shop.id).isEmpty())
        }
    }

    @Test fun evenExchangeRestoresThroughEncryptedBackupAndRejectsMissingOrAlteredLinks() {
        val directory = Files.createTempDirectory("pos-cash-exchange-")
        val backup = directory.resolve("shop.backup")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val other = repo.createTenant("Other")
                val malt = repo.addProduct(shop.id, "Malt", "bottle", 100, 6.0)
                val pack = repo.addSellingUnit(shop.id, malt.id, "six pack", 6, 600)
                val price = 600L
                val original = repo.checkout(shop.id, listOf(CartLine(malt, 1, pack.id, pack.name, price, 6)), price)
                val returned = mapOf(original.lines.single().key to 1)
                val replacements = listOf(CartLine(malt, 6))
                assertFails { repo.reviewCashExchange(other.id, original.id, returned, replacements, true, "Wrong shop") }
                val review = repo.reviewCashExchange(shop.id, original.id, returned, replacements, true, "Swap a pack for bottles")
                val saved = repo.saveCashExchange(shop.id, review, "even")
                assertEquals(1, repo.cashEntries(shop.id).size)
                assertEquals(0L, repo.products(shop.id).single().exactStockScaled)
                BackupService.create(repo, shop.id, "exchange-test-password", backup)
                val snapshot = BackupService.read(backup, "exchange-test-password")
                PosRepository(":memory:").use { recovered ->
                    val restored = recovered.restoreTenantSnapshot(snapshot, "Recovered")
                    val replacement = recovered.sales(restored.id).single { it.id == saved.replacementReceiptId }
                    assertEquals(price, replacement.exchange?.amountMinor)
                    assertEquals(0L, replacement.cashMinor)
                    assertEquals(saved.originalReceiptId, replacement.exchange?.linkedReceiptId)
                    assertFails { recovered.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - "sale_exchanges"), "Missing links") }
                    val altered = snapshot.tables.getValue("sale_exchanges").single() + ("applied_minor" to "599")
                    assertFails { recovered.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables + ("sale_exchanges" to listOf(altered))), "Altered link") }
                    assertEquals(listOf(restored), recovered.tenants())
                }
            }
        } finally {
            Files.deleteIfExists(backup)
            Files.delete(directory)
        }
    }

    @Test fun receiptsWithCreditOrExternalPaymentsRequireTheirOwnSettlementWorkflow() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val item = repo.addProduct(shop.id, "Item", "piece", 500, 4.0)
            val customer = repo.addCustomer(shop.id, "Customer")
            val credit = repo.checkout(shop.id, listOf(CartLine(item, 1)), 0, customer.id)
            val transfer = repo.checkout(shop.id, listOf(CartLine(item, 1)), 0,
                externalPayments = listOf(ExternalPayment("transfer", 500, "Reference", "Shop account")))
            for (sale in listOf(credit, transfer)) {
                assertFails {
                    repo.reviewCashExchange(shop.id, sale.id, mapOf(item.id to 1), listOf(CartLine(item, 1)),
                        true, "Requested exchange")
                }
            }
            assertTrue(repo.saleReturns(shop.id).isEmpty())
        }
    }
}
