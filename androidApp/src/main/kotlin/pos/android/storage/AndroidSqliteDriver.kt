package pos.android.storage

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteBindOrColumnIndexOutOfRangeException
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.Properties
import java.util.logging.Logger

/**
 * Narrow JDBC boundary over Android's native SQLite. Business SQL and transactions stay in
 * the shared local repositories; no network or desktop native library is involved.
 * Unsupported JDBC operations fail explicitly instead of returning fabricated defaults.
 */
object AndroidSqliteDriver {
    private const val PREFIX = "jdbc:sqlite:"
    @Volatile private var registered = false

    @Synchronized fun install() {
        if (registered) return
        DriverManager.registerDriver(object : Driver {
            override fun acceptsURL(url: String?) = url?.startsWith(PREFIX) == true
            override fun connect(url: String?, info: Properties?): Connection? =
                if (acceptsURL(url)) open(requireNotNull(url).removePrefix(PREFIX)) else null
            override fun getPropertyInfo(url: String?, info: Properties?) = emptyArray<DriverPropertyInfo>()
            override fun getMajorVersion() = 1
            override fun getMinorVersion() = 0
            override fun jdbcCompliant() = false
        })
        registered = true
    }

    fun open(path: String): Connection {
        require(path.isNotBlank()) { "A local database path is required." }
        val sqlite = if (path == ":memory:") SQLiteDatabase.create(null) else {
            File(path).absoluteFile.parentFile?.let { directory ->
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create local database directory." }
            }
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.CREATE_IF_NECESSARY)
        }
        try {
            sqlite.setForeignKeyConstraintsEnabled(true)
            val handler = ConnectionHandler(sqlite)
            return proxy(Connection::class.java, handler).also { handler.connection = it }
        } catch (failure: Throwable) { sqlite.close(); throw failure }
    }
}

private fun unsupported(method: Method): Nothing =
    throw SQLFeatureNotSupportedException("Unsupported local JDBC operation: ${method.declaringClass.simpleName}.${method.name}")

private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
    type.cast(Proxy.newProxyInstance(AndroidSqliteDriver::class.java.classLoader, arrayOf(type), handler))

private abstract class JdbcHandler : InvocationHandler {
    final override fun invoke(receiver: Any, method: Method, raw: Array<out Any?>?): Any? {
        val args = raw ?: emptyArray()
        return when (method.name) {
            "toString" -> "Android local SQLite ${method.declaringClass.simpleName}"
            "hashCode" -> System.identityHashCode(receiver)
            "equals" -> receiver === args.firstOrNull()
            "isWrapperFor" -> (args[0] as Class<*>).isInstance(receiver)
            "unwrap" -> if ((args[0] as Class<*>).isInstance(receiver)) receiver else throw SQLException("Not a supported wrapper")
            else -> try { call(method, args) } catch (failure: android.database.SQLException) {
                throw SQLException(failure.message ?: "Local SQLite operation failed", failure)
            }
        }
    }
    abstract fun call(method: Method, args: Array<out Any?>): Any?
}

private class ConnectionHandler(val sqlite: SQLiteDatabase) : JdbcHandler() {
    lateinit var connection: Connection
    private var closed = false
    private var automatic = true
    private var transaction = false
    private val statements = mutableSetOf<StatementHandler>()

