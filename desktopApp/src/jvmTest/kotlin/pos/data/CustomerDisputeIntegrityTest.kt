package pos.data

import kotlin.test.*
import pos.domain.CartLine
import pos.domain.StaffPermission

class CustomerDisputeIntegrityTest {
    @Test fun disputeAndResolutionKeepDebtCashAndStockAndRecoverTheirHistory() {
        PosRepository(":memory:").use { repo ->
            val shop = repo.createTenant("Shop").id
            val customer = repo.addCustomer(shop, "Amina")
            val item = repo.addProduct(shop, "Soap", "piece", 500, 8.0)
            val sale = repo.checkout(shop, listOf(CartLine(item, 4)), 0, customer.id, "sale")
            val before = repo.exportTenantSnapshot(shop)
            val opened = repo.openCustomerDispute(shop, customer.id, sale.id, 1500, 2000, 2000,
                "Customer questions agreed quantity", "Call notes", "dispute")
            assertEquals(opened, repo.openCustomerDispute(shop, customer.id, sale.id, 1500, 2000, 2000,
                "Customer questions agreed quantity", "Call notes", "dispute"))
            assertEquals(CustomerDisputeBalance(2000,1500), repo.customerDisputeBalance(shop,customer.id))
            assertFails { repo.openCustomerDispute(shop,customer.id,sale.id,500,2000,2000,"Overlapping claim",operationId="second") }
            assertEquals(before.tables.getValue("customers"),repo.exportTenantSnapshot(shop).tables.getValue("customers"))
            assertEquals(before.tables.getValue("cash_entries"),repo.exportTenantSnapshot(shop).tables.getValue("cash_entries"))
            assertEquals(4L,repo.products(shop).single().exactStockScaled)
            val closed = repo.resolveCustomerDispute(shop,opened.disputeId,1,"Customer confirmed the receipt", "resolved")
            assertEquals(closed,repo.resolveCustomerDispute(shop,opened.disputeId,1,"Customer confirmed the receipt","resolved"))
            assertFails {repo.resolveCustomerDispute(shop,opened.disputeId,1,"Different decision","another")}
            assertEquals(CustomerDisputeBalance(2000,0),repo.customerDisputeBalance(shop,customer.id))
            val backup=repo.exportTenantSnapshot(shop)
            val restored=repo.restoreTenantSnapshot(backup,"Recovered")
            assertEquals(repo.customerDisputes(shop),repo.customerDisputes(restored.id))
            assertFails {repo.restoreTenantSnapshot(backup.copy(tables=backup.tables-customerDisputeSnapshotTables.toSet()),"Missing disputes")}
        }
    }

    @Test fun currentDisputedAmountFollowsAssignmentsAndReturnsWithoutGuessingUnassignedPayments() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop").id
            val customer=repo.addCustomer(shop,"Amina")
            val item=repo.addProduct(shop,"Soap","piece",500,8.0)
            val sale=repo.checkout(shop,listOf(CartLine(item,4)),0,customer.id,"sale")
            repo.openCustomerDispute(shop,customer.id,sale.id,1500,2000,2000,"Questioned receipt",operationId="dispute")
            repo.recordRepayment(shop,customer.id,1000,"payment")
            assertNull(repo.customerDisputeBalance(shop,customer.id).disputedMinor)
            repo.allocateRepayment(shop,repo.repayments(shop,customer.id).single().id,sale.id,1000,"assignment")
            assertEquals(CustomerDisputeBalance(1000,1000),repo.customerDisputeBalance(shop,customer.id))
            repo.returnItems(shop,sale.id,mapOf(item.id to 1),true,"One piece returned","return")
            assertEquals(CustomerDisputeBalance(500,500),repo.customerDisputeBalance(shop,customer.id))
            repo.returnItems(shop,sale.id,mapOf(item.id to 1),true,"Another piece returned","return-two")
            assertEquals(CustomerDisputeBalance(0,0),repo.customerDisputeBalance(shop,customer.id))
            assertTrue(repo.customerDisputes(shop).single().open)
        }
    }

    @Test fun validatesScopeReviewPermissionAndHistory() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop").id
            val other=repo.createTenant("Other").id
            val customer=repo.addCustomer(shop,"Amina")
            val item=repo.addProduct(shop,"Soap","piece",500,8.0)
            val sale=repo.checkout(shop,listOf(CartLine(item,2)),0,customer.id,"sale")
            assertFails {repo.openCustomerDispute(other,customer.id,sale.id,500,1000,1000,"Wrong shop")}
            assertFails {repo.openCustomerDispute(shop,customer.id,sale.id,1001,1000,1000,"Excess claim")}
            assertFails {repo.openCustomerDispute(shop,customer.id,sale.id,500,900,1000,"Stale review")}
            repo.setPermissionCheck {_,required ->require(required != StaffPermission.MANAGE_CUSTOMERS)}
            assertFails {repo.openCustomerDispute(shop,customer.id,sale.id,500,1000,1000,"Denied")}
            repo.setPermissionCheck(null)
            val event=repo.openCustomerDispute(shop,customer.id,sale.id,500,1000,1000,"Questioned price",operationId="dispute")
            val snapshot=repo.exportTenantSnapshot(shop)
            val rows=snapshot.tables.getValue("customer_dispute_events")
            val tampered=rows.map {it+("version" to "2")}
            assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables+("customer_dispute_events" to tampered)),"Tampered")}
            assertFails {repo.openCustomerDispute(shop,customer.id,sale.id,400,1000,1000,event.reason,operationId=event.id)}
        }
    }
}
