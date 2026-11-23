package pos.security

import java.nio.file.Files
import kotlin.test.*
import pos.domain.*

class CashHandoverSecurityTest {
    private fun request()=CashHandoverRequest("shop","handover","shift",200_000,190_000,CashCount(mapOf(100_000L to 1L,50_000L to 1L,20_000L to 2L)).encode(),"Evening shift")
    private fun fixture(block:(StaffAccess,StaffSession,()->Long,(Long)->Unit)->Unit) {
        val directory=Files.createTempDirectory("handover-security-");var now=1_000_000L
        try {
            WorkspaceAccess(directory).register("shop","owner password")
            StaffAccess(directory,clockMillis={now}).use {access ->block(access,requireNotNull(access.unlockOwner("shop","owner password")),{now},{now=it})}
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun activeRecipientAcceptsExactCountAndCannotBeSender()=fixture {access,owner,_,_ ->
        val person=access.addMember(owner,"Amina",StaffRole.CASHIER,"cashier password")
        assertFails {access.authorizeCashHandover(owner,request(),null,"owner password")}
        assertNull(access.authorizeCashHandover(owner,request(),person.id,"incorrect password"))
        val proof=requireNotNull(access.authorizeCashHandover(owner,request(),person.id,"cashier password"))
        assertEquals(person.id,proof.receiverId)
        assertEquals(proof,CashHandoverProof.decode(CashHandoverProof.encode(proof)))
        assertEquals(proof,access.requireCashHandover(owner,proof.id,request()))
        assertEquals(proof,access.requireCashHandover(owner,proof.id,request()))
        assertFails {access.requireCashHandover(owner,proof.id,request().copy(reason="Different reason"))}
        assertFails {access.requireCashHandover(owner,proof.id,request().copy(operationId="other"))}
        assertFails {CashHandoverProof.verify(proof.copy(request=request().copy(expectedMinor=210_000)))}
        val cashier=requireNotNull(access.unlockStaff("shop",person.id,"cashier password"))
        assertFails {access.authorizeCashHandover(cashier,request(),null,"owner password")}
        assertEquals(1,access.auditEvents(owner).count {it.kind=="cash.handover_accepted"})
        assertEquals(1,access.auditEvents(owner).count {it.kind=="cash.handover_consent_used"})
    }
    @Test fun changedReceiverCredentialsOrRoleAndExpiryInvalidateConsent()=fixture {access,owner,clock,setClock ->
        val receiver=access.addMember(owner,"Amina",StaffRole.CASHIER,"cashier password")
        val proof=requireNotNull(access.authorizeCashHandover(owner,request(),receiver.id,"cashier password"))
        access.resetPassword(owner,receiver.id,"replacement password")
        assertFails {access.requireCashHandover(owner,proof.id,request())}
        val second=requireNotNull(access.authorizeCashHandover(owner,request(),receiver.id,"replacement password"))
        access.updateMember(owner,receiver.id,"Amina",StaffRole.MANAGER,true)
        assertFails {access.requireCashHandover(owner,second.id,request())}
        val third=requireNotNull(access.authorizeCashHandover(owner,request(),receiver.id,"replacement password"))
        setClock(clock()+300_000)
        assertFails {access.requireCashHandover(owner,third.id,request())}
    }
    @Test fun recipientPasswordThrottlePersistsAcrossRestart() {
        val directory=Files.createTempDirectory("handover-restart-");var now=1_000_000L
        try {
            WorkspaceAccess(directory).register("shop","owner password")
            lateinit var managerId:String
            StaffAccess(directory,clockMillis={now}).use {access ->
                val owner=requireNotNull(access.unlockOwner("shop","owner password"))
                managerId=access.addMember(owner,"Manager",StaffRole.MANAGER,"manager password").id
                val manager=requireNotNull(access.unlockStaff("shop",managerId,"manager password"))
                repeat(5) {assertNull(access.authorizeCashHandover(manager,request(),null,"incorrect password"))}
            }
            StaffAccess(directory,clockMillis={now}).use {access ->
                val manager=requireNotNull(access.unlockStaff("shop",managerId,"manager password"))
                assertNull(access.authorizeCashHandover(manager,request(),null,"owner password"))
                now+=60_000
                assertNotNull(access.authorizeCashHandover(manager,request(),null,"owner password"))
            }
        } finally {directory.toFile().deleteRecursively()}
    }
}
