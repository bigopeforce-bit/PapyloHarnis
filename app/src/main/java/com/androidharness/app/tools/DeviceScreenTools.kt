package com.androidharness.app.tools

import android.content.Context
import android.graphics.BitmapFactory
import com.androidharness.app.core.ImageRef
import com.androidharness.app.data.ImageStore
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

data class DeviceCommandResult(
    val output: String,
    val tier: com.androidharness.app.data.env.ExecutionTier,
) {
    val receipt: com.androidharness.app.data.env.ExecutionReceipt
        get() = com.androidharness.app.data.env.ExecutionReceipt(
            when (tier) {
                com.androidharness.app.data.env.ExecutionTier.ROOT -> com.androidharness.app.data.env.DeviceBackend.ROOT
                com.androidharness.app.data.env.ExecutionTier.PRIVILEGED -> com.androidharness.app.data.env.DeviceBackend.SHIZUKU
                else -> com.androidharness.app.data.env.DeviceBackend.NORMAL
            }
        )
}

/**
 * Shared privileged-shell execution for the device control tools. All commands
 * are built here from validated arguments, never passed through as raw text,
 * so the generic ShellPolicy path-containment rules (which target user-typed
 * workspace commands and reject /sdcard) do not apply to these fixed commands.
 */
class DeviceShell(private val router: ShellTierRouter) {
    suspend fun run(command: String, timeoutMs: Int = 20_000, maxOutput: Int = 120_000): DeviceCommandResult =
        withContext(Dispatchers.IO) {
            val result = router.run(command, File("/"), timeoutMs, maxOutput)
            val body = result.rawOutput.ifBlank { result.rawStderr }
            if (result.exitCode != 0 && body.isBlank()) {
                throw ToolFailure(
                    "Device command failed (exit ${result.exitCode}). " +
                        "A privileged shell is required: start Shizuku, connect Wireless ADB, or grant root/Device Owner in Settings → Terminal. " +
                        (result.note ?: "")
                )
            }
            DeviceCommandResult(buildString {
                if (result.note != null) append(result.note).append('\n')
                append(body)
            }, result.tier)
        }
}

/** Reads the current Android screen as a numbered UI hierarchy the agent can tap. */
class DeviceScreenTool(
    private val router: ShellTierRouter,
    private val context: Context,
    private val imageStore: ImageStore? = null,
    private val shell: DeviceShell = DeviceShell(router),
    private val accessibility: () -> O2oAccessibilityService? = { O2oAccessibilityService.instance },
) : Tool {
    override val name = "device_screen"
    override val description =
        "Read the phone's current screen as a numbered UI hierarchy (text, content descriptions, element ids, " +
            "bounds and click/scroll flags). Prefers O2OAgent's own AccessibilityService (no shell needed); " +
            "falls back to uiautomator through a privileged shell (Shizuku, Wireless ADB, root or Device Owner). " +
            "Open the target app first with device_input open_app, because this reads whatever is currently on " +
            "screen. Set include_screenshot=true to attach an image as well when the text alone is not enough."
    override val parametersSchema = Schema.obj(
        mapOf(
            "include_screenshot" to Schema.boolean("Also capture and attach a screenshot image. Default false."),
            "max_nodes" to Schema.integer("Maximum elements to return; default 300, max 800."),
        ),
    )
    override val isReadOnly = true
    override fun isAvailable(ctx: ToolContext) = ctx.workspace !is com.androidharness.app.workspace.SshFs

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val maxNodes = (args["max_nodes"]?.jsonPrimitive?.intOrNull ?: 300).coerceIn(1, 800)
        val service = accessibility()
        if (service != null) {
            val listing = service.readScreen(maxNodes)
            val store = imageStore
            return if (args["include_screenshot"]?.jsonPrimitive?.booleanOrNull == true && store != null) {
                val shot = runCatching { captureScreenshot(context, store, shell) }.getOrNull()
                val note = if (shot == null) "\n[screenshot unavailable: image capture still needs a privileged shell]" else ""
                ToolResult(true, listing + note, image = shot)
            } else {
                ToolResult(true, listing)
            }
        }
        // Shell-owned scratch: the shell uid writes the dump and cats it back as
        // text. No TTY and no app-readable file path are needed.
        val dump = "/data/local/tmp/.o2o-window-${UUID.randomUUID()}.xml"
        val command = "/system/bin/sh -c " + shellQuote(
            "/system/bin/uiautomator dump ${shellQuote(dump)} >/dev/null 2>&1; /system/bin/cat ${shellQuote(dump)}; /system/bin/rm -f ${shellQuote(dump)}"
        )
        val result = shell.run(command, timeoutMs = 30_000, maxOutput = 400_000)
        val listing = ScreenXmlParser.parse(result.output, maxNodes)
        val store = imageStore
        return if (args["include_screenshot"]?.jsonPrimitive?.booleanOrNull == true && store != null) {
            val shot = runCatching { captureScreenshot(context, store, shell) }.getOrNull()
            val note = if (shot == null) "\n[screenshot unavailable: grant Storage access or check the privileged shell]" else ""
            ToolResult(true, listing + note, image = shot, execution = listOf(result.receipt))
        } else {
            ToolResult(true, listing, execution = listOf(result.receipt))
        }
    }
}

