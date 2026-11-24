package com.androidharness.app.tools

import com.androidharness.app.data.AgentSettingsAccess
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.settings.AgentSettingsCatalog
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppSettingsListToolTest {
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject
    private val ctx = ToolContext(FileFs(File(".")))

    @Test fun listsEverySettingByDefault() = runBlocking {
        val tool = AppSettingsListTool { AppSettings() }
        val result = tool.execute(args("{}"), ctx)
        assertTrue(result.ok)
        assertTrue(result.output.contains("O2OAgent settings: ${AgentSettingsCatalog.all.size} total"))
        AgentSettingsCatalog.all.forEach { assertTrue("missing ${it.key}", result.output.contains(it.key)) }
    }

    @Test fun filtersByPageAndPaginates() = runBlocking {
        val tool = AppSettingsListTool { AppSettings() }
        val page = tool.execute(args("{\"page\":\"AGENT\"}"), ctx)
        assertTrue(page.ok)
        assertTrue(page.output.contains("[AGENT]"))
        assertFalse(page.output.contains("[APPEARANCE]"))
        val batch = tool.execute(args("{\"offset\":2,\"limit\":3}"), ctx)
        assertTrue(batch.ok)
        assertTrue(batch.output.contains("showing 3 from 2"))
        assertTrue(batch.output.contains("More: call app_settings_list again with offset 5."))
    }

    @Test fun reportsAccessModeAndRejectsUnknownPage() = runBlocking {
        val blocked = AppSettingsListTool { AppSettings(agentSettingsAccess = AgentSettingsAccess.OFF) }
        assertTrue(blocked.execute(args("{}"), ctx).output.contains("turned off settings changes"))
        val invalid = AppSettingsListTool { AppSettings() }.execute(args("{\"page\":\"NOPE\"}"), ctx)
        assertFalse(invalid.ok)
        assertTrue(invalid.output.contains("Unknown settings page"))
    }
}
