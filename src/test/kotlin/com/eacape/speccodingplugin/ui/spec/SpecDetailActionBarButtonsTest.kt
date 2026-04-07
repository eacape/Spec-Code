package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton

class SpecDetailActionBarButtonsTest {

    @Test
    fun `composer action ids should remain stable across action bar buttons`() {
        val buttons = buttons()

        assertEquals(buttons.all().size, buttons.all().distinct().size)
        assertEquals(
            listOf(
                "generate",
                "openEditor",
                "historyDiff",
                "edit",
                "save",
                "cancelEdit",
                "confirmGenerate",
                "regenerateClarification",
                "skipClarification",
                "cancelClarification",
            ),
            buttons.composerActions().mapNotNull(buttons::composerActionId),
        )
    }

    @Test
    fun `visible composer action order should ignore hidden and unrelated components`() {
        val buttons = buttons()
        buttons.save.isVisible = false

        val order = buttons.visibleComposerActionOrder(
            listOf(
                buttons.edit,
                JButton("unrelated"),
                buttons.generate,
                buttons.save,
                buttons.cancelClarification,
            ),
        )

        assertEquals(
            listOf("edit", "generate", "cancelClarification"),
            order,
        )
    }

    @Test
    fun `state snapshot should expose action bar button state for tests`() {
        val buttons = buttons()
        buttons.generate.isVisible = false
        buttons.generate.toolTipText = "Generate tooltip"
        buttons.generate.accessibleContext.accessibleName = "Generate accessible"
        buttons.confirmGenerate.isEnabled = false
        buttons.confirmGenerate.toolTipText = "Confirm tooltip"
        buttons.confirmGenerate.accessibleContext.accessibleDescription = "Confirm accessible description"

        val snapshot = buttons.stateSnapshotForTest()

        assertFalse(snapshot["generateVisible"] as Boolean)
        assertEquals("Generate tooltip", snapshot["generateTooltip"])
        assertEquals("Generate accessible", snapshot["generateAccessibleName"])
        assertFalse(snapshot["confirmGenerateEnabled"] as Boolean)
        assertTrue(snapshot["confirmGenerateVisible"] as Boolean)
        assertEquals("Confirm tooltip", snapshot["confirmGenerateTooltip"])
        assertEquals(
            "Confirm accessible description",
            snapshot["confirmGenerateAccessibleDescription"],
        )
    }

    private fun buttons(): SpecDetailActionBarButtons {
        return SpecDetailActionBarButtons(
            generate = JButton(),
            nextPhase = JButton(),
            goBack = JButton(),
            complete = JButton(),
            pauseResume = JButton(),
            openEditor = JButton(),
            historyDiff = JButton(),
            edit = JButton(),
            save = JButton(),
            cancelEdit = JButton(),
            confirmGenerate = JButton(),
            regenerateClarification = JButton(),
            skipClarification = JButton(),
            cancelClarification = JButton(),
        )
    }
}
