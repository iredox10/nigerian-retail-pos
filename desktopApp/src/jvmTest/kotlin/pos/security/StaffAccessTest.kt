package pos.security

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.nio.file.Path

/** Local staff sessions must be genuine, scoped, revocable, and absent from business backups. */
class StaffAccessTest {
    private fun fixture(block:(Path,StaffAccess,StaffSession)->Unit) {
        val directory=Files.createTempDirectory("staff-access-test-")
        try {
            WorkspaceAccess(directory).register("shop-a","owner-secret")
            WorkspaceAccess(directory).register("shop-b","other-owner-secret")
            StaffAccess(directory).use { access -> block(directory,access,requireNotNull(access.unlockOwner("shop-a","owner-secret"))) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun ownerPasswordAndStaffPasswordsAreTenantBoundAndWrongCredentialsFail() = fixture { _,access,owner ->
        assertNull(access.unlockOwner("shop-b","owner-secret"))
        assertNull(access.unlockOwner("missing-shop","owner-secret"))
        assertNull(access.unlockOwner("shop-a","wrong-secret"))
        val staff=access.addMember(owner,"Amina",StaffRole.CASHIER,"staff-secret")
        assertNull(access.unlockStaff("shop-a",staff.id,"wrong-secret"))
        assertNull(access.unlockStaff("shop-b",staff.id,"staff-secret"))
        assertNull(access.unlockStaff("shop-a","missing-id","staff-secret"))
        val session=requireNotNull(access.unlockStaff("shop-a",staff.id,"staff-secret"))
        assertEquals(staff.id,session.staffId)
        assertEquals("shop-a",session.tenantId)
        assertEquals("Amina",session.name)
        assertFalse(session.toString().contains(session.token))
    }

    @Test fun forgedOrAlteredSessionsCannotGainPermissionsAndLogoutRevokes() = fixture { _,access,owner ->
        val staff=access.addMember(owner,"Amina",StaffRole.CASHIER,"staff-secret")
        val session=requireNotNull(access.unlockStaff("shop-a",staff.id,"staff-secret"))
        access.requireSession(session,StaffPermission.SELL)
        access.requireSession(session,StaffPermission.VIEW_SALES)
        assertFails { access.requireSession(session,StaffPermission.DISCOUNT) }
        assertFails { access.requireSession(session,StaffPermission.SELL_CREDIT) }
        assertFails { access.requireSession(session.copy(role=StaffRole.OWNER,staffId=null),StaffPermission.MANAGE_STAFF) }
        assertFails { access.requireSession(owner.copy(tenantId="shop-b"),StaffPermission.MANAGE_STAFF) }
        assertFails { access.requireSession(owner.copy(token="invented"),StaffPermission.SELL) }
        access.logout(session)
        assertFails { access.requireSession(session,StaffPermission.SELL) }
        access.requireSession(owner,StaffPermission.MANAGE_STAFF)
    }

    @Test fun roleMatrixAndManagementNeverPermitStaffToCreateOwners() = fixture { _,access,owner ->
        StaffPermission.entries.forEach { permission -> assertTrue(StaffRole.OWNER.allows(permission)) }
        val manager=access.addMember(owner,"Manager",StaffRole.MANAGER,"manager-secret")
        val managerSession=requireNotNull(access.unlockStaff("shop-a",manager.id,"manager-secret"))
        StaffPermission.entries.forEach { permission ->
            if(permission in setOf(StaffPermission.BACKUP,StaffPermission.MANAGE_STAFF)) assertFails {access.requireSession(managerSession,permission)}
            else access.requireSession(managerSession,permission)
        }
        assertFails {access.members(managerSession)}
        assertFails {access.addMember(managerSession,"Cashier",StaffRole.CASHIER,"cashier-secret")}
        assertFails {access.updateMember(managerSession,manager.id,"Manager",StaffRole.CASHIER,true)}
        assertFails {access.resetPassword(managerSession,manager.id,"replacement-secret")}
        assertFails {access.addMember(owner,"Another owner",StaffRole.OWNER,"owner-password")}
        assertFails {access.updateMember(owner,manager.id,"Manager",StaffRole.OWNER,true)}
    }

    @Test fun roleNameDisableAndPasswordChangesRevokeSessionsImmediately() = fixture { _,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"first-secret")
        val first=requireNotNull(access.unlockStaff("shop-a",member.id,"first-secret"))
        access.updateMember(owner,member.id,"Manager",StaffRole.MANAGER,true)
        assertFails {access.requireSession(first,StaffPermission.SELL)}
        val second=requireNotNull(access.unlockStaff("shop-a",member.id,"first-secret"))
        access.requireSession(second,StaffPermission.DISCOUNT)
        access.resetPassword(owner,member.id,"second-secret")
        assertFails {access.requireSession(second,StaffPermission.SELL)}
        assertNull(access.unlockStaff("shop-a",member.id,"first-secret"))
        val third=requireNotNull(access.unlockStaff("shop-a",member.id,"second-secret"))
        access.updateMember(owner,member.id,"Manager",StaffRole.MANAGER,false)
        assertFails {access.requireSession(third,StaffPermission.SELL)}
        assertNull(access.unlockStaff("shop-a",member.id,"second-secret"))
        assertTrue(access.signInMembers("shop-a").isEmpty())
        assertFalse(access.members(owner).single().active)
        access.updateMember(owner,member.id,"Manager",StaffRole.MANAGER,true)
        assertFails {access.requireSession(third,StaffPermission.SELL)}
        assertNotNull(access.unlockStaff("shop-a",member.id,"second-secret"))
    }

    @Test fun sessionsAreInstanceLocalButRevocationIsObservedAcrossInstancesAndReopen() = fixture { directory,access,owner ->
        val member=access.addMember(owner,"Cashier",StaffRole.CASHIER,"staff-secret")
        val original=requireNotNull(access.unlockStaff("shop-a",member.id,"staff-secret"))
        StaffAccess(directory).use { second ->
            assertFails {second.requireSession(original,StaffPermission.SELL)}
            val secondOwner=requireNotNull(second.unlockOwner("shop-a","owner-secret"))
            second.resetPassword(secondOwner,member.id,"replacement-secret")
            assertFails {access.requireSession(original,StaffPermission.SELL)}
            val issued=requireNotNull(second.unlockStaff("shop-a",member.id,"replacement-secret"))
            access.updateMember(owner,member.id,"Cashier",StaffRole.CASHIER,false)
            assertFails {second.requireSession(issued,StaffPermission.SELL)}
        }
        access.updateMember(owner,member.id,"Cashier",StaffRole.CASHIER,true)
        StaffAccess(directory).use { reopened ->
            assertEquals("Cashier",reopened.signInMembers("shop-a").single().name)
            assertNotNull(reopened.unlockStaff("shop-a",member.id,"replacement-secret"))
            assertFails {reopened.requireSession(original,StaffPermission.SELL)}
        }
    }

    @Test fun originalCreateRetryAfterPasswordResetAndDisableDoesNotReactivateOrResetCredentials() = fixture { _,access,owner ->
        val original=access.addMember(owner,"Cashier",StaffRole.CASHIER,"first-secret","create-request")
        access.resetPassword(owner,original.id,"second-secret")
        access.updateMember(owner,original.id,"Cashier renamed",StaffRole.MANAGER,false)
        val replay=access.addMember(owner,"Cashier",StaffRole.CASHIER,"first-secret","create-request")
        assertEquals(original.id,replay.id)
        assertEquals("Cashier renamed",replay.name)
        assertEquals(StaffRole.MANAGER,replay.role)
        assertFalse(replay.active)
        assertEquals(1,access.members(owner).size)
        assertFails {access.addMember(owner,"Different",StaffRole.CASHIER,"first-secret","create-request")}
        assertFails {access.addMember(owner,"Cashier",StaffRole.MANAGER,"first-secret","create-request")}
        assertFails {access.addMember(owner,"Cashier",StaffRole.CASHIER,"second-secret","create-request")}
        assertNull(access.unlockStaff("shop-a",original.id,"second-secret"))
        access.updateMember(owner,original.id,"Cashier renamed",StaffRole.MANAGER,true)
        assertNull(access.unlockStaff("shop-a",original.id,"first-secret"))
        assertNotNull(access.unlockStaff("shop-a",original.id,"second-secret"))
    }

    @Test fun namesIdsAndCredentialsAreValidatedWithoutExposingPasswords() = fixture { directory,access,owner ->
        listOf(""," ","bad\nname","x".repeat(101)).forEach {name -> assertFails {access.addMember(owner,name,StaffRole.CASHIER,"staff-secret")}}
        assertFails {access.addMember(owner,"Amina",StaffRole.CASHIER,"short")}
        assertFails {access.addMember(owner,"Amina",StaffRole.CASHIER,"x".repeat(1025))}
        val member=access.addMember(owner,"Amina",StaffRole.CASHIER,"unusual-staff-password-92")
        assertFails {access.addMember(owner,"amina",StaffRole.CASHIER,"other-password")}
        access.updateMember(owner,member.id,"Amina",StaffRole.CASHIER,false)
        assertFails {access.addMember(owner,"Amina",StaffRole.CASHIER,"other-password")}
        val otherOwner=requireNotNull(access.unlockOwner("shop-b","other-owner-secret"))
        assertTrue(access.members(otherOwner).isEmpty())
        assertFails {access.updateMember(otherOwner,member.id,"Changed",StaffRole.CASHIER,true)}
        assertFails {access.resetPassword(otherOwner,member.id,"other-password")}
        access.addMember(otherOwner,"Amina",StaffRole.CASHIER,"other-password")
        val database=Files.readAllBytes(directory.resolve("staff-access.db")).toString(Charsets.ISO_8859_1)
        assertFalse(database.contains("unusual-staff-password-92"))
        assertFalse(database.contains("owner-secret"))
    }
}
