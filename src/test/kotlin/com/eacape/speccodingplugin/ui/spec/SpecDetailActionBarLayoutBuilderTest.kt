package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBScrollPane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JButton

class SpecDetailActionBarLayoutBuilderTest {

    @Test
    fun `build should initialize presentation bind listeners and mount composer actions in stable order`() {
        val buttons = buttons()
        var refreshCalls = 0

        val layout = builder(
            buttons = buttons,
            initializePresentation = { refreshCalls += 1 },
        ).build()

        assertEquals(1, refreshCalls)
        assertEquals(buttons.composerActions(), layout.buttonPanel.components.toList())
        assertEquals(1, buttons.generate.actionListeners.size)
        assertEquals(1, buttons.openEditor.actionListeners.size)
        assertEquals(1, buttons.skipClarification.actionListeners.size)
        assertFalse(buttons.generate.isEnabled)
        assertFalse(buttons.edit.isEnabled)
    }

    @Test
    fun `build should apply setup visibility and wrap button panel in footer scroll container`() {
        val buttons = buttons()

        val layout = builder(buttons = buttons).build()
        val scrollPane = layout.footerContainer.getComponent(0) as JBScrollPane

        assertSame(layout.buttonPanel, scrollPane.viewport.view)
        assertTrue(buttons.generate.isVisible)
        assertTrue(buttons.edit.isVisible)
        assertFalse(buttons.save.isVisible)
        assertFalse(buttons.cancelEdit.isVisible)
        assertFalse(buttons.confirmGenerate.isVisible)
        assertFalse(buttons.regenerateClarification.isVisible)
        assertFalse(buttons.skipClarification.isVisible)
        assertFalse(buttons.cancelClarification.isVisible)
    }

    private fun builder(
        buttons: SpecDetailActionBarButtons,
        initializePresentation: () -> Unit = {},
    ): SpecDetailActionBarLayoutBuilder {
        return SpecDetailActionBarLayoutBuilder(
            buttons = buttons,
            presenter = SpecDetailActionBarPresenter(buttons),
            chromePresenter = SpecDetailActionBarChromePresenter(buttons),
            commandAdapter = SpecDetailActionBarCommandAdapter(
                buttons = buttons,
                context = SpecDetailActionBarCommandContext(
                    inputText = { "" },
                    currentWorkflow = { null },
                    selectedPhase = { null },
                    workbenchArtifactFileName = { null },
                    canGenerateWithEmptyInput = { false },
                    resolveDetailViewState = {
                        SpecDetailPanelViewState(
                            artifactOnlyView = false,
                            displayedDocumentPhase = null,
                            editablePhase = null,
                            revisionLockedPhase = null,
                            selectedDocumentAvailable = false,
                            artifactOpenAvailable = false,
                        )
                    },
                    clarificationState = { null },
                    clarificationText = {
                        SpecDetailClarificationText(
                            confirmedTitle = "Confirmed",
                            notApplicableTitle = "Not applicable",
                            detailPrefix = "Detail",
                            confirmedSectionMarkers = listOf("confirmed"),
                            notApplicableSectionMarkers = listOf("not applicable"),
                        )
                    },
                ),
                callbacks = SpecDetailActionBarCommandCallbacks(
                    onGenerate = {},
                    onInputRequired = {},
                    onClearInput = {},
                    onNextPhase = {},
                    onGoBack = {},
                    onComplete = {},
                    onPauseResume = {},
                    onOpenInEditor = {},
                    onOpenArtifactInEditor = {},
                    onShowHistoryDiff = {},
                    onSetExplicitRevisionPhase = {},
                    onStartEditing = {},
                    onSaveEditing = {},
                    onCancelEditing = {},
                    onApplyClarificationActionPlan = {},
                ),
            ),
            footerDivider = Color.GRAY,
            initializePresentation = initializePresentation,
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
