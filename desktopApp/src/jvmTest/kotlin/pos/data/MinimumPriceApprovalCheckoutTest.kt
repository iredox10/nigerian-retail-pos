package pos.data

import java.nio.file.Files
import kotlin.test.*
import pos.domain.*
import pos.security.*

/** Exercises the same genuine StaffAccess gateway installed by the desktop shell. */
class MinimumPriceApprovalCheckoutTest {
    private fun fixture(block:(PosRepository,StaffAccess,Tenant,Product,StaffSession,StaffSession,StaffMember)->Unit) {
        val directory=Files.createTempDirectory("price-approval-checkout-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            val item=repo.addProduct(shop.id,"Rice","bag",1000,20.0)
            repo.openCashShift(shop.id,0)
            repo.saveMinimumPrice(shop.id,item.id,null,900,0,"Normal selling floor")
            WorkspaceAccess(directory).register(shop.id,"owner password")
            StaffAccess(directory).use {access ->
                val owner=requireNotNull(access.unlockOwner(shop.id,"owner password"))
                val manager=access.addMember(owner,"Manager",StaffRole.MANAGER,"manager password")
                val cashier=access.addMember(owner,"Cashier",StaffRole.CASHIER,"cashier password")
                val session=requireNotNull(access.unlockStaff(shop.id,cashier.id,"cashier password"))
                // Snapshot inspection is trusted test tooling; checkout permissions remain genuine cashier permissions.
                repo.setPermissionCheck {tenant,permission ->if(permission!=StaffPermission.BACKUP) {require(tenant==shop.id);access.requireSession(session,permission,touch=false)}}
                repo.setActorProvider {tenant ->access.requireSession(session,StaffPermission.SELL,touch=false);AuditActor(tenant,session.staffId,session.name,session.role)}
                repo.setMinimumPriceApprovalCheck {tenant,grant,request ->require(tenant==session.tenantId);access.requireMinimumPriceApproval(session,grant,request)}
                block(repo,access,shop,item,owner,session,manager)
            }
        }} finally {directory.toFile().deleteRecursively()}
    }
    private fun line(item:Product)=CartLine(item,1,discountPerUnitMinor=200,discountReason="Clear older stock")
    private fun snapshot(repo:PosRepository,tenant:String):TenantSnapshot = repo.exportTenantSnapshot(tenant)

    @Test fun genuineManagerApprovalLetsCashierCompleteOnlyTheApprovedBelowFloorSaleAndPreservesBackupEvidence()=fixture {repo,access,shop,item,owner,cashier,manager ->
        val lines=listOf(line(item));val request=repo.minimumPriceApprovalRequest(shop.id,"approved-sale",lines)
        assertFails {repo.checkout(shop.id,lines,800,operationId=request.operationId)}
        val proof=requireNotNull(access.authorizeMinimumPrice(cashier,request,manager.id,"manager password","Clear older stock"))
        val sale=repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id)
        assertEquals(800L,sale.totalMinor)
        assertEquals(proof,repo.minimumPriceApprovalForSale(shop.id,sale.id))
        // Completed-operation retries return the same immutable sale, with no duplicate grant use.
        assertEquals(repo.sales(shop.id).single(),repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id))
        assertEquals(1,access.auditEvents(owner).count {it.kind=="approval.sale_prices_used"})
        val backup=snapshot(repo,shop.id)
        assertEquals("900",backup.tables.getValue("sale_minimum_price_checks").single()["required_minor"])
        assertEquals(19L,repo.products(shop.id).single().exactStockScaled)
        repo.setPermissionCheck(null);repo.setActorProvider(null)
        val restored=repo.restoreTenantSnapshot(backup,"Recovered")
        assertEquals(proof,repo.minimumPriceApprovalForSale(restored.id,sale.id))
        val restoredAgain=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(restored.id),"Recovered twice")
        assertEquals(proof,repo.minimumPriceApprovalForSale(restoredAgain.id,sale.id))
        val noProof=backup.copy(tables=backup.tables+("sale_minimum_price_approvals" to emptyList())+
            ("outbox" to backup.tables.getValue("outbox").filter {it["kind"]!="minimum_price.override_used"})+
            ("activity_audit" to backup.tables.getValue("activity_audit").filter {it["kind"]!="minimum_price.override_used"}))
        val forged=backup.copy(tables=backup.tables+("sale_minimum_price_approvals" to backup.tables.getValue("sale_minimum_price_approvals").map {it+("grant_id" to "invented")}))
        val tenants=repo.tenants()
        listOf(noProof,forged).forEach {assertFails {repo.restoreTenantSnapshot(it,"Invalid approval")};assertEquals(tenants,repo.tenants())}
    }

    @Test fun lateFinancialFailureRollsBackAndSameOperationCanRetryItsAuthenticatedGrant()=fixture {repo,access,shop,item,owner,cashier,manager ->
        val lines=listOf(line(item));val request=repo.minimumPriceApprovalRequest(shop.id,"retry-sale",lines)
        val proof=requireNotNull(access.authorizeMinimumPrice(cashier,request,manager.id,"manager password","Clear older stock"))
        repo.featureSchema("CREATE TRIGGER reject_price_capture BEFORE INSERT ON sale_minimum_price_approvals BEGIN SELECT RAISE(ABORT,'Injected late financial failure'); END")
        val before=snapshot(repo,shop.id)
        assertFails {repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id)}
        assertEquals(before,repo.exportTenantSnapshot(shop.id))
        assertEquals(1,access.auditEvents(owner).count {it.kind=="approval.sale_prices_used"})
        repo.featureSchema("DROP TRIGGER reject_price_capture")
        assertEquals(800L,repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id).totalMinor)
        assertEquals(1,access.auditEvents(owner).count {it.kind=="approval.sale_prices_used"})
        assertFails {repo.checkout(shop.id,lines,800,operationId="different-sale",priceApprovalId=proof.id)}
        assertEquals(1,repo.sales(shop.id).size)
    }

    @Test fun changedCartOrPolicyAndUnavailableGatewayFailWithoutMutatingFinancialRecords()=fixture {repo,access,shop,item,_,cashier,manager ->
        val lines=listOf(line(item));val request=repo.minimumPriceApprovalRequest(shop.id,"changed-sale",lines)
        val proof=requireNotNull(access.authorizeMinimumPrice(cashier,request,manager.id,"manager password","Clear older stock"))
        val before=snapshot(repo,shop.id)
        assertFails {repo.checkout(shop.id,listOf(line(item).copy(discountReason="Another reason")),800,operationId=request.operationId,priceApprovalId=proof.id)}
        assertFails {repo.checkout(shop.id,listOf(line(item).copy(quantity=2)),1600,operationId=request.operationId,priceApprovalId=proof.id)}
        assertEquals(before,repo.exportTenantSnapshot(shop.id))
        repo.setMinimumPriceApprovalCheck(null)
        assertFails {repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id)}
        assertEquals(before,repo.exportTenantSnapshot(shop.id))
        repo.setMinimumPriceApprovalCheck {_,id,current ->access.requireMinimumPriceApproval(cashier,id,current)}
        repo.setPermissionCheck(null)
        repo.saveMinimumPrice(shop.id,item.id,null,950,1,"New floor while cashier waits")
        val changed=repo.exportTenantSnapshot(shop.id)
        assertFails {repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id)}
        assertEquals(changed,repo.exportTenantSnapshot(shop.id))
    }

    @Test fun currentManagerCredentialsAreRecheckedAtTheActualCheckout()=fixture {repo,access,shop,item,owner,cashier,manager ->
        val lines=listOf(line(item));val request=repo.minimumPriceApprovalRequest(shop.id,"revoked-sale",lines)
        val proof=requireNotNull(access.authorizeMinimumPrice(cashier,request,manager.id,"manager password","Clear older stock"))
        access.resetPassword(owner,manager.id,"replacement manager password")
        val before=snapshot(repo,shop.id)
        assertFails {repo.checkout(shop.id,lines,800,operationId=request.operationId,priceApprovalId=proof.id)}
        assertEquals(before,repo.exportTenantSnapshot(shop.id))
    }
}