    @Synchronized fun ensureOpen() { if (closed || !sqlite.isOpen) throw SQLException("Local database is closed") }
    @Synchronized fun beforeSql() {
        ensureOpen()
        if (!automatic && !transaction) { sqlite.beginTransaction(); transaction = true }
    }
    @Synchronized private fun finish(commit: Boolean) {
        ensureOpen()
        if (transaction) {
            try { if (commit) sqlite.setTransactionSuccessful() }
            finally { try { sqlite.endTransaction() } finally { transaction = false } }
        }
    }
    @Synchronized private fun close() {
        if (closed) return
        try {
            statements.toList().forEach { it.close() }
            if (transaction) finish(false)
        } finally { closed = true; sqlite.close() }
    }
    fun release(handler: StatementHandler) { statements.remove(handler) }
    @Synchronized override fun call(method: Method, args: Array<out Any?>): Any? {
        if (method.name == "close") { close(); return null }
        if (method.name == "isClosed") return closed || !sqlite.isOpen
        ensureOpen()
        return when (method.name) {
            "getAutoCommit" -> automatic
            "setAutoCommit" -> {
                val next = args[0] as Boolean
                if (next != automatic) {
                    if (next) finish(true)
                    automatic = next
                }
                null
            }
            "commit" -> { if (automatic) throw SQLException("Commit requires a transaction"); finish(true); null }
            "rollback" -> { if (automatic) throw SQLException("Rollback requires a transaction"); finish(false); null }
            "createStatement", "prepareStatement" -> {
                if ((method.name == "createStatement" && args.isNotEmpty()) ||
                    (method.name == "prepareStatement" && args.size != 1)) unsupported(method)
                val handler = StatementHandler(this, args.firstOrNull() as? String)
                statements += handler
                if (method.name == "prepareStatement") proxy(PreparedStatement::class.java, handler)
                else proxy(Statement::class.java, handler)
            }
            "isValid" -> true
            "getTransactionIsolation" -> Connection.TRANSACTION_SERIALIZABLE
            "getWarnings" -> null
            "clearWarnings" -> null
            "isReadOnly" -> sqlite.isReadOnly
            "nativeSQL" -> args[0] as String
            else -> unsupported(method)
        }
    }
}

private class StatementHandler(private val owner: ConnectionHandler, private val sql: String?) : JdbcHandler() {
    private var closed = false
    private val bindings = sortedMapOf<Int, Any?>()
    private var result: ResultSet? = null
    private var updateCount = -1

    fun close() {
        if (closed) return
        try { result?.close() } finally { result = null; closed = true; owner.release(this) }
    }
    private fun ensureOpen() { owner.ensureOpen(); if (closed) throw SQLException("Local statement is closed") }
    private fun parameters(): List<Any?> {
        if (bindings.isEmpty()) return emptyList()
        val size = bindings.lastKey()
        if ((1..size).any { !bindings.containsKey(it) }) throw SQLException("An SQL parameter is missing")
        return (1..size).map { bindings[it] }
    }
    private fun executeQuery(source: String): ResultSet {
        ensureOpen(); result?.close(); owner.beforeSql()
        val values = parameters()
        val cursor = owner.sqlite.rawQueryWithFactory({ _, driver, editTable, query ->
            query.bindParameters(values)
            SQLiteCursor(driver, editTable, query)
        }, source, emptyArray(), "")
        return proxy(ResultSet::class.java, ResultHandler(cursor)).also { result = it; updateCount = -1 }
    }
    private fun executeUpdate(source: String): Int {
        ensureOpen(); result?.close(); result = null; owner.beforeSql()
        owner.sqlite.compileStatement(source).use { statement ->
            statement.bindParameters(parameters())
            updateCount = statement.executeUpdateDelete()
        }
        return updateCount
    }
    override fun call(method: Method, args: Array<out Any?>): Any? {
        if (method.name == "close") { close(); return null }
        if (method.name == "isClosed") return closed
        ensureOpen()
        return when (method.name) {
            "setObject", "setString", "setLong", "setInt", "setDouble", "setFloat", "setBoolean", "setBytes", "setNull" -> {
                if (sql == null || args.size < 2) unsupported(method)
                val index = args[0] as Int
                if (index < 1) throw SQLException("SQL parameter indices start at one")
                val value = if (method.name == "setNull") null else args[1]
                requireBinding(value)
                bindings[index] = (value as? ByteArray)?.copyOf() ?: value
                null
            }
            "clearParameters" -> { bindings.clear(); null }
            "executeQuery" -> executeQuery(sql ?: args.single() as String)
            "executeUpdate" -> executeUpdate(sql ?: args.single() as String)
            "execute" -> {
                val source = sql ?: args.single() as String
                val leading = source.trimStart().substringBefore(' ').substringBefore('(').uppercase()
                if (leading in setOf("SELECT", "PRAGMA", "WITH", "EXPLAIN")) {
                    val rows = executeQuery(source)
                    // Android SQLite executes a PRAGMA assignment when its cursor is stepped.
                    if (leading == "PRAGMA") { rows.next(); rows.close(); result = null; false } else true
                } else { executeUpdate(source); false }
            }
            "getResultSet" -> result
            "getUpdateCount" -> updateCount
            "getConnection" -> owner.connection
            "getWarnings" -> null
            "clearWarnings" -> null
            else -> unsupported(method)
        }
    }
}

