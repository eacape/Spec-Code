package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal data class SpecDetailActionBarCommandContext(
    val inputText: () -> String,
    val currentWorkflow: () -> SpecWorkflow?,
    val selectedPhase: () -> SpecPhase?,
    val workbenchArtifactFileName: () -> String?,
    val canGenerateWithEmptyInput: () -> Boolean,
    val resolveDetailViewState: (SpecWorkflow) -> SpecDetailPanelViewState,
    val clarificationState: () -> SpecDetailClarificationFormState?,
    val clarificationText: () -> SpecDetailClarificationText,
)

internal data class SpecDetailActionBarCommandCallbacks(
    val onGenerate: (String) -> Unit,
    val onInputRequired: (SpecPhase?) -> Unit,
    val onClearInput: () -> Unit,
    val onNextPhase: () -> Unit,
    val onGoBack: () -> Unit,
    val onComplete: () -> Unit,
    val onPauseResume: () -> Unit,
    val onOpenInEditor: (SpecPhase) -> Unit,
    val onOpenArtifactInEditor: (String) -> Unit,
    val onShowHistoryDiff: (SpecPhase) -> Unit,
    val onSetExplicitRevisionPhase: (SpecPhase) -> Unit,
    val onStartEditing: () -> Unit,
    val onSaveEditing: () -> Unit,
    val onCancelEditing: () -> Unit,
    val onApplyClarificationActionPlan: (SpecDetailClarificationActionPlan) -> Unit,
)

internal class SpecDetailActionBarCommandAdapter(
    private val buttons: SpecDetailActionBarButtons,
    private val context: SpecDetailActionBarCommandContext,
    private val callbacks: SpecDetailActionBarCommandCallbacks,
) {

    fun bind() {
        buttons.generate.addActionListener { handleGenerate() }
        buttons.nextPhase.addActionListener { callbacks.onNextPhase() }
        buttons.goBack.addActionListener { callbacks.onGoBack() }
        buttons.complete.addActionListener { callbacks.onComplete() }
        buttons.pauseResume.addActionListener { callbacks.onPauseResume() }
        buttons.openEditor.addActionListener { handleOpenEditor() }
        buttons.historyDiff.addActionListener { handleHistoryDiff() }
        buttons.edit.addActionListener { handleEdit() }
        buttons.save.addActionListener { callbacks.onSaveEditing() }
        buttons.cancelEdit.addActionListener { callbacks.onCancelEditing() }
        buttons.confirmGenerate.addActionListener { handleClarificationConfirm() }
        buttons.regenerateClarification.addActionListener { handleClarificationRegenerate() }
        buttons.skipClarification.addActionListener { handleClarificationSkip() }
        buttons.cancelClarification.addActionListener { handleClarificationCancel() }
    }

    private fun handleGenerate() {
        when (
            val plan = SpecDetailActionBarCommandCoordinator.resolveGenerate(
                input = context.inputText(),
                phase = context.currentWorkflow()?.currentPhase,
                canReuseLastInput = context.canGenerateWithEmptyInput(),
            )
        ) {
            is SpecDetailActionBarGeneratePlan.InputRequired -> {
                callbacks.onInputRequired(plan.phase)
            }

            is SpecDetailActionBarGeneratePlan.Submit -> {
                callbacks.onGenerate(plan.input)
                callbacks.onClearInput()
            }
        }
    }

    private fun handleOpenEditor() {
        when (
            val plan = SpecDetailActionBarCommandCoordinator.resolveOpenEditor(
                selectedPhase = context.selectedPhase(),
                artifactFileName = context.workbenchArtifactFileName(),
            )
        ) {
            SpecDetailActionBarOpenEditorPlan.Ignore -> Unit
            is SpecDetailActionBarOpenEditorPlan.ArtifactFile -> callbacks.onOpenArtifactInEditor(plan.fileName)
            is SpecDetailActionBarOpenEditorPlan.PhaseDocument -> callbacks.onOpenInEditor(plan.phase)
        }
    }

    private fun handleHistoryDiff() {
        context.selectedPhase()?.let(callbacks.onShowHistoryDiff)
    }

    private fun handleEdit() {
        val viewState = context.currentWorkflow()?.let(context.resolveDetailViewState)
        when (val plan = SpecDetailActionBarCommandCoordinator.resolveEdit(viewState)) {
            SpecDetailActionBarEditPlan.Ignore -> Unit
            is SpecDetailActionBarEditPlan.StartEditing -> {
                plan.explicitRevisionPhase?.let(callbacks.onSetExplicitRevisionPhase)
                callbacks.onStartEditing()
            }
        }
    }

    private fun handleClarificationConfirm() {
        callbacks.onApplyClarificationActionPlan(
            SpecDetailClarificationActionCoordinator.confirm(
                state = context.clarificationState(),
                clarificationInput = context.inputText(),
                clarificationText = context.clarificationText(),
            ),
        )
    }

    private fun handleClarificationRegenerate() {
        callbacks.onApplyClarificationActionPlan(
            SpecDetailClarificationActionCoordinator.regenerate(
                state = context.clarificationState(),
                clarificationInput = context.inputText(),
                clarificationText = context.clarificationText(),
            ),
        )
    }

    private fun handleClarificationSkip() {
        callbacks.onApplyClarificationActionPlan(
            SpecDetailClarificationActionCoordinator.skip(context.clarificationState()),
        )
    }

    private fun handleClarificationCancel() {
        callbacks.onApplyClarificationActionPlan(
            SpecDetailClarificationActionCoordinator.cancel(context.clarificationState()),
        )
    }
}
