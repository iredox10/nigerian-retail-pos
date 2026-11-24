package pos.cloud.desktop

import pos.cloud.client.*
import java.nio.file.*
import java.nio.file.attribute.*
import kotlin.test.*

class DesktopCloudFilesTest {
    @Test fun privateSecretsAndCheckpointsSurviveReopenWithOwnerOnlyPermissions() {
        val root=Files.createTempDirectory("retailpos-cloud-permissions-")
        try {
            val directory=root.resolve("cloud")
            val files=desktopCloudFiles(directory)
            val secrets=FileCloudCredentialStore(files)
            val token="q".repeat(43)
            secrets.write("device",token)
            val identity=CloudStateStore(files).installationId
            assertEquals(identity,CloudStateStore(desktopCloudFiles(directory)).installationId)
            assertEquals(token,FileCloudCredentialStore(desktopCloudFiles(directory)).read("device"))
            val secretFile=Files.list(directory).use {stream ->stream.filter {it.fileName.toString().startsWith("token-")}.findFirst().orElseThrow()}
            if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory))
                assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(secretFile))
            } else {
                listOf(directory,secretFile).forEach {path ->
                    val owner=Files.getOwner(path)
                    val acl=requireNotNull(Files.getFileAttributeView(path,AclFileAttributeView::class.java)).acl
                    assertTrue(acl.isNotEmpty());assertTrue(acl.all {it.type()==AclEntryType.ALLOW && it.principal()==owner})
                }
            }
            secrets.remove("device");assertNull(secrets.read("device"))
        } finally {Files.walk(root).use {paths ->paths.sorted(Comparator.reverseOrder()).forEach {Files.delete(it)}}}
    }
}
