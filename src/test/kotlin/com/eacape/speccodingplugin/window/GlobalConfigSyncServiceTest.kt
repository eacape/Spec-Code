package com.eacape.speccodingplugin.window

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GlobalConfigSyncServiceTest {

    private lateinit var messageBus: MessageBus
    private lateinit var listener: GlobalConfigSyncListener
    private lateinit var settings: SpecCodingSettingsState
    private lateinit var windowRegistry: WindowRegistry
    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        messageBus = mockk(relaxed = true)
        listener = mockk(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<GlobalConfigSyncListener>>()) } returns listener

        settings = SpecCodingSettingsState().apply {
            defaultProvider = "claude_cli"
            selectedCliModel = "claude-sonnet-4-20250514"
            useProxy = true
            proxyHost = "127.0.0.1"
            proxyPort = 7890
            autoSaveConversation = true
            maxHistorySize = 200
            codexCliPath = "codex"
            claudeCodeCliPath = "claude"
            defaultOperationMode = "AGENT"
            teamPromptRepoUrl = "https://example.com/team-prompts.git"
            teamPromptRepoBranch = "main"
            teamSkillRepoUrl = "https://example.com/team-skills.git"
            teamSkillRepoBranch = "main"
        }

        windowRegistry = mockk(relaxed = true)
        every { windowRegistry.currentWindowId(any()) } returns "window-1"

        project = mockk(relaxed = true)
        every { project.name } returns "DemoProject"
    }

    @Test
    fun `notifyGlobalConfigChanged should publish event with snapshot`() {
        val service = GlobalConfigSyncService(
            messageBus = messageBus,
            settingsProvider = { settings },
            windowRegistryProvider = { windowRegistry },
            clock = { 123456789L },
        )

        val event = service.notifyGlobalConfigChanged(
            sourceProject = project,
            reason = "unit-test",
        )

        assertEquals("window-1", event.sourceWindowId)
        assertEquals("DemoProject", event.sourceProjectName)
        assertEquals("unit-test", event.reason)
        assertEquals(123456789L, event.changedAt)
        assertEquals("claude_cli", event.snapshot.defaultProvider)
        assertEquals("claude-sonnet-4-20250514", event.snapshot.selectedCliModel)
        assertTrue(event.snapshot.useProxy)
        assertEquals("https://example.com/team-prompts.git", event.snapshot.teamPromptRepoUrl)
        assertEquals("https://example.com/team-skills.git", event.snapshot.teamSkillRepoUrl)

        verify(exactly = 1) {
            listener.onGlobalConfigChanged(match {
                it.reason == "unit-test" &&
                    it.sourceWindowId == "window-1" &&
                    it.snapshot.defaultProvider == "claude_cli"
            })
        }
    }
}
