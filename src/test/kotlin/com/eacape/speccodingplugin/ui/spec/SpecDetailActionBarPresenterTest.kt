package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Cursor
import javax.swing.JButton

class SpecDetailActionBarPresenterTest {

    @Test
    fun `applyEmptyState should restore default empty visibility and disable actions`() {
        val buttons = buttons()
        val presenter = SpecDetailActionBarPresenter(buttons)

        presenter.applyEmptyState()

        assertTrue(buttons.generate.isVisible)
        assertTrue(buttons.nextPhase.isVisible)
        assertTrue(buttons.goBack.isVisible)
        assertFalse(buttons.complete.isVisible)
        assertFalse(buttons.pauseResume.isVisible)
        assertTrue(buttons.openEditor.isVisible)
        assertTrue(buttons.historyDiff.isVisible)
        assertTrue(buttons.edit.isVisible)
        assertFalse(buttons.save.isVisible)
        assertFalse(buttons.cancelEdit.isVisible)
        assertFalse(buttons.confirmGenerate.isVisible)
        assertFalse(buttons.skipClarification.isVisible)
        assertFalse(buttons.cancelClarification.isVisible)
        buttons.all().forEach { button ->
            assertFalse(button.isEnabled)
            assertEquals(Cursor.DEFAULT_CURSOR, button.cursor.type)
        }
    }

    @Test
    fun `apply should update visibility enabled state and cursor for each action`() {
        val buttons = buttons()
        val presenter = SpecDetailActionBarPresenter(buttons)

        presenter.apply(
            hiddenState().copy(
                generate = buttonState(visible = true, enabled = true),
                pauseResume = buttonState(visible = true, enabled = false),
                openEditor = buttonState(visible = true, enabled = true),
                confirmGenerate = buttonState(visible = true, enabled = false, disabledReason = "locked"),
                skipClarification = buttonState(visible = true, enabled = true),
            ),
        )

        assertTrue(buttons.generate.isVisible)
        assertTrue(buttons.generate.isEnabled)
        assertEquals(Cursor.HAND_CURSOR, buttons.generate.cursor.type)
        assertTrue(buttons.pauseResume.isVisible)
        assertFalse(buttons.pauseResume.isEnabled)
        assertEquals(Cursor.DEFAULT_CURSOR, buttons.pauseResume.cursor.type)
        assertTrue(buttons.openEditor.isVisible)
        assertTrue(buttons.openEditor.isEnabled)
        assertTrue(buttons.confirmGenerate.isVisible)
        assertFalse(buttons.confirmGenerate.isEnabled)
        assertEquals("locked", buttons.confirmGenerate.getClientProperty("spec.iconActionButton.disabledReason"))
        assertTrue(buttons.skipClarification.isVisible)
        assertTrue(buttons.skipClarification.isEnabled)
        assertFalse(buttons.complete.isVisible)
        assertFalse(buttons.complete.isEnabled)
    }

    @Test
    fun `applyPresentation should configure icons tooltips and accessible names`() {
        val buttons = buttons()
        val presenter = SpecDetailActionBarPresenter(buttons)

        presenter.applyPresentation(
            SpecDetailActionBarPresentationCoordinator.resolve(
                composeMode = ArtifactComposeActionMode.REVISE,
                workflowStatus = WorkflowStatus.PAUSED,
                editRequiresExplicitRevisionStart = true,
                customIcons = SpecDetailActionBarCustomIcons(
                    save = SpecWorkflowIcons.Save,
                    startRevision = SpecWorkflowIcons.Add,
                ),
            ),
        )

        assertEquals("execute", SpecWorkflowIcons.debugId(buttons.generate.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.revise"), buttons.generate.toolTipText)
        assertEquals("resume", SpecWorkflowIcons.debugId(buttons.pauseResume.icon))
        assertEquals(SpecCodingBundle.message("spec.detail.resume"), buttons.pauseResume.toolTipText)
        assertEquals("add", SpecWorkflowIcons.debugId(buttons.edit.icon))
        assertEquals(
            SpecCodingBundle.message("spec.detail.revision.start"),
            buttons.edit.toolTipText,
        )
        assertEquals(
            SpecCodingBundle.message("spec.detail.revision.start"),
            buttons.edit.accessibleContext?.accessibleName,
        )
        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.confirmRevise"),
            buttons.confirmGenerate.toolTipText,
        )
    }

    @Test
    fun `disableAll should preserve visibility and disable every action`() {
        val buttons = buttons().apply {
            generate.isVisible = false
            confirmGenerate.isVisible = true
            skipClarification.isVisible = true
        }
        val presenter = SpecDetailActionBarPresenter(buttons)

        presenter.apply(
            hiddenState().copy(
                confirmGenerate = buttonState(visible = true, enabled = true),
                skipClarification = buttonState(visible = true, enabled = true),
            ),
        )
        presenter.disableAll()

        assertFalse(buttons.generate.isVisible)
        assertTrue(buttons.confirmGenerate.isVisible)
        assertTrue(buttons.skipClarification.isVisible)
        buttons.all().forEach { button ->
            assertFalse(button.isEnabled)
            assertEquals(Cursor.DEFAULT_CURSOR, button.cursor.type)
        }
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

    private fun hiddenState(): SpecDetailPanelActionState {
        val hidden = buttonState(visible = false, enabled = false)
        return SpecDetailPanelActionState(
            generate = hidden,
            nextPhase = hidden,
            goBack = hidden,
            complete = hidden,
            pauseResume = hidden,
            openEditor = hidden,
            historyDiff = hidden,
            edit = hidden,
            save = hidden,
            cancelEdit = hidden,
            confirmGenerate = hidden,
            regenerateClarification = hidden,
            skipClarification = hidden,
            cancelClarification = hidden,
        )
    }

    private fun buttonState(
        visible: Boolean,
        enabled: Boolean,
        disabledReason: String? = null,
    ): SpecDetailPanelActionButtonState {
        return SpecDetailPanelActionButtonState(
            visible = visible,
            enabled = enabled,
            disabledReason = disabledReason,
        )
    }
}
