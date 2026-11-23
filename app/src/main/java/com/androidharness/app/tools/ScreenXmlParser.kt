package com.androidharness.app.tools

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One node of an Android `uiautomator dump` hierarchy, flattened for the model. */
internal data class ScreenNode(
    val text: String,
    val desc: String,
    val className: String,
    val packageName: String,
    val resourceId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val depth: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Turns the XML produced by `uiautomator dump` into a compact, numbered, indented
 * listing an agent can act on. No Android APIs here so it can be unit tested.
 */
internal object ScreenXmlParser {

    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    fun parse(xml: String, maxNodes: Int = 300): String {
        // `uiautomator dump /dev/tty` can prefix a chatty status line before the XML.
        val xmlStart = listOf("<?xml", "<hierarchy").mapNotNull { xml.indexOf(it).takeIf { i -> i >= 0 } }.minOrNull() ?: 0
        val clean = xml.substring(xmlStart)
        if (!clean.contains("<node")) {
            return "No UI elements were returned. The screen may be empty, secure (e.g. a password field or payment sheet), or uiautomator may be unavailable."
        }
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // Hardening is best-effort across JVM and Android XML implementations.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isNamespaceAware = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(clean.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return "Could not parse the UI hierarchy: ${e.message}"
        }
        val root = document.documentElement ?: return "The UI hierarchy contained no root element."
        val nodes = mutableListOf<ScreenNode>()
        collect(root, 0, nodes, maxNodes)

        val activePackage = nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()
        val screenSize = nodes.firstOrNull { it.right > 0 && it.bottom > 0 }
            ?.let { "${it.right}x${it.bottom}" } ?: "unknown"
        val out = StringBuilder()
        out.append("Screen $screenSize; ${nodes.size} element(s)")
        if (activePackage.isNotEmpty()) out.append("; package $activePackage")
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

    private fun collect(node: Node, depth: Int, into: MutableList<ScreenNode>, maxNodes: Int) {
        if (into.size >= maxNodes) return
        if (node is Element && node.tagName == "node") {
            val bounds = BOUNDS.find(node.getAttribute("bounds") ?: "")
            // groupValues[0] is the full match (non-numeric), so the four capture
            // groups are the only values that survive toIntOrNull().
            val values = bounds?.groupValues?.mapNotNull { it.toIntOrNull() }
            val coords = if (values != null && values.size == 4) values else listOf(0, 0, 0, 0)
            into += ScreenNode(
                text = attr(node, "text"),
                desc = attr(node, "content-desc"),
                className = attr(node, "class"),
                packageName = attr(node, "package"),
                resourceId = attr(node, "resource-id"),
                left = coords[0], top = coords[1], right = coords[2], bottom = coords[3],
                clickable = attr(node, "clickable") == "true",
                longClickable = attr(node, "long-clickable") == "true",
                scrollable = attr(node, "scrollable") == "true",
                enabled = attr(node, "enabled") != "false",
                checked = attr(node, "checked") == "true",
                focused = attr(node, "focused") == "true",
                depth = depth,
            )
        }
        val children: NodeList = node.childNodes
        for (i in 0 until children.length) {
            collect(children.item(i), if (node is Element && node.tagName == "node") depth + 1 else depth, into, maxNodes)
            if (into.size >= maxNodes) return
        }
    }

    private fun attr(element: Element, name: String): String = runCatching { element.getAttribute(name) }.getOrDefault("")
}
