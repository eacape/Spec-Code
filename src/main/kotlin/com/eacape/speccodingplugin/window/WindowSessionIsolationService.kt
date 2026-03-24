package com.eacape.speccodingplugin.window

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class WindowSessionIsolationService internal constructor(
    private val windowStateStore: WindowStateStore,
) {

    @Volatile
    private var activeSessionId: String? = normalize(windowStateStore.snapshot().activeSessionId)

    constructor(project: Project) : this(
        windowStateStore = WindowStateStore.getInstance(project),
    )

    fun activeSessionId(): String? = activeSessionId

    fun restorePersistedSessionId(): String? {
        val restored = normalize(windowStateStore.snapshot().activeSessionId)
        activeSessionId = restored
        return restored
    }

    fun activateSession(sessionId: String) {
        val normalized = normalize(sessionId) ?: return
        activeSessionId = normalized
        windowStateStore.updateActiveSessionId(normalized)
    }

    fun clearActiveSession() {
        activeSessionId = null
        windowStateStore.updateActiveSessionId(null)
    }

    private fun normalize(value: String?): String? {
        return value?.trim()?.ifBlank { null }
    }

    companion object {
        fun getInstance(project: Project): WindowSessionIsolationService = project.service()
    }
}

