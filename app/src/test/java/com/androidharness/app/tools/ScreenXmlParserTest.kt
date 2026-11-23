package com.androidharness.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenXmlParserTest {

    @Test
    fun `parses nodes into numbered tappable coordinates`() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" content-desc="" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
                <node index="0" text="Send" resource-id="com.example:id/send" class="android.widget.Button" package="com.example" content-desc="Send message" clickable="true" long-clickable="true" enabled="true" scrollable="false" checked="false" focused="true" bounds="[400,280][680,320]" />
              </node>
            </hierarchy>
        """.trimIndent()
        val out = ScreenXmlParser.parse(xml)
        assertTrue(out.contains("Screen 1080x2400"))
        assertTrue(out.contains("package com.example"))
        assertTrue(out.contains("[0] FrameLayout"))
        assertTrue(out.contains("Send message")) // content-desc fallback when text is empty
        assertTrue(out.contains("id=send"))
        assertTrue(out.contains("@540,300"))
        assertTrue(out.contains("{click,longclick,focused}"))
        assertTrue(out.contains("[1] Button"))
    }

    @Test
    fun `honours max_nodes and says so`() {
        val node = """<node text="x" class="android.view.View" clickable="true" enabled="true" bounds="[0,0][100,100]" />"""
        val xml = "<hierarchy rotation=\"0\">${node.repeat(6)}</hierarchy>"
        val out = ScreenXmlParser.parse(xml, maxNodes = 2)
        assertTrue(out.contains("2 element(s)"))
        assertTrue(out.contains("[truncated at 2 elements]"))
        assertFalse(out.contains("[2]"))
    }

    @Test
    fun `handles empty and malformed hierarchies safely`() {
        assertTrue(ScreenXmlParser.parse("<hierarchy rotation=\"0\" />").contains("No UI elements"))
        val xxe = """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY x SYSTEM "file:///etc/passwd">]><hierarchy><node text="&x;" class="View" bounds="[0,0][10,10]" /></hierarchy>"""
        val out = ScreenXmlParser.parse(xxe)
        assertTrue(out.contains("Could not parse"))
        assertFalse(out.contains("root:"))
    }
}
