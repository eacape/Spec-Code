package com.eacape.speccodingplugin.ui.spec

import java.awt.Cursor
import java.awt.Component
import javax.swing.JButton

internal enum class SpecDetailComposerActionId(val value: String) {
    GENERATE("generate"),
    OPEN_EDITOR("openEditor"),
    HISTORY_DIFF("historyDiff"),
    EDIT("edit"),
    SAVE("save"),
    CANCEL_EDIT("cancelEdit"),
    CONFIRM_GENERATE("confirmGenerate"),
    REGENERATE_CLARIFICATION("regenerateClarification"),
    SKIP_CLARIFICATION("skipClarification"),
    CANCEL_CLARIFICATION("cancelClarification"),
}

internal data class SpecDetailActionBarButtons(
    val generate: JButton,
    val nextPhase: JButton,
    val goBack: JButton,
    val complete: JButton,
    val pauseResume: JButton,
    val openEditor: JButton,
    val historyDiff: JButton,
    val edit: JButton,
    val save: JButton,
    val cancelEdit: JButton,
    val confirmGenerate: JButton,
    val regenerateClarification: JButton,
    val skipClarification: JButton,
    val cancelClarification: JButton,
) {
    companion object {
        fun create(buttonFactory: () -> JButton = { JButton() }): SpecDetailActionBarButtons {
            return SpecDetailActionBarButtons(
                generate = buttonFactory(),
                nextPhase = buttonFactory(),
                goBack = buttonFactory(),
                complete = buttonFactory(),
                pauseResume = buttonFactory(),
                openEditor = buttonFactory(),
                historyDiff = buttonFactory(),
                edit = buttonFactory(),
                save = buttonFactory(),
                cancelEdit = buttonFactory(),
                confirmGenerate = buttonFactory(),
                regenerateClarification = buttonFactory(),
                skipClarification = buttonFactory(),
                cancelClarification = buttonFactory(),
            )
        }
    }

    fun all(): List<JButton> {
        return listOf(
            generate,
            nextPhase,
            goBack,
            complete,
            pauseResume,
            openEditor,
            historyDiff,
            edit,
            save,
            cancelEdit,
            confirmGenerate,
            regenerateClarification,
            skipClarification,
            cancelClarification,
        )
    }

    fun composerActions(): List<JButton> {
        return composerActionEntries().map { it.second }
    }

    fun composerActionId(button: JButton): String? {
        return composerActionEntries()
            .firstOrNull { (_, candidate) -> candidate === button }
            ?.first
            ?.value
    }

    fun visibleComposerActionOrder(components: Iterable<Component>): List<String> {
        return components.mapNotNull { component ->
            val button = component as? JButton ?: return@mapNotNull null
            if (!button.isVisible) {
                return@mapNotNull null
            }
            composerActionId(button)
        }
    }

    fun stateSnapshotForTest(): Map<String, Any> {
        return mapOf(
            "generateEnabled" to generate.isEnabled,
            "generateVisible" to generate.isVisible,
            "generateIconId" to SpecWorkflowIcons.debugId(generate.icon),
            "generateFocusable" to generate.isFocusable,
            "generateTooltip" to generate.toolTipText.orEmpty(),
            "generateAccessibleName" to (generate.accessibleContext?.accessibleName ?: ""),
            "generateAccessibleDescription" to (generate.accessibleContext?.accessibleDescription ?: ""),
            "nextEnabled" to nextPhase.isEnabled,
            "nextIconId" to SpecWorkflowIcons.debugId(nextPhase.icon),
            "nextFocusable" to nextPhase.isFocusable,
            "goBackEnabled" to goBack.isEnabled,
            "goBackIconId" to SpecWorkflowIcons.debugId(goBack.icon),
            "goBackFocusable" to goBack.isFocusable,
            "completeEnabled" to complete.isEnabled,
            "completeIconId" to SpecWorkflowIcons.debugId(complete.icon),
            "completeFocusable" to complete.isFocusable,
            "completeVisible" to complete.isVisible,
            "pauseResumeEnabled" to pauseResume.isEnabled,
            "pauseResumeIconId" to SpecWorkflowIcons.debugId(pauseResume.icon),
            "pauseResumeText" to (pauseResume.toolTipText ?: pauseResume.text),
            "pauseResumeFocusable" to pauseResume.isFocusable,
            "pauseResumeVisible" to pauseResume.isVisible,
            "openEditorEnabled" to openEditor.isEnabled,
            "openEditorIconId" to SpecWorkflowIcons.debugId(openEditor.icon),
            "openEditorFocusable" to openEditor.isFocusable,
            "openEditorVisible" to openEditor.isVisible,
            "historyDiffEnabled" to historyDiff.isEnabled,
            "historyDiffIconId" to SpecWorkflowIcons.debugId(historyDiff.icon),
            "historyDiffFocusable" to historyDiff.isFocusable,
            "historyDiffVisible" to historyDiff.isVisible,
            "editEnabled" to edit.isEnabled,
            "editVisible" to edit.isVisible,
            "editTooltip" to edit.toolTipText.orEmpty(),
            "editAccessibleName" to (edit.accessibleContext?.accessibleName ?: ""),
            "saveVisible" to save.isVisible,
            "cancelEditVisible" to cancelEdit.isVisible,
            "confirmGenerateEnabled" to confirmGenerate.isEnabled,
            "confirmGenerateVisible" to confirmGenerate.isVisible,
            "confirmGenerateIconId" to SpecWorkflowIcons.debugId(confirmGenerate.icon),
            "confirmGenerateFocusable" to confirmGenerate.isFocusable,
            "confirmGenerateTooltip" to confirmGenerate.toolTipText.orEmpty(),
            "confirmGenerateAccessibleName" to (confirmGenerate.accessibleContext?.accessibleName ?: ""),
            "confirmGenerateAccessibleDescription" to (confirmGenerate.accessibleContext?.accessibleDescription ?: ""),
            "regenerateClarificationEnabled" to regenerateClarification.isEnabled,
            "regenerateClarificationVisible" to regenerateClarification.isVisible,
            "regenerateClarificationIconId" to SpecWorkflowIcons.debugId(regenerateClarification.icon),
            "regenerateClarificationFocusable" to regenerateClarification.isFocusable,
            "skipClarificationEnabled" to skipClarification.isEnabled,
            "skipClarificationVisible" to skipClarification.isVisible,
            "skipClarificationIconId" to SpecWorkflowIcons.debugId(skipClarification.icon),
            "skipClarificationFocusable" to skipClarification.isFocusable,
            "cancelClarificationEnabled" to cancelClarification.isEnabled,
            "cancelClarificationVisible" to cancelClarification.isVisible,
            "cancelClarificationIconId" to SpecWorkflowIcons.debugId(cancelClarification.icon),
            "cancelClarificationFocusable" to cancelClarification.isFocusable,
        )
    }

    private fun composerActionEntries(): List<Pair<SpecDetailComposerActionId, JButton>> {
        return listOf(
            SpecDetailComposerActionId.GENERATE to generate,
            SpecDetailComposerActionId.OPEN_EDITOR to openEditor,
            SpecDetailComposerActionId.HISTORY_DIFF to historyDiff,
            SpecDetailComposerActionId.EDIT to edit,
            SpecDetailComposerActionId.SAVE to save,
            SpecDetailComposerActionId.CANCEL_EDIT to cancelEdit,
            SpecDetailComposerActionId.CONFIRM_GENERATE to confirmGenerate,
            SpecDetailComposerActionId.REGENERATE_CLARIFICATION to regenerateClarification,
            SpecDetailComposerActionId.SKIP_CLARIFICATION to skipClarification,
            SpecDetailComposerActionId.CANCEL_CLARIFICATION to cancelClarification,
        )
    }
}

