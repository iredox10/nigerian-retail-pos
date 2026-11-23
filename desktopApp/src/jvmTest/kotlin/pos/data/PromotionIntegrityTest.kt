package pos.data

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import pos.domain.BasketDiscount
import pos.domain.CartLine
import pos.domain.PromotionReduction
import pos.domain.PromotionSource
import pos.domain.PromotionTerms
import pos.domain.StaffPermission
import pos.domain.allocateBasketDiscount
import pos.domain.displayPriceListReason

class PromotionIntegrityTest {
    private val wat = ZoneId.of("Africa/Lagos")

    @Test fun heldBasketKeepsReviewedPromotionAndCheckoutCountsItOnce() {
        val directory = Files.createTempDirectory("promotion-draft-")
        try {
            PosRepository(":memory:").use { repo ->
                val shop = repo.createTenant("Shop")
                val soap = repo.addProduct(shop.id, "Soap", "piece", 1_000, 20.0)
                val pack = repo.addSellingUnit(shop.id, soap.id, "box", 3, 3_000)
                val day = Instant.now().atZone(wat).toLocalDate()
                val terms = PromotionTerms(
                    "Weekend / offer", soap.id, null, 2, 0, day.toString(), day.plusDays(1).toString(),
                    PromotionReduction.PERCENTAGE, 1_500, maximumSales = 1,
                )
                repo.savePromotion(shop.id, "weekend", terms, 0, "Approved weekend prices", "setup")
                val line = CartLine(soap, 2)
                val packLine = CartLine(soap, 2, pack.id, pack.name, pack.priceMinor, pack.baseUnitsPerUnit)
                val end = day.plusDays(2).atStartOfDay(wat).toInstant()
                assertTrue(repo.quotePromotions(shop.id, listOf(line), end).isEmpty())
                assertEquals(1, repo.quotePromotions(shop.id, listOf(line), end.minusMillis(1)).size)
                assertTrue(repo.quotePromotions(shop.id, listOf(line.copy(quantity = 1), packLine)).isEmpty())

                val quote = repo.quotePromotions(shop.id, listOf(line, packLine)).single()
                assertEquals(150L, quote.discount.amountMinor)
                val draft = SaleDraft("${line.key}:2", "sale", discounts = mapOf(line.key to quote.discount))
                val store = DraftStore(shop.id, directory)
                store.save(draft)
                val held = store.hold("Afternoon pickup", draft)
                val resumed = DraftStore(shop.id, directory).resume(held.id)
                assertEquals(draft, resumed)

                val discount = resumed.discounts.getValue(line.key)
                val promoted = line.copy(discountPerUnitMinor = discount.amountMinor, discountReason = discount.reason)
                val sale = repo.checkout(shop.id, listOf(promoted), 1_700, operationId = resumed.operationId)
                assertEquals(sale.id, repo.checkout(shop.id, listOf(promoted), 1_700, operationId = resumed.operationId).id)
                val reason = checkNotNull(repo.sales(shop.id).single().lines.single().discountReason)
                assertEquals("Promotion · Weekend / offer", displayPriceListReason(reason))
                assertEquals(quote.promotion.version, PromotionSource.decode(reason)?.version)
                assertEquals(1L, repo.promotions(shop.id).single().sales)
                assertTrue(repo.quotePromotions(shop.id, listOf(line)).isEmpty())
                assertFails { repo.checkout(shop.id, listOf(promoted), 1_700, operationId = "another-sale") }
                val next = repo.savePromotion(shop.id, "weekend", terms.copy(maximumSales = 2), 1,
                    "Extended number of sales", "limit-change")
                assertEquals(1L, repo.promotions(shop.id).single().sales)
                assertEquals(next, repo.quotePromotions(shop.id, listOf(line)).single().promotion)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun staleTermsAndStackingFailBeforeRecordingSale() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val product = repo.addProduct(shop.id, "Soap", "piece", 1_000, 12.0)
            val today = Instant.now().atZone(wat).toLocalDate()
            val terms = PromotionTerms("Shop discount", product.id, null, 1, 0, today.toString(),
                today.plusDays(1).toString(), PromotionReduction.FIXED, 200)
            val original = repo.savePromotion(shop.id, "deal", terms, 0, "Set a sale price", "setup")
            assertEquals(original, repo.savePromotion(shop.id, "deal", terms, 0, "Set a sale price", "setup"))
            val line = CartLine(product, 2)
            val quote = repo.quotePromotions(shop.id, listOf(line)).single()
            val promoted = line.copy(discountPerUnitMinor = quote.discount.amountMinor, discountReason = quote.discount.reason)

            val basket = BasketDiscount("fixed", 100, "Manual price reduction")
            val stacked = allocateBasketDiscount(listOf(promoted), basket)
            assertFails {
                repo.checkout(shop.id, stacked, 1_500, operationId = "stacked", basketDiscount = basket)
            }
            assertFails {
                repo.checkout(shop.id, listOf(promoted.copy(quantity = 3)), 2_400, operationId = "quantity-changed")
            }
            repo.savePromotion(shop.id, "deal", terms.copy(amount = 100), 1, "Changed the promotion amount", "edit")
            assertFails { repo.checkout(shop.id, listOf(promoted), 1_600, operationId = "stale") }
            assertTrue(repo.sales(shop.id).isEmpty())

            val current = repo.quotePromotions(shop.id, listOf(line)).single()
            val currentLine = line.copy(discountPerUnitMinor = current.discount.amountMinor, discountReason = current.discount.reason)
            repo.setPermissionCheck { _, permission -> require(permission != StaffPermission.DISCOUNT) { "Denied" } }
            assertFails { repo.quotePromotions(shop.id, listOf(line)) }
            assertFails { repo.checkout(shop.id, listOf(currentLine), 1_800, operationId = "forbidden-sale") }
        }
    }

    @Test fun backupRestoresOriginalSaleTermsAndRejectsMissingPromotionHistory() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop")
            val product = repo.addProduct(shop.id, "Soap", "piece", 1_000, 12.0)
            val today = Instant.now().atZone(wat).toLocalDate()
            val terms = PromotionTerms("Soap discount", product.id, null, 1, 0, today.toString(),
                today.plusDays(1).toString(), PromotionReduction.FIXED, 200)
            repo.savePromotion(shop.id, "deal", terms, 0, "Set a sale price", "setup")
            val line = CartLine(product, 2)
            val quote = repo.quotePromotions(shop.id, listOf(line)).single()
            val promoted = line.copy(discountPerUnitMinor = quote.discount.amountMinor, discountReason = quote.discount.reason)
            repo.checkout(shop.id, listOf(promoted), 1_600, operationId = "sale")
            repo.savePromotion(shop.id, "deal", terms.copy(name = "Updated offer", archived = true), 1, "Ended offer", "archive")

            val snapshot = repo.exportTenantSnapshot(shop.id)
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored shop")
            assertEquals(2, repo.promotionHistory(restored.id, "deal").size)
            assertEquals(1L, repo.promotions(restored.id).single().sales)
            val restoredSale = repo.sales(restored.id).single()
            assertEquals(1_600L, restoredSale.totalMinor)
            assertEquals("Soap discount", PromotionSource.decode(checkNotNull(restoredSale.lines.single().discountReason))?.title)
            assertFails { repo.restoreTenantSnapshot(snapshot.copy(tables = snapshot.tables - promotionSnapshotTables.toSet()), "Missing") }
            assertFails { repo.featureExecute("UPDATE promotion_revisions SET name='Tampered' WHERE tenant_id=?", shop.id) }
        }
    }
}
