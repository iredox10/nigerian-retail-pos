package pos.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Platform boundary keeps credential/key files owner-only without assuming desktop NIO support. */
object LocalFilePermissions {
    @Volatile private var adapter: ((Path, String) -> Unit)? = null
    fun installPlatformAdapter(restrict: (Path, String) -> Unit) { adapter = restrict }
    fun restrict(path: Path, mode: String) {
        val platform = adapter
        if (platform != null) platform(path, mode)
        else if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }
}
