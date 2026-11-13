package pos.security

import kotlin.test.Test
import kotlin.test.assertEquals
import pos.domain.StaffPermission
import java.nio.file.Files

class StaffClockReadRegressionTest {
    @Test fun activityUsesExactlyTheTimestampWhichWasValidated() {
        val directory=Files.createTempDirectory("staff-clock-read")
        try {
            WorkspaceAccess(directory).register("shop","owner-secret")
            var reads=0
            StaffAccess(directory,clockMillis={ reads++;1_000_000L }).use { access ->
                val session=requireNotNull(access.unlockOwner("shop","owner-secret"))
                reads=0
                access.requireSession(session,StaffPermission.SELL)
                assertEquals(1,reads)
                reads=0
                access.touchSession(session)
                assertEquals(1,reads)
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
