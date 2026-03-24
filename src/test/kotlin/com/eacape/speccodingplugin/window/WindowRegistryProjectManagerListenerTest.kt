package com.eacape.speccodingplugin.window

import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class WindowRegistryProjectManagerListenerTest {

    @Test
    fun `projectOpened should register window`() {
        val registry = mockk<WindowRegistry>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        val listener = WindowRegistryProjectManagerListener(registryProvider = { registry })

        listener.projectOpened(project)

        verify(exactly = 1) { registry.registerWindow(project) }
    }

    @Test
    fun `projectClosed should unregister window`() {
        val registry = mockk<WindowRegistry>(relaxed = true)
        val project = mockk<Project>(relaxed = true)
        val listener = WindowRegistryProjectManagerListener(registryProvider = { registry })

        listener.projectClosed(project)

        verify(exactly = 1) { registry.unregisterWindow(project) }
    }
}
