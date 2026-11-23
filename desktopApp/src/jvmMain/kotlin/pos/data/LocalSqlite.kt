package pos.data

import java.sql.Connection
import java.sql.DriverManager

/** The platform supplies a local connection; business transactions remain in the repository. */
object LocalSqlite {
    @Volatile private var opener: ((String) -> Connection)? = null
    fun installPlatformAdapter(adapter: (String) -> Connection) { opener = adapter }
    fun open(path: String): Connection = opener?.invoke(path) ?: run {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path")
    }
}
