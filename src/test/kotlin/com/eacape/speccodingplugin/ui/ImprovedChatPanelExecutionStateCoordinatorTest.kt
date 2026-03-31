package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelExecutionStateCoordinatorTest {

    @Test
    fun `resolve should expose stop action while generating`() {
        val uiState = ImprovedChatPanelExecutionStateCoordinator.resolve(
            ImprovedChatPanelExecutionState(isGenerating = true),
        )

        assertEquals(ComposerSendActionKind.CHAT_STOP, uiState.sendAction.kind)
        assertTrue(uiState.sendEnabled)
        assertFalse(uiState.compactEnabled)
        assertEquals(
            SpecCodingBundle.message("toolwindow.status.generating"),
            uiState.busyStatusText,
        )
    }

    @Test
    fun `resolve should prioritize restoring state over stop action`() {
        val uiState = ImprovedChatPanelExecutionStateCoordinator.resolve(
            ImprovedChatPanelExecutionState(
                isGenerating = true,
                isRestoringSession = true,
            ),
        )

        assertEquals(ComposerSendActionKind.CHAT_SEND, uiState.sendAction.kind)
        assertFalse(uiState.sendEnabled)
        assertFalse(uiState.compactEnabled)
        assertEquals(
            SpecCodingBundle.message("toolwindow.status.session.restoring"),
            uiState.busyStatusText,
        )
    }

    @Test
    fun `resolve should keep finalizing busy text and disable send`() {
        val uiState = ImprovedChatPanelExecutionStateCoordinator.resolve(
            ImprovedChatPanelExecutionState(isGenerating = true).withFinalizing(true),
        )

        assertEquals(ComposerSendActionKind.CHAT_SEND, uiState.sendAction.kind)
        assertFalse(uiState.sendEnabled)
        assertFalse(uiState.compactEnabled)
        assertEquals(
            SpecCodingBundle.message("toolwindow.status.finalizing"),
            uiState.busyStatusText,
        )
    }

    @Test
    fun `withSending false should clear finalizing state`() {
        val state = ImprovedChatPanelExecutionState(
            isGenerating = true,
            isFinalizingResponse = true,
        ).withSending(false)

        assertFalse(state.isGenerating)
        assertFalse(state.isFinalizingResponse)
        assertFalse(state.isRestoringSession)
    }
}
