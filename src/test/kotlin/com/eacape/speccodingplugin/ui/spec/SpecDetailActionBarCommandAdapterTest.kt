package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton

class SpecDetailActionBarCommandAdapterTest {

    @Test
    fun `bind should submit trimmed generate input and clear composer`() {
        val buttons = buttons()
        var generatedInput: String? = null
        var clearCalls = 0
        var inputRequiredPhase: SpecPhase? = null

        val adapter = SpecDetailActionBarCommandAdapter(
            buttons = buttons,
            context = context(
                inputText = { "  refine workflow  " },
                currentWorkflow = { workflow(phase = SpecPhase.SPECIFY) },
            ),
            callbacks = callbacks(
                onGenerate = { generatedInput = it },
                onInputRequired = { inputRequiredPhase = it },
                onClearInput = { clearCalls += 1 },
            ),
        )

        adapter.bind()
        buttons.generate.doClick()

        assertEquals("refine workflow", generatedInput)
        assertEquals(1, clearCalls)
        assertNull(inputRequiredPhase)
    }

    @Test
    fun `bind should show input required hint when generate input is blank in specify phase`() {
        val buttons = buttons()
        var inputRequiredPhase: SpecPhase? = null
        var generated = false

        val adapter = SpecDetailActionBarCommandAdapter(
            buttons = buttons,
            context = context(
                inputText = { "   " },
                currentWorkflow = { workflow(phase = SpecPhase.SPECIFY) },
            ),
            callbacks = callbacks(
                onGenerate = { generated = true },
                onInputRequired = { inputRequiredPhase = it },
            ),
        )

        adapter.bind()
        buttons.generate.doClick()

        assertFalse(generated)
        assertEquals(SpecPhase.SPECIFY, inputRequiredPhase)
    }

    @Test
    fun `bind should open artifact file when no document phase is selected`() {
        val buttons = buttons()
        val openedArtifacts = mutableListOf<String>()

        val adapter = SpecDetailActionBarCommandAdapter(
            buttons = buttons,
            context = context(
                selectedPhase = { null },
                workbenchArtifactFileName = { "tasks.md" },
            ),
            callbacks = callbacks(
                onOpenArtifactInEditor = openedArtifacts::add,
            ),
        )

        adapter.bind()
        buttons.openEditor.doClick()

        assertEquals(listOf("tasks.md"), openedArtifacts)
    }

    @Test
    fun `bind should set explicit revision phase before starting edit`() {
        val buttons = buttons()
        var explicitRevisionPhase: SpecPhase? = null
        var startedEditing = false

        val adapter = SpecDetailActionBarCommandAdapter(
            buttons = buttons,
            context = context(
                currentWorkflow = { workflow(phase = SpecPhase.DESIGN) },
                resolveDetailViewState = {
                    SpecDetailPanelViewState(
                        artifactOnlyView = false,
                        displayedDocumentPhase = SpecPhase.DESIGN,
                        editablePhase = SpecPhase.DESIGN,
                        revisionLockedPhase = SpecPhase.DESIGN,
                        selectedDocumentAvailable = true,
                        artifactOpenAvailable = false,
                    )
                },
            ),
            callbacks = callbacks(
                onSetExplicitRevisionPhase = { explicitRevisionPhase = it },
                onStartEditing = { startedEditing = true },
            ),
        )

        adapter.bind()
        buttons.edit.doClick()

        assertEquals(SpecPhase.DESIGN, explicitRevisionPhase)
        assertTrue(startedEditing)
    }

    @Test
    fun `bind should forward skip clarification plan`() {
        val buttons = buttons()
        var appliedPlan: SpecDetailClarificationActionPlan? = null

        val adapter = SpecDetailActionBarCommandAdapter(
            buttons = buttons,
            context = context(
                clarificationState = {
                    SpecDetailClarificationFormState(
                        phase = SpecPhase.SPECIFY,
                        input = "clarify persistence",
                        questionsMarkdown = "1. Which store owns the draft?",
                    )
                },
            ),
            callbacks = callbacks(
                onApplyClarificationActionPlan = { appliedPlan = it },
            ),
        )

        adapter.bind()
        buttons.skipClarification.doClick()

        assertEquals(
            SpecDetailClarificationActionPlan.Skip(input = "clarify persistence"),
            appliedPlan,
        )
    }

    private fun context(
        inputText: () -> String = { "" },
        currentWorkflow: () -> SpecWorkflow? = { null },
        selectedPhase: () -> SpecPhase? = { SpecPhase.DESIGN },
        workbenchArtifactFileName: () -> String? = { null },
        canGenerateWithEmptyInput: () -> Boolean = { false },
        resolveDetailViewState: (SpecWorkflow) -> SpecDetailPanelViewState = {
            SpecDetailPanelViewState(
                artifactOnlyView = false,
                displayedDocumentPhase = SpecPhase.DESIGN,
                editablePhase = SpecPhase.DESIGN,
                revisionLockedPhase = null,
                selectedDocumentAvailable = true,
                artifactOpenAvailable = false,
            )
        },
        clarificationState: () -> SpecDetailClarificationFormState? = { null },
        clarificationText: () -> SpecDetailClarificationText = ::clarificationText,
    ): SpecDetailActionBarCommandContext {
        return SpecDetailActionBarCommandContext(
            inputText = inputText,
            currentWorkflow = currentWorkflow,
            selectedPhase = selectedPhase,
            workbenchArtifactFileName = workbenchArtifactFileName,
            canGenerateWithEmptyInput = canGenerateWithEmptyInput,
            resolveDetailViewState = resolveDetailViewState,
            clarificationState = clarificationState,
            clarificationText = clarificationText,
        )
    }

    private fun callbacks(
        onGenerate: (String) -> Unit = {},
        onInputRequired: (SpecPhase?) -> Unit = {},
        onClearInput: () -> Unit = {},
        onOpenArtifactInEditor: (String) -> Unit = {},
        onSetExplicitRevisionPhase: (SpecPhase) -> Unit = {},
        onStartEditing: () -> Unit = {},
        onApplyClarificationActionPlan: (SpecDetailClarificationActionPlan) -> Unit = {},
    ): SpecDetailActionBarCommandCallbacks {
        return SpecDetailActionBarCommandCallbacks(
            onGenerate = onGenerate,
            onInputRequired = onInputRequired,
            onClearInput = onClearInput,
            onNextPhase = {},
            onGoBack = {},
            onComplete = {},
            onPauseResume = {},
            onOpenInEditor = {},
            onOpenArtifactInEditor = onOpenArtifactInEditor,
            onShowHistoryDiff = {},
            onSetExplicitRevisionPhase = onSetExplicitRevisionPhase,
            onStartEditing = onStartEditing,
            onSaveEditing = {},
            onCancelEditing = {},
            onApplyClarificationActionPlan = onApplyClarificationActionPlan,
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

    private fun workflow(phase: SpecPhase): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-action-bar-command-adapter",
            currentPhase = phase,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Action bar",
            description = "adapter test",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun clarificationText(): SpecDetailClarificationText {
        return SpecDetailClarificationText(
            confirmedTitle = "Confirmed",
            notApplicableTitle = "Not applicable",
            detailPrefix = "Detail",
            confirmedSectionMarkers = listOf("confirmed"),
            notApplicableSectionMarkers = listOf("not applicable"),
        )
    }
}
