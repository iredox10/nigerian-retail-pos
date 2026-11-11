package pos.data

import kotlin.test.*
import pos.domain.CartLine
import java.nio.file.Files

class PosRepositoryTest {
    @Test fun failedOwnerProvisionDoesNotLeaveUnclaimedWorkspace() {
        PosRepository(":memory:").use { db ->
            assertFailsWith<IllegalStateException> {
                db.createTenant("Unclaimed shop") { error("Credential storage failed") }
            }
            assertTrue(db.tenants().isEmpty())
        }
    }
    @Test fun repeatedOperationCannotDuplicateSaleOrStockDeduction() {
        PosRepository(":memory:").use { db ->
            val tenant=db.createTenant("Shop")
            val product=db.addProduct(tenant.id,"Malt","bottle",50000,2.0)
            val lines=listOf(CartLine(product,2))
            val first=db.checkout(tenant.id,lines,100000,operationId="stable-checkout")
            val second=db.checkout(tenant.id,lines,100000,operationId="stable-checkout")
            assertEquals(first.id,second.id)
            assertEquals(1,db.sales(tenant.id).size)
            assertEquals(0.0,db.products(tenant.id).single().stock)
            assertEquals(2,db.pendingSyncCount(tenant.id))
            assertFailsWith<IllegalArgumentException> { db.checkout(tenant.id,listOf(CartLine(product,1)),50000,operationId="stable-checkout") }
        }
    }

    @Test fun tenantsCannotReadOrMutateEachOthersRecords() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("Shop A"); val b=db.createTenant("Shop B")
            val product=db.addProduct(a.id,"Malt","bottle",50000,10.0)
            val customer=db.addCustomer(a.id,"Grace")
            assertTrue(db.products(b.id).isEmpty())
            assertTrue(db.customers(b.id).isEmpty())
            assertFailsWith<IllegalArgumentException> { db.checkout(b.id,listOf(CartLine(product,1)),50000) }
            assertFailsWith<IllegalArgumentException> { db.recordRepayment(b.id,customer.id,10000) }
            assertEquals(10.0,db.products(a.id).single().stock)
            assertTrue(db.sales(a.id).isEmpty())
            assertEquals(0,db.pendingSyncCount(b.id))
        }
    }
    @Test fun saleAndRepaymentAreDurableAndTenantScoped() {
        val path=Files.createTempDirectory("pos-persistence-").resolve("test.db").toString()
        var tenantId=""; var customerId=""
        PosRepository(path).use { db ->
            tenantId=db.createTenant("Persistent shop").id
            val product=db.addProduct(tenantId,"Malt","bottle",50000,10.0)
            customerId=db.addCustomer(tenantId,"Grace").id
            val sale=db.checkout(tenantId,listOf(CartLine(product,2)),60000,customerId)
            assertEquals(100000L,sale.totalMinor)
            assertEquals(40000L,sale.creditMinor)
            assertEquals(8.0,db.products(tenantId).single().stock)
            assertEquals(40000L,db.customers(tenantId).single().balanceMinor)
        }
        PosRepository(path).use { db ->
            assertEquals(1,db.sales(tenantId).size)
            db.recordRepayment(tenantId,customerId,15000)
            assertEquals(25000L,db.customers(tenantId).single().balanceMinor)
            assertEquals(100000L,db.sales(tenantId).single().totalMinor)
            assertEquals(4,db.pendingSyncCount(tenantId))
        }
    }
    @Test fun failedCheckoutDoesNotLeavePartialFinancialEffects() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("Shop")
            val p=db.addProduct(a.id,"Malt","bottle",50000,2.0)
            val pending=db.pendingSyncCount(a.id)
            assertFailsWith<IllegalArgumentException> { db.checkout(a.id,listOf(CartLine(p,3)),150000) }
            assertFailsWith<IllegalArgumentException> { db.checkout(a.id,listOf(CartLine(p,1)),10000) }
            assertTrue(db.sales(a.id).isEmpty())
            assertEquals(2.0,db.products(a.id).single().stock)
            assertEquals(pending,db.pendingSyncCount(a.id))
        }
    }
    @Test fun anotherTenantsCustomerCannotReceiveCredit() {
        PosRepository(":memory:").use { db ->
            val a=db.createTenant("Shop A"); val b=db.createTenant("Shop B")
            val p=db.addProduct(a.id,"Malt","bottle",50000,2.0)
            val foreignCustomer=db.addCustomer(b.id,"Grace")
            assertFailsWith<IllegalArgumentException> { db.checkout(a.id,listOf(CartLine(p,1)),0,foreignCustomer.id) }
            assertEquals(0L,db.customers(b.id).single().balanceMinor)
            assertEquals(2.0,db.products(a.id).single().stock)
        }
    }
}
