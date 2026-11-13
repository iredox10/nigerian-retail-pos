package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.ReceiptService
import java.nio.file.Files

class PercentageDiscountIntegrityTest {
    @Test fun savedPercentageReductionSurvivesDraftRecoveryAndRefundsExactNetUnitPrice() {
        val dir=Files.createTempDirectory("percentage-discount")
        try {
            PosRepository(":memory:").use { repo ->
                val shop=repo.createTenant("Shop")
                repo.openCashShift(shop.id,0)
                val item=repo.addProduct(shop.id,"Soap","piece",101,5.0)
                val reduction=requireNotNull(parseDiscountPercentage("12.5")).unitDiscount(item.priceMinor,"Agreed price")
                val draft=SaleDraft("${item.id}:3","percentage-sale",discounts=mapOf(item.id to reduction))
                DraftStore(shop.id,dir).save(draft)
                val recovered=DraftStore(shop.id,dir).load()!!
                assertEquals(reduction,recovered.discounts.getValue(item.id))
                val held=DraftStore(shop.id,dir).hold("Agreed percentage",recovered)
                assertEquals(recovered,DraftStore(shop.id,dir).resume(held.id))
                val savedReduction=recovered.discounts.getValue(item.id)
                val sale=repo.checkout(shop.id,listOf(CartLine(item,3,discountPerUnitMinor=savedReduction.amountMinor,discountReason=savedReduction.reason)),264,operationId="percentage-sale")
                assertEquals(264L,sale.totalMinor)
                assertEquals(13L,sale.lines.single().discountPerUnitMinor)
                assertContains(ReceiptService.format(shop,sale),"12.5% off: Agreed price")
                val first=repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Unopened item","first")
                assertEquals(88L,first.refundMinor)
                val rest=repo.returnItems(shop.id,sale.id,mapOf(item.id to 2),true,"Remaining items","rest")
                assertEquals(176L,rest.refundMinor)
                assertEquals(sale.totalMinor,first.refundMinor+rest.refundMinor)
                val snapshot=repo.exportTenantSnapshot(shop.id)
                val restored=repo.restoreTenantSnapshot(snapshot,"Recovered shop")
                assertEquals(sale.lines.single().discountReason,repo.sales(restored.id).single().lines.single().discountReason)
                assertEquals(264L,repo.sales(restored.id).single().totalMinor)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun fullyDiscountedPercentageCreatesNoDebtAndCannotInventARefund() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
                repo.openCashShift(shop.id,0)
            val item=repo.addProduct(shop.id,"Sample","piece",123,1.0)
            val reduction=DiscountPercentage(10000).unitDiscount(123,"Owner sample")
            val sale=repo.checkout(shop.id,listOf(CartLine(item,1,discountPerUnitMinor=reduction.amountMinor,discountReason=reduction.reason)),0,operationId="free")
            assertEquals(0L,sale.totalMinor)
            assertEquals(0L,sale.creditMinor)
            assertEquals(0L,repo.returnItems(shop.id,sale.id,mapOf(item.id to 1),true,"Sample returned").refundMinor)
        }
    }
}
