package pos.security

import kotlin.test.*
import pos.data.PosRepository
import pos.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Controlled clock covers restart and absolute PIN authority without sleeps or provider access. */
class StaffPinAccessTest {
    private class Clock(var now:Long=1_000_000L)
    private fun fixture(block:(Path,Clock,StaffAccess,StaffSession,StaffMember)->Unit) {
        val directory=Files.createTempDirectory("staff-pin-access-")
        val clock=Clock()
        try {
            WorkspaceAccess(directory).register("shop-a","owner-secret")
            WorkspaceAccess(directory).register("shop-b","other-owner-secret")
            StaffAccess(directory,clockMillis={clock.now},idleTimeoutMillis=86_400_000L).use {access ->
                val owner=requireNotNull(access.unlockOwner("shop-a","owner-secret"))
                val staff=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret","cashier-id")
                block(directory,clock,access,owner,staff)
            }
        } finally {directory.toFile().deleteRecursively()}
    }

    @Test fun setupRequiresFullPasswordAndPinCreatesOnlyTheStaffMembersRole() = fixture {_,_,access,_,staff ->
        assertNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
        assertFalse(access.configureStaffPin("shop-a",staff.id,"wrong-password","738492"))
        assertNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        val signed=requireNotNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
        assertEquals(staff.id,signed.staffId)
        assertEquals(StaffRole.CASHIER,signed.role)
        access.requireSession(signed,StaffPermission.SELL)
        assertFails {access.requireSession(signed,StaffPermission.MANAGE_STAFF)}
        assertFails {access.requireSession(signed.copy(role=StaffRole.OWNER,staffId=null),StaffPermission.MANAGE_STAFF)}
        assertNull(access.unlockStaffPin("shop-b",staff.id,"738492"))
    }

    @Test fun setupAcceptsExactlySixAsciiDigitsAndInvalidLoginCountsTowardCooldown() = fixture {_,clock,access,_,staff ->
        listOf("","12345","1234567","１２３４５６","12a456"," 123456","+12345","12345\n").forEach {pin ->
            assertFails {access.configureStaffPin("shop-a",staff.id,"staff-secret",pin)}
        }
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","000012"))
        repeat(5) {assertNull(access.unlockStaffPin("shop-a",staff.id,"bad"))}
        assertNull(access.unlockStaffPin("shop-a",staff.id,"000012"))
        assertNull(access.unlockStaff("shop-a",staff.id,"staff-secret"))
        clock.now+=60_000
        assertNotNull(access.unlockStaffPin("shop-a",staff.id,"000012"))
    }

    @Test fun pinAndPasswordSharePersistentFailuresAndCorrectPasswordCannotBypassCooldown() = fixture {directory,clock,access,_,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        repeat(2) {assertNull(access.unlockStaff("shop-a",staff.id,"wrong-password"))}
        StaffAccess(directory,clockMillis={clock.now}).use {second ->
            repeat(3) {assertNull(second.unlockStaffPin("shop-a",staff.id,"999999"))}
            assertNull(access.unlockStaff("shop-a",staff.id,"staff-secret"))
        }
        StaffAccess(directory,clockMillis={clock.now}).use {reopened ->
            assertNull(reopened.unlockStaffPin("shop-a",staff.id,"738492"))
            clock.now+=59_999
            assertNull(reopened.unlockStaff("shop-a",staff.id,"staff-secret"))
            clock.now++
            assertNotNull(reopened.unlockStaff("shop-a",staff.id,"staff-secret"))
            assertNotNull(reopened.unlockStaffPin("shop-a",staff.id,"738492"))
        }
    }

    @Test fun failuresStayScopedToStaffIdentityAndShop() = fixture {_,_,access,owner,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        val otherOwner=requireNotNull(access.unlockOwner("shop-b","other-owner-secret"))
        val other=access.addMember(otherOwner,"Other cashier",StaffRole.CASHIER,"other-secret",staff.id)
        assertTrue(access.configureStaffPin("shop-b",other.id,"other-secret","738492"))
        val peer=access.addMember(owner,"Another cashier",StaffRole.CASHIER,"peer-secret")
        repeat(5) {assertNull(access.unlockStaffPin("shop-a",staff.id,"999999"))}
        assertNotNull(access.unlockStaffPin("shop-b",other.id,"738492"))
        assertNotNull(access.unlockStaff("shop-a",peer.id,"peer-secret"))
        assertNotNull(access.unlockOwner("shop-a","owner-secret"))
    }

    @Test fun repeatedPinLoginAndActivityNeverExtendTwelveHourAuthorityEvenAcrossRestart() = fixture {directory,clock,access,_,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        val authorizedAt=clock.now
        clock.now+=43_199_000
        val signed=requireNotNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
        access.touchSession(signed)
        StaffAccess(directory,clockMillis={clock.now}).use {reopened ->
            assertNotNull(reopened.unlockStaffPin("shop-a",staff.id,"738492"))
            clock.now=authorizedAt+43_200_000
            assertFails {access.requireSession(signed,StaffPermission.SELL,touch=false)}
            assertNull(reopened.unlockStaffPin("shop-a",staff.id,"738492"))
            // A full password is the only action which renews this authorization.
            assertNotNull(reopened.unlockStaff("shop-a",staff.id,"staff-secret"))
            assertNotNull(reopened.unlockStaffPin("shop-a",staff.id,"738492"))
        }
    }

