package com.eacape.speccodingplugin.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsSidebarSectionCatalogTest {

    @Test
    fun `visible sections should include mcp and hooks by default`() {
        assertEquals(
            listOf(
                SettingsSidebarSection.BASIC,
                SettingsSidebarSection.PROMPTS,
                SettingsSidebarSection.SKILLS,
                SettingsSidebarSection.MCP,
                SettingsSidebarSection.HOOKS,
            ),
            SettingsSidebarSection.visibleSections(),
        )
    }
}
