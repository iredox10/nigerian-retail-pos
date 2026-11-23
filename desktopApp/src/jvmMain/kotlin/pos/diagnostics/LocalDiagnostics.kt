package pos.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.print.PrintServiceLookup

/** Export contains only typed allowlisted values. No raw OS properties, SQL rows or errors survive. */
enum class DiagnosticState { PASSED, FAILED, UNAVAILABLE, NOT_CHECKED }
enum class DiagnosticPlatform { WINDOWS, MACOS, LINUX, OTHER, UNAVAILABLE }
data class LocalDiagnosticReport(
    val createdAt: Instant,
    val platform: DiagnosticPlatform,
    val applicationVersion: String?,
    val database: DiagnosticState,
    val storageWrite: DiagnosticState,
    val storageCleanup: DiagnosticState,
    val usableBytes: Long?,
    val printServiceCount: Int?,
) {
    init {
        require(applicationVersion == null || applicationVersion.matches(Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")))
        require(usableBytes == null || usableBytes >= 0)
        require(printServiceCount == null || printServiceCount >= 0)
    }
    fun fields(): List<Pair<String, String>> = listOf(
        "format" to "1", "application" to "Retail POS", "generated_utc" to createdAt.toString(),
        "platform" to platform.name, "application_version" to (applicationVersion ?: "UNAVAILABLE"),
        "sqlite_quick_check" to database.name, "storage_write_probe" to storageWrite.name,
        "storage_cleanup" to storageCleanup.name, "storage_usable_bytes" to (usableBytes?.toString() ?: "UNAVAILABLE"),
        "local_print_services" to (printServiceCount?.toString() ?: "UNAVAILABLE"),
        "printer_job_test" to "NOT_CHECKED", "backup_status" to "UNAVAILABLE",
        "network" to "NOT_CHECKED", "sync" to "NOT_CHECKED", "update_status" to "NOT_CHECKED",
    )
    fun text(): String = fields().joinToString("\n", postfix = "\n") { "${it.first}: ${it.second}" }
    fun json(): String = fields().joinToString(",\n", "{\n", "\n}\n") { "  \"${it.first}\": \"${it.second}\"" }
}

internal data class StorageDiagnostic(val write: DiagnosticState, val cleanup: DiagnosticState, val usableBytes: Long?)
internal fun storageDiagnostic(directory: Path?): StorageDiagnostic {
    if (directory == null) return StorageDiagnostic(DiagnosticState.UNAVAILABLE, DiagnosticState.NOT_CHECKED, null)
    val usable = runCatching { Files.getFileStore(directory).usableSpace.takeIf { it >= 0 } }.getOrNull()
    var probe: Path? = null
    var written = DiagnosticState.FAILED
    var cleaned = DiagnosticState.NOT_CHECKED
    try {
        probe = Files.createTempFile(directory, ".retail-diagnostic-", ".tmp")
        Files.write(probe, byteArrayOf(0x52, 0x50))
        written = if (Files.readAllBytes(probe).contentEquals(byteArrayOf(0x52, 0x50))) DiagnosticState.PASSED else DiagnosticState.FAILED
    } catch (_: Exception) { written = DiagnosticState.FAILED }
    finally {
        if (probe != null) cleaned = try { Files.deleteIfExists(probe); DiagnosticState.PASSED } catch (_: Exception) { DiagnosticState.FAILED }
    }
    return StorageDiagnostic(written, cleaned, usable)
}

internal fun collectLocalDiagnostics(
    quickCheck: () -> List<String>, directory: () -> Path?,
    printers: () -> Int = { PrintServiceLookup.lookupPrintServices(null, null).size },
    osName: () -> String = { System.getProperty("os.name", "") },
    version: () -> String? = { LocalDiagnosticReport::class.java.`package`.implementationVersion },
    now: () -> Instant = { Instant.now() },
): LocalDiagnosticReport {
    val database = try {
        if (quickCheck() == listOf("ok")) DiagnosticState.PASSED else DiagnosticState.FAILED
    } catch (_: Exception) { DiagnosticState.UNAVAILABLE }
    val storage = storageDiagnostic(runCatching(directory).getOrNull())
    val platform = try { val os = osName().lowercase(); when {
        os.startsWith("windows") -> DiagnosticPlatform.WINDOWS
        os == "mac os x" -> DiagnosticPlatform.MACOS
        os == "linux" -> DiagnosticPlatform.LINUX
        else -> DiagnosticPlatform.OTHER
    } } catch (_: Exception) { DiagnosticPlatform.UNAVAILABLE }
    return LocalDiagnosticReport(now(), platform,
        runCatching(version).getOrNull()?.takeIf { it.matches(Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")) },
        database, storage.write, storage.cleanup, storage.usableBytes,
        runCatching(printers).getOrNull()?.takeIf { it >= 0 })
}
