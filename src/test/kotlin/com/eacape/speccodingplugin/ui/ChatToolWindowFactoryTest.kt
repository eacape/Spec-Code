package com.eacape.speccodingplugin.ui

import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContentUiType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatToolWindowFactoryTest {

    @Test
    fun `ensureTabbedContentUi should switch combo tool window to tabbed`() {
        val toolWindow = mockk<ToolWindowEx>()

        every { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) } just Runs
        every { toolWindow.contentUiType } returns ToolWindowContentUiType.COMBO
        every { toolWindow.setContentUiType(ToolWindowContentUiType.TABBED, null) } just Runs
        every { toolWindow.updateContentUi() } just Runs

        ChatToolWindowFactory.ensureTabbedContentUi(toolWindow)

        verify(exactly = 1) { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) }
        verify(exactly = 1) { toolWindow.setContentUiType(ToolWindowContentUiType.TABBED, null) }
        verify(exactly = 1) { toolWindow.updateContentUi() }
    }

    @Test
    fun `ensureTabbedContentUi should keep tabbed tool window unchanged`() {
        val toolWindow = mockk<ToolWindowEx>()

        every { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) } just Runs
        every { toolWindow.contentUiType } returns ToolWindowContentUiType.TABBED

        ChatToolWindowFactory.ensureTabbedContentUi(toolWindow)

        verify(exactly = 1) { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) }
        verify(exactly = 0) { toolWindow.setContentUiType(any(), any()) }
        verify(exactly = 0) { toolWindow.updateContentUi() }
    }

    @Test
    fun `selectSpecContent should select content by stable key`() {
        val toolWindow = mockk<ToolWindow>()
        val contentManager = mockk<ContentManager>()
        val chatContent = mockk<Content>()
        val specContent = mockk<Content>()

        every { toolWindow.contentManager } returns contentManager
        every { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) } just Runs
        every { toolWindow.contentUiType } returns ToolWindowContentUiType.TABBED
        every { contentManager.contents } returns arrayOf(chatContent, specContent)
        every { chatContent.getUserData(any<Key<Boolean>>()) } returns false
        every { specContent.getUserData(any<Key<Boolean>>()) } returns true
        every { contentManager.setSelectedContent(specContent) } just Runs

        assertTrue(ChatToolWindowFactory.selectSpecContent(toolWindow))
        verify(exactly = 1) { contentManager.setSelectedContent(specContent) }
    }

    @Test
    fun `selectSpecContent should return false when spec content is missing`() {
        val toolWindow = mockk<ToolWindow>()
        val contentManager = mockk<ContentManager>()
        val chatContent = mockk<Content>()

        every { toolWindow.contentManager } returns contentManager
        every { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) } just Runs
        every { toolWindow.contentUiType } returns ToolWindowContentUiType.TABBED
        every { contentManager.contents } returns arrayOf(chatContent)
        every { chatContent.getUserData(any<Key<Boolean>>()) } returns false

        assertFalse(ChatToolWindowFactory.selectSpecContent(toolWindow))
    }

    @Test
    fun `selectContent should select ensured content when matching tab is missing`() {
        val toolWindow = mockk<ToolWindow>()
        val contentManager = mockk<ContentManager>()
        val specContent = mockk<Content>()

        every { toolWindow.contentManager } returns contentManager
        every { toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED) } just Runs
        every { toolWindow.contentUiType } returns ToolWindowContentUiType.TABBED
        every { contentManager.contents } returns emptyArray()
        every { contentManager.setSelectedContent(specContent) } just Runs

        assertTrue(
            ChatToolWindowFactory.selectContent(
                toolWindow = toolWindow,
                matcher = { false },
                ensureContent = { specContent },
            ),
        )
        verify(exactly = 1) { contentManager.setSelectedContent(specContent) }
    }

    @Test
    fun `ensureContent should reuse existing primary tab`() {
        val contentManager = mockk<ContentManager>()
        val existing = mockk<Content>()

        every { contentManager.contents } returns arrayOf(existing)

        val content = ChatToolWindowFactory.ensureContent(
            contentManager = contentManager,
            matcher = { it === existing },
            create = { error("should not create") },
        )

        assertSame(existing, content)
        verify(exactly = 0) { contentManager.addContent(any()) }
    }

    @Test
    fun `ensureContent should add created primary tab when missing`() {
        val contentManager = mockk<ContentManager>()
        val created = mockk<Content>()

        every { contentManager.contents } returns emptyArray()
        every { contentManager.addContent(created) } just Runs

        val content = ChatToolWindowFactory.ensureContent(
            contentManager = contentManager,
            matcher = { false },
            create = { created },
        )

        assertSame(created, content)
        verify(exactly = 1) { contentManager.addContent(created) }
    }
}