/** Captures the phone screen and returns it as a chat image the model can see. */
class DeviceScreenshotTool(
    private val router: ShellTierRouter,
    private val context: Context,
    private val imageStore: ImageStore? = null,
    private val shell: DeviceShell = DeviceShell(router),
) : Tool {
    override val name = "device_screenshot"
    override val description =
        "Take a screenshot of the phone screen and attach it as an image to the chat, for visual understanding " +
            "of layouts, icons, maps, images, or anything the UI text does not describe. Image capture needs a " +
            "privileged shell (Shizuku, Wireless ADB, root or Device Owner); the AccessibilityService only reads " +
            "the UI tree, not pixels. Secure screens may block capture."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true
    override fun isAvailable(ctx: ToolContext) = ctx.workspace !is com.androidharness.app.workspace.SshFs

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (imageStore == null) return ToolResult(false, "Image storage is not available in this run.")
        return try {
            val ref = captureScreenshot(context, imageStore, shell)
                ?: return ToolResult(false, "Screenshot capture produced no readable image. Secure screens and the lock screen block capture.")
            ToolResult(true, "Screenshot captured: ${ref.name} (${ref.bytes ?: 0} bytes). Attached below.", image = ref)
        } catch (e: ToolFailure) {
            ToolResult(false, e.message ?: "Screenshot failed.")
        } catch (e: Exception) {
            ToolResult(false, "Screenshot failed: ${e.message}")
        }
    }
}

