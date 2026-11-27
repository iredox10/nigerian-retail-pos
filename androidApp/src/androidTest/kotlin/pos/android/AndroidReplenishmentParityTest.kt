package pos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*

@RunWith(AndroidJUnit4::class)
class AndroidReplenishmentParityTest {
    @Test fun measuredBuyingRulesRoundExactlyAndRetainVersionHistoryWithoutMovingStock() {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use { r ->
            val t=r.createTenant("Buying rule parity").id
            val item=r.addProduct(t,"Loose rice","kg",100,0.5,quantityPrecision=3)
            val first=r.saveReplenishmentPolicy(t,item.id,3,2250,1000,0,"Supplier buying terms","first-policy")
            assertEquals(first,r.saveReplenishmentPolicy(t,item.id,3,2250,1000,0,"Supplier buying terms","first-policy"))
            val suggestion=r.reorderSuggestions(t,14).rows.single()
            assertEquals(2250L,suggestion.target);assertEquals(2000L,suggestion.shortage)
            assertEquals(500L,r.products(t).single().exactStockScaled)
            val second=r.saveReplenishmentPolicy(t,item.id,4,3250,1000,first.version,"Increased shelf buffer","second-policy")
            assertThrows(IllegalArgumentException::class.java) {
                r.saveReplenishmentPolicy(t,item.id,1,0,1000,first.version,"Stale review","stale-policy")
            }
            assertEquals(2,r.replenishmentPolicyHistory(t).size)
            assertEquals(second,r.replenishmentPolicies(t)[item.id])
            r.setPermissionCheck {_,permission -> require(permission!=StaffPermission.MANAGE_STOCK) {"Stock authority revoked"} }
            assertThrows(IllegalArgumentException::class.java) {
                r.saveReplenishmentPolicy(t,item.id,1,0,1000,second.version,"Not allowed","denied-policy")
            }
            assertEquals(500L,r.products(t).single().exactStockScaled)
            assertTrue(r.stockReceipts(t).isEmpty());assertTrue(r.sales(t).isEmpty())
        }
    }
}
