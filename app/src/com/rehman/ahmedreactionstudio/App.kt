package com.rehman.ahmedreactionstudio

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide safety net.
 *
 * Before this existed, ANY uncaught exception — a Camera2 framework thread
 * failure, an NDK SIGSEGV in a media codec, a device ROM quirk — killed the
 * process instantly with only logcat (which a normal user never sees) to
 * explain it. "The app just crashes" is untriable from that state.
 *
 * This handler:
 *  1. writes the full stack trace to `filesDir/crashes/crash-<time>.txt` so
 *     the Diagnostics screen (and the user, sharing the file) can report
 *     exactly what died and on which thread;
 *  2. keeps a rolling window of the last 6 crash logs so repeated crashes
 *     don't fill storage;
 *  3. then hands the throwable back to the platform default handler, so the
 *     process still dies deterministically (we never try to "carry on" in an
 *     unknown state — that is how files get corrupted) — but the evidence
 *     survives.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                writeCrash(thread, ex)
            } catch (_: Throwable) {
                // the crash handler itself must never throw
            }
            previous?.uncaughtException(thread, ex)
                ?: Process.killProcess(Process.myPid())
        }
    }

    private fun writeCrash(thread: Thread, ex: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("Ahmed Reaction Studio — uncaught exception")
        pw.println("time:     ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        pw.println("android:  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        pw.println("device:   ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("thread:   ${thread.name} (id ${thread.id})")
        pw.println("process:  ${applicationContext.packageName}")
        pw.println()
        ex.printStackTrace(pw)
        val cause = ex.cause
        if (cause != null && cause !== ex) {
            pw.println()
            pw.println("caused by:")
            cause.printStackTrace(pw)
        }
        pw.flush()
        val dir = File(filesDir, "crashes")
        dir.mkdirs()
        val f = File(dir, "crash-${System.currentTimeMillis()}.txt")
        f.writeText(sw.toString())
        Log.e(TAG, "uncaught on ${thread.name}", ex)
        // keep only the newest few
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_LOGS)
            ?.forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val TAG = "AhmedStudio"
        private const val MAX_CRASH_LOGS = 6

        /** Crash log files newest-first (empty when none). */
        fun crashLogs(ctx: android.content.Context): List<File> {
            val dir = File(ctx.filesDir, "crashes")
            return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }
}
