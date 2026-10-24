package com.claxe.sorune

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why Sorune closed last time, so it can be reported. Kotlin crashes are written down as they
 * happen; anything else (a crash inside WebView, the phone stopping a frozen app) comes from the
 * system's own record of how the app last ended (Android 11 and newer).
 */
object CrashReport {
    private const val FILE = "crash.txt"
    private const val SEEN = "crash-seen"

    /** Writes uncaught Kotlin errors down before the app closes. */
    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(header() + "Hata (${thread.name}):\n" + error.stackTraceToString().take(8000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of the last unexpected close, once; null if Sorune ended normally. */
    fun takePending(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            file.delete()
            markSystemRecordSeen(context)
            if (!text.isNullOrBlank()) return text
        }
        return systemRecord(context)
    }

    private fun header(): String =
        "Sorune ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Zaman: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n"

    /** Native crashes and freezes: the system remembers how the process last ended. */
    private fun systemRecord(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val prefs = context.getSharedPreferences("sorune-crash", Context.MODE_PRIVATE)
        if (prefs.getLong(SEEN, 0) >= info.timestamp) return null
        prefs.edit().putLong(SEEN, info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Çöktü"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Çöktü (WebView ya da sistem kodu)"
            ApplicationExitInfo.REASON_ANR -> "Donup yanıt vermedi"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Telefonun belleği yetmedi"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Çok fazla kaynak kullandı"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Başlatılamadı"
            else -> return null // closed by the user, by the system in the background, by an update...
        }
        val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
        return header() + "Kapanma: $time\nNeden: $reason\n${info.description.orEmpty()}\n\n" + trace.take(8000)
    }

    private fun markSystemRecordSeen(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val info = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return
        context.getSharedPreferences("sorune-crash", Context.MODE_PRIVATE).edit().putLong(SEEN, info.timestamp).apply()
    }
}
