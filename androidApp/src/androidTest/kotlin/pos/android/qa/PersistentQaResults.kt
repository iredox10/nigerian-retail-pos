package pos.android.qa

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** QA-only evidence survives a dropped adb output stream; no merchant values or credentials. */
class PersistentQaResults:RunListener() {
    private val runId=InstrumentationRegistry.getArguments().getString("qaRunId")
        ?.also {require(it.matches(Regex("[A-Za-z0-9-]{1,80}")))} ?: "unspecified"
    private val started=System.currentTimeMillis()
    private val failed=mutableListOf<String>()
    private val completed=mutableListOf<String>()
    private var assumptions=0
    private var wakeLock:android.os.PowerManager.WakeLock?=null
    @Suppress("DEPRECATION")
    override fun testRunStarted(description:Description) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".acceptance"))
        val power=context.getSystemService(android.os.PowerManager::class.java)
        wakeLock=power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"RetailPOS:physical-acceptance")
            .also {it.acquire(10*60*1000L)}
        write("running",null)
    }
    override fun testFailure(failure:Failure) {failed+=failure.description.displayName}
    override fun testAssumptionFailure(failure:Failure) {assumptions++}
    override fun testFinished(description:Description) {completed+=description.displayName}
    override fun testRunFinished(result:Result) {
        try {write("finished",result)} finally {wakeLock?.let {if(it.isHeld)it.release()};wakeLock=null}
    }
    private fun write(status:String,result:Result?) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".acceptance")) {"Persistent QA evidence requires the isolated acceptance package."}
        val directory=File(context.filesDir,"qa-results").apply {check(isDirectory || mkdirs())}
        val report=JSONObject().put("runId",runId).put("status",status).put("startedAtMillis",started)
            .put("package",context.packageName).put("androidApi",android.os.Build.VERSION.SDK_INT)
            .put("tests",result?.runCount ?: 0).put("failures",result?.failureCount ?: 0)
            .put("ignored",result?.ignoreCount ?: 0).put("assumptions",assumptions)
            .put("durationMillis",result?.runTime ?: 0).put("completed",JSONArray(completed))
            .put("failed",JSONArray(failed))
        val temp=File.createTempFile(".report-",".json",directory)
        try {
            FileOutputStream(temp).use {it.write(report.toString(2).toByteArray(Charsets.UTF_8));it.fd.sync()}
            Files.move(temp.toPath(),File(directory,"last-run.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {temp.delete()}
    }
}
