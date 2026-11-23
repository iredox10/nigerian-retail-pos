package pos.security

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*
import pos.domain.StaffPermission
import pos.domain.StaffRole

class OwnerRecoveryTest {
    private fun fixture(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("owner-recovery-test-")
        try {
            WorkspaceAccess(directory).register("shop", "original owner password")
            WorkspaceAccess(directory).register("other", "other owner password")
            block(directory)
        } finally { directory.toFile().deleteRecursively() }
    }
    private fun credential(directory: Path): ByteArray = Files.list(directory.resolve("credentials")).use { paths ->
        val selected = paths.filter { it.fileName.toString().endsWith(".credential") }
            .filter { Files.readString(it).startsWith("2:") }.findFirst().orElseThrow()
        Files.readAllBytes(selected)
    }

    @Test fun enrollmentRequiresOwnerPasswordAndRotationReplacesTheProofWithoutChangingThePassword() = fixture { directory ->
        StaffAccess(directory).use { access ->
            val owner = requireNotNull(access.unlockOwner("shop", "original owner password"))
            assertFalse(access.ownerRecoveryEnabled(owner))
            assertNull(access.configureOwnerRecovery(owner, "wrong password"))
            val first = requireNotNull(access.configureOwnerRecovery(owner, "original owner password"))
            assertTrue(first.matches(Regex("[0-9A-F]{8}(-[0-9A-F]{8}){7}")))
            assertTrue(access.ownerRecoveryEnabled(owner))
            val record = credential(directory).toString(Charsets.UTF_8)
            assertFalse(record.contains(first))
            assertFalse(record.contains(first.replace("-", "")))
            assertFalse(record.contains("original owner password"))
            val second = requireNotNull(access.configureOwnerRecovery(owner, "original owner password"))
            assertNotEquals(first, second)
            val before = credential(directory)
            assertFalse(access.recoverOwner("shop", first, "replacement owner password"))
            assertContentEquals(before, credential(directory))
            assertTrue(WorkspaceAccess(directory).unlock("shop", "original owner password"))
            assertFalse(access.recoverOwner("other", second, "replacement owner password"))
            assertTrue(WorkspaceAccess(directory).unlock("other", "other owner password"))
            val manager = access.addMember(owner, "Manager", StaffRole.MANAGER, "manager password")
            val managerSession = requireNotNull(access.unlockStaff("shop", manager.id, "manager password"))
            assertFails { access.configureOwnerRecovery(managerSession, "original owner password") }
            assertFails { access.ownerRecoveryEnabled(managerSession) }
        }
    }

    @Test fun recoveryConsumesTheCodeAndRevokesSessionsAndPinsAcrossInstances() = fixture { directory ->
        StaffAccess(directory).use { first ->
            val owner = requireNotNull(first.unlockOwner("shop", "original owner password"))
            val code = requireNotNull(first.configureOwnerRecovery(owner, "original owner password"))
            val staff = first.addMember(owner, "Amina", StaffRole.CASHIER, "cashier password")
            assertTrue(first.configureStaffPin("shop", staff.id, "cashier password", "123456"))
            StaffAccess(directory).use { second ->
                val otherOwner = requireNotNull(second.unlockOwner("shop", "original owner password"))
                val passwordSession = requireNotNull(second.unlockStaff("shop", staff.id, "cashier password"))
                val pinSession = requireNotNull(second.unlockStaffPin("shop", staff.id, "123456"))
                assertTrue(first.recoverOwner("shop", code.lowercase().replace('-', ' '), "recovered owner password"))
                assertFails { first.requireSession(owner, StaffPermission.SELL) }
                assertFails { second.requireSession(otherOwner, StaffPermission.SELL) }
                assertFails { second.requireSession(passwordSession, StaffPermission.SELL) }
                assertFails { second.requireSession(pinSession, StaffPermission.SELL) }
                assertNull(second.unlockOwner("shop", "original owner password"))
                val recovered = requireNotNull(second.unlockOwner("shop", "recovered owner password"))
                assertFalse(second.ownerRecoveryEnabled(recovered))
                assertNull(second.unlockStaffPin("shop", staff.id, "123456"))
                assertNotNull(second.unlockStaff("shop", staff.id, "cashier password"))
                assertNull(second.unlockStaffPin("shop", staff.id, "123456"))
                assertFalse(second.recoverOwner("shop", code, "another owner password"))
                val audit = second.auditEvents(recovered)
                assertEquals(1, audit.count { it.kind == "owner.password_recovered" })
                assertTrue(audit.any { it.kind == "owner.recovery_enrolled" })
                assertTrue(audit.none { it.description.orEmpty().contains(code) || it.description.orEmpty().contains("recovered owner password") })
            }
        }
        assertFalse(WorkspaceAccess(directory).unlock("shop", "original owner password"))
        assertTrue(WorkspaceAccess(directory).unlock("shop", "recovered owner password"))
    }

    @Test fun recoverySharesThePersistentOwnerCooldownAndUnknownShopsFailClosed() = fixture { directory ->
        var now = 100_000L
        var code = ""
        StaffAccess(directory, clockMillis = { now }).use { access ->
            val owner = requireNotNull(access.unlockOwner("shop", "original owner password"))
            code = requireNotNull(access.configureOwnerRecovery(owner, "original owner password"))
            repeat(5) { assertFalse(access.recoverOwner("shop", "0".repeat(64), "replacement owner password")) }
            assertNull(access.unlockOwner("shop", "original owner password"))
        }
        StaffAccess(directory, clockMillis = { now }).use { restarted ->
            assertFalse(restarted.recoverOwner("shop", code, "replacement owner password"))
            now += 60_000
            assertTrue(restarted.recoverOwner("shop", code, "replacement owner password"))
            assertNotNull(restarted.unlockOwner("shop", "replacement owner password"))
            assertFalse(restarted.recoverOwner("missing", code, "missing owner password"))
            assertNull(restarted.unlockOwner("missing", "missing owner password"))
        }
    }

    @Test fun credentialJournalRecoversEnrollmentAuditAfterInterruptedAuditWrite() = fixture { directory ->
        StaffAccess(directory).use { access ->
            val owner = requireNotNull(access.unlockOwner("shop", "original owner password"))
            DriverManager.getConnection("jdbc:sqlite:${directory.resolve("staff-access.db")}").use { db ->
                db.createStatement().use { it.execute("CREATE TRIGGER stop_audit BEFORE INSERT ON staff_audit BEGIN SELECT RAISE(ABORT,'Test audit interruption'); END") }
                val code = requireNotNull(access.configureOwnerRecovery(owner, "original owner password"))
                assertTrue(code.isNotEmpty())
                assertFails { access.configureOwnerRecovery(owner, "original owner password") }
                db.createStatement().use { it.execute("DROP TRIGGER stop_audit") }
            }
        }
        StaffAccess(directory).use { access ->
            val owner = requireNotNull(access.unlockOwner("shop", "original owner password"))
            assertTrue(access.ownerRecoveryEnabled(owner))
            assertEquals(1, access.auditEvents(owner).count { it.kind == "owner.recovery_enrolled" })
        }
    }
}
