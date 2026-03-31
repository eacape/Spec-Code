package com.eacape.speccodingplugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImprovedChatPanelComposerInputCoordinatorTest {

    @Test
    fun `clear should reset pasted text state`() {
        val state = ImprovedChatPanelComposerInputState(
            pendingPastedTextBlocks = mapOf("[Pasted text #1 +60 lines]" to "alpha"),
            pastedTextSequence = 3,
            lastComposerTextSnapshot = "draft",
        )

        val cleared = ImprovedChatPanelComposerInputCoordinator.clear()

        assertEquals(ImprovedChatPanelComposerInputState(), cleared)
        assertFalse(cleared.pendingPastedTextBlocks.containsValue("alpha"))
        assertFalse(cleared.lastComposerTextSnapshot == state.lastComposerTextSnapshot)
    }

    @Test
    fun `prepare collapsed clipboard paste should append marker and expand raw text`() {
        val largeText = buildLargeText(lines = 60, prefix = "alpha")

        val collapsedPaste = ImprovedChatPanelComposerInputCoordinator.prepareCollapsedClipboardPaste(
            state = ImprovedChatPanelComposerInputState(),
            clipboardText = largeText,
        )

        assertNotNull(collapsedPaste)
        assertEquals("[Pasted text #1 +60 lines]", collapsedPaste!!.marker)
        assertEquals(1, collapsedPaste.state.pastedTextSequence)
        assertEquals(
            largeText,
            ImprovedChatPanelComposerInputCoordinator.expandPendingPastedTextBlocks(
                state = collapsedPaste.state,
                input = "summary\n${collapsedPaste.marker}",
            ).substringAfter("summary\n"),
        )
    }

    @Test
    fun `resolve auto collapse should replace inserted large block with marker and remember snapshot`() {
        val previousSnapshot = "Plan:\n"
        val insertedRawText = buildLargeText(lines = 55, prefix = "beta")

        val update = ImprovedChatPanelComposerInputCoordinator.resolveAutoCollapse(
            state = ImprovedChatPanelComposerInputState(lastComposerTextSnapshot = previousSnapshot),
            currentInput = previousSnapshot + insertedRawText,
        )

        assertNotNull(update.mutation)
        assertEquals("Plan:\n[Pasted text #1 +55 lines]", update.mutation!!.text)
        assertEquals(update.mutation!!.text.length, update.mutation!!.caretPosition)
        assertEquals(update.mutation!!.text, update.state.lastComposerTextSnapshot)
        assertEquals(insertedRawText, update.state.pendingPastedTextBlocks["[Pasted text #1 +55 lines]"])
    }

    @Test
    fun `resolve auto collapse should deduplicate raw pasted block when marker is also present`() {
        val marker = "[Pasted text #1 +60 lines]"
        val rawText = buildLargeText(lines = 60, prefix = "alpha")

        val update = ImprovedChatPanelComposerInputCoordinator.resolveAutoCollapse(
            state = ImprovedChatPanelComposerInputState(
                pendingPastedTextBlocks = linkedMapOf(marker to rawText),
                pastedTextSequence = 1,
                lastComposerTextSnapshot = marker,
            ),
            currentInput = "$rawText\n$marker",
        )

        assertEquals(marker, update.mutation!!.text)
        assertEquals(marker, update.state.lastComposerTextSnapshot)
        assertTrue(update.state.pendingPastedTextBlocks.containsKey(marker))
    }

    @Test
    fun `sync external text change should prune removed markers and refresh snapshot`() {
        val state = ImprovedChatPanelComposerInputState(
            pendingPastedTextBlocks = linkedMapOf(
                "[Pasted text #1 +60 lines]" to buildLargeText(lines = 60, prefix = "alpha"),
            ),
            pastedTextSequence = 1,
            lastComposerTextSnapshot = "[Pasted text #1 +60 lines]",
        )

        val synced = ImprovedChatPanelComposerInputCoordinator.syncExternalTextChange(
            state = state,
            currentInput = "follow-up summary",
        )

        assertTrue(synced.pendingPastedTextBlocks.isEmpty())
        assertEquals("follow-up summary", synced.lastComposerTextSnapshot)
        assertEquals(1, synced.pastedTextSequence)
    }

    private fun buildLargeText(lines: Int, prefix: String): String {
        return (1..lines).joinToString("\n") { index -> "$prefix-$index" }
    }
}
