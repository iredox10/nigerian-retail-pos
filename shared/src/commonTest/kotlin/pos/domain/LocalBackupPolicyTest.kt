package pos.domain

import kotlin.test.*

class LocalBackupPolicyTest {
    @Test fun boundedIntervalsAndRetentionKeepMultipleRecoverableVersions() {
        assertEquals(10,LocalBackupPolicy("/backups").retainedVersions)
        assertFails {LocalBackupPolicy("/backups",4)}
        assertFails {LocalBackupPolicy("/backups",1441)}
        assertFails {LocalBackupPolicy("/backups",retainedVersions=1)}
        assertFails {LocalBackupPolicy("/backups",retainedVersions=101)}
    }
    @Test fun destinationMustBeExplicitPrintableText() {
        assertFails {LocalBackupPolicy(" ")}
        assertFails {LocalBackupPolicy("/backups\nelsewhere")}
        assertEquals("/offline/drive",LocalBackupPolicy("/offline/drive").destinationDirectory)
    }
}
