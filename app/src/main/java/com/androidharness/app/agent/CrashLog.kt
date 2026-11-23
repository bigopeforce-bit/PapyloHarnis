package com.androidharness.app.agent

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists O2OAgent's own uncaught exceptions so the in-app agent can read them
 * later with the o2o_crash_log tool, even after logcat has rotated.
 */
object CrashLog {
    private const val MAX_REPORTS = 8
    private const val MAX_CHARS = 20_000

    fun reportsDir(context: Context): File = File(context.filesDir, "crashes").apply { mkdirs() }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(context, thread, throwable) }
            // Always preserve the platform's normal behaviour (force-stop / tombstone).
            runCatching { previous?.uncaughtException(thread, throwable) }
        }
    }

    fun latest(context: Context, limit: Int = 2): String {
        val files = reportsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (files.isEmpty()) return "No persisted O2OAgent crash reports. The app has not crashed since this feature was added."
        return buildString {
            append("${files.size} crash report(s) kept; showing ${limit.coerceAtMost(files.size)}.\n")
            files.take(limit.coerceAtLeast(1)).forEach { file ->
                append("\n===== ${file.name} =====\n")
                append(file.readText().take(MAX_CHARS))
                if (file.length() > MAX_CHARS) append("\n[truncated]")
            }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = reportsDir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        }.getOrDefault("unknown")
        val trace = StringWriter().use { sw ->
            throwable.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val body = buildString {
            append("O2OAgent crash\n")
            append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("version: ").append(version).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append(trace.take(MAX_CHARS))
        }
        File(dir, "crash-$stamp.txt").writeText(body)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)
            ?.forEach { runCatching { it.delete() } }
    }
}
