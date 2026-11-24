package pos.backup

import kotlin.test.*
import pos.data.PosRepository
import pos.domain.LocalBackupPolicy
import pos.domain.StaffPermission
import pos.output.BackupService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AutoBackupCoordinatorTest {
    private val passphrase="secret session backup password"
    private val start=Instant.parse("2026-11-14T10:00:00Z")
    private fun policy(folder:Path,keep:Int=2)=LocalBackupPolicy(folder.toString(),5,keep)
    private fun managedFiles(folder:Path)=Files.walk(folder).use {stream ->stream.filter {it.fileName.toString().endsWith(".rpos")}.toList()}

    @Test fun scheduledVersionsAreEncryptedAndRetentionTouchesOnlyVerifiedOwnedCopies() {
        val root=Files.createTempDirectory("auto-backup-version-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Confidential merchant")
            repo.addProduct(shop.id,"Private product","piece",12345,4.0)
            val folder=Files.createDirectory(root.resolve("copies"));var now=start
            AutoBackupCoordinator(repo,root.resolve("settings")) {now}.use {service ->
                val supplied=passphrase.toCharArray()
                val first=service.enable(shop.id,policy(folder),supplied)
                assertTrue(supplied.all {it=='\u0000'})
                assertFalse(Files.readAllBytes(first).toString(Charsets.ISO_8859_1).contains("Confidential merchant"))
                val unrelated=first.parent.resolve("manual-copy.rpos")
                BackupService.create(repo,shop.id,passphrase,unrelated)
                now=now.plusSeconds(299);service.tick();assertEquals(1,service.status(shop.id).recordedVersions)
                now=now.plusSeconds(1);service.tick();assertEquals(2,service.status(shop.id).recordedVersions)
                now=now.plusSeconds(300);service.tick()
                assertEquals(2,service.status(shop.id).recordedVersions)
                assertFalse(Files.exists(first));assertTrue(Files.exists(unrelated))
                assertEquals(3,managedFiles(folder).size)
                Files.walk(root.resolve("settings")).use {paths ->paths.filter {Files.isRegularFile(it)}.forEach {
                    assertFalse(Files.readString(it).contains(passphrase))
                }}
                assertEquals(now.plusSeconds(300),service.status(shop.id).nextDueAt)
            }
        }} finally {root.toFile().deleteRecursively()}
    }
    @Test fun restartRequiresPasswordAgainAndMistypedPasswordCannotChangeExistingScheduleOrVersions() {
        val root=Files.createTempDirectory("auto-backup-restart-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val folder=Files.createDirectory(root.resolve("copies"))
            AutoBackupCoordinator(repo,root.resolve("settings")) {start}.use {it.enable(shop.id,policy(folder),passphrase.toCharArray())}
            AutoBackupCoordinator(repo,root.resolve("settings")) {start.plusSeconds(301)}.use {service ->
                assertTrue(service.policy(shop.id)!!.enabled)
                assertFalse(service.status(shop.id).runningForSession);assertNull(service.status(shop.id).checkedAt)
                service.tick();assertEquals(1,managedFiles(folder).size)
                val wrong="mistyped password".toCharArray()
                assertFails {service.enable(shop.id,policy(folder,5),wrong)}
                assertTrue(wrong.all {it=='\u0000'})
                assertEquals(2,service.policy(shop.id)!!.retainedVersions)
                assertEquals(1,managedFiles(folder).size)
                service.enable(shop.id,policy(folder),passphrase.toCharArray())
                assertTrue(service.status(shop.id).runningForSession)
                assertEquals(2,managedFiles(folder).size)
            }
        }} finally {root.toFile().deleteRecursively()}
    }
    @Test fun lockExpiryBackwardsClockPauseAndCloseStopSchedulingWithoutDeletingCopies() {
        val root=Files.createTempDirectory("auto-backup-session-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val folder=Files.createDirectory(root.resolve("copies"));var now=start
            val service=AutoBackupCoordinator(repo,root.resolve("settings")) {now}
            try {
                service.enable(shop.id,policy(folder),passphrase.toCharArray());service.lock()
                now=now.plusSeconds(300);service.tick();assertEquals(1,managedFiles(folder).size)
                assertFails {service.runNow(shop.id)}
                service.enable(shop.id,policy(folder),passphrase.toCharArray())
                now=now.plusSeconds(4*3600);service.tick();assertFalse(service.status(shop.id).runningForSession)
                assertEquals(2,managedFiles(folder).size)
                service.enable(shop.id,policy(folder),passphrase.toCharArray());now=now.minusSeconds(1)
                service.tick();assertFalse(service.status(shop.id).runningForSession)
                now=now.plusSeconds(2);service.enable(shop.id,policy(folder),passphrase.toCharArray())
                service.pause(shop.id);assertFalse(service.policy(shop.id)!!.enabled)
                now=now.plusSeconds(300);service.tick();assertEquals(2,managedFiles(folder).size)
                service.startScheduler();service.startScheduler();service.close()
                val password=passphrase.toCharArray()
                assertFails {service.enable(shop.id,policy(folder),password)}
                assertTrue(password.all {it=='\u0000'})
            } finally {service.close()}
        }} finally {root.toFile().deleteRecursively()}
    }
    @Test fun permissionDenialAndRevocationNeverWriteCopiesAndOtherShopsStaySeparate() {
        val root=Files.createTempDirectory("auto-backup-denial-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val other=repo.createTenant("Other")
            val folder=Files.createDirectory(root.resolve("copies"));var now=start;var allowed=false
            repo.setPermissionCheck {tenant,permission ->require(allowed && tenant==shop.id && permission in setOf(StaffPermission.BACKUP,StaffPermission.SELL)) {"Backup permission denied."}}
            AutoBackupCoordinator(repo,root.resolve("settings")) {now}.use {service ->
                assertFails {service.enable(shop.id,policy(folder),passphrase.toCharArray())}
                assertFails {service.status(shop.id)};assertTrue(managedFiles(folder).isEmpty())
                allowed=true;val first=service.enable(shop.id,policy(folder),passphrase.toCharArray())
                assertEquals(shop.id,BackupService.read(first,passphrase).tenant.id)
                assertFails {service.policy(other.id)}
                allowed=false;now=now.plusSeconds(300);service.tick()
                assertEquals(1,managedFiles(folder).size)
                allowed=true;assertFalse(service.status(shop.id).runningForSession)
                assertContains(service.status(shop.id).error.orEmpty(),"denied")
            }
        }} finally {root.toFile().deleteRecursively()}
    }
    @Test fun changedOldCopiesAreNeverPrunedAndPasswordCheckRejectsCorruptedLatest() {
        val root=Files.createTempDirectory("auto-backup-corruption-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val folder=Files.createDirectory(root.resolve("copies"));var now=start
            AutoBackupCoordinator(repo,root.resolve("settings")) {now}.use {service ->
                val first=service.enable(shop.id,policy(folder),passphrase.toCharArray())
                now=now.plusSeconds(300);val second=service.runNow(shop.id)
                Files.writeString(second,"Changed file must never be deleted")
                now=now.plusSeconds(300);val latest=service.runNow(shop.id)
                assertEquals(3,managedFiles(folder).size)
                assertTrue(Files.exists(first),"The first good copy remains; a damaged newer copy does not consume retention quota.")
                assertEquals("Changed file must never be deleted",Files.readString(second))
                assertNotNull(service.status(shop.id).retentionWarning)
                val bytes=Files.readAllBytes(latest);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();Files.write(latest,bytes)
                assertFails {service.checkLatest(shop.id,passphrase.toCharArray())}
                assertNull(service.status(shop.id).checkedAt)
                assertEquals(3,managedFiles(folder).size)
                assertFails {service.enable(shop.id,policy(folder),passphrase.toCharArray())}
                assertEquals(3,managedFiles(folder).size)
            }
        }} finally {root.toFile().deleteRecursively()}
    }
    @Test fun unavailableFolderAndBrokenIndexPreserveEveryOlderFile() {
        val root=Files.createTempDirectory("auto-backup-failure-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");val folder=Files.createDirectory(root.resolve("copies"));var now=start
            AutoBackupCoordinator(repo,root.resolve("settings")) {now}.use {service ->
                val first=service.enable(shop.id,policy(folder),passphrase.toCharArray())
                val original=Files.readAllBytes(first)
                val away=root.resolve("disconnected-drive");Files.move(folder,away)
                now=now.plusSeconds(300);assertFails {service.runNow(shop.id)}
                assertFalse(Files.exists(folder));assertContentEquals(original,Files.readAllBytes(away.resolve(folder.relativize(first))))
                Files.move(away,folder)
                Files.writeString(first.parent.resolve("versions.properties"),"format=invalid")
                assertFails {service.runNow(shop.id)}
                assertContentEquals(original,Files.readAllBytes(first));assertEquals(1,managedFiles(folder).size)
            }
        }} finally {root.toFile().deleteRecursively()}
    }
    @Test fun verifiedAutomaticVersionCanRestoreAsNewWorkspaceWithOriginalUntouched() {
        val root=Files.createTempDirectory("auto-backup-restore-")
        try {PosRepository(":memory:").use {repo ->
            val shop=repo.createTenant("Shop");repo.addProduct(shop.id,"Rice","bag",10000,4.0)
            val folder=Files.createDirectory(root.resolve("copies"))
            AutoBackupCoordinator(repo,root.resolve("settings")) {start}.use {service ->
                val first=service.enable(shop.id,policy(folder),passphrase.toCharArray())
                val wrong=passphrase.reversed().toCharArray()
                assertFails {service.checkLatest(shop.id,wrong)};assertTrue(wrong.all {it=='\u0000'})
                service.checkLatest(shop.id,passphrase.toCharArray())
                assertEquals(first,service.status(shop.id).checkedFile)
                val recovered=repo.restoreTenantSnapshot(BackupService.read(first,passphrase),"Recovered test copy")
                assertNotEquals(shop.id,recovered.id)
                assertEquals(4.0,repo.products(recovered.id).single().stock)
                assertEquals(4.0,repo.products(shop.id).single().stock)
                assertEquals(1,managedFiles(folder).size)
            }
        }} finally {root.toFile().deleteRecursively()}
    }
}
