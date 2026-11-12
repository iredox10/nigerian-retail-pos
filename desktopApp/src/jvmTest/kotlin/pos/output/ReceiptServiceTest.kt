package pos.output

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import pos.domain.CartLine
import pos.domain.Product
import pos.domain.ReturnLine
import pos.domain.Sale
import pos.domain.SaleReturn
import pos.domain.Tenant

class ReceiptServiceTest {
    private val tenant = Tenant("shop-a", "Adewale Stores")
    private val product = Product("p1", tenant.id, "Malt drink 330 ml", "bottle", 50000L, 20.0)
    private fun sale(credit: Boolean = false) = Sale(
        "receipt-1", tenant.id, listOf(CartLine(product, 2)), 100000L,
        if (credit) 50000L else 100000L, if (credit) 50000L else 0L,
        if (credit) "customer-1" else null, "2026-11-11T10:00:00Z",
    )

    @Test fun cashReceiptShowsActualShopAndAmountsInWATWithoutInventingChange() {
        val text = ReceiptService.format(tenant, sale())
        assertContains(text, "Adewale Stores")
        assertContains(text, "2 bottle x ₦500.00")
        assertContains(text, "Original sale total: ₦1,000.00")
        assertContains(text, "Cash applied at sale: ₦1,000.00")
        assertContains(text, "11 Nov 2026 · 11:00 WAT")
        assertContains(text, "Paid in cash")
        assertFalse(text.contains("Change to give"))
    }

    @Test fun creditReceiptDoesNotClaimCurrentDebtOrFullyPaid() {
        val text = ReceiptService.format(tenant, sale(credit = true))
        assertContains(text, "Customer owed at sale: ₦500.00")
        assertContains(text, "This is not the customer's current debt.")
        assertFalse(text.contains("Paid in cash"))
    }

    @Test fun printedShopCannotBeSubstitutedAcrossTenants() {
        assertFailsWith<IllegalArgumentException> { ReceiptService.format(Tenant("shop-b", "Other shop"), sale()) }
    }

    @Test fun returnsRemainSeparateFromOriginalSaleAndCannotCrossReceiptBoundary() {
        val returned = SaleReturn("r1", tenant.id, "receipt-1",
            listOf(ReturnLine(product.id, product.name, product.unit, 1, 50000)),
            50000, true, "Wrong item", "2026-11-11T11:00:00Z")
        val text = ReceiptService.format(tenant, sale(), listOf(returned))
        assertContains(text, "Original sale total: ₦1,000.00")
        assertContains(text, "Total cash refunded: ₦500.00")
        assertContains(text, "Sale value after returns: ₦500.00")
        assertContains(text, "Returned goods added back to stock")
        assertFailsWith<IllegalArgumentException> {
            ReceiptService.format(tenant, sale(), listOf(returned.copy(saleId = "other-receipt")))
        }
        assertFailsWith<IllegalArgumentException> {
            ReceiptService.format(tenant, sale(), listOf(returned.copy(tenantId = "other-shop")))
        }
    }
}