internal class SpecDetailActionBarPresenter(
    private val buttons: SpecDetailActionBarButtons,
) {

    fun applyPresentation(presentation: SpecDetailActionBarPresentation) {
        applyPresentation(buttons.generate, presentation.generate)
        applyPresentation(buttons.nextPhase, presentation.nextPhase)
        applyPresentation(buttons.goBack, presentation.goBack)
        applyPresentation(buttons.complete, presentation.complete)
        applyPresentation(buttons.pauseResume, presentation.pauseResume)
        applyPresentation(buttons.openEditor, presentation.openEditor)
        applyPresentation(buttons.historyDiff, presentation.historyDiff)
        applyPresentation(buttons.edit, presentation.edit)
        applyPresentation(buttons.save, presentation.save)
        applyPresentation(buttons.cancelEdit, presentation.cancelEdit)
        applyPresentation(buttons.confirmGenerate, presentation.confirmGenerate)
        applyPresentation(buttons.regenerateClarification, presentation.regenerateClarification)
        applyPresentation(buttons.skipClarification, presentation.skipClarification)
        applyPresentation(buttons.cancelClarification, presentation.cancelClarification)
    }

    fun apply(state: SpecDetailPanelActionState) {
        apply(buttons.generate, state.generate)
        apply(buttons.nextPhase, state.nextPhase)
        apply(buttons.goBack, state.goBack)
        apply(buttons.complete, state.complete)
        apply(buttons.pauseResume, state.pauseResume)
        apply(buttons.openEditor, state.openEditor)
        apply(buttons.historyDiff, state.historyDiff)
        apply(buttons.edit, state.edit)
        apply(buttons.save, state.save)
        apply(buttons.cancelEdit, state.cancelEdit)
        apply(buttons.confirmGenerate, state.confirmGenerate)
        apply(buttons.regenerateClarification, state.regenerateClarification)
        apply(buttons.skipClarification, state.skipClarification)
        apply(buttons.cancelClarification, state.cancelClarification)
    }

    fun applyEmptyState() {
        buttons.generate.isVisible = true
        buttons.nextPhase.isVisible = true
        buttons.goBack.isVisible = true
        buttons.complete.isVisible = false
        buttons.pauseResume.isVisible = false
        buttons.openEditor.isVisible = true
        buttons.historyDiff.isVisible = true
        buttons.edit.isVisible = true
        buttons.save.isVisible = false
        buttons.cancelEdit.isVisible = false
        buttons.confirmGenerate.isVisible = false
        buttons.regenerateClarification.isVisible = false
        buttons.skipClarification.isVisible = false
        buttons.cancelClarification.isVisible = false
        disableAll()
    }

    fun disableAll() {
        buttons.all().forEach { button ->
            SpecUiStyle.setIconActionEnabled(
                button = button,
                enabled = false,
            )
            updateCursor(button)
        }
    }

    fun refreshCursors() {
        buttons.all().forEach(::updateCursor)
    }

    private fun apply(button: JButton, state: SpecDetailPanelActionButtonState) {
        button.isVisible = state.visible
        SpecUiStyle.setIconActionEnabled(
            button = button,
            enabled = state.enabled,
            disabledReason = state.disabledReason,
        )
        updateCursor(button)
    }

    private fun applyPresentation(button: JButton, presentation: SpecIconActionPresentation) {
        SpecUiStyle.configureIconActionButton(
            button = button,
            icon = presentation.icon,
            text = presentation.text,
            tooltip = presentation.tooltip,
            accessibleName = presentation.accessibleName,
        )
        updateCursor(button)
    }

    private fun updateCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }
}
