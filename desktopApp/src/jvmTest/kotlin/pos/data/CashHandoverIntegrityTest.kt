package pos.data

import java.nio.file.Files
import kotlin.test.*
import pos.domain.*
import pos.security.*

class CashHandoverIntegrityTest {
    @Test fun authenticatedHandoverClosesAndOpensAtomicallyAndRestoresSignedHistoricalCounts() {
        val directory=Files.createTempDirectory("cash-handover-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop")
            WorkspaceAccess(directory).register(shop.id,"owner password")
            StaffAccess(directory).use {access ->
                val owner=requireNotNull(access.unlockOwner(shop.id,"owner password"))
                val receiver=access.addMember(owner,"Amina",StaffRole.CASHIER,"cashier password")
                repo.setCashHandoverAuthorizationCheck {tenant,id,request ->assertEquals(shop.id,tenant);access.requireCashHandover(owner,id,request)}
                val shift=repo.openCashShift(shop.id,200_000)
                val count=CashCount(mapOf(100_000L to 1L,50_000L to 1L,20_000L to 2L))
                val request=CashHandoverRequest(shop.id,"handover",shift.id,200_000,count.totalMinor,count.encode(),"Evening shift")
                val proof=requireNotNull(access.authorizeCashHandover(owner,request,receiver.id,"cashier password"))
                repo.featureSchema("CREATE TRIGGER reject_handover BEFORE INSERT ON cash_handovers BEGIN SELECT RAISE(ABORT,'Injected handover failure'); END")
                val before=repo.exportTenantSnapshot(shop.id)
                assertFails {repo.handoverCash(shop.id,request,proof.id)}
                assertEquals(before,repo.exportTenantSnapshot(shop.id))
                assertEquals(shift,repo.activeCashShift(shop.id))
                repo.featureSchema("DROP TRIGGER reject_handover")
                val saved=repo.handoverCash(shop.id,request,proof.id)
                assertEquals(saved,repo.handoverCash(shop.id,request,proof.id))
                assertEquals(190_000L,repo.activeCashShift(shop.id)?.openingMinor)
                val closed=repo.cashShifts(shop.id).single {it.id==shift.id}
                assertEquals(200_000L,closed.expectedMinor);assertEquals(190_000L,closed.countedMinor)
                assertEquals(count,closed.closingCount)
                assertFails {repo.featureExecute("DELETE FROM cash_handovers")}
                val snapshot=repo.exportTenantSnapshot(shop.id)
                val restored=repo.restoreTenantSnapshot(snapshot,"Recovered")
                assertEquals(saved.witness,repo.cashHandovers(restored.id).single().witness)
                val restoredAgain=repo.restoreTenantSnapshot(repo.exportTenantSnapshot(restored.id),"Recovered again")
                assertEquals(saved.witness,repo.cashHandovers(restoredAgain.id).single().witness)
                val bad=snapshot.copy(tables=snapshot.tables+("cash_handovers" to snapshot.tables.getValue("cash_handovers").map {it+("grant_id" to "fabricated")}))
                val tenants=repo.tenants();assertFails {repo.restoreTenantSnapshot(bad,"Invalid")};assertEquals(tenants,repo.tenants())
            }
        }} finally {directory.toFile().deleteRecursively()}
    }
    @Test fun handoverCannotUseMissingGatewayOrStaleDrawerBalance() {
        val directory=Files.createTempDirectory("handover-stale-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");WorkspaceAccess(directory).register(shop.id,"owner password")
            StaffAccess(directory).use {access ->
                val owner=requireNotNull(access.unlockOwner(shop.id,"owner password"))
                val receiver=access.addMember(owner,"Amina",StaffRole.CASHIER,"cashier password")
                val shift=repo.openCashShift(shop.id,1000)
                val request=CashHandoverRequest(shop.id,"handover",shift.id,1000,1000,null,"Shift change")
                val proof=requireNotNull(access.authorizeCashHandover(owner,request,receiver.id,"cashier password"))
                assertFails {repo.handoverCash(shop.id,request,proof.id)}
                repo.setCashHandoverAuthorizationCheck {_,id,scope ->access.requireCashHandover(owner,id,scope)}
                repo.recordCashMovement(shop.id,100,"Extra cash")
                assertFails {repo.handoverCash(shop.id,request,proof.id)}
                assertNull(repo.cashShifts(shop.id).single().closedAt)
                assertTrue(repo.cashHandovers(shop.id).isEmpty())
            }
        }} finally {directory.toFile().deleteRecursively()}
    }
}
