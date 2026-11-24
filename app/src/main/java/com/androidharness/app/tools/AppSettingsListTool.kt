package com.androidharness.app.tools

import com.androidharness.app.data.AgentSettingsAccess
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.settings.AgentSettingsCatalog
import com.androidharness.app.ui.settings.SettingsPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lists every setting the agent can change, grouped by page, with its current
 * value. Keeps discovery reliable when a keyword search would miss a setting
 * or when the whole catalogue does not fit in one answer.
 */
internal class AppSettingsListTool(private val settings: suspend () -> AppSettings) : Tool {
    override val name = NAME
    override val description = "List O2OAgent's own settings with their current values, grouped by page. " +
        "Use this to see what the agent can change about itself, to check the result after app_settings_change, " +
        "or when app_settings_find returns no match. Optionally filter by page name and page through the list. " +
        "Use app_settings_change to actually change a value."
    override val parametersSchema = Schema.obj(mapOf(
        "page" to Schema.string("Optional settings page name, e.g. AGENT or APPEARANCE. Leave empty for every page."),
        "offset" to Schema.integer("First setting to show, starting at 0."),
        "limit" to Schema.integer("How many settings to show, up to 100."),
    ))
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val current = settings()
        val pageName = args["page"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase().takeUnless { it.isNullOrEmpty() }
        val page = pageName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        if (pageName != null && page == null) {
            return ToolResult(false, "Unknown settings page \"$pageName\". Pages: ${SettingsPage.entries.joinToString { it.name }}.")
        }
        val offset = (args["offset"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = ((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 100).coerceIn(1, 100)
        val all = AgentSettingsCatalog.all.filter { page == null || it.page == page }
        val shown = all.drop(offset).take(limit)
        val text = buildString {
            appendLine("O2OAgent settings: ${all.size} total, showing ${shown.size} from $offset.")
            shown.groupBy { it.page }.forEach { (group, items) ->
                appendLine("[$group]")
                items.forEach { appendLine("- ${it.describe(current)}") }
            }
            if (offset + shown.size < all.size) appendLine("More: call app_settings_list again with offset ${offset + shown.size}.")
            when (current.agentSettingsAccess) {
                AgentSettingsAccess.OFF -> appendLine("The user turned off settings changes by the agent. Only explain how to change them on the page.")
                AgentSettingsAccess.ASK -> appendLine("The user approves every change in the chat.")
                AgentSettingsAccess.ALLOWED -> Unit
            }
            appendLine("Pages for needs_user: ${SettingsPage.entries.joinToString { it.name }}.")
        }.trim()
        return ToolResult(true, text)
    }

    companion object { const val NAME = "app_settings_list" }
}
