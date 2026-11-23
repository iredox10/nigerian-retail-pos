package pos.diagnostics

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*
import pos.data.PosRepository
import pos.data.localDiagnostics

class LocalDiagnosticsTest {
    @Test fun allowlistDiscardsPrivateErrorsAndUntrustedProperties() {
        val secret = "/home/merchant/private.db PIN=1234 customer Ada payment bank-secret"
        val result = collectLocalDiagnostics(
            quickCheck = { listOf(secret) }, directory = { error(secret) }, printers = { error(secret) },
            osName = { secret }, version = { secret }, now = { Instant.parse("2026-11-23T12:00:00Z") },
        )
        assertEquals(DiagnosticState.FAILED, result.database)
        assertEquals(DiagnosticState.UNAVAILABLE, result.storageWrite)
        assertNull(result.printServiceCount)
        assertNull(result.applicationVersion)
        assertEquals(DiagnosticPlatform.OTHER, result.platform)
        for (export in listOf(result.text(), result.json())) {
            listOf(secret, "merchant", "1234", "Ada", "bank-secret", "private.db").forEach { assertFalse(export.contains(it)) }
            assertTrue(export.contains("NOT_CHECKED"))
        }
        assertFails { result.copy(applicationVersion = secret) }
        assertFails { result.copy(usableBytes = -1) }
    }

    @Test fun storageProbeUsesActualDirectoryAndCleansUpEvenWhenUnavailable() {
        val directory = Files.createTempDirectory("diagnostic-test")
        try {
            val report = collectLocalDiagnostics({ listOf("ok") }, { directory }, { 0 }, { "Linux" }, { "0.1.0" })
            assertEquals(DiagnosticState.PASSED, report.database)
            assertEquals(DiagnosticState.PASSED, report.storageWrite)
            assertEquals(DiagnosticState.PASSED, report.storageCleanup)
            assertEquals(0, report.printServiceCount)
            Files.list(directory).use { assertEquals(0L, it.count()) }
            assertEquals(DiagnosticState.FAILED, storageDiagnostic(directory.resolve("missing")).write)
            assertEquals(DiagnosticState.UNAVAILABLE, collectLocalDiagnostics({ error("private") }, { null }, { -1 }).database)
        } finally { Files.delete(directory) }
    }

    @Test fun checksAreAuthorizedAndDoNotChangeFinancialSnapshot() {
        PosRepository(":memory:").use { repo ->
            val tenant = repo.createTenant("Private shop").id
            repo.addProduct(tenant, "Private product", "piece", 12345, 3.0)
            repo.openCashShift(tenant, 123000)
            val before = repo.exportTenantSnapshot(tenant)
            val report = repo.localDiagnostics(tenant)
            assertEquals(DiagnosticState.PASSED, report.database)
            assertEquals(DiagnosticState.UNAVAILABLE, report.storageWrite)
            assertFalse(report.json().contains(tenant))
            assertFalse(report.json().contains("Private"))
            assertEquals(before, repo.exportTenantSnapshot(tenant))
            repo.setPermissionCheck { _, _ -> error("Access denied private-user") }
            assertFails { repo.localDiagnostics(tenant) }
        }
    }
}
