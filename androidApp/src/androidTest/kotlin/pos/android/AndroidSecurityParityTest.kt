package pos.android

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pos.android.platform.AndroidPrivateFiles
import pos.android.storage.AndroidSqliteDriver
import pos.data.*
import pos.domain.*
import pos.security.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSecurityParityTest {
    private fun fixture(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "android-security-parity-${UUID.randomUUID()}").apply { mkdirs() }
        val previous = System.getProperty("pos.dataDir")
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        AndroidPrivateFiles.install()
        System.setProperty("pos.dataDir", directory.path)
        try { block(directory) } finally {
            directory.deleteRecursively()
            if (previous == null) System.clearProperty("pos.dataDir") else System.setProperty("pos.dataDir", previous)
        }
    }
    private fun denied(block: () -> Unit) {
        try { block(); fail("Expected rejected access") } catch (_: IllegalArgumentException) { }
    }

    @Test fun staffPinRequiresFullPasswordExpiresAndIsRevokedByPasswordReset() = fixture { directory ->
        var now = 1_000_000L
        WorkspaceAccess(directory.toPath()).register("shop", "owner full password")
        StaffAccess(directory.toPath(), clockMillis = { now }, pinAuthorizationMillis = 60_000).use { access ->
            val owner = requireNotNull(access.unlockOwner("shop", "owner full password"))
            val person = access.addMember(owner, "Cashier", StaffRole.CASHIER, "cashier full password")
            assertFalse(access.configureStaffPin("shop", person.id, "incorrect password", "123456"))
            assertNull(access.unlockStaffPin("shop", person.id, "123456"))
            assertTrue(access.configureStaffPin("shop", person.id, "cashier full password", "123456"))
            val pinSession = requireNotNull(access.unlockStaffPin("shop", person.id, "123456"))
            access.requireSession(pinSession, StaffPermission.SELL)
            now += 60_000
            assertNull(access.unlockStaffPin("shop", person.id, "123456"))
            denied { access.requireSession(pinSession, StaffPermission.SELL) }
            assertNotNull(access.unlockStaff("shop", person.id, "cashier full password"))
            assertNotNull(access.unlockStaffPin("shop", person.id, "123456"))
            access.resetPassword(owner, person.id, "replacement full password")
            assertNull(access.unlockStaffPin("shop", person.id, "123456"))
            assertTrue(access.configureStaffPin("shop", person.id, "replacement full password", "654321"))
            assertTrue(access.configureStaffPin("shop", person.id, "replacement full password", null))
            assertNull(access.unlockStaffPin("shop", person.id, "654321"))
        }
    }

    @Test fun ownerRecoveryConsumesProofRevokesSessionsAndPinsWithoutChangingFinancialRecords() = fixture { directory ->
        PosRepository(File(directory, "pos.db").path).use { repo ->
            val shop = repo.createTenant("Recovery shop")
            repo.addProduct(shop.id, "Soap", "piece", 45000, 10.0)
            val before = repo.exportTenantSnapshot(shop.id)
            WorkspaceAccess(directory.toPath()).register(shop.id, "original owner password")
            var code = ""
            lateinit var staffId: String
            StaffAccess(directory.toPath()).use { access ->
                val owner = requireNotNull(access.unlockOwner(shop.id, "original owner password"))
                staffId = access.addMember(owner, "Cashier", StaffRole.CASHIER, "cashier full password").id
                assertTrue(access.configureStaffPin(shop.id, staffId, "cashier full password", "123456"))
                val cashier = requireNotNull(access.unlockStaffPin(shop.id, staffId, "123456"))
                assertNull(access.configureOwnerRecovery(owner, "incorrect password"))
                code = requireNotNull(access.configureOwnerRecovery(owner, "original owner password"))
                assertTrue(access.ownerRecoveryEnabled(owner))
                assertFalse(access.recoverOwner("other shop", code, "replacement owner password"))
                assertTrue(access.recoverOwner(shop.id, code, "replacement owner password"))
                denied { access.requireSession(owner, StaffPermission.SELL) }
                denied { access.requireSession(cashier, StaffPermission.SELL) }
                assertNull(access.unlockOwner(shop.id, "original owner password"))
                assertNull(access.unlockStaffPin(shop.id, staffId, "123456"))
                val replacement = requireNotNull(access.unlockOwner(shop.id, "replacement owner password"))
                assertFalse(access.ownerRecoveryEnabled(replacement))
                assertFalse(access.recoverOwner(shop.id, code, "third owner password"))
            }
            StaffAccess(directory.toPath()).use { reopened ->
                assertNotNull(reopened.unlockOwner(shop.id, "replacement owner password"))
                assertFalse(reopened.recoverOwner(shop.id, code, "third owner password"))
                assertNull(reopened.unlockStaffPin(shop.id, staffId, "123456"))
            }
            assertEquals(before, repo.exportTenantSnapshot(shop.id))
        }
    }

    @Test fun recipientFullPasswordHandoverRetainsShortageAndSignedHistoryAcrossRestore() = fixture { directory ->
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        PosRepository(File(directory, "pos.db").path).use { repo ->
            val shop = repo.createTenant("Handover shop")
            WorkspaceAccess(directory.toPath()).register(shop.id, "owner full password")
            StaffAccess(directory.toPath()).use { access ->
                val owner = requireNotNull(access.unlockOwner(shop.id, "owner full password"))
                val receiver = access.addMember(owner, "Receiving cashier", StaffRole.CASHIER, "receiver full password")
                val shift = repo.openCashShift(shop.id, 100000)
                repo.setPermissionCheck { id, permission -> require(id == owner.tenantId); access.requireSession(owner, permission) }
                repo.setCashHandoverAuthorizationCheck { id, grant, request -> require(id == owner.tenantId); access.requireCashHandover(owner, grant, request) }
                val request = CashHandoverRequest(shop.id, "handover", shift.id, 100000, 99000, null, "Counted end of shift")
                val before = repo.exportTenantSnapshot(shop.id)
                assertNull(access.authorizeCashHandover(owner, request, receiver.id, "incorrect password"))
                denied { repo.handoverCash(shop.id, request, "unissued consent") }
                assertEquals(before, repo.exportTenantSnapshot(shop.id))
                val proof = requireNotNull(access.authorizeCashHandover(owner, request, receiver.id, "receiver full password"))
                val saved = repo.handoverCash(shop.id, request, proof.id)
                assertEquals(saved, repo.handoverCash(shop.id, request, proof.id))
                val closed = repo.cashShifts(shop.id).single { it.id == shift.id }
                assertEquals(100000L, closed.expectedMinor)
                assertEquals(99000L, closed.countedMinor)
                assertNotNull(closed.closedAt)
                val opened = requireNotNull(repo.activeCashShift(shop.id))
                assertEquals(saved.openedShiftId, opened.id)
                assertEquals(99000L, opened.openingMinor)
                assertEquals(99000L, repo.expectedCash(shop.id, opened.id))
                denied { repo.handoverCash(shop.id, request.copy(countedMinor = 98000), proof.id) }
                assertEquals(1, repo.cashHandovers(shop.id).size)
                val snapshot = repo.exportTenantSnapshot(shop.id)
                repo.setPermissionCheck(null)
                val restored = repo.restoreTenantSnapshot(snapshot, "Recovered")
                assertEquals(saved.witness, repo.cashHandovers(restored.id).single().witness)
                assertEquals(99000L, repo.activeCashShift(restored.id)?.openingMinor)
            }
        }
    }
}
