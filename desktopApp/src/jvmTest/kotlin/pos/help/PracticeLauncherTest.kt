package pos.help

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PracticeLauncherTest {
    @Test fun inheritedPreviewRoleAndScreenCannotChangeMerchantPracticeEntry() {
        val environment = mutableMapOf("POS_PREVIEW_ROLE" to "Cashier", "POS_PREVIEW_SCREEN" to "Backup", "POS_PREVIEW_IDLE_MS" to "1000", "POS_PRACTICE_READY_FILE" to "/old", "DISPLAY" to ":97")
        configurePracticeEnvironment(environment, Path.of("/tmp/new-practice/ready"))
        assertFalse(environment.containsKey("POS_PREVIEW_ROLE"))
        assertFalse(environment.containsKey("POS_PREVIEW_SCREEN"))
        assertFalse(environment.containsKey("POS_PREVIEW_IDLE_MS"))
        assertEquals("/tmp/new-practice/ready", environment["POS_PRACTICE_READY_FILE"])
        assertEquals("1", environment["POS_PRACTICE_CHILD"])
        assertEquals(":97", environment["DISPLAY"])
    }

    @Test fun installedLauncherUsesPracticeFlagWithoutRequiringAnExternalJavaRuntime() {
        val launcher = Files.createTempFile("installed-pos-", ".exe")
        try {
            assertEquals(listOf(launcher.toString(), "--practice"), practiceCommand(launcher.toString(), "/missing/runtime", ""))
        } finally { Files.delete(launcher) }
        assertFailsWith<IllegalArgumentException> { practiceCommand("/missing/retail-pos", "/missing/runtime", "") }
    }

    @Test fun developmentLauncherTargetsOnlyTheIsolatedPreviewEntry() {
        val command = practiceCommand(null)
        assertEquals("pos.PreviewKt", command.last())
        assertEquals("-cp", command[1])
        assertFalse(command.any { it.contains("pos.dataDir") || it.contains("pos.MainKt") })
    }

    @Test fun readinessIsRequiredAndDuplicateLaunchDoesNotStartAnotherProcess() {
        var starts = 0
        val child = FakeProcess()
        var launchDirectory: Path? = null
        val launcher = PracticeLauncher({ builder ->
            starts++
            val ready = Path.of(builder.environment().getValue("POS_PRACTICE_READY_FILE"))
            launchDirectory = ready.parent
            Files.writeString(ready, "ready")
            child
        }, timeoutMillis = 100)
        try {
            assertEquals(PracticeLaunchResult.STARTED, launcher.launch())
            assertEquals(PracticeLaunchResult.ALREADY_RUNNING, launcher.launch())
            assertEquals(1, starts)
        } finally { launcher.close() }
        assertFalse(child.isAlive)
        assertFalse(Files.exists(launchDirectory))
    }

    @Test fun startupFailureOrTimeoutCannotReportAReadyPracticeWindow() {
        for (alive in listOf(false, true)) {
            val child = FakeProcess(alive)
            val launcher = PracticeLauncher({ child }, timeoutMillis = 0)
            try {
                assertFailsWith<IllegalStateException> { launcher.launch() }
                assertFalse(child.isAlive)
            } finally { launcher.close() }
        }
    }

    private class FakeProcess(private var running: Boolean = true) : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = running
        override fun waitFor(): Int { running = false; return 0 }
        override fun exitValue(): Int { check(!running); return 0 }
        override fun destroy() { running = false }
    }
}
