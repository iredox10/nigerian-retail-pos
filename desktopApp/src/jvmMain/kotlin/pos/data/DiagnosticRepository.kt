package pos.data

import pos.diagnostics.LocalDiagnosticReport
import pos.diagnostics.collectLocalDiagnostics
import pos.domain.StaffPermission
import java.nio.file.Path

/** Uses the actual connected database location, never a guessed default or an exported path. */
fun PosRepository.localDiagnostics(tenantId: String): LocalDiagnosticReport = featureRead(tenantId, StaffPermission.BACKUP) {
    collectLocalDiagnostics(
        quickCheck = { featureQuery("PRAGMA quick_check(1)") { it.getString(1) } },
        directory = {
            featureQuery("PRAGMA database_list") { it.getString("name") to it.getString("file") }
                .firstOrNull { it.first == "main" }?.second?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().parent }
        },
    )
}
