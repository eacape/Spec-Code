package com.eacape.speccodingplugin.ui.settings

internal enum class SettingsSidebarSection(
    val cardId: String,
    val titleKey: String,
    val experimental: Boolean = false,
) {
    BASIC("basic", "settings.sidebar.basic"),
    PROMPTS("prompts", "settings.sidebar.prompts"),
    SKILLS("skills", "settings.sidebar.skills"),
    MCP("mcp", "settings.sidebar.mcp", experimental = true),
    HOOKS("hooks", "settings.sidebar.hooks", experimental = true),
    ;

    companion object {
        fun visibleSections(includeExperimental: Boolean): List<SettingsSidebarSection> {
            return buildList {
                add(BASIC)
                add(PROMPTS)
                add(SKILLS)
                if (includeExperimental) {
                    add(MCP)
                    add(HOOKS)
                }
            }
        }
    }
}