/** Taps, swipes, types, presses keys and opens apps on the real phone. */
class DeviceInputTool(
    private val router: ShellTierRouter,
    private val shell: DeviceShell = DeviceShell(router),
    private val accessibility: () -> O2oAccessibilityService? = { O2oAccessibilityService.instance },
) : Tool {
    override val name = "device_input"
    override val description =
        "Act on the phone screen: tap, long-press, swipe, type text, press a system key, or open an app. " +
            "Use device_screen first to find coordinates. Coordinates are physical screen pixels from the " +
            "@x,y shown by device_screen. Prefers O2OAgent's own AccessibilityService; falls back to a privileged " +
            "shell (Shizuku, Wireless ADB, root or Device Owner) for gestures and key events accessibility cannot " +
            "perform. This controls the real device and follows the app's permission mode and approvals."
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("Required: tap, long_press, swipe, text, key, press, or open_app."),
            "x" to Schema.integer("X coordinate in screen pixels for tap/long_press."),
            "y" to Schema.integer("Y coordinate in screen pixels for tap/long_press."),
            "x2" to Schema.integer("End X for swipe."),
            "y2" to Schema.integer("End Y for swipe."),
            "duration_ms" to Schema.integer("Swipe duration in milliseconds; default 300."),
            "text" to Schema.string("Text to type for action=text."),
            "key" to Schema.string("Key name for action=key: home, back, recents, notifications, enter, delete, volume_up, volume_down."),
            "package" to Schema.string("Package name for action=open_app."),
        ),
        required = listOf("action"),
    )
    override val isReadOnly = false
    override fun isAvailable(ctx: ToolContext) = ctx.workspace !is com.androidharness.app.workspace.SshFs

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content?.trim()?.lowercase()
            ?: throw ToolFailure("Missing required argument: action")
        val command = when (action) {
            "tap" -> "/system/bin/input tap ${intArg(args, "x")} ${intArg(args, "y")}"
            "long_press" -> {
                val x = intArg(args, "x"); val y = intArg(args, "y")
                "/system/bin/input swipe $x $y $x $y 800"
            }
            "swipe" -> "/system/bin/input swipe ${intArg(args, "x")} ${intArg(args, "y")} " +
                "${intArg(args, "x2")} ${intArg(args, "y2")} " +
                (args["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300).coerceIn(50, 3000)
            "text" -> {
                val text = args["text"]?.jsonPrimitive?.content.orEmpty()
                if (text.isEmpty() || text.any { it == '\n' || it == '\r' || it.isISOControl() })
                    throw ToolFailure("text must be a single line without control characters; input text cannot type them.")
                "/system/bin/input text ${shellQuote(text)}"
            }
            "key" -> when (args["key"]?.jsonPrimitive?.content?.trim()?.lowercase()) {
                "home" -> "/system/bin/input keyevent 3"
                "back" -> "/system/bin/input keyevent 4"
                "recents" -> "/system/bin/input keyevent 187"
                "notifications" -> "/system/bin/input keyevent 83"
                "enter" -> "/system/bin/input keyevent 66"
                "delete" -> "/system/bin/input keyevent 67"
                "volume_up" -> "/system/bin/input keyevent 24"
                "volume_down" -> "/system/bin/input keyevent 25"
                else -> throw ToolFailure("Unsupported key. Use home, back, recents, notifications, enter, delete, volume_up or volume_down.")
            }
            "press" -> when (args["key"]?.jsonPrimitive?.content?.trim()?.lowercase()) {
                "home", null -> "/system/bin/input keyevent 3"
                "back" -> "/system/bin/input keyevent 4"
                "recents" -> "/system/bin/input keyevent 187"
                "notifications" -> "/system/bin/input keyevent 83"
                else -> throw ToolFailure("Unsupported press target.")
            }
            "open_app" -> {
                val pkg = args["package"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (!PKG_REGEX.matches(pkg)) throw ToolFailure("package must be a valid Android package name.")
                "/system/bin/monkey -p ${shellQuote(pkg)} -c android.intent.category.LAUNCHER 1"
            }
            else -> throw ToolFailure("Unsupported action '$action'. Use tap, long_press, swipe, text, key, press or open_app.")
        }
        val service = accessibility()
        if (service != null) {
            // Only fall through to the shell when accessibility did not dispatch
            // anything; never repeat an action it may already have performed.
            val outcome = when (action) {
                "tap" -> service.tap(intArg(args, "x"), intArg(args, "y"))
                "long_press" -> {
                    val x = intArg(args, "x"); val y = intArg(args, "y")
                    service.longPress(x, y)
                }
                "swipe" -> service.swipe(
                    intArg(args, "x"), intArg(args, "y"), intArg(args, "x2"), intArg(args, "y2"),
                    args["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300,
                )
                "text" -> service.setText(args["text"]?.jsonPrimitive?.content.orEmpty())
                "key", "press" -> service.globalKey(args["key"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty())
                "open_app" -> service.openApp(args["package"]?.jsonPrimitive?.content?.trim().orEmpty())
                else -> null
            }
            if (outcome != null) return ToolResult(true, outcome)
        }
        return try {
            val result = shell.run(command, timeoutMs = 20_000, maxOutput = 8_000)
            ToolResult(true, "Performed $action on the device.\n${result.output}", execution = listOf(result.receipt))
        } catch (e: ToolFailure) {
            ToolResult(false, e.message ?: "Device input failed.")
        }
    }

    private fun intArg(args: JsonObject, name: String): Int =
        args[name]?.jsonPrimitive?.intOrNull ?: throw ToolFailure("Missing or invalid integer argument: $name")

    private companion object {
        val PKG_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** Runs screencap into app-writable shared storage, then hands the bytes to ImageStore. */
private suspend fun captureScreenshot(
    context: Context,
    imageStore: ImageStore,
    shell: DeviceShell,
): ImageRef? = withContext(Dispatchers.IO) {
    // Public Downloads is writable by the shell uid and readable by the app
    // when Storage access is granted. The app's private external dir is not
    // reliably writable by a different uid.
    val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    runCatching { downloads.mkdirs() }
    val raw = File(downloads, ".o2o-shot-${UUID.randomUUID()}.png")
    try {
        shell.run("/system/bin/screencap -p ${shellQuote(raw.absolutePath)}", timeoutMs = 20_000, maxOutput = 2_000)
        if (!raw.isFile || raw.length() < 100) return@withContext null
        val bytes = raw.readBytes()
        if (bytes.size > 24 * 1024 * 1024) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1568) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        try {
            imageStore.saveCloseUp(decoded, "device-screen")
        } finally {
            decoded.recycle()
        }
    } finally {
        runCatching { raw.delete() }
    }
}
