package com.oniel.music

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves crash details so the app can show them on the next launch
 * (Android's "app has a bug" dialog says nothing useful).
 *
 * - Kotlin/Java crashes: default uncaught-exception handler -> java.txt
 * - Go crashes inside built-in Tailscale: the Go runtime writes to go.txt
 *   (tsbridge.SetCrashFile)
 */
object CrashLog {
    private const val DIR = "crash"
    @Volatile private var installed = false

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    fun goCrashFile(context: Context): File = File(dir(context), "go.txt")

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(dir(appContext), "java.txt").writeText(
                    header(appContext) + "Thread: ${thread.name}\n\n" + trace
                )
            } catch (e: Throwable) {
                // Never let crash logging hide the crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash report(s), or null when there are none. */
    fun pending(context: Context): String? {
        val parts = listOf("java.txt", "go.txt").mapNotNull { name ->
            File(dir(context), name).takeIf { it.isFile && it.length() > 0 }?.readText()?.let {
                if (name == "go.txt") header(context) + "Go (Tailscale) crash:\n\n" + it.takeLast(12_000) else it
            }
        }
        return parts.joinToString("\n\n-----\n\n").ifBlank { null }
    }

    fun clear(context: Context) {
        File(dir(context), "java.txt").delete()
        File(dir(context), "go.txt").delete()
    }

    private fun header(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "SpinUs $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()} · $time\n"
    }
}
