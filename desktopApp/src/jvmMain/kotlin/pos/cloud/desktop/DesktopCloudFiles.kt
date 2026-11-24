package pos.cloud.desktop

import pos.cloud.client.CloudFileProtection
import pos.cloud.client.PrivateCloudFiles
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.EnumSet

/** Desktop-only ACL APIs stay out of Android's JVM library. */
fun desktopCloudFiles(directory:Path)=PrivateCloudFiles(directory,CloudFileProtection {path,isDirectory ->
    if(Files.getFileStore(path).supportsFileAttributeView("posix")) {
        val mode=PosixFilePermissions.fromString(if(isDirectory)"rwx------" else "rw-------")
        Files.setPosixFilePermissions(path,mode)
        check(Files.getPosixFilePermissions(path)==mode) {"Private cloud file permissions could not be verified."}
    } else {
        val view=requireNotNull(Files.getFileAttributeView(path,AclFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS)) {"Private cloud storage is unavailable on this filesystem."}
        val owner=Files.getOwner(path,LinkOption.NOFOLLOW_LINKS)
        val entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        if(isDirectory)entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT)
        view.acl=listOf(entry.build())
        check(view.acl.isNotEmpty() && view.acl.all {it.type()==AclEntryType.ALLOW && it.principal()==owner}) {"Owner-only cloud storage could not be verified."}
    }
})
