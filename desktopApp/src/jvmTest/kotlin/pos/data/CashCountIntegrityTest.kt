package pos.data

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*
import pos.domain.*
import pos.output.BackupService

class CashCountIntegrityTest {
    private val opening = CashCount(mapOf(100000L to 2, 50000L to 1))
    private val closing = CashCount(mapOf(100000L to 2, 20000L to 2, 500L to 1))

    @Test fun sheetsSurviveRestartAndTheClosingDifferenceDoesNotRewriteMoney() {
        val path = Files.createTempDirectory("pos-count-sheet-").resolve("shop.db").toString()
        var tenant = ""
        var shiftId = ""
        PosRepository(path).use { repo ->
            tenant = repo.createTenant("Count shop").id
            val shift = repo.openCashShift(tenant, opening.totalMinor, openingCount = opening)
            shiftId = shift.id
            assertEquals(opening, shift.openingCount)
            val result = repo.closeCashShift(tenant, shiftId, closing.totalMinor, closingCount = closing, blindClosing = true)
            assertEquals(-9500L, result.differenceMinor)
            assertTrue(result.blindClosing)
            assertTrue(repo.cashEntries(tenant).isEmpty())
            assertTrue(repo.sales(tenant).isEmpty())
        }
        PosRepository(path).use { repo ->
            val saved = repo.cashShifts(tenant).single()
            assertEquals(shiftId, saved.id)
            assertEquals(opening, saved.openingCount)
            assertEquals(closing, saved.closingCount)
            assertTrue(saved.blindClosing)
            assertEquals(opening.totalMinor, repo.expectedCash(tenant, shiftId))
        }
    }

    @Test fun invalidTotalsAndDeniedWritesLeaveNoPartialShiftOrOutboxEffects() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Count shop").id
            val before = repo.exportTenantSnapshot(tenant)
            assertFails { repo.openCashShift(tenant, 1, openingCount = opening) }
            assertEquals(before, repo.exportTenantSnapshot(tenant))
            val shift = repo.openCashShift(tenant, opening.totalMinor, openingCount = opening)
            val openSnapshot = repo.exportTenantSnapshot(tenant)
            assertFails { repo.closeCashShift(tenant, shift.id, 1, closingCount = closing) }
            assertEquals(openSnapshot, repo.exportTenantSnapshot(tenant))
            repo.setPermissionCheck { _, _ -> error("Permission denied") }
            assertFails { repo.closeCashShift(tenant, shift.id, closing.totalMinor, closingCount = closing) }
            repo.setPermissionCheck(null)
            assertEquals(openSnapshot, repo.exportTenantSnapshot(tenant))
        }
    }

    @Test fun closingReplayRequiresTheSameOriginalSheetAndCountMethod() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Count shop").id
            val shift = repo.openCashShift(tenant, 100000)
            val first = CashCount(mapOf(100000L to 1))
            val other = CashCount(mapOf(50000L to 2))
            val closed = repo.closeCashShift(tenant, shift.id, first.totalMinor, closingCount = first, blindClosing = true)
            val snapshot = repo.exportTenantSnapshot(tenant)
            assertEquals(closed, repo.closeCashShift(tenant, shift.id, first.totalMinor, closingCount = first, blindClosing = true))
            assertFails { repo.closeCashShift(tenant, shift.id, other.totalMinor, closingCount = other, blindClosing = true) }
            assertFails { repo.closeCashShift(tenant, shift.id, first.totalMinor, closingCount = first, blindClosing = false) }
            assertEquals(snapshot, repo.exportTenantSnapshot(tenant))
        }
    }

    @Test fun sheetsTravelInEncryptedBackupsAndLegacyBackupsKeepTheirTotals() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Count shop").id
            val shift = repo.openCashShift(tenant, opening.totalMinor, openingCount = opening)
            repo.closeCashShift(tenant, shift.id, closing.totalMinor, closingCount = closing, blindClosing = true)
            val file = Files.createTempDirectory("pos-count-backup-").resolve("shop.retailbackup")
            BackupService.create(repo, tenant, "count-backup-password", file)
            val snapshot = BackupService.read(file, "count-backup-password")
            val restored = repo.restoreTenantSnapshot(snapshot, "Restored count shop")
            val saved = repo.cashShifts(restored.id).single()
            assertEquals(opening, saved.openingCount)
            assertEquals(closing, saved.closingCount)
            assertTrue(saved.blindClosing)
            val legacy = snapshot.copy(tables = snapshot.tables + ("cash_shifts" to snapshot.tables.getValue("cash_shifts").map { row -> row - setOf("opening_count_sheet", "closing_count_sheet", "closing_blind") }))
            val legacyTenant = repo.restoreTenantSnapshot(legacy, "Legacy shop")
            val legacyShift = repo.cashShifts(legacyTenant.id).single()
            assertNull(legacyShift.openingCount)
            assertNull(legacyShift.closingCount)
            assertFalse(legacyShift.blindClosing)
            assertEquals(closing.totalMinor, legacyShift.countedMinor)
        }
    }

    @Test fun tamperedBackupSheetsRollBackTheWholeRestoreAndCrossShopCloseFails() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Count shop").id
            val other = repo.createTenant("Other shop").id
            val shift = repo.openCashShift(tenant, opening.totalMinor, openingCount = opening)
            assertFails { repo.closeCashShift(other, shift.id, 0) }
            repo.closeCashShift(tenant, shift.id, closing.totalMinor, closingCount = closing)
            val snapshot = repo.exportTenantSnapshot(tenant)
            val before = repo.tenants()
            listOf("v1|100000:1", "v1|100000:1;100000:1", "v1|300:1").forEach { invalid ->
                val tampered = snapshot.copy(tables = snapshot.tables + ("cash_shifts" to snapshot.tables.getValue("cash_shifts").map { it + ("closing_count_sheet" to invalid) }))
                assertFails { repo.restoreTenantSnapshot(tampered, "Must not exist") }
                assertEquals(before, repo.tenants())
            }
        }
    }

    @Test fun migrationAddsNullableSheetsToExistingCashShiftsWithoutInventingCounts() {
        val path = Files.createTempDirectory("pos-count-migration-").resolve("shop.db").toString()
        var tenant = ""
        PosRepository(path).use { repo ->
            tenant = repo.createTenant("Legacy shop").id
            val shift = repo.openCashShift(tenant, 12345)
            repo.closeCashShift(tenant, shift.id, 12000)
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            listOf("opening_count_sheet", "closing_count_sheet", "closing_blind").forEach { column -> db.createStatement().use { it.execute("ALTER TABLE cash_shifts DROP COLUMN $column") } }
        }
        PosRepository(path).use { repo ->
            val shift = repo.cashShifts(tenant).single()
            assertEquals(12345L, shift.openingMinor)
            assertEquals(12000L, shift.countedMinor)
            assertNull(shift.openingCount)
            assertNull(shift.closingCount)
            assertFalse(shift.blindClosing)
        }
    }
}
