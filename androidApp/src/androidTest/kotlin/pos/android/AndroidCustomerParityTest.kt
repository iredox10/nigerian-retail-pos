package pos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.documents.updateReviewedAndroidCustomer
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*

@RunWith(AndroidJUnit4::class)
class AndroidCustomerParityTest {
    @Test fun reviewedContactEditsPreserveConcurrentDebtAndRejectStaleOrRevokedChanges() {
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        PosRepository(":memory:").use { r ->
            val t=r.createTenant("Customer parity").id
            val original=r.addCustomer(t,"Amina", "08012345678",creditLimitMinor=1000)
            val product=r.addProduct(t,"Soap","piece",500,10.0)
            r.openCashShift(t,0)
            r.checkout(t,listOf(CartLine(product,1)),0,customerId=original.id,operationId="credit-sale")
            val requested=original.copy(name="Amina shop",address="Market road",creditLimitMinor=0)
            val saved=r.updateReviewedAndroidCustomer(t,original,requested,"contact-edit")
            assertEquals(500L,saved.balanceMinor)
            assertEquals(0L,saved.creditLimitMinor)
            assertEquals(saved,r.updateReviewedAndroidCustomer(t,original,requested,"contact-edit"))
            assertEquals(1,r.customerChanges(t,original.id).size)
            assertEquals("Amina",r.customerChanges(t,original.id).single().previousName)
            assertThrows(IllegalArgumentException::class.java) {
                r.updateReviewedAndroidCustomer(t,original,requested.copy(name="Stale edit"),"stale-edit")
            }
            r.setPermissionCheck {_,permission ->require(permission!=StaffPermission.MANAGE_CUSTOMERS) {"Customer authority revoked"}}
            assertThrows(IllegalArgumentException::class.java) {
                r.updateReviewedAndroidCustomer(t,saved,saved.copy(name="Denied"),"denied")
            }
            assertEquals(saved,r.customers(t).single())
            assertEquals(1,r.sales(t).size)
            assertEquals(0L,r.expectedCash(t,r.activeCashShift(t)!!.id))
        }
    }
}
