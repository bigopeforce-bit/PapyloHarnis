package com.androidharness.app.tools

import android.content.Context
import com.androidharness.app.agent.CrashLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Lets the agent read O2OAgent's persisted crash reports to fix its own errors. */
class CrashLogTool(private val context: Context) : Tool {
    override val name = "o2o_crash_log"
    override val description =
        "Read O2OAgent's own persisted crash reports (stack traces saved when the app crashed), newest first. " +
            "Use this together with read_logcat to diagnose and fix bugs in O2OAgent itself. Reports survive " +
            "app restarts and logcat rotation."
    override val parametersSchema = Schema.obj(mapOf(
        "limit" to Schema.integer("How many recent crash reports to return; default 2, max 5."),
    ))
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 5)
        val text = CrashLog.latest(context, limit)
        // The agent has now seen the reports; stop injecting the crash directive next run.
        CrashLog.markHandled(context)
        return ToolResult(true, text)
    }
}
