package com.velastudio.teltv.util

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches what would otherwise be a silent process death.
 *
 * On a phone you'd just pull the crash from Play Console or a connected debugger. Most Android
 * TV boxes in the wild have neither -- the launcher just relaunches the app and whatever caused
 * it is gone. So: write the last crash to a plain file under `filesDir` (pullable via
 * `adb shell run-as com.velastudio.teltv cat files/last_crash.txt`, or just `adb pull` if the
 * device is debuggable) *before* letting the default handler proceed to actually kill the
 * process.
 */
object CrashLogger {

    private const val CRASH_FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Timber.e(throwable, "Uncaught exception on thread ${thread.name}")
                writeCrashFile(appContext, thread, throwable)
            } catch (loggingFailure: Throwable) {
                // Never let a failure in the crash logger itself swallow the original crash.
                Log.e("CrashLogger", "Failed to persist crash report", loggingFailure)
            } finally {
                // Preserve default behavior (process death, ANR dialogs suppressed correctly,
                // etc.) instead of swallowing the crash -- we're only observing it here.
                defaultHandler?.uncaughtException(thread, throwable)
                    ?: run {
                        Runtime.getRuntime().exit(10)
                    }
            }
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = "Crash at $timestamp on thread ${thread.name}\n$stackTrace"
        File(context.filesDir, CRASH_FILE_NAME).writeText(report)
    }

    /** Last persisted crash report, if any -- handy to surface in a debug/settings screen. */
    fun lastCrashReport(context: Context): String? =
        File(context.filesDir, CRASH_FILE_NAME).takeIf { it.exists() }?.readText()

    /**
     * Minimal release Tree: keeps Timber calls cheap and safe in production builds (no verbose
     * spam, no crash-reporter dependency required) while still funnelling WARN/ERROR into
     * logcat in case a debugger is attached. Swap the `Log.println` call for a real crash
     * reporter's log-breadcrumb API if one gets added later.
     */
    class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            Log.println(priority, tag ?: "TelTV", message)
        }
    }
}
