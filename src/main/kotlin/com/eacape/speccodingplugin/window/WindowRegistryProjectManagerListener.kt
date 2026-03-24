package com.eacape.speccodingplugin.window

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

@Suppress("OVERRIDE_DEPRECATION")
class WindowRegistryProjectManagerListener(
    private val registryProvider: () -> WindowRegistry = { WindowRegistry.getInstance() },
) : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        registryProvider().registerWindow(project)
    }

    override fun projectClosed(project: Project) {
        registryProvider().unregisterWindow(project)
    }
}
