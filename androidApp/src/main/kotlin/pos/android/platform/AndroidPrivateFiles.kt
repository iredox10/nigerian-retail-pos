package pos.android.platform

import android.system.Os
import pos.security.LocalFilePermissions

object AndroidPrivateFiles {
    fun install() = LocalFilePermissions.installPlatformAdapter { path, mode ->
        val bits=when(mode) {"rwx------" -> 448; "rw-------" -> 384;else -> error("Unsupported private-file mode.")}
        Os.chmod(path.toString(),bits)
        check(Os.stat(path.toString()).st_mode and 511 == bits) {"Private file permissions could not be verified."}
    }
}
