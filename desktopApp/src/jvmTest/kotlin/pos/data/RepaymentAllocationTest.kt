package pos.data

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files

class RepaymentAllocationTest {
    private data class Fixture(val shop:Tenant,val customer:Customer,val item:Product,val first:Sale,val second:Sale,val payment:CustomerRepayment)
    private fun fixture(repo:PosRepository):Fixture {
        val shop=repo.createTenant("Shop")
        val item=repo.addProduct(shop.id,"Malt","bottle",15001,20.0)
        val customer=repo.addCustomer(shop.id,"Amina")
        repo.openCashShift(shop.id,500000)
        val first=repo.checkout(shop.id,listOf(CartLine(item,2)),0,customer.id,"first-credit")
        val second=repo.checkout(shop.id,listOf(CartLine(item,2)),0,customer.id,"second-credit")
        repo.recordRepayment(shop.id,customer.id,20001,"first-payment")
        return Fixture(shop,customer,item,first,second,repo.repayments(shop.id).single())
    }

    @Test fun allocationPreservesDebtCashStockAndOriginalFinancialEvents() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val beforeCustomer=repo.customers(f.shop.id)
            val beforeCash=repo.cashEntries(f.shop.id)
            val beforeStock=repo.products(f.shop.id)
            val beforeRepayments=repo.repayments(f.shop.id)
            val allocation=repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,12345,"allocation")
            assertEquals(12345L,allocation.amountMinor)
            assertEquals(f.customer.id,allocation.customerId)
            assertEquals(7656L,repo.unallocatedRepayment(f.shop.id,f.payment.id))
            assertEquals(17657L,repo.unallocatedCredit(f.shop.id,f.first.id))
            assertEquals(beforeCustomer,repo.customers(f.shop.id))
            assertEquals(beforeCash,repo.cashEntries(f.shop.id))
            assertEquals(beforeStock,repo.products(f.shop.id))
            assertEquals(beforeRepayments,repo.repayments(f.shop.id))
            assertEquals(f.first.totalMinor,repo.sales(f.shop.id).single {it.id==f.first.id}.totalMinor)
            assertEquals(listOf(allocation),repo.repaymentAllocations(f.shop.id,f.customer.id))
        }
    }

    @Test fun retriesDoNotAssignAPaymentTwiceAndDifferentPayloadIsRejected() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val original=repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,20001,"allocation")
            val pending=repo.pendingSyncCount(f.shop.id)
            assertEquals(original,repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,20001,"allocation"))
            assertEquals(pending,repo.pendingSyncCount(f.shop.id))
            assertEquals(0L,repo.unallocatedRepayment(f.shop.id,f.payment.id))
            assertFails {repo.allocateRepayment(f.shop.id,f.payment.id,f.second.id,20001,"allocation")}
            assertFails {repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,20000,"allocation")}
            assertEquals(1,repo.repaymentAllocations(f.shop.id).size)
        }
    }

    @Test fun paymentAndReceiptCapsRejectOverAllocationBeforeAnyMutation() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val pending=repo.pendingSyncCount(f.shop.id)
            listOf(0L,-1L,20002L).forEach { amount -> assertFails {repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,amount)} }
            assertTrue(repo.repaymentAllocations(f.shop.id).isEmpty())
            assertEquals(pending,repo.pendingSyncCount(f.shop.id))
            repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,20001,"one")
            repo.recordRepayment(f.shop.id,f.customer.id,20001,"second-payment")
            val other=repo.repayments(f.shop.id).single {it.id!=f.payment.id}
            assertFails {repo.allocateRepayment(f.shop.id,other.id,f.first.id,10002)}
            assertEquals(20001L,repo.unallocatedRepayment(f.shop.id,other.id))
            repo.allocateRepayment(f.shop.id,other.id,f.first.id,10001,"two")
            assertEquals(0L,repo.unallocatedCredit(f.shop.id,f.first.id))
            repo.allocateRepayment(f.shop.id,other.id,f.second.id,10000,"three")
            assertEquals(0L,repo.unallocatedRepayment(f.shop.id,other.id))
            assertEquals(20002L,repo.unallocatedCredit(f.shop.id,f.second.id))
            assertEquals(20002L,repo.customers(f.shop.id).single().balanceMinor)
        }
    }

    @Test fun allocationsRejectForeignTenantsCustomersAndCashReceipts() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val another=repo.createTenant("Other")
            val other=repo.addCustomer(f.shop.id,"Other buyer")
            val foreignSale=repo.checkout(f.shop.id,listOf(CartLine(f.item,1)),0,other.id)
            val cash=repo.checkout(f.shop.id,listOf(CartLine(f.item,1)),15001,f.customer.id)
            assertFails {repo.allocateRepayment(f.shop.id,f.payment.id,foreignSale.id,100)}
            assertFails {repo.allocateRepayment(f.shop.id,f.payment.id,cash.id,100)}
            assertFails {repo.allocateRepayment(another.id,f.payment.id,f.first.id,100)}
            assertFails {repo.unallocatedRepayment(another.id,f.payment.id)}
            assertFails {repo.unallocatedCredit(another.id,f.first.id)}
            assertFails {repo.repaymentAllocations(another.id,f.customer.id)}
            assertTrue(repo.repaymentAllocations(f.shop.id).isEmpty())
            assertTrue(repo.repaymentAllocations(another.id).isEmpty())
        }
    }

    @Test fun snapshotRoundTripKeepsExplicitLinksAndOldBackupDoesNotInventThem() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val allocation=repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,15000,"allocation")
            val saved=repo.exportTenantSnapshot(f.shop.id)
            val restored=repo.restoreTenantSnapshot(saved,"Restored")
            assertEquals(allocation.copy(tenantId=restored.id),repo.repaymentAllocations(restored.id).single())
            assertEquals(5001L,repo.unallocatedRepayment(restored.id,f.payment.id))
            assertEquals(15002L,repo.unallocatedCredit(restored.id,f.first.id))
            val legacy=repo.restoreTenantSnapshot(saved.copy(tables=saved.tables.filterKeys {it!="repayment_allocations"}),"Old format")
            assertTrue(repo.repaymentAllocations(legacy.id).isEmpty())
            assertEquals(f.payment.amountMinor,repo.unallocatedRepayment(legacy.id,f.payment.id))
            assertEquals(repo.customers(f.shop.id).single().balanceMinor,repo.customers(legacy.id).single().balanceMinor)
        }
    }

    @Test fun malformedBackupAllocationRejectsWithoutCreatingAnyShop() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val other=repo.addCustomer(f.shop.id,"Other buyer")
            val otherSale=repo.checkout(f.shop.id,listOf(CartLine(f.item,1)),0,other.id)
            repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,10000,"allocation")
            val saved=repo.exportTenantSnapshot(f.shop.id)
            val rows=saved.tables.getValue("repayment_allocations")
            val before=repo.tenants().size
            val variants=listOf(
                rows.map {it+("amount_minor" to "20002")},
                rows.map {it+("sale_id" to otherSale.id)},
                rows.map {it+("customer_id" to other.id)},
                rows.map {it+("repayment_id" to "missing")},
                rows.map {it+("created_at" to "not-a-timestamp")},
                rows+rows.map {it+("id" to "duplicate-effect")+("amount_minor" to "10002")},
            )
            variants.forEach { forged ->
                assertFails {repo.restoreTenantSnapshot(saved.copy(tables=saved.tables+("repayment_allocations" to forged)),"Invalid")}
                assertEquals(before,repo.tenants().size)
            }
            assertEquals(10001L,repo.unallocatedRepayment(f.shop.id,f.payment.id))
        }
    }

    @Test fun reopeningExistingDatabaseRetainsAllocationWithoutReapplyingRepayment() {
        val file=Files.createTempFile("pos-allocations-",".db")
        try {
            val fixture=PosRepository(file.toString()).use {repo ->
                val f=fixture(repo)
                repo.allocateRepayment(f.shop.id,f.payment.id,f.first.id,20001,"allocation")
                f
            }
            PosRepository(file.toString()).use {repo ->
                assertEquals(1,repo.repaymentAllocations(fixture.shop.id).size)
                assertEquals(0L,repo.unallocatedRepayment(fixture.shop.id,fixture.payment.id))
                assertEquals(40003L,repo.customers(fixture.shop.id).single().balanceMinor)
                assertEquals(1,repo.repayments(fixture.shop.id).size)
                assertEquals(1,repo.cashEntries(fixture.shop.id).count {it.kind=="repayment"})
            }
        } finally {Files.deleteIfExists(file)}
    }
}
