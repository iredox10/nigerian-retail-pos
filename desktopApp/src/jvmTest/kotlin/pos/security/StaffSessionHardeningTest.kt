package pos.security

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.nio.file.Path

/** Use a controlled wall clock: no test relies on sleeping through an expiry or cooldown. */
class StaffSessionHardeningTest {
    private class Clock(var now:Long = 1_000_000L)
    private fun fixture(timeout:Long=120_000L,block:(Path,Clock,StaffAccess,StaffSession)->Unit) {
        val directory=Files.createTempDirectory("staff-session-hardening-")
        val clock=Clock()
        try {
            WorkspaceAccess(directory).register("shop-a","owner-secret")
            WorkspaceAccess(directory).register("shop-b","other-owner-secret")
            StaffAccess(directory,clockMillis={clock.now},idleTimeoutMillis=timeout).use { access ->
                block(directory,clock,access,requireNotNull(access.unlockOwner("shop-a","owner-secret")))
            }
        } finally {directory.toFile().deleteRecursively()}
    }

    @Test fun exactIdleBoundaryExpiresStaffAndOwnerWithoutBeingExtendedByPassiveChecks() = fixture(1_000L) { _,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val staff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        clock.now+=999
        access.requireSession(staff,StaffPermission.SELL,touch=false)
        access.requireSession(owner,StaffPermission.MANAGE_STAFF,touch=false)
        clock.now++
        assertFails {access.requireSession(staff,StaffPermission.SELL,touch=false)}
        assertFails {access.requireSession(owner,StaffPermission.MANAGE_STAFF,touch=false)}
        val renewed=requireNotNull(access.unlockOwner("shop-a","owner-secret"))
        assertNotEquals(owner.token,renewed.token)
        access.requireSession(renewed,StaffPermission.MANAGE_STAFF,touch=false)
    }

    @Test fun genuineActivityRefreshesIdleTimerButPollingDoesNot() = fixture(1_000L) { _,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val staff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        clock.now+=500
        access.requireSession(staff,StaffPermission.SELL,touch=false)
        clock.now+=400
        access.touchSession(staff)
        clock.now+=999
        access.requireSession(staff,StaffPermission.SELL,touch=false)
        clock.now++
        assertFails {access.touchSession(staff)}
        assertFails {access.requireSession(staff,StaffPermission.SELL,touch=false)}
    }

    @Test fun defaultAuthorizedActionsRefreshAfterValidation() = fixture(1_000L) { _,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val staff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        clock.now+=900
        access.requireSession(staff,StaffPermission.SELL)
        clock.now+=900
        access.requireSession(staff,StaffPermission.VIEW_SALES)
        clock.now+=999
        access.requireSession(staff,StaffPermission.SELL,touch=false)
        clock.now++
        assertFails {access.requireSession(staff,StaffPermission.SELL,touch=false)}
    }

    @Test fun forgedExpiredAndRevokedTokensCannotBeRefreshedOrRevived() = fixture(1_000L) { _,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val staff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        assertFails {access.touchSession(staff.copy(role=StaffRole.OWNER,staffId=null))}
        assertFails {access.touchSession(staff.copy(token="forged-token"))}
        access.requireSession(staff,StaffPermission.SELL,touch=false)
        clock.now+=1_000
        assertFails {access.touchSession(staff)}
        val freshOwner=requireNotNull(access.unlockOwner("shop-a","owner-secret"))
        val freshStaff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        access.updateMember(freshOwner,member.id,"Cashier",StaffRole.CASHIER,false)
        assertFails {access.touchSession(freshStaff)}
        access.updateMember(freshOwner,member.id,"Cashier",StaffRole.CASHIER,true)
        assertFails {access.touchSession(freshStaff)}
        assertFails {access.touchSession(staff)}
        val replacement=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        access.touchSession(replacement)
    }

