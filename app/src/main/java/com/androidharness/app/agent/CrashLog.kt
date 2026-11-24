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
    private const val HANDLED_MARKER = "handled.marker"

    fun reportsDir(context: Context): File = reportsDir(context.filesDir)

    private fun reportsDir(filesDir: File): File = File(filesDir, "crashes").apply { mkdirs() }

    /**
     * The newest report written after the last time the agent read the crash
     * log. Used to inject a self-debug directive into the next run instead of
     * waiting for the user to notice a crash.
     */
    fun pendingReport(filesDir: File): File? {
        val dir = File(filesDir, "crashes")
        val handledAt = runCatching { File(dir, HANDLED_MARKER).readText().trim().toLong() }.getOrDefault(0L)
        return reports(dir).firstOrNull { it.lastModified() > handledAt }
    }

    fun pendingReport(context: Context): File? = pendingReport(context.filesDir)

    /** Called after the agent reads the crash log, so the directive stops nagging. */
    fun markHandled(filesDir: File) {
        val dir = reportsDir(filesDir)
        runCatching { File(dir, HANDLED_MARKER).writeText(System.currentTimeMillis().toString()) }
    }

    fun markHandled(context: Context) = markHandled(context.filesDir)

    fun pendingDirective(context: Context): String? = pendingReport(context)?.let { report ->
        "- O2OAgent has an unhandled crash report from a previous run (${report.name}). Before continuing, call o2o_crash_log, then load the o2oagent-self-debug skill and fix the cause."
    }

    private fun reports(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(context, thread, throwable) }
            // Always preserve the platform's normal behaviour (force-stop / tombstone).
            runCatching { previous?.uncaughtException(thread, throwable) }
        }
    }

    fun latest(context: Context, limit: Int = 2): String = latest(context.filesDir, limit)

    fun latest(filesDir: File, limit: Int = 2): String {
        val files = reports(File(filesDir, "crashes"))
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
