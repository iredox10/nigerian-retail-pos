package pos.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WorkspaceAccessTest {
    @Test fun ownerPasswordPersistsAndWrongPasswordCannotOpenShop() {
        val directory = Files.createTempDirectory("pos-access-")
        try {
            WorkspaceAccess(directory).register("shop-a", "correct horse battery")
            val reopened = WorkspaceAccess(directory)
            assertTrue(reopened.unlock("shop-a", "correct horse battery"))
            assertFalse(reopened.unlock("shop-a", "wrong password"))
            assertFalse(reopened.unlock("shop-b", "correct horse battery"))
            assertFalse(reopened.unlock("shop-a", ""))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun registrationNeverReplacesExistingOwner() {
        val directory = Files.createTempDirectory("pos-access-")
        try {
            val gate = WorkspaceAccess(directory)
            gate.register("shop-a", "original password")
            assertFailsWith<IllegalStateException> { gate.register("shop-a", "replacement password") }
            assertTrue(gate.unlock("shop-a", "original password"))
            assertFalse(gate.unlock("shop-a", "replacement password"))
            assertFailsWith<IllegalArgumentException> { gate.register("shop-b", "short") }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun credentialsContainNoPlaintextAndCorruptionFailsClosed() {
        val directory = Files.createTempDirectory("pos-access-")
        try {
            val gate = WorkspaceAccess(directory)
            gate.register("shop-a", "private owner password")
            val record = Files.list(directory.resolve("credentials")).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".credential") }.findFirst().orElseThrow()
            }
            assertFalse(Files.readString(record).contains("private owner password"))
            Files.writeString(record, "invalid")
            assertFalse(gate.unlock("shop-a", "private owner password"))
        } finally { directory.toFile().deleteRecursively() }
    }
}
