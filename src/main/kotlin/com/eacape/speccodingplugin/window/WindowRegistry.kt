package com.eacape.speccodingplugin.window

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.MessageBus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class WindowRegistry internal constructor(
    private val projectManager: ProjectManager,
    private val messageBus: MessageBus,
    private val idGenerator: () -> String,
    private val clock: () -> Long,
) {
    private val logger = thisLogger()
    private val windows = ConcurrentHashMap<String, WindowInfo>()
    private val projectWindowMapping = ConcurrentHashMap<Project, String>()

    constructor() : this(
        projectManager = ProjectManager.getInstance(),
        messageBus = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus,
        idGenerator = { UUID.randomUUID().toString() },
        clock = { System.currentTimeMillis() },
    )

    fun registerWindow(project: Project): WindowInfo {
        projectWindowMapping[project]?.let { existingId ->
            windows[existingId]?.let { return it }
        }

        val now = clock()
        val info = WindowInfo(
            windowId = idGenerator(),
            projectName = project.name,
            projectBasePath = project.basePath,
            openedAt = now,
            updatedAt = now,
        )
        windows[info.windowId] = info
        projectWindowMapping[project] = info.windowId

        logger.info("Registered window: ${info.windowId}, project=${project.name}")
        return info
    }

    fun unregisterWindow(project: Project): Boolean {
        val windowId = projectWindowMapping.remove(project) ?: return false
        val removed = windows.remove(windowId) != null
        if (removed) {
            logger.info("Unregistered window: $windowId, project=${project.name}")
        }
        return removed
    }

    fun currentWindowId(project: Project): String {
        return projectWindowMapping[project] ?: registerWindow(project).windowId
    }

    fun activeWindows(): List<WindowInfo> {
        return windows.values.sortedByDescending { it.updatedAt }
    }

    fun broadcastMessage(
        fromProject: Project,
        toWindowId: String,
        messageType: String,
        payload: String,
    ): Result<CrossWindowMessage> {
        return runCatching {
            require(messageType.isNotBlank()) { "messageType cannot be blank" }
            require(payload.isNotBlank()) { "payload cannot be blank" }

            val fromWindowId = currentWindowId(fromProject)
            val target = windows[toWindowId]
                ?: error("Target window not found: $toWindowId")

            val now = clock()
            val message = CrossWindowMessage(
                messageId = idGenerator(),
                fromWindowId = fromWindowId,
                fromProjectName = fromProject.name,
                toWindowId = target.windowId,
                messageType = messageType.trim(),
                payload = payload,
                createdAt = now,
            )

            windows.computeIfPresent(fromWindowId) { _, info -> info.copy(updatedAt = now) }
            windows.computeIfPresent(target.windowId) { _, info -> info.copy(updatedAt = now) }

            messageBus.syncPublisher(CrossWindowMessageListener.TOPIC)
                .onMessageReceived(message)

            logger.info("Broadcasted cross-window message: ${message.messageId}, type=${message.messageType}")
            message
        }
    }

    companion object {
        fun getInstance(): WindowRegistry = service()
    }
}

data class WindowInfo(
    val windowId: String,
    val projectName: String,
    val projectBasePath: String?,
    val openedAt: Long,
    val updatedAt: Long,
)

