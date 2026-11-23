package pos.security

import java.nio.file.Files
import kotlin.test.*
import pos.domain.*

class MinimumPriceApprovalSecurityTest {
    private fun fixture(block: (StaffAccess, StaffSession, () -> Long, (Long) -> Unit) -> Unit) {
        val directory = Files.createTempDirectory("price-approval-test-")
        var now = 1_000_000L
        try {
            WorkspaceAccess(directory).register("shop", "owner password")
            StaffAccess(directory, clockMillis = { now }).use { access ->
                block(access, requireNotNull(access.unlockOwner("shop", "owner password")), { now }, { now = it })
            }
        } finally { directory.toFile().deleteRecursively() }
    }
    private fun request(operation: String = "sale") = MinimumPriceApprovalRequest("shop", operation, "a".repeat(64),
        listOf(MinimumPriceApprovalLine("item", "item", "Rice", "1", "bag", 100)),
        listOf(MinimumPriceApprovalCheck("item", "item", "", 1, 200)), 100, false)

    @Test fun separatePasswordAuthenticationCreatesBoundedSignedGrantAndIdempotentUse() = fixture { access, owner, _, _ ->
        val manager = access.addMember(owner, "Manager", StaffRole.MANAGER, "manager password")
        val cashier = access.addMember(owner, "Cashier", StaffRole.CASHIER, "cashier password")
        val session = requireNotNull(access.unlockStaff("shop", cashier.id, "cashier password"))
        assertFails { access.authorizeMinimumPrice(session, request(), cashier.id, "cashier password", "Clear old stock") }
        assertNull(access.authorizeMinimumPrice(session, request(), manager.id, "wrong password", "Clear old stock"))
        val proof = requireNotNull(access.authorizeMinimumPrice(session, request(), manager.id, "manager password", "Clear old stock"))
        assertEquals(manager.id, proof.approverId)
        assertEquals(proof, MinimumPriceApprovalProof.decode(MinimumPriceApprovalProof.encode(proof)))
        assertEquals(proof, access.requireMinimumPriceApproval(session, proof.id, request()))
        assertEquals(proof, access.requireMinimumPriceApproval(session, proof.id, request()))
        assertFails { access.requireMinimumPriceApproval(session, proof.id, request("another-sale")) }
        assertFails { access.requireMinimumPriceApproval(session, proof.id, request().copy(fingerprint = "b".repeat(64))) }
        assertFails { MinimumPriceApprovalProof.verify(proof.copy(reason = "Different explanation")) }
        val anotherSession = requireNotNull(access.unlockStaff("shop", cashier.id, "cashier password"))
        assertFails { access.requireMinimumPriceApproval(anotherSession, proof.id, request()) }
        val events = access.auditEvents(owner)
        assertEquals(1, events.count { it.kind == "approval.sale_prices_granted" })
        assertEquals(1, events.count { it.kind == "approval.sale_prices_used" })
    }

    @Test fun expiryAndCurrentAccountVersionsAreRequiredAtConsumption() = fixture { access, owner, clock, setClock ->
        val manager = access.addMember(owner, "Manager", StaffRole.MANAGER, "manager password")
        val cashier = access.addMember(owner, "Cashier", StaffRole.CASHIER, "cashier password")
        val session = requireNotNull(access.unlockStaff("shop", cashier.id, "cashier password"))
        val proof = requireNotNull(access.authorizeMinimumPrice(session, request(), manager.id, "manager password", "Clear old stock"))
        access.updateMember(owner, manager.id, "Manager", StaffRole.CASHIER, true)
        assertFails { access.requireMinimumPriceApproval(session, proof.id, request()) }
        val ownerProof = requireNotNull(access.authorizeMinimumPrice(session, request(), null, "owner password", "Owner reviewed price"))
        setClock(clock() + 300_000)
        assertFails { access.requireMinimumPriceApproval(session, ownerProof.id, request()) }
    }

    @Test fun approversPasswordResetAndOwnerRecoveryRevokeUnconsumedGrants() = fixture { access, owner, _, _ ->
        val manager = access.addMember(owner, "Manager", StaffRole.MANAGER, "manager password")
        val cashier = access.addMember(owner, "Cashier", StaffRole.CASHIER, "cashier password")
        val session = requireNotNull(access.unlockStaff("shop", cashier.id, "cashier password"))
        val proof = requireNotNull(access.authorizeMinimumPrice(session, request(), manager.id, "manager password", "Clear old stock"))
        access.resetPassword(owner, manager.id, "new manager password")
        assertFails { access.requireMinimumPriceApproval(session, proof.id, request()) }
        val ownerProof = requireNotNull(access.authorizeMinimumPrice(session, request(), null, "owner password", "Owner reviewed price"))
        val recovery = requireNotNull(access.configureOwnerRecovery(owner, "owner password"))
        assertTrue(access.recoverOwner("shop", recovery, "new owner password"))
        assertFails { access.requireMinimumPriceApproval(session, ownerProof.id, request()) }
    }

    @Test fun approverCannotBeCashierAndWrongPasswordUsesPersistentCooldown() = fixture { access, owner, clock, setClock ->
        val cashier = access.addMember(owner, "Cashier", StaffRole.CASHIER, "cashier password")
        assertFails { access.authorizeMinimumPrice(owner, request(), cashier.id, "cashier password", "Clear old stock") }
        val session = requireNotNull(access.unlockStaff("shop", cashier.id, "cashier password"))
        repeat(5) { assertNull(access.authorizeMinimumPrice(session, request(), null, "wrong password", "Clear old stock")) }
        assertNull(access.authorizeMinimumPrice(session, request(), null, "owner password", "Clear old stock"))
        setClock(clock() + 60_000)
        assertNotNull(access.authorizeMinimumPrice(session, request(), null, "owner password", "Clear old stock"))
    }
    @Test fun signingIdentityAndPasswordThrottleSurviveRestartButSessionGrantsDoNotTransfer() {
        val directory=Files.createTempDirectory("approval-restart-test-")
        var now=1_000_000L
        try {
            WorkspaceAccess(directory).register("shop","owner password")
            lateinit var proof:MinimumPriceApprovalWitness
            lateinit var cashierId:String
            StaffAccess(directory,clockMillis={now}).use {access ->
                val owner=requireNotNull(access.unlockOwner("shop","owner password"))
                cashierId=access.addMember(owner,"Cashier",StaffRole.CASHIER,"cashier password").id
                val cashier=requireNotNull(access.unlockStaff("shop",cashierId,"cashier password"))
                proof=requireNotNull(access.authorizeMinimumPrice(cashier,request(),null,"owner password","Owner checked prices"))
                repeat(5) {assertNull(access.authorizeMinimumPrice(cashier,request(),null,"incorrect password","Owner checked prices"))}
            }
            StaffAccess(directory,clockMillis={now}).use {access ->
                val cashier=requireNotNull(access.unlockStaff("shop",cashierId,"cashier password"))
                assertNull(access.authorizeMinimumPrice(cashier,request(),null,"owner password","Owner checked prices"))
                assertFails {access.requireMinimumPriceApproval(cashier,proof.id,request())}
                now+=60_000
                val next=requireNotNull(access.authorizeMinimumPrice(cashier,request(),null,"owner password","Owner checked prices"))
                assertEquals(proof.issuerPublicKey,next.issuerPublicKey)
                MinimumPriceApprovalProof.verify(proof)
            }
        } finally {directory.toFile().deleteRecursively()}
    }

}
