package com.androidharness.app.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O2OAgent's own AccessibilityService. Runs in the app UID (no ADB/root needed),
 * reads the foreground UI hierarchy and performs taps, gestures and text input.
 * This is the preferred path for device_screen / device_input whenever the user
 * has enabled it; the privileged-shell implementation remains the fallback.
 */
class O2oAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    /** Reads the foreground window into the same numbered listing as the shell path. */
    fun readScreen(maxNodes: Int): String {
        val root = rootInActiveWindow
            ?: return "The accessibility service has no active window to read. Unlock the screen and open the target app, then retry."
        val nodes = mutableListOf<ScreenNode>()
        flatten(root, 0, nodes, maxNodes)
        if (nodes.isEmpty()) {
            return "No UI elements were returned. The screen may be empty or secure (password field, payment sheet), or the target app may hide its view hierarchy from accessibility."
        }
        val activePackage = nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()
        val screenSize = nodes.firstOrNull { it.right > 0 && it.bottom > 0 }
            ?.let { "${it.right}x${it.bottom}" } ?: "unknown"
        return ScreenFormatter.format(
            nodes = nodes,
            maxNodes = maxNodes,
            activePackage = activePackage,
            screenSize = screenSize,
            via = "accessibility",
        )
    }

    /** Returns a human-readable result, or null when the caller should use the shell fallback. */
    fun tap(x: Int, y: Int): String? {
        val node = findNodeAt(x, y, clickableOnly = true)
        if (node != null && runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            return "Tapped \"${describe(node)}\" at $x,$y via accessibility."
        }
        return dispatchPoint(x, y, HOLD_TAP_MS)?.let { "Tapped at $x,$y via accessibility gesture." }
    }

    fun longPress(x: Int, y: Int): String? {
        val node = findNodeAt(x, y, clickableOnly = true)
        if (node != null && runCatching { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)) {
            return "Long-pressed \"${describe(node)}\" at $x,$y via accessibility."
        }
        return dispatchPoint(x, y, HOLD_LONG_MS)?.let { "Long-pressed at $x,$y via accessibility gesture." }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String? {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50, 3000).toLong())
        val ok = runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
        return if (ok) "Swiped $x1,$y1 → $x2,$y2 via accessibility." else null
    }

    fun setText(text: String): String? {
        val focused = findFocusedEditable(rootInActiveWindow)
            ?: return null
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        return if (ok) "Set focused field text via accessibility." else null
    }

    /** Only global navigation keys are possible through AccessibilityService; others use the shell. */
    fun globalKey(key: String): String? {
        val action = when (key) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return null
        }
        val ok = runCatching { performGlobalAction(action) }.getOrDefault(false)
        return if (ok) "Pressed $key via accessibility." else null
    }

    fun openApp(packageName: String): String? {
        val intent = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: return null
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { startActivity(intent) }.isSuccess) "Opened $packageName via accessibility." else null
    }

    private fun dispatchPoint(x: Int, y: Int, holdMs: Long): String? {
        // A zero-length stroke is rejected on some OEM builds; nudge by one pixel.
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo((x + 1).toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs)
        val ok = runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
        return if (ok) "ok" else null
    }

    private fun flatten(node: AccessibilityNodeInfo, depth: Int, into: MutableList<ScreenNode>, max: Int) {
        if (into.size >= max) return
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        into += ScreenNode(
            text = runCatching { node.text?.toString() }.getOrNull().orEmpty(),
            desc = runCatching { node.contentDescription?.toString() }.getOrNull().orEmpty(),
            className = runCatching { node.className?.toString() }.getOrNull().orEmpty(),
            packageName = runCatching { node.packageName?.toString() }.getOrNull().orEmpty(),
            resourceId = runCatching { node.viewIdResourceName }.getOrNull().orEmpty(),
            left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
            clickable = runCatching { node.isClickable }.getOrDefault(false),
            longClickable = runCatching { node.isLongClickable }.getOrDefault(false),
            scrollable = runCatching { node.isScrollable }.getOrDefault(false),
            enabled = runCatching { node.isEnabled }.getOrDefault(false),
            checked = runCatching { node.isChecked }.getOrDefault(false),
            focused = runCatching { node.isFocused }.getOrDefault(false),
            depth = depth,
        )
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            flatten(child, depth + 1, into, max)
            if (into.size >= max) return
        }
    }

    private fun findNodeAt(x: Int, y: Int, clickableOnly: Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return
            val rect = Rect()
            if (runCatching { node.getBoundsInScreen(rect) }.isSuccess &&
                x in rect.left..rect.right && y in rect.top..rect.bottom
            ) {
                val clickable = runCatching { node.isClickable || node.isLongClickable }.getOrDefault(false)
                val area = (rect.width().toLong() * rect.height().toLong()).coerceAtLeast(1)
                if ((!clickableOnly || clickable) && area < bestArea && runCatching { node.isEnabled }.getOrDefault(false)) {
                    best = node
                    bestArea = area
                }
            }
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { node.getChild(i) }.getOrNull()?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        return best
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (runCatching { node.isFocused && node.isEditable }.getOrDefault(false)) return node
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val found = runCatching { node.getChild(i) }.getOrNull()?.let { findFocusedEditable(it) }
            if (found != null) return found
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): String =
        runCatching { node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() } }
            .getOrNull().orEmpty().take(60)

    companion object {
        private const val HOLD_TAP_MS = 90L
        private const val HOLD_LONG_MS = 800L
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()

        @Volatile
        var instance: O2oAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null && _connected.value
    }
}