    @Test fun persistedPinClockRollbackFailsClosedWithoutRemovingPasswordFallback() = fixture {directory,clock,access,_,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        clock.now+=1_000
        assertNotNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
        clock.now--
        StaffAccess(directory,clockMillis={clock.now}).use {second ->
            assertNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
            assertNotNull(second.unlockStaff("shop-a",staff.id,"staff-secret"))
            assertNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
            clock.now++
            assertNotNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
        }
    }

    @Test fun anotherInstancesLaterPinUseMakesAnOlderClockFailClosedForActiveSessions() = fixture {directory,clock,access,_,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        val original=requireNotNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
        clock.now+=1_000
        StaffAccess(directory,clockMillis={clock.now}).use {second ->
            assertNotNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
        }
        clock.now--
        assertFails {access.touchSession(original)}
        clock.now++
        assertFails {access.requireSession(original,StaffPermission.SELL)}
        assertNotNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
    }

    @Test fun resettingPasswordClearsPinAndRevokesPinSessionsAcrossInstances() = fixture {directory,clock,access,owner,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        StaffAccess(directory,clockMillis={clock.now}).use {second ->
            val signed=requireNotNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
            access.resetPassword(owner,staff.id,"replacement-secret")
            assertFails {second.requireSession(signed,StaffPermission.SELL)}
            assertNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
            assertNotNull(second.unlockStaff("shop-a",staff.id,"replacement-secret"))
            assertNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
        }
    }

    @Test fun roleRenameDisableAndReenableNeverRetainAnOldPin() = fixture {_,_,access,owner,staff ->
        var current=staff
        listOf<(StaffMember)->StaffMember>(
            {it.copy(name="Renamed")}, {it.copy(role=StaffRole.MANAGER)}, {it.copy(active=false)}, {it.copy(active=true)},
        ).forEach {change ->
            if(current.active) assertTrue(access.configureStaffPin("shop-a",current.id,"staff-secret","738492"))
            val next=change(current)
            access.updateMember(owner,next.id,next.name,next.role,next.active)
            assertNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
            current=next
        }
        assertNotNull(access.unlockStaff("shop-a",staff.id,"staff-secret"))
        assertNull(access.unlockStaffPin("shop-a",staff.id,"738492"))
    }

    @Test fun removingOrChangingPinRevokesEarlierPinSessionsAcrossInstances() = fixture {directory,clock,access,_,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        StaffAccess(directory,clockMillis={clock.now}).use {second ->
            val first=requireNotNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
            assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","638491"))
            assertFails {second.requireSession(first,StaffPermission.SELL)}
            assertNull(second.unlockStaffPin("shop-a",staff.id,"738492"))
            val replacement=requireNotNull(second.unlockStaffPin("shop-a",staff.id,"638491"))
            assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret",null))
            assertFails {second.requireSession(replacement,StaffPermission.SELL)}
            assertNull(second.unlockStaffPin("shop-a",staff.id,"638491"))
            assertNotNull(second.unlockStaff("shop-a",staff.id,"staff-secret"))
        }
    }

    @Test fun secretsNeverEnterAuditOrBusinessBackupsAndOnlyDerivedPinIsStored() = fixture {directory,_,access,owner,staff ->
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret",null))
        val audit=access.auditEvents(owner)
        assertEquals(listOf("staff.pin_configured","staff.pin_removed"),audit.filter {it.kind.startsWith("staff.pin_")}.map {it.kind}.sorted())
        audit.forEach {event ->
            assertFalse(event.toString().contains("738492"))
            assertFalse(event.toString().contains("staff-secret"))
            assertFalse(event.toString().contains("pin_hash"))
        }
        assertTrue(access.configureStaffPin("shop-a",staff.id,"staff-secret","738492"))
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("staff-access.db")}").use {db ->
            db.createStatement().use {statement -> statement.executeQuery("SELECT salt,pin_hash FROM staff_pins").use {rows ->
                assertTrue(rows.next())
                assertNotEquals("738492",rows.getString("pin_hash"))
                assertEquals(24,java.util.Base64.getDecoder().decode(rows.getString("salt")).size)
                assertEquals(32,java.util.Base64.getDecoder().decode(rows.getString("pin_hash")).size)
            }}
        }
        PosRepository(":memory:").use {repository ->
            val shop=repository.createTenant("Business")
            val backup=repository.exportTenantSnapshot(shop.id)
            assertFalse(backup.tables.keys.any {it.contains("pin") || it.contains("staff") || it.contains("login")})
            val recovered=repository.restoreTenantSnapshot(backup,"Recovered")
            assertTrue(access.signInMembers(recovered.id).isEmpty())
            assertNull(access.unlockStaffPin(recovered.id,staff.id,"738492"))
        }
    }
}
