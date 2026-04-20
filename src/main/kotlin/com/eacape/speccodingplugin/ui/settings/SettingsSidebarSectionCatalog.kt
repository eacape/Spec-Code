package com.eacape.speccodingplugin.ui.settings

internal enum class SettingsSidebarSection(
    val cardId: String,
    val titleKey: String,
) {
    BASIC("basic", "settings.sidebar.basic"),
    PROMPTS("prompts", "settings.sidebar.prompts"),
    SKILLS("skills", "settings.sidebar.skills"),
    MCP("mcp", "settings.sidebar.mcp"),
    HOOKS("hooks", "settings.sidebar.hooks"),
    ;

    companion object {
        fun visibleSections(): List<SettingsSidebarSection> = entries.toList()
    }
}
