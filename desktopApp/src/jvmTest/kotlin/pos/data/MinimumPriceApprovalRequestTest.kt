package pos.data

import kotlin.test.*
import pos.domain.*

class MinimumPriceApprovalRequestTest {
    @Test fun reviewBindsAllSellingDetailsAndFloorVersionsAndAllowsCashierReview() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Rice","bag",1000,20.0)
            repo.saveMinimumPrice(shop.id,item.id,null,900,0,"Initial floor")
            val line=CartLine(item,1,discountPerUnitMinor=200,discountReason="Old stock")
            val first=repo.minimumPriceApprovalRequest(shop.id,"sale",listOf(line))
            assertTrue(first.belowFloor)
            assertTrue(first.requiresApproval)
            assertEquals(900L,first.checks.single().requiredMinor)
            assertNotEquals(first.fingerprint,repo.minimumPriceApprovalRequest(shop.id,"other",listOf(line)).fingerprint)
            assertNotEquals(first.fingerprint,repo.minimumPriceApprovalRequest(shop.id,"sale",listOf(line.copy(discountReason="End of day"))).fingerprint)
            assertNotEquals(first.fingerprint,repo.minimumPriceApprovalRequest(shop.id,"sale",listOf(line.copy(quantity=2))).fingerprint)
            repo.saveMinimumPrice(shop.id,item.id,null,950,1,"Raise floor")
            val changed=repo.minimumPriceApprovalRequest(shop.id,"sale",listOf(line))
            assertNotEquals(first.fingerprint,changed.fingerprint)
            assertEquals(2L,changed.checks.single().policyVersion)
            repo.setPermissionCheck {_,permission ->require(permission==StaffPermission.SELL)}
            assertEquals(changed,repo.minimumPriceApprovalRequest(shop.id,"sale",listOf(line)))
            assertFails {repo.minimumPricePolicies(shop.id)}
        }
    }
}
