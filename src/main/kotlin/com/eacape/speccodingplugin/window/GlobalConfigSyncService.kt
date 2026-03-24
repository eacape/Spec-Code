package com.eacape.speccodingplugin.window

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic

interface GlobalConfigSyncListener {
    fun onGlobalConfigChanged(event: GlobalConfigChangedEvent) {}

    companion object {
        val TOPIC: Topic<GlobalConfigSyncListener> = Topic.create(
            "SpecCoding.GlobalConfigChanged",
            GlobalConfigSyncListener::class.java,
        )
    }
}

data class GlobalConfigChangedEvent(
    val sourceWindowId: String?,
    val sourceProjectName: String?,
    val reason: String,
    val changedAt: Long,
    val snapshot: GlobalConfigSnapshot,
)

data class GlobalConfigSnapshot(
    val defaultProvider: String,
    val selectedCliModel: String,
    val useProxy: Boolean,
    val proxyHost: String,
    val proxyPort: Int,
    val autoSaveConversation: Boolean,
    val maxHistorySize: Int,
    val codexCliPath: String,
    val claudeCodeCliPath: String,
    val defaultOperationMode: String,
    val interfaceLanguage: String,
    val teamPromptRepoUrl: String,
    val teamPromptRepoBranch: String,
    val teamSkillRepoUrl: String,
    val teamSkillRepoBranch: String,
)

@Service(Service.Level.APP)
class GlobalConfigSyncService internal constructor(
    private val messageBus: MessageBus,
    private val settingsProvider: () -> SpecCodingSettingsState,
    private val windowRegistryProvider: () -> WindowRegistry,
    private val clock: () -> Long,
) {
    private val logger = thisLogger()

    constructor() : this(
        messageBus = ApplicationManager.getApplication().messageBus,
        settingsProvider = { SpecCodingSettingsState.getInstance() },
        windowRegistryProvider = { WindowRegistry.getInstance() },
        clock = { System.currentTimeMillis() },
    )

    fun currentSnapshot(): GlobalConfigSnapshot {
        val settings = settingsProvider()
        return GlobalConfigSnapshot(
            defaultProvider = settings.defaultProvider,
            selectedCliModel = settings.selectedCliModel,
            useProxy = settings.useProxy,
            proxyHost = settings.proxyHost,
            proxyPort = settings.proxyPort,
            autoSaveConversation = settings.autoSaveConversation,
            maxHistorySize = settings.maxHistorySize,
            codexCliPath = settings.codexCliPath,
            claudeCodeCliPath = settings.claudeCodeCliPath,
            defaultOperationMode = settings.defaultOperationMode,
            interfaceLanguage = settings.interfaceLanguage,
            teamPromptRepoUrl = settings.teamPromptRepoUrl,
            teamPromptRepoBranch = settings.teamPromptRepoBranch,
            teamSkillRepoUrl = settings.teamSkillRepoUrl,
            teamSkillRepoBranch = settings.teamSkillRepoBranch,
        )
    }

    fun notifyGlobalConfigChanged(
        sourceProject: Project? = null,
        reason: String = "unspecified",
    ): GlobalConfigChangedEvent {
        val sourceWindowId = sourceProject?.let { project ->
            runCatching { windowRegistryProvider().currentWindowId(project) }
                .onFailure { error -> logger.warn("Failed to resolve source window for config sync", error) }
                .getOrNull()
        }

        val event = GlobalConfigChangedEvent(
            sourceWindowId = sourceWindowId,
            sourceProjectName = sourceProject?.name,
            reason = reason,
            changedAt = clock(),
            snapshot = currentSnapshot(),
        )

        messageBus.syncPublisher(GlobalConfigSyncListener.TOPIC)
            .onGlobalConfigChanged(event)
        logger.info("Published global config sync event, reason=$reason")
        return event
    }

    companion object {
        fun getInstance(): GlobalConfigSyncService = service()
    }
}
