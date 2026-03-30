package com.eacape.speccodingplugin.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsSidebarSectionCatalogTest {

    @Test
    fun `visible sections should keep beta primary sections first by default`() {
        assertEquals(
            listOf(
                SettingsSidebarSection.BASIC,
                SettingsSidebarSection.PROMPTS,
                SettingsSidebarSection.SKILLS,
            ),
            SettingsSidebarSection.visibleSections(includeExperimental = false),
        )
    }

    @Test
    fun `visible sections should append experimental automation when requested`() {
        assertEquals(
            listOf(
                SettingsSidebarSection.BASIC,
                SettingsSidebarSection.PROMPTS,
                SettingsSidebarSection.SKILLS,
                SettingsSidebarSection.MCP,
                SettingsSidebarSection.HOOKS,
            ),
            SettingsSidebarSection.visibleSections(includeExperimental = true),
        )
        assertTrue(SettingsSidebarSection.MCP.experimental)
        assertTrue(SettingsSidebarSection.HOOKS.experimental)
    }
}
