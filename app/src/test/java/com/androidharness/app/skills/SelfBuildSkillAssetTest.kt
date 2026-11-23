package com.androidharness.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelfBuildSkillAssetTest {

    @Test
    fun `self-build skill is bundled and actionable`() {
        val raw = File("src/main/assets/skills/o2oagent-self/SKILL.md").readText()
        val parsed = SkillParser.parse(raw)
        assertEquals("o2oagent-self", parsed.name)
        assertTrue(parsed.catalogDescription.contains("O2OAgent", ignoreCase = true))
        assertTrue(parsed.body.contains("bigopeforce-bit/PapyloHarnis"))
        assertTrue(parsed.body.contains("nightly.yml"))
        assertTrue(parsed.body.contains("signing-keys/debug.keystore"))
        assertTrue(parsed.body.contains("Confirm"))
    }
}
