package com.androidharness.app.data.env

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Enables O2OAgent's own AccessibilityService. A Device Owner shell (Dhizuku)
 * runs with WRITE_SECURE_SETTINGS, so it can flip the secure settings directly;
 * otherwise the user is guided to Android's accessibility settings.
 */
object AccessibilityEnabler {

    private const val SERVICE_CLASS = "com.androidharness.app.tools.O2oAccessibilityService"

    fun component(context: Context): String = "${context.packageName}/$SERVICE_CLASS"

    fun isEnabled(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val list = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled && list.split(':').any { it.equals(component(context), ignoreCase = true) }
    }.getOrDefault(false)

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Writes the secure settings through the Device Owner shell. Never falls back silently. */
    suspend fun enableViaDeviceOwner(manager: DhizukuManager, context: Context): Result<String> = runCatching {
        check(manager.state.value.status == DhizukuStatus.GRANTED) {
            "Device Owner access is not granted. Grant it in the Device Owner step first."
        }
        val raw = component(context)
        val quoted = "'" + raw.replace("'", "'\\''") + "'"
        val command = buildString {
            append("c=").append(quoted).append(';')
            append("cur=\$(/system/bin/settings get secure enabled_accessibility_services 2>/dev/null);")
            append("[ \"\$cur\" = \"null\" ] && cur=;")
            append("case \"\$cur\" in")
            append(" *").append(quoted).append("*) ;;")
            append(" *) if [ -n \"\$cur\" ]; then /system/bin/settings put secure enabled_accessibility_services \"\$cur:\$c\";")
            append(" else /system/bin/settings put secure enabled_accessibility_services \"\$c\"; fi ;;")
            append("esac;")
            append("/system/bin/settings put secure accessibility_enabled 1")
        }
        val result = manager.run(command, timeoutMs = 15_000)
        if (result.timedOut || result.exitCode != 0) {
            error("Device Owner could not enable accessibility (exit ${result.exitCode}): ${result.stderr.ifBlank { result.output }}")
        }
        "Accessibility service enabled through Device Owner. Waiting for it to connect."
    }
}
