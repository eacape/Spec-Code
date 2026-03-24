package com.eacape.speccodingplugin.window

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WindowRegistryTest {

    private lateinit var projectManager: ProjectManager
    private lateinit var messageBus: MessageBus
    private lateinit var listener: CrossWindowMessageListener
    private lateinit var projectA: Project
    private lateinit var projectB: Project

    @BeforeEach
    fun setUp() {
        projectManager = mockk(relaxed = true)
        messageBus = mockk(relaxed = true)
        listener = mockk(relaxed = true)
        every { messageBus.syncPublisher(any<Topic<CrossWindowMessageListener>>()) } returns listener

        projectA = mockk(relaxed = true)
        every { projectA.name } returns "ProjectA"
        every { projectA.basePath } returns "D:/repo/project-a"

        projectB = mockk(relaxed = true)
        every { projectB.name } returns "ProjectB"
        every { projectB.basePath } returns "D:/repo/project-b"
    }

    @Test
    fun `register and unregister should maintain active windows`() {
        var sequence = 0
        val registry = WindowRegistry(
            projectManager = projectManager,
            messageBus = messageBus,
            idGenerator = { "id-${++sequence}" },
            clock = { 1000L + sequence },
        )

        val infoA = registry.registerWindow(projectA)
        val infoB = registry.registerWindow(projectB)

        assertEquals("id-1", infoA.windowId)
        assertEquals("id-2", infoB.windowId)
        assertEquals(2, registry.activeWindows().size)

        val removed = registry.unregisterWindow(projectA)
        assertTrue(removed)
        assertEquals(1, registry.activeWindows().size)
    }

    @Test
    fun `broadcastMessage should publish message to topic`() {
        var sequence = 0
        val registry = WindowRegistry(
            projectManager = projectManager,
            messageBus = messageBus,
            idGenerator = { "id-${++sequence}" },
            clock = { 2000L + sequence },
        )

        val from = registry.registerWindow(projectA)
        val target = registry.registerWindow(projectB)

        val result = registry.broadcastMessage(
            fromProject = projectA,
            toWindowId = target.windowId,
            messageType = "CONTEXT_SHARE",
            payload = "{\"path\":\"src/Main.kt\"}",
        ).getOrThrow()

        assertEquals(from.windowId, result.fromWindowId)
        assertEquals(target.windowId, result.toWindowId)
        assertEquals("CONTEXT_SHARE", result.messageType)

        verify(exactly = 1) {
            listener.onMessageReceived(match {
                it.messageId == result.messageId &&
                    it.fromWindowId == from.windowId &&
                    it.toWindowId == target.windowId
            })
        }
    }
}

