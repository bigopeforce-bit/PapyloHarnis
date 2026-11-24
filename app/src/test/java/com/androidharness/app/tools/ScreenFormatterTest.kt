package com.androidharness.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFormatterTest {

    private fun node(
        text: String = "",
        desc: String = "",
        cls: String = "android.widget.Button",
        pkg: String = "com.example",
        id: String = "",
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 40,
        depth: Int = 0,
        clickable: Boolean = false,
        focused: Boolean = false,
    ) = ScreenNode(
        text = text, desc = desc, className = cls, packageName = pkg, resourceId = id,
        left = left, top = top, right = right, bottom = bottom,
        clickable = clickable, longClickable = false, scrollable = false,
        enabled = true, checked = false, focused = focused, depth = depth,
    )

    @Test
    fun `accessibility nodes render like the shell listing`() {
        val out = ScreenFormatter.format(
            nodes = listOf(
                node(cls = "android.widget.FrameLayout", depth = 0),
                node(text = "Send", id = "com.example:id/send", left = 400, top = 280,
                    right = 680, bottom = 320, depth = 1, clickable = true, focused = true),
            ),
            activePackage = "com.example",
            screenSize = "1080x2400",
            via = "accessibility",
        )
        assertTrue(out.contains("Screen 1080x2400; 2 element(s); package com.example (via accessibility)"))
        assertTrue(out.contains("[0] FrameLayout @50,20"))
        assertTrue(out.contains("[1]   Button \"Send\" id=send @540,300 {click,focused}"))
        assertTrue(out.contains("Use device_input with action=tap"))
    }

    @Test
    fun `falls back to content description and reports truncation`() {
        val nodes = (0 until 5).map { node(desc = "Item $it") }
        val out = ScreenFormatter.format(nodes, maxNodes = 2)
        assertTrue(out.contains("Item 0"))
        assertFalse(out.contains("Item 2"))
        assertTrue(out.contains("[truncated at 2 elements]"))
        assertTrue(out.contains("2 element(s)"))
    }
}