/** Android defaults unbound arguments to NULL; the repositories require JDBC's missing-bind error. */
private fun SQLiteProgram.bindParameters(values: List<Any?>) {
    values.forEachIndexed { index, value -> bindValue(index + 1, value) }
    // Ask the compiled native program whether another parameter exists. This respects quoted
    // question marks and comments without attempting to parse SQL or accessing hidden APIs.
    val missing = try {
        bindNull(values.size + 1)
        true
    } catch (_: SQLiteBindOrColumnIndexOutOfRangeException) {
        false
    } catch (_: IllegalArgumentException) {
        // Framework SQLiteProgram checks this before JNI on newer Android releases.
        false
    }
    if (missing) throw SQLException("An SQL parameter is missing")
}

private fun requireBinding(value: Any?) {
    if (value != null && value !is String && value !is ByteArray && value !is Boolean &&
        value !is Byte && value !is Short && value !is Int && value !is Long && value !is Float && value !is Double)
        throw SQLException("Unsupported local SQL parameter type")
    if ((value is Double && !value.isFinite()) || (value is Float && !value.isFinite()))
        throw SQLException("SQL numeric parameters must be finite")
}

private fun SQLiteProgram.bindValue(index: Int, value: Any?) {
    requireBinding(value)
    when (value) {
        null -> bindNull(index)
        is ByteArray -> bindBlob(index, value)
        is String -> bindString(index, value)
        is Boolean -> bindLong(index, if (value) 1 else 0)
        is Byte, is Short, is Int, is Long -> bindLong(index, (value as Number).toLong())
        is Float, is Double -> bindDouble(index, (value as Number).toDouble())
    }
}

private class ResultHandler(private val cursor: Cursor) : JdbcHandler() {
    private var closed = false
    private var lastNull = false
    private val names = cursor.columnNames
    private fun column(value: Any?): Int = when (value) {
        is Int -> (value - 1).also { if (it !in names.indices) throw SQLException("SQL column is outside the result") }
        is String -> names.indexOfFirst { it.equals(value, ignoreCase = true) }.also { if (it < 0) throw SQLException("SQL column was not found") }
        else -> throw SQLException("SQL column must be a name or one-based index")
    }
    private fun ensureOpen() { if (closed || cursor.isClosed) throw SQLException("Local result is closed") }
    override fun call(method: Method, args: Array<out Any?>): Any? {
        if (method.name == "close") { if (!closed) { closed = true; cursor.close() }; return null }
        if (method.name == "isClosed") return closed || cursor.isClosed
        ensureOpen()
        return when (method.name) {
            "next" -> cursor.moveToNext()
            "wasNull" -> lastNull
            "findColumn" -> column(args[0]) + 1
            "getMetaData" -> proxy(ResultSetMetaData::class.java, object : JdbcHandler() {
                override fun call(method: Method, args: Array<out Any?>): Any? = when (method.name) {
                    "getColumnCount" -> names.size
                    "getColumnName", "getColumnLabel" -> names[column(args[0])]
                    else -> unsupported(method)
                }
            })
            "getString", "getInt", "getLong", "getDouble", "getFloat", "getBoolean", "getBytes", "getObject" -> {
                if (args.size != 1) unsupported(method)
                if (cursor.position !in 0 until cursor.count) throw SQLException("Move to a result row before reading it")
                val index = column(args[0]); lastNull = cursor.isNull(index)
                when (method.name) {
                    "getString" -> if (lastNull) null else cursor.getString(index)
                    "getInt" -> if (lastNull) 0 else cursor.getInt(index)
                    "getLong" -> if (lastNull) 0L else cursor.getLong(index)
                    "getDouble" -> if (lastNull) 0.0 else cursor.getDouble(index)
                    "getFloat" -> if (lastNull) 0f else cursor.getFloat(index)
                    "getBoolean" -> !lastNull && cursor.getLong(index) != 0L
                    "getBytes" -> if (lastNull) null else cursor.getBlob(index)
                    else -> when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                        else -> cursor.getString(index)
                    }
                }
            }
            else -> unsupported(method)
        }
    }
}
