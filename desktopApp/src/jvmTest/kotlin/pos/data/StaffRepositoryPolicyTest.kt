package pos.data

import kotlin.test.*
import pos.domain.*

/** The policy callback represents the live session validator installed by the desktop. */
class StaffRepositoryPolicyTest {
    private data class Fixture(
        val tenant: Tenant, val item: Product, val customer: Customer,
        val cashSale: Sale, val creditSale: Sale, val payment: CustomerRepayment,
        val assignment: RepaymentAllocation, val purchase: StockReceipt,
        val buyingUnit: PurchaseUnit, val sellingUnit: SellingUnit, val shift: CashShift,
    )

    private fun fixture(repo: PosRepository): Fixture {
        val t=repo.createTenant("Market")
        val p=repo.addProduct(t.id,"Malt","bottle",10_000,50.0)
        val c=repo.addCustomer(t.id,"Amina")
        val shift=repo.openCashShift(t.id,100_000)
        val cash=repo.checkout(t.id,listOf(CartLine(p,1)),10_000)
        val credit=repo.checkout(t.id,listOf(CartLine(p,2)),0,c.id)
        repo.recordRepayment(t.id,c.id,5_000)
        val payment=repo.repayments(t.id).single()
        val assignment=repo.allocateRepayment(t.id,payment.id,credit.id,5_000)
        val purchase=repo.receiveStock(t.id,p.id,2,5_000,0,"Wholesaler")
        val buying=repo.addPurchaseUnit(t.id,p.id,"carton",24)
        val selling=repo.addSellingUnit(t.id,p.id,"carton",24,200_000)
        return Fixture(t,p,c,cash,credit,payment,assignment,purchase,buying,selling,shift)
    }

