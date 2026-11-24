package com.androidharness.app.tools

/**
 * Renders a flattened UI hierarchy as the compact, numbered, indented listing
 * the agent acts on. Shared by the uiautomator XML parser and the
 * AccessibilityService-backed reader so both paths produce the same @x,y
 * coordinates and flag notation. No Android APIs here so it can be unit tested.
 */
internal object ScreenFormatter {

    fun format(
        nodes: List<ScreenNode>,
        maxNodes: Int = 300,
        activePackage: String = "",
        screenSize: String = "unknown",
        via: String = "",
    ): String {
        val out = StringBuilder()
        out.append("Screen $screenSize; ${nodes.size} element(s)")
        if (activePackage.isNotEmpty()) out.append("; package $activePackage")
        if (via.isNotEmpty()) out.append(" (via $via)")
        out.append('\n')
        nodes.forEachIndexed { index, node ->
            out.append('[').append(index).append("] ")
            repeat(node.depth.coerceAtMost(12)) { out.append("  ") }
            val label = node.text.ifBlank { node.desc }
            out.append(node.className.substringAfterLast('.').ifBlank { "View" })
            if (label.isNotBlank()) out.append(" \"").append(label.take(90)).append('"')
            if (node.resourceId.isNotBlank()) out.append(" id=").append(node.resourceId.substringAfterLast('/'))
            out.append(" @").append(node.centerX).append(',').append(node.centerY)
            val flags = buildList {
                if (node.clickable) add("click")
                if (node.longClickable) add("longclick")
                if (node.scrollable) add("scroll")
                if (node.checked) add("checked")
                if (node.focused) add("focused")
                if (!node.enabled) add("disabled")
            }
            if (flags.isNotEmpty()) out.append(" {").append(flags.joinToString(",")).append('}')
            out.append('\n')
        }
        if (nodes.size >= maxNodes) out.append("[truncated at $maxNodes elements]\n")
        out.append("Use device_input with action=tap and the @x,y coordinate of an element to interact with it.")
        return out.toString()
    }
}
