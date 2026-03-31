package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle

internal data class ImprovedChatPanelExecutionState(
    val isGenerating: Boolean = false,
    val isFinalizingResponse: Boolean = false,
    val isRestoringSession: Boolean = false,
) {
    fun withSending(sending: Boolean): ImprovedChatPanelExecutionState {
        return if (sending) {
            copy(isGenerating = true)
        } else {
            copy(
                isGenerating = false,
                isFinalizingResponse = false,
            )
        }
    }

    fun withFinalizing(finalizing: Boolean): ImprovedChatPanelExecutionState {
        if (isFinalizingResponse == finalizing) {
            return this
        }
        return copy(isFinalizingResponse = finalizing)
    }

    fun withRestoring(restoring: Boolean): ImprovedChatPanelExecutionState {
        if (isRestoringSession == restoring) {
            return this
        }
        return copy(isRestoringSession = restoring)
    }
}

internal enum class ComposerSendActionKind {
    CHAT_SEND,
    CHAT_STOP,
    TASK_EXECUTE,
    TASK_RETRY,
    TASK_COMPLETE,
    TASK_STOP,
}

internal data class ComposerSendAction(
    val kind: ComposerSendActionKind,
    val tooltip: String,
    val accessibleName: String,
    val enabled: Boolean = true,
    val taskId: String? = null,
)

internal data class ImprovedChatPanelExecutionUiState(
    val sendAction: ComposerSendAction,
    val sendEnabled: Boolean,
    val compactEnabled: Boolean,
    val busyStatusText: String?,
)

internal object ImprovedChatPanelExecutionStateCoordinator {

    fun resolve(state: ImprovedChatPanelExecutionState): ImprovedChatPanelExecutionUiState {
        val sendAction = resolveSendAction(state)
        return ImprovedChatPanelExecutionUiState(
            sendAction = sendAction,
            sendEnabled = !state.isRestoringSession && !state.isFinalizingResponse && sendAction.enabled,
            compactEnabled = !state.isGenerating && !state.isRestoringSession,
            busyStatusText = resolveBusyStatusText(state),
        )
    }

    private fun resolveSendAction(state: ImprovedChatPanelExecutionState): ComposerSendAction {
        return if (state.isGenerating && !state.isRestoringSession && !state.isFinalizingResponse) {
            ComposerSendAction(
                kind = ComposerSendActionKind.CHAT_STOP,
                tooltip = SpecCodingBundle.message("toolwindow.stop"),
                accessibleName = SpecCodingBundle.message("toolwindow.stop"),
            )
        } else {
            ComposerSendAction(
                kind = ComposerSendActionKind.CHAT_SEND,
                tooltip = SpecCodingBundle.message("toolwindow.send"),
                accessibleName = SpecCodingBundle.message("toolwindow.send"),
            )
        }
    }

    private fun resolveBusyStatusText(state: ImprovedChatPanelExecutionState): String? {
        return when {
            state.isRestoringSession -> SpecCodingBundle.message("toolwindow.status.session.restoring")
            !state.isGenerating -> null
            state.isFinalizingResponse -> SpecCodingBundle.message("toolwindow.status.finalizing")
            else -> SpecCodingBundle.message("toolwindow.status.generating")
        }
    }
}
