package com.eacape.speccodingplugin.window

import com.eacape.speccodingplugin.session.canonicalizeWorkflowChatModeKey
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "SpecCodingWindowState",
    storages = [Storage("specCodingWindowState.xml")],
)
class WindowStateStore : PersistentStateComponent<WindowStateStore.WindowState> {

    private var state: WindowState = WindowState()

    @Synchronized
    override fun getState(): WindowState = state.copy()

    @Synchronized
    override fun loadState(state: WindowState) {
        this.state = state.copy(
            chatInteractionMode = canonicalizeWorkflowChatModeKey(state.chatInteractionMode),
            chatComposerDividerProportion = normalizeChatComposerDividerProportion(state.chatComposerDividerProportion),
        )
    }

    @Synchronized
    fun snapshot(): WindowRuntimeState {
        return WindowRuntimeState(
            selectedTabTitle = state.selectedTabTitle,
            activeSessionId = state.activeSessionId,
            operationMode = state.operationMode,
            chatInteractionMode = state.chatInteractionMode,
            chatSpecSidebarVisible = state.chatSpecSidebarVisible,
            chatSpecSidebarDividerLocation = state.chatSpecSidebarDividerLocation,
            chatComposerDividerProportion = state.chatComposerDividerProportion,
            updatedAt = state.updatedAt,
        )
    }

    @Synchronized
    fun updateSelectedTabTitle(tabTitle: String?) {
        val value = tabTitle?.trim()?.ifBlank { null } ?: return
        state.selectedTabTitle = value
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateActiveSessionId(sessionId: String?) {
        state.activeSessionId = sessionId?.trim()?.ifBlank { null }
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateOperationMode(modeName: String?) {
        state.operationMode = modeName?.trim()?.ifBlank { null }
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateChatInteractionMode(modeKey: String?) {
        state.chatInteractionMode = canonicalizeWorkflowChatModeKey(modeKey)
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateChatSpecSidebar(visible: Boolean, dividerLocation: Int?) {
        state.chatSpecSidebarVisible = visible
        if (dividerLocation != null && dividerLocation > 0) {
            state.chatSpecSidebarDividerLocation = dividerLocation
        }
        state.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun updateChatComposerDividerProportion(proportion: Float?) {
        state.chatComposerDividerProportion = normalizeChatComposerDividerProportion(proportion)
        state.updatedAt = System.currentTimeMillis()
    }

    data class WindowState(
        var selectedTabTitle: String = "Chat",
        var activeSessionId: String? = null,
        var operationMode: String? = null,
        var chatInteractionMode: String? = null,
        var chatSpecSidebarVisible: Boolean = false,
        var chatSpecSidebarDividerLocation: Int = 0,
        var chatComposerDividerProportion: Float = 0f,
        var updatedAt: Long = 0L,
    )

    companion object {
        fun getInstance(project: Project): WindowStateStore = project.service()
    }
}

data class WindowRuntimeState(
    val selectedTabTitle: String,
    val activeSessionId: String?,
    val operationMode: String?,
    val chatInteractionMode: String?,
    val chatSpecSidebarVisible: Boolean,
    val chatSpecSidebarDividerLocation: Int,
    val chatComposerDividerProportion: Float,
    val updatedAt: Long,
)

private fun normalizeChatComposerDividerProportion(value: Float?): Float {
    if (value == null || !value.isFinite()) {
        return 0f
    }
    return value.takeIf { it > 0f && it < 1f } ?: 0f
}