    @Test fun everyGuardedMutationChecksPermissionBeforeAnyFinancialOrCatalogueWrite() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val before=repo.exportTenantSnapshot(f.tenant.id)
            val cases:List<Pair<StaffPermission,()->Any?>> = listOf(
                StaffPermission.MANAGE_STOCK to {repo.addProduct(f.tenant.id,"Soap","piece",500,1.0)},
                StaffPermission.MANAGE_STOCK to {repo.setReorderLevel(f.tenant.id,f.item.id,5)},
                StaffPermission.MANAGE_STOCK to {repo.updateProduct(f.tenant.id,f.item.id,"Malt new","bottle",12_000,null)},
                StaffPermission.MANAGE_STOCK to {repo.archiveProduct(f.tenant.id,f.item.id,true)},
                StaffPermission.MANAGE_STOCK to {repo.adjustStock(f.tenant.id,f.item.id,40,"Counted today")},
                StaffPermission.MANAGE_STOCK to {repo.importProducts(f.tenant.id,listOf(ProductImportRow("Soap","piece",500,3)))},
                StaffPermission.MANAGE_STOCK to {repo.addSellingUnit(f.tenant.id,f.item.id,"pack",6,50_000)},
                StaffPermission.MANAGE_STOCK to {repo.archiveSellingUnit(f.tenant.id,f.sellingUnit.id,true)},
                StaffPermission.MANAGE_STOCK to {repo.addPurchaseUnit(f.tenant.id,f.item.id,"pack",6)},
                StaffPermission.MANAGE_STOCK to {repo.archivePurchaseUnit(f.tenant.id,f.buyingUnit.id,true)},
                StaffPermission.MANAGE_STOCK to {repo.receiveStock(f.tenant.id,f.item.id,1,5_000,0,"Wholesaler")},
                StaffPermission.MANAGE_CUSTOMERS to {repo.addCustomer(f.tenant.id,"Bisi")},
                StaffPermission.MANAGE_CUSTOMERS to {repo.updateCustomer(f.tenant.id,f.customer.id,"Amina new",null,null,null)},
                StaffPermission.MANAGE_CUSTOMERS to {repo.recordRepayment(f.tenant.id,f.customer.id,1_000)},
                StaffPermission.MANAGE_CUSTOMERS to {repo.allocateRepayment(f.tenant.id,f.payment.id,f.creditSale.id,1)},
                StaffPermission.MANAGE_CUSTOMERS to {repo.reverseRepaymentAllocation(f.tenant.id,f.assignment.id,"Wrong receipt")},
                StaffPermission.MANAGE_MONEY to {repo.recordExpense(f.tenant.id,"Fuel","Generator",1_000)},
                StaffPermission.MANAGE_MONEY to {repo.openCashShift(f.tenant.id,0)},
                StaffPermission.MANAGE_MONEY to {repo.recordCashMovement(f.tenant.id,1_000,"Owner added cash")},
                StaffPermission.MANAGE_MONEY to {repo.closeCashShift(f.tenant.id,f.shift.id,100_000)},
                StaffPermission.RETURN_SALE to {repo.returnItems(f.tenant.id,f.cashSale.id,mapOf(f.item.id to 1),true,"Unopened")},
                StaffPermission.MANAGE_SUPPLIERS to {repo.paySupplierReceipt(f.tenant.id,f.purchase.id,1_000)},
                StaffPermission.BACKUP to {repo.exportTenantSnapshot(f.tenant.id)},
                StaffPermission.BACKUP to {repo.restoreTenantSnapshot(before,"Recovered")},
            )
            cases.forEach { (expected,command) ->
                var checked:Pair<String,StaffPermission>?=null
                repo.setPermissionCheck { tenant,permission -> checked=tenant to permission; throw SecurityException("No permission") }
                assertFailsWith<SecurityException> {command()}
                assertEquals(f.tenant.id to expected,checked)
            }
            repo.setPermissionCheck(null)
            assertEquals(before,repo.exportTenantSnapshot(f.tenant.id))
            assertEquals(1,repo.tenants().size)
        }
    }

    @Test fun cashierCanCompleteCashSalesButCannotHideCreditOrDiscountInRequest() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            repo.setPermissionCheck { tenant,permission ->
                require(tenant==f.tenant.id)
                if(!StaffRole.CASHIER.allows(permission)) throw SecurityException("Owner or manager required")
            }
            val sale=repo.checkout(f.tenant.id,listOf(CartLine(f.item,1)),10_000,operationId="cashier-cash")
            assertEquals(10_000L,sale.totalMinor)
            val stock=repo.products(f.tenant.id).single().stock
            val pending=repo.pendingSyncCount(f.tenant.id)
            assertFailsWith<SecurityException> {repo.checkout(f.tenant.id,listOf(CartLine(f.item,1)),0,f.customer.id)}
            assertFailsWith<SecurityException> {repo.checkout(f.tenant.id,listOf(CartLine(f.item,1,discountPerUnitMinor=1_000,discountReason="Agreed price")),9_000)}
            assertFailsWith<SecurityException> {repo.returnItems(f.tenant.id,sale.id,mapOf(f.item.id to 1),true,"Unopened")}
            assertEquals(stock,repo.products(f.tenant.id).single().stock)
            assertEquals(pending,repo.pendingSyncCount(f.tenant.id))
        }
    }

    @Test fun revokedSessionCannotUseSuccessfulMutationReplayToBypassLiveValidation() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            var active=true
            repo.setPermissionCheck { tenant,_ -> if(!active || tenant!=f.tenant.id) throw SecurityException("Session revoked") }
            val line=CartLine(f.item,1)
            repo.checkout(f.tenant.id,listOf(line),10_000,operationId="saved-sale")
            repo.updateCustomer(f.tenant.id,f.customer.id,"Amina",null,null,null,"saved-edit")
            val pending=repo.pendingSyncCount(f.tenant.id)
            active=false
            assertFailsWith<SecurityException> {repo.checkout(f.tenant.id,listOf(line),10_000,operationId="saved-sale")}
            assertFailsWith<SecurityException> {repo.updateCustomer(f.tenant.id,f.customer.id,"Amina",null,null,null,"saved-edit")}
            assertFailsWith<SecurityException> {repo.pendingSyncCount(f.tenant.id)}
            active=true // Explicitly restore the test session before inspecting saved state.
            assertEquals(pending,repo.pendingSyncCount(f.tenant.id))
        }
    }

    @Test fun conditionalPermissionsAreRecheckedOnSavedCreditAndDiscountSales() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val discounted=CartLine(f.item,1,discountPerUnitMinor=1_000,discountReason="Agreed price")
            repo.checkout(f.tenant.id,listOf(discounted),9_000,operationId="discount")
            repo.checkout(f.tenant.id,listOf(CartLine(f.item,1)),0,f.customer.id,"credit")
            val seen=mutableListOf<StaffPermission>()
            repo.setPermissionCheck { _,permission ->
                seen+=permission
                if(permission!=StaffPermission.SELL) throw SecurityException("Cash sale only")
            }
            assertFailsWith<SecurityException> {repo.checkout(f.tenant.id,listOf(discounted),9_000,operationId="discount")}
            assertEquals(listOf(StaffPermission.SELL,StaffPermission.DISCOUNT),seen)
            seen.clear()
            assertFailsWith<SecurityException> {repo.checkout(f.tenant.id,listOf(CartLine(f.item,1)),0,f.customer.id,"credit")}
            assertEquals(listOf(StaffPermission.SELL,StaffPermission.SELL_CREDIT),seen)
        }
    }

    @Test fun sessionTenantCannotMutateAnotherShopAndOwnerPolicyAllowsNormalCommands() {
        PosRepository(":memory:").use { repo ->
            val f=fixture(repo)
            val other=repo.createTenant("Other")
            repo.setPermissionCheck { tenant,permission ->
                if(tenant!=f.tenant.id || !StaffRole.OWNER.allows(permission)) throw SecurityException("Wrong shop")
            }
            assertFailsWith<SecurityException> {repo.addProduct(other.id,"Soap","piece",100,1.0)}
            repo.setReorderLevel(f.tenant.id,f.item.id,10)
            assertEquals(10,repo.products(f.tenant.id).single().reorderLevel)
            assertEquals(f.tenant.id,repo.exportTenantSnapshot(f.tenant.id).tenant.id)
            assertFailsWith<IllegalArgumentException> {repo.seedDemoTenants()}
            assertEquals(2,repo.tenants().size)
        }
    }
}
