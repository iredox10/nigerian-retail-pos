package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.*
import java.nio.file.Files

class CreditReturnIntegrityTest {
    private data class Fixture(val shop:Tenant,val customer:Customer,val item:Product,val sale:Sale)
    private fun PosRepository.fixture(cash:Long=200):Fixture {
        val shop=createTenant("Shop");val customer=addCustomer(shop.id,"Amina");val item=addProduct(shop.id,"Soap","piece",100,20.0)
        val sale=checkout(shop.id,listOf(CartLine(item,10)),cash,customer.id,"sale")
        return Fixture(shop,customer,item,sale)
    }
    @Test fun unpaidCreditReturnReducesDebtAndRestocksWithoutNeedingCashDrawer() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture()
            val cash=repo.cashEntries(f.shop.id)
            val returned=repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 3),true,"Goods returned unopened","return")
            assertEquals(300L,returned.creditReductionMinor);assertEquals(0L,returned.refundMinor)
            assertEquals(500L,repo.customers(f.shop.id).single().balanceMinor)
            assertEquals(500L,repo.unallocatedCredit(f.shop.id,f.sale.id))
            assertEquals(13.0,repo.products(f.shop.id).single().stock)
            assertEquals(cash,repo.cashEntries(f.shop.id));assertTrue(repo.repayments(f.shop.id).isEmpty())
            assertEquals(800L,repo.sales(f.shop.id).single().creditMinor)
            assertEquals(300L,repo.creditReturnReductions(f.shop.id).single().amountMinor)
            val snapshot=repo.exportTenantSnapshot(f.shop.id)
            assertEquals(returned,repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 3),true,"Goods returned unopened","return"))
            assertEquals(snapshot,repo.exportTenantSnapshot(f.shop.id))
            assertFails {repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 4),true,"Goods returned unopened","return")}
            assertEquals(snapshot,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun partPaidReturnRequiresAssignmentsThenRefundsOnlyPaidPortion() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();repo.recordRepayment(f.shop.id,f.customer.id,400,"payment")
            val before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 10),true,"All goods returned","return")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.allocateRepayment(f.shop.id,repo.repayments(f.shop.id).single().id,f.sale.id,400,"assignment")
            repo.openCashShift(f.shop.id,1000)
            val returned=repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 10),true,"All goods returned","return")
            assertEquals(400L,returned.creditReductionMinor);assertEquals(600L,returned.refundMinor)
            assertEquals(0L,repo.customers(f.shop.id).single().balanceMinor)
            assertEquals(0L,repo.unallocatedCredit(f.shop.id,f.sale.id))
            assertEquals(20.0,repo.products(f.shop.id).single().stock)
            assertEquals(-600L,repo.cashEntries(f.shop.id).single {it.kind=="refund"}.amountMinor)
            assertEquals(1,repo.repayments(f.shop.id).size)
            val snapshot=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.reverseRepaymentAllocation(f.shop.id,"assignment","Reassign refunded money","undo")}
            assertEquals(snapshot,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun fullyRepaidCreditSaleReturnsAsCashWithoutChangingOriginalCredit() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture(0);repo.recordRepayment(f.shop.id,f.customer.id,1000,"payment")
            repo.allocateRepayment(f.shop.id,repo.repayments(f.shop.id).single().id,f.sale.id,1000,"assignment")
            repo.openCashShift(f.shop.id,1000)
            val returned=repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 10),false,"Paid goods returned","return")
            assertEquals(0L,returned.creditReductionMinor);assertEquals(1000L,returned.refundMinor)
            assertEquals(0L,repo.customers(f.shop.id).single().balanceMinor)
            assertEquals(1000L,repo.sales(f.shop.id).single().creditMinor)
            assertEquals(0L,repo.creditReturnReductions(f.shop.id).single().amountMinor)
            assertEquals(1000L,repo.creditReturnReductions(f.shop.id).single().cashRefundMinor)
            val restored=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(f.shop.id),"Restored")
            assertEquals(returned.refundMinor,repo.saleReturns(restored.id).single().refundMinor)
        }
    }
    @Test fun insufficientCashOrStaleReviewedSplitLeavesStockDebtAndHistoryUnchanged() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture();val reviewed=repo.creditReturnAllowance(f.shop.id,f.sale.id)
            repo.recordRepayment(f.shop.id,f.customer.id,400,"payment");repo.allocateRepayment(f.shop.id,repo.repayments(f.shop.id).single().id,f.sale.id,400,"assignment")
            repo.openCashShift(f.shop.id,500)
            val before=repo.exportTenantSnapshot(f.shop.id)
            assertFails {repo.returnCreditItemsReviewed(f.shop.id,f.sale.id,mapOf(f.item.id to 1),true,"Stale preview","stale",reviewed)}
            assertFails {repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 10),true,"Too much cash needed","return")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun exactFractionalDiscountedReturnsConserveCreditAndCash() {
        PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Measured shop");val customer=repo.addCustomer(shop.id,"Amina")
            val item=repo.addProduct(shop.id,"Rice","kg",1001,5.0,quantityPrecision=3)
            val rule=BasketDiscount("fixed",1,"Agreed whole sale price")
            val lines=allocateBasketDiscount(listOf(CartLine(item,1000)),rule)
            val sale=repo.checkout(shop.id,lines,300,customer.id,"sale",basketDiscount=rule)
            repo.openCashShift(shop.id,300)
            val amounts=listOf(125,375,500).mapIndexed {index,q ->repo.returnItems(shop.id,sale.id,mapOf(item.id to q),true,"Measured return","return-$index")}
            assertEquals(1000L,amounts.sumOf {it.returnedValueMinor})
            assertEquals(700L,amounts.sumOf {it.creditReductionMinor});assertEquals(300L,amounts.sumOf {it.refundMinor})
            assertEquals(0L,repo.customers(shop.id).single().balanceMinor);assertEquals(5.0,repo.products(shop.id).single().stock)
            assertEquals(0L,repo.expectedCash(shop.id,repo.activeCashShift(shop.id)!!.id))
        }
    }
    @Test fun returnsProtectRepaymentFundingButPermitUnconsumedAssignmentUndo() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture(800);repo.recordRepayment(f.shop.id,f.customer.id,200,"payment")
            repo.allocateRepayment(f.shop.id,repo.repayments(f.shop.id).single().id,f.sale.id,200,"assignment")
            repo.openCashShift(f.shop.id,100)
            repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 1),false,"One item returned","return")
            repo.reverseRepaymentAllocation(f.shop.id,"assignment","Payment not consumed by refund","undo")
            assertEquals(200L,repo.unallocatedRepayment(f.shop.id,repo.repayments(f.shop.id).single().id))
            assertEquals(200L,repo.unallocatedCredit(f.shop.id,f.sale.id))
            assertEquals(0L,repo.customers(f.shop.id).single().balanceMinor)
            assertFails {repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 1),false,"Another item returned","next")}
        }
    }
    @Test fun assignmentCapsRespectDebtReductionAndPermissionsActorFailureAreAtomic() {
        PosRepository(":memory:").use {repo ->
            val f=repo.fixture(0)
            repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 6),true,"Six items returned","return")
            repo.recordRepayment(f.shop.id,f.customer.id,400,"payment")
            assertFails {repo.allocateRepayment(f.shop.id,repo.repayments(f.shop.id).single().id,f.sale.id,401,"over")}
            repo.allocateRepayment(f.shop.id,repo.repayments(f.shop.id).single().id,f.sale.id,400,"assignment")
            repo.openCashShift(f.shop.id,400)
            val before=repo.exportTenantSnapshot(f.shop.id)
            repo.setPermissionCheck {_,p ->require(p!=StaffPermission.MANAGE_CUSTOMERS) {"Denied customer corrections"}}
            assertFails {repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 1),true,"Denied return","denied")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
            repo.setPermissionCheck(null);repo.setActorProvider {throw IllegalStateException("Actor missing")}
            assertFails {repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 1),true,"Actor failure","failed")}
            assertEquals(before,repo.exportTenantSnapshot(f.shop.id))
        }
    }
    @Test fun sourcesAreImmutableAndOmissionsOrTamperingRejectBackupAtomically() {
        val dir=Files.createTempDirectory("credit-return-test-")
        try {
            val db=dir.resolve("shop.db");var tenant=""
            PosRepository(db.toString()).use {repo ->
                val f=repo.fixture();tenant=f.shop.id
                repo.returnItems(f.shop.id,f.sale.id,mapOf(f.item.id to 3),true,"Returned goods","return")
                assertFails {repo.featureExecute("UPDATE credit_return_reductions SET amount_minor=0 WHERE tenant_id=?",tenant)}
                assertFails {repo.featureExecute("DELETE FROM credit_return_reductions WHERE tenant_id=?",tenant)}
                val snapshot=repo.exportTenantSnapshot(tenant);val tenants=repo.tenants()
                assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-creditReturnSnapshotTables.toSet()),"Missing")}
                assertFails {repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables+("credit_return_reductions" to snapshot.tables.getValue("credit_return_reductions").map {it+("cash_refund_minor" to "1")})),"Tampered")}
                assertEquals(tenants,repo.tenants())
                val file=dir.resolve("credit.posbackup");BackupService.create(repo,tenant,"Backup-password-2026",file)
                val recovered=repo.restoreTenantSnapshot(BackupService.read(file,"Backup-password-2026"),"Recovered")
                assertEquals(500L,repo.customers(recovered.id).single().balanceMinor)
                assertEquals(300L,repo.saleReturns(recovered.id).single().creditReductionMinor)
            }
            PosRepository(db.toString()).use {repo ->assertEquals(300L,repo.creditReturnReductions(tenant).single().amountMinor)}
        } finally {dir.toFile().deleteRecursively()}
    }
}
