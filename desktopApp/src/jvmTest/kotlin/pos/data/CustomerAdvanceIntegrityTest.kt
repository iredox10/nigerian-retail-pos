package pos.data

import kotlin.test.*
import pos.domain.CartLine
import pos.domain.StaffPermission

class CustomerAdvanceIntegrityTest {
    @Test fun receivesAppliesAndRefundsCashOnceWithRecoverableLiabilityHistory() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val shift = repo.openCashShift(shop, 0)
            val received = repo.receiveCustomerAdvance(shop, customer.id, 1500, 0, 0, "Customer cash for later purchases", "received")
            assertEquals(received, repo.receiveCustomerAdvance(shop,customer.id,1500,0,0,"Customer cash for later purchases","received"))
            assertEquals(1500L,repo.customerAdvanceBalance(shop,customer.id))
            assertEquals(1500L,repo.expectedCash(shop,shift.id))
            assertEquals(0L,repo.customers(shop).single().balanceMinor)
            val item = repo.addProduct(shop,"Soap","piece",500,8.0)
            val sale = repo.checkout(shop,listOf(CartLine(item,4)),0,customer.id,"sale")
            val applied = repo.applyCustomerAdvance(shop,customer.id,sale.id,1000,1500,1,2000,2000,"Apply customer held cash","applied")
            assertEquals(applied,repo.applyCustomerAdvance(shop,customer.id,sale.id,1000,1500,1,2000,2000,"Apply customer held cash","applied"))
            assertEquals(500L,repo.customerAdvanceBalance(shop,customer.id))
            assertEquals(1000L,repo.customers(shop).single().balanceMinor)
            assertEquals(1000L,repo.unallocatedCredit(shop,sale.id))
            assertEquals(0L,repo.unallocatedRepayment(shop,requireNotNull(applied.repaymentId)))
            assertEquals(1500L,repo.expectedCash(shop,shift.id))
            assertTrue(repo.cashEntries(shop).none {it.kind=="repayment"})
            val refunded = repo.refundCustomerAdvance(shop,customer.id,500,500,2,"Customer collected remaining cash","refunded")
            assertEquals(refunded,repo.refundCustomerAdvance(shop,customer.id,500,500,2,"Customer collected remaining cash","refunded"))
            assertEquals(0L,repo.customerAdvanceBalance(shop,customer.id))
            assertEquals(1000L,repo.expectedCash(shop,shift.id))
            assertEquals(1000L,repo.customers(shop).single().balanceMinor)
            val snapshot = repo.exportTenantSnapshot(shop)
            val restored = repo.restoreTenantSnapshot(snapshot,"Restored")
            assertEquals(repo.customerAdvances(shop),repo.customerAdvances(restored.id))
            assertEquals(1000L,repo.expectedCash(restored.id,shift.id))
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-customerAdvanceSnapshotTables.toSet()),"Missing advances")}
            val rows=snapshot.tables.getValue("customer_advance_events")
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables+("customer_advance_events" to rows.map {it+("after_minor" to "99")})),"Tampered balance")}
        }
    }

    @Test fun guardsDrawerFundsScopeStaleReviewAndExactReceiptSelection() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop").id
            val other=repo.createTenant("Other").id
            val customer=repo.addCustomer(shop,"Amina")
            val otherCustomer=repo.addCustomer(shop,"Chidi")
            assertFails {repo.receiveCustomerAdvance(shop,customer.id,1000,0,0,"No open drawer")}
            val shift=repo.openCashShift(shop,0)
            assertFails {repo.receiveCustomerAdvance(other,customer.id,1000,0,0,"Wrong shop")}
            repo.receiveCustomerAdvance(shop,customer.id,1000,0,0,"Receive actual customer cash","received")
            val item=repo.addProduct(shop,"Rice","bag",1000,8.0)
            val sale=repo.checkout(shop,listOf(CartLine(item,1)),0,customer.id,"sale")
            val foreignSale=repo.checkout(shop,listOf(CartLine(item,1)),0,otherCustomer.id,"other-sale")
            val before=repo.exportTenantSnapshot(shop)
            assertFails {repo.applyCustomerAdvance(shop,customer.id,foreignSale.id,500,1000,1,1000,1000,"Wrong customer")}
            assertFails {repo.applyCustomerAdvance(shop,customer.id,sale.id,500,1000,1,999,1000,"Stale receipt")}
            assertFails {repo.refundCustomerAdvance(shop,customer.id,1001,1000,1,"Too much")}
            assertFails {repo.refundCustomerAdvance(shop,customer.id,500,1000,0,"Stale version")}
            assertEquals(before,repo.exportTenantSnapshot(shop))
            repo.recordCashMovement(shop,-900,"Cash moved out","cash-out")
            assertFails {repo.refundCustomerAdvance(shop,customer.id,500,1000,1,"Drawer too low")}
            assertEquals(1000L,repo.customerAdvanceBalance(shop,customer.id))
            assertEquals(100L,repo.expectedCash(shop,shift.id))
            repo.setPermissionCheck {_,permission ->require(permission != StaffPermission.MANAGE_MONEY)}
            assertFails {repo.receiveCustomerAdvance(shop,customer.id,200,1000,1,"Denied cash action")}
            repo.setPermissionCheck(null)
            assertEquals(1,repo.customerAdvances(shop,customer.id).size)
        }
    }

    @Test fun unassignedRepaymentMustBeReconciledAndOriginalAssignmentCorrectionsKeepFundingHistory() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop").id
            val customer=repo.addCustomer(shop,"Amina")
            val shift=repo.openCashShift(shop,0)
            repo.receiveCustomerAdvance(shop,customer.id,2000,0,0,"Customer held cash","received")
            val item=repo.addProduct(shop,"Soap","piece",1000,8.0)
            val sale=repo.checkout(shop,listOf(CartLine(item,3)),0,customer.id,"sale")
            repo.recordRepayment(shop,customer.id,500,"cash-payment")
            assertFails {repo.applyCustomerAdvance(shop,customer.id,sale.id,1000,2000,1,3000,2500,"Assignment pending")}
            val cash=repo.repayments(shop,customer.id).single()
            repo.allocateRepayment(shop,cash.id,sale.id,500,"cash-assigned")
            val applied=repo.applyCustomerAdvance(shop,customer.id,sale.id,1000,2000,1,2500,2500,"Use advance","applied")
            assertEquals(2500L,repo.expectedCash(shop,shift.id))
            repo.reverseRepaymentAllocation(shop,requireNotNull(applied.allocationId),"Customer requests receipt reassignment","reversed")
            assertEquals(1000L,repo.customerAdvanceBalance(shop,customer.id))
            assertEquals(1500L,repo.customers(shop).single().balanceMinor)
            val snapshot=repo.exportTenantSnapshot(shop)
            val restored=repo.restoreTenantSnapshot(snapshot,"Restored after assignment correction")
            assertEquals(1000L,repo.customerAdvanceBalance(restored.id,customer.id))
            assertEquals(2500L,repo.expectedCash(restored.id,shift.id))
        }
    }
}
