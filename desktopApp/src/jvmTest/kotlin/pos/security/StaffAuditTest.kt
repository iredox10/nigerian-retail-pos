package pos.security

import kotlin.test.*
import pos.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class StaffAuditTest {
    private fun fixture(block:(Path,StaffAccess,StaffSession)->Unit) {
        val dir=Files.createTempDirectory("staff-audit-test-")
        try {
            WorkspaceAccess(dir).register("shop","owner-password")
            WorkspaceAccess(dir).register("other","other-owner-password")
            StaffAccess(dir).use { access -> block(dir,access,requireNotNull(access.unlockOwner("shop","owner-password"))) }
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun createUpdateAndResetKeepOwnerSnapshotAndExcludeCredentialSecrets() = fixture { _,access,owner ->
        val staff=access.addMember(owner,"Amina",StaffRole.CASHIER,"private-staff-password","create-once")
        val original=access.auditEvents(owner).single()
        assertEquals("staff.created",original.kind)
        assertEquals(staff.id,original.entityId)
        assertEquals(AuditActor("shop",null,"Owner",StaffRole.OWNER),original.actor)
        assertContains(original.description.orEmpty(),"Amina")
        access.updateMember(owner,staff.id,"Amina renamed",StaffRole.MANAGER,false)
        access.resetPassword(owner,staff.id,"replacement-secret")
        val events=access.auditEvents(owner)
        assertEquals(3,events.size)
        assertEquals(setOf("staff.created","staff.updated","staff.password_reset"),events.map {it.kind}.toSet())
        val changed=events.single {it.kind=="staff.updated"}
        assertContains(changed.description.orEmpty(),"name=Amina;")
        assertContains(changed.description.orEmpty(),"name=Amina renamed;")
        assertContains(changed.description.orEmpty(),"active=true")
        assertContains(changed.description.orEmpty(),"active=false")
        assertEquals(original,events.single {it.id==original.id})
        events.forEach { event ->
            assertEquals("shop",event.tenantId)
            assertEquals(original.actor,event.actor)
            assertTrue(event.description!!.length<=1000)
            val all=event.toString()
            listOf("private-staff-password","replacement-secret","owner-password",owner.token,"password_hash","creation_fingerprint").forEach {secret ->assertFalse(all.contains(secret))}
            java.time.Instant.parse(event.createdAt)
        }
    }

    @Test fun retriesNoOpUpdatesAndDeniedRequestsNeverAddAuditRows() = fixture { _,access,owner ->
        val staff=access.addMember(owner,"Amina",StaffRole.CASHIER,"staff-password","operation")
        val before=access.auditEvents(owner)
        access.addMember(owner,"Amina",StaffRole.CASHIER,"staff-password","operation")
        access.updateMember(owner,staff.id,"Amina",StaffRole.CASHIER,true)
        assertEquals(before,access.auditEvents(owner))
        val session=requireNotNull(access.unlockStaff("shop",staff.id,"staff-password"))
        assertFails {access.auditEvents(session)}
        assertFails {access.addMember(session,"Other",StaffRole.CASHIER,"other-password")}
        assertFails {access.updateMember(session,staff.id,"Changed",StaffRole.MANAGER,true)}
        assertFails {access.resetPassword(session,staff.id,"replacement-password")}
        assertFails {access.addMember(owner,"Amina",StaffRole.CASHIER,"different-password","operation")}
        assertFails {access.resetPassword(owner,staff.id,"bad")}
        assertEquals(before,access.auditEvents(owner))
    }

    @Test fun logsSurviveReopenRemainTenantScopedAndCannotBeReadByManager() = fixture { dir,access,owner ->
        val staff=access.addMember(owner,"Manager",StaffRole.MANAGER,"manager-password")
        val manager=requireNotNull(access.unlockStaff("shop",staff.id,"manager-password"))
        assertFails {access.auditEvents(manager)}
        val saved=access.auditEvents(owner)
        val other=requireNotNull(access.unlockOwner("other","other-owner-password"))
        assertTrue(access.auditEvents(other).isEmpty())
        assertFails {access.auditEvents(owner.copy(tenantId="other"))}
        StaffAccess(dir).use { reopened ->
            val fresh=requireNotNull(reopened.unlockOwner("shop","owner-password"))
            assertEquals(saved,reopened.auditEvents(fresh))
            assertFails {reopened.auditEvents(owner)}
        }
    }

    @Test fun auditTableCannotBeUpdatedOrDeletedAndFailureRollsBackStaffMutation() = fixture { dir,access,owner ->
        val staff=access.addMember(owner,"Amina",StaffRole.CASHIER,"staff-password")
        val before=access.auditEvents(owner)
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("staff-access.db")}").use { db ->
            db.createStatement().use { statement ->
                assertFails {statement.executeUpdate("UPDATE staff_audit SET description='edited'")}
                assertFails {statement.executeUpdate("DELETE FROM staff_audit")}
                // Simulate a failed audit write: the corresponding member update must roll back too.
                statement.execute("CREATE TRIGGER reject_staff_audit BEFORE INSERT ON staff_audit BEGIN SELECT RAISE(ABORT,'fixture audit storage failure'); END")
            }
        }
        assertFails {access.updateMember(owner,staff.id,"Changed",StaffRole.MANAGER,false)}
        val unchanged=access.members(owner).single()
        assertEquals("Amina",unchanged.name)
        assertEquals(StaffRole.CASHIER,unchanged.role)
        assertTrue(unchanged.active)
        assertEquals(before,access.auditEvents(owner))
    }
}
