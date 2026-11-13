package pos.help

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

enum class PracticeLaunchResult { STARTED, ALREADY_RUNNING }

/** A separate process is essential: Preview sets a process-wide, temporary data directory. */
class PracticeLauncher(
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
    private val timeoutMillis: Long = 30_000,
) : AutoCloseable {
    @Volatile private var process: Process? = null
    private var directory: Path? = null
    private val shutdownHook = Thread({ close() }, "practice-shutdown")

    init { Runtime.getRuntime().addShutdownHook(shutdownHook) }

    @Synchronized fun launch(): PracticeLaunchResult {
        if (process?.isAlive == true) return PracticeLaunchResult.ALREADY_RUNNING
        cleanFiles()
        val temporary = Files.createTempDirectory("retail-pos-practice-launch-")
        directory = temporary
        val ready = temporary.resolve("ready")
        val builder = ProcessBuilder(practiceCommand())
        configurePracticeEnvironment(builder.environment(), ready)
        builder.redirectErrorStream(true)
        builder.redirectOutput(temporary.resolve("startup.log").toFile())
        try {
            val child = startProcess(builder)
            process = child
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (child.isAlive && System.nanoTime() < deadline) {
                if (Files.isRegularFile(ready)) return PracticeLaunchResult.STARTED
                Thread.sleep(50)
            }
            throw IllegalStateException("The practice window could not start. Your shop records have not been changed. Close other practice windows and try again.")
        } catch (failure: Exception) {
            stopProcess()
            cleanFiles()
            throw failure
        }
    }

    @Synchronized override fun close() {
        stopProcess()
        cleanFiles()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    private fun stopProcess() {
        process?.let { child ->
            if (child.isAlive) {
                child.destroy()
                if (!child.waitFor(2, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    child.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
        process = null
    }

    private fun cleanFiles() {
        directory?.let { path ->
            runCatching {
                Files.list(path).use { files -> files.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(path)
            }
        }
        directory = null
    }
}

internal fun practiceCommand(
    packagedLauncher: String? = System.getProperty("jpackage.app-path"),
    javaHome: String = System.getProperty("java.home"),
    classpath: String = System.getProperty("java.class.path"),
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
): List<String> {
    if (!packagedLauncher.isNullOrBlank()) {
        require(File(packagedLauncher).isFile) { "The installed practice launcher is unavailable. Reinstall the app and try again." }
        return listOf(packagedLauncher, "--practice")
    }
    val executable = File(javaHome, "bin/${if (windows) "java.exe" else "java"}")
    require(executable.isFile && classpath.isNotBlank()) { "The practice runtime is unavailable. Reinstall the app and try again." }
    return listOf(executable.absolutePath, "-cp", classpath, "pos.PreviewKt")
}

internal fun configurePracticeEnvironment(environment: MutableMap<String, String>, ready: Path) {
    environment.keys.filter { it.startsWith("POS_PREVIEW_") || it.startsWith("POS_PRACTICE_") }.forEach(environment::remove)
    environment["POS_PREVIEW_WIDTH"] = "1280"
    environment["POS_PREVIEW_HEIGHT"] = "900"
    environment["POS_PRACTICE_CHILD"] = "1"
    environment["POS_PRACTICE_READY_FILE"] = ready.toAbsolutePath().toString()
}
