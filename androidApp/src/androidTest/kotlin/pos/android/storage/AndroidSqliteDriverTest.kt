package pos.android.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSqliteDriverTest {
    @Test fun missingArgumentsCannotSilentlyBecomeNullQueriesOrFinancialWrites() {
        AndroidSqliteDriver.open(":memory:").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE balances(id TEXT PRIMARY KEY,amount INTEGER,reference TEXT)") }
            db.prepareStatement("INSERT INTO balances VALUES(?,?,?)").use { write ->
                write.setString(1,"receipt"); write.setLong(2,12345)
                assertThrows(SQLException::class.java) { write.executeUpdate() }
                write.setNull(3,java.sql.Types.VARCHAR)
                assertEquals(1,write.executeUpdate())
                write.clearParameters()
                assertThrows(SQLException::class.java) { write.executeUpdate() }
            }
            db.prepareStatement("SELECT COUNT(*) FROM balances WHERE reference IS ?").use { read ->
                assertThrows(SQLException::class.java) { read.executeQuery() }
                read.setNull(1,java.sql.Types.VARCHAR)
                read.executeQuery().use { rows ->assertTrue(rows.next());assertEquals(1L,rows.getLong(1)) }
                read.clearParameters()
                assertThrows(SQLException::class.java) { read.executeQuery() }
            }
            // The native parameter count ignores question marks in actual data and SQL comments.
            db.prepareStatement("SELECT amount, '?' FROM balances WHERE id=? /* ? */").use { read ->
                read.setString(1,"receipt")
                read.executeQuery().use { rows ->assertTrue(rows.next());assertEquals(12345L,rows.getLong(1));assertEquals("?",rows.getString(2));assertFalse(rows.next()) }
            }
        }
    }

    @Test fun integerBlobNullAndMetadataRoundTripWithoutFloatingPointMoney() {
        AndroidSqliteDriver.open(":memory:").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE values_test(id INTEGER PRIMARY KEY, amount INTEGER NOT NULL, bytes BLOB, optional TEXT)") }
            val exact = 9_007_199_254_740_993L
            db.prepareStatement("INSERT INTO values_test VALUES(?,?,?,?)").use {
                it.setObject(1, 1); it.setObject(2, exact); it.setObject(3, byteArrayOf(0, 127, -1)); it.setObject(4, null)
                assertEquals(1, it.executeUpdate())
            }
            db.prepareStatement("SELECT id,amount,bytes,optional FROM values_test WHERE amount=?").use {
                it.setObject(1, exact)
                it.executeQuery().use { rows ->
                    assertEquals(4, rows.metaData.columnCount)
                    assertEquals("amount", rows.metaData.getColumnName(2))
                    assertTrue(rows.next())
                    assertEquals(exact, rows.getLong("amount"))
                    assertEquals(exact, rows.getObject("amount"))
                    assertArrayEquals(byteArrayOf(0, 127, -1), rows.getBytes(3))
                    assertNull(rows.getString("optional")); assertTrue(rows.wasNull())
                    assertEquals(1L, rows.getLong("id")); assertFalse(rows.wasNull())
                    assertFalse(rows.next())
                }
            }
            assertThrows(SQLFeatureNotSupportedException::class.java) { db.setReadOnly(true) }
        }
    }

    @Test fun failedFinancialWriteRollsBackWholeTransactionAndForeignKeysAreEnforced() {
        AndroidSqliteDriver.open(":memory:").use { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE parent(id TEXT PRIMARY KEY,amount INTEGER NOT NULL CHECK(amount>0))")
                it.execute("CREATE TABLE child(id TEXT PRIMARY KEY,parent_id TEXT NOT NULL REFERENCES parent(id))")
            }
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT INTO parent VALUES(?,?)").use { it.setObject(1, "receipt"); it.setObject(2, 12345L); it.executeUpdate() }
                assertThrows(SQLException::class.java) {
                    db.prepareStatement("INSERT INTO child VALUES(?,?)").use { it.setObject(1, "cash"); it.setObject(2, "missing"); it.executeUpdate() }
                }
                db.rollback()
            } finally { db.autoCommit = true }
            db.prepareStatement("SELECT COUNT(*) FROM parent").use { it.executeQuery().use { r -> assertTrue(r.next()); assertEquals(0L, r.getLong(1)) } }
            db.autoCommit = false
            db.prepareStatement("INSERT INTO parent VALUES(?,?)").use { it.setObject(1, "committed"); it.setObject(2, 500L); it.executeUpdate() }
            db.commit(); db.autoCommit = true
            db.prepareStatement("SELECT amount FROM parent").use { it.executeQuery().use { r -> assertTrue(r.next()); assertEquals(500L, r.getLong(1)) } }
        }
    }

    @Test fun realDatabasePersistsAcrossReopenAndUncommittedCloseDoesNotCommit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "sqlite-adapter-${System.nanoTime()}").apply { mkdirs() }
        val path = File(directory, "business.db").path
        try {
            AndroidSqliteDriver.open(path).use { db ->
                db.createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA synchronous=FULL"); it.execute("CREATE TABLE ledger(id INTEGER PRIMARY KEY,amount INTEGER)") }
                db.prepareStatement("INSERT INTO ledger VALUES(?,?)").use { it.setObject(1, 1); it.setObject(2, Long.MAX_VALUE); it.executeUpdate() }
                db.autoCommit = false
                db.prepareStatement("INSERT INTO ledger VALUES(?,?)").use { it.setObject(1, 2); it.setObject(2, 50); it.executeUpdate() }
            }
            AndroidSqliteDriver.open(path).use { db ->
                db.prepareStatement("SELECT id,amount FROM ledger").use { it.executeQuery().use { r ->
                    assertTrue(r.next()); assertEquals(1L, r.getLong(1)); assertEquals(Long.MAX_VALUE, r.getLong(2)); assertFalse(r.next())
                } }
            }
        } finally { directory.deleteRecursively() }
    }
}