    @Test fun ownerCooldownBlocksCorrectPasswordUntilBoundaryAndStaysScopedToOwnerAndTenant() = fixture { _,clock,access,owner ->
        val staff=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        repeat(5) {assertNull(access.unlockOwner("shop-a","wrong-secret"))}
        assertNull(access.unlockOwner("shop-a","owner-secret"))
        assertNotNull(access.unlockStaff("shop-a",staff.id,"staff-secret"))
        assertNotNull(access.unlockOwner("shop-b","other-owner-secret"))
        clock.now+=59_999
        assertNull(access.unlockOwner("shop-a","owner-secret"))
        clock.now++
        assertNotNull(access.unlockOwner("shop-a","owner-secret"))
        // Success resets the failure count; one following mistake must not impose another lock.
        assertNull(access.unlockOwner("shop-a","wrong-secret"))
        assertNotNull(access.unlockOwner("shop-a","owner-secret"))
    }

    @Test fun staffFailureCounterAndCooldownSurviveOtherInstancesAndDatabaseReopen() = fixture { directory,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        repeat(3) {assertNull(access.unlockStaff("shop-a",member.id,"wrong-secret"))}
        StaffAccess(directory,clockMillis={clock.now}).use { second ->
            repeat(2) {assertNull(second.unlockStaff("shop-a",member.id,"wrong-secret"))}
            assertNull(second.unlockStaff("shop-a",member.id,"staff-secret"))
            assertNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        }
        StaffAccess(directory,clockMillis={clock.now}).use { reopened ->
            assertNull(reopened.unlockStaff("shop-a",member.id,"staff-secret"))
            clock.now+=60_000
            assertNotNull(reopened.unlockStaff("shop-a",member.id,"staff-secret"))
            assertNull(reopened.unlockStaff("shop-a",member.id,"wrong-secret"))
            assertNotNull(reopened.unlockStaff("shop-a",member.id,"staff-secret"))
        }
        assertNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
    }

    @Test fun failureSubjectsAreSeparatedForEachStaffMemberAndTenant() = fixture { _,_,access,owner ->
        val first=access.addMember(owner,"First cashier",StaffRole.CASHIER,"first-secret","same-staff-id")
        val second=access.addMember(owner,"Second cashier",StaffRole.CASHIER,"second-secret")
        val otherOwner=requireNotNull(access.unlockOwner("shop-b","other-owner-secret"))
        val other=access.addMember(otherOwner,"Other cashier",StaffRole.CASHIER,"third-secret","same-staff-id")
        repeat(5) {assertNull(access.unlockStaff("shop-a",first.id,"wrong-secret"))}
        assertNull(access.unlockStaff("shop-a",first.id,"first-secret"))
        assertNotNull(access.unlockStaff("shop-a",second.id,"second-secret"))
        assertNotNull(access.unlockStaff("shop-b",other.id,"third-secret"))
        assertNotNull(access.unlockOwner("shop-a","owner-secret"))
    }

    @Test fun ownerCooldownPersistsAcrossReopenAndCannotBeBypassedByClockRollback() = fixture { directory,clock,access,_ ->
        repeat(5) {assertNull(access.unlockOwner("shop-a","wrong-secret"))}
        clock.now-=1_000
        StaffAccess(directory,clockMillis={clock.now}).use { reopened ->
            assertNull(reopened.unlockOwner("shop-a","owner-secret"))
            clock.now+=60_999
            assertNull(reopened.unlockOwner("shop-a","owner-secret"))
            clock.now++
            assertNotNull(reopened.unlockOwner("shop-a","owner-secret"))
        }
    }

    @Test fun sessionClockRollbackFailsClosedAndRequiresFreshAuthentication() = fixture(1_000L) { _,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val staff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        val issuedAt=clock.now
        clock.now--
        assertFails {access.requireSession(staff,StaffPermission.SELL,touch=false)}
        assertFails {access.touchSession(owner)}
        clock.now=issuedAt
        assertFails {access.touchSession(staff)}
        assertFails {access.requireSession(owner,StaffPermission.MANAGE_STAFF,touch=false)}
        assertNotNull(access.unlockOwner("shop-a","owner-secret"))
    }

    @Test fun failedPermissionChecksCannotExtendCashierIdleSession() = fixture(1_000L) { _,clock,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val staff=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        clock.now+=900
        assertFails {access.requireSession(staff,StaffPermission.DISCOUNT)}
        clock.now+=100
        assertFails {access.requireSession(staff,StaffPermission.SELL,touch=false)}
    }
}
