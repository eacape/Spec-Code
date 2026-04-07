package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactComposeActionMode
import com.eacape.speccodingplugin.spec.WorkflowStatus
import javax.swing.Icon

internal data class SpecDetailActionBarCustomIcons(
    val save: Icon,
    val startRevision: Icon,
)

internal data class SpecDetailActionBarPresentation(
    val generate: SpecIconActionPresentation,
    val nextPhase: SpecIconActionPresentation,
    val goBack: SpecIconActionPresentation,
    val complete: SpecIconActionPresentation,
    val pauseResume: SpecIconActionPresentation,
    val openEditor: SpecIconActionPresentation,
    val historyDiff: SpecIconActionPresentation,
    val edit: SpecIconActionPresentation,
    val save: SpecIconActionPresentation,
    val cancelEdit: SpecIconActionPresentation,
    val confirmGenerate: SpecIconActionPresentation,
    val regenerateClarification: SpecIconActionPresentation,
    val skipClarification: SpecIconActionPresentation,
    val cancelClarification: SpecIconActionPresentation,
)

internal object SpecDetailActionBarPresentationCoordinator {

    fun resolve(
        composeMode: ArtifactComposeActionMode,
        workflowStatus: WorkflowStatus?,
        editRequiresExplicitRevisionStart: Boolean,
        customIcons: SpecDetailActionBarCustomIcons,
    ): SpecDetailActionBarPresentation {
        return SpecDetailActionBarPresentation(
            generate = iconAction(
                icon = SpecWorkflowIcons.Execute,
                tooltip = ArtifactComposeActionUiText.actionLabel(composeMode),
            ),
            nextPhase = iconAction(
                icon = SpecWorkflowIcons.Advance,
                tooltip = SpecCodingBundle.message("spec.detail.nextPhase"),
            ),
            goBack = iconAction(
                icon = SpecWorkflowIcons.Back,
                tooltip = SpecCodingBundle.message("spec.detail.goBack"),
            ),
            complete = iconAction(
                icon = SpecWorkflowIcons.Complete,
                tooltip = SpecCodingBundle.message("spec.detail.complete"),
            ),
            pauseResume = iconAction(
                icon = if (workflowStatus == WorkflowStatus.PAUSED) SpecWorkflowIcons.Resume else SpecWorkflowIcons.Pause,
                tooltip = SpecCodingBundle.message(
                    if (workflowStatus == WorkflowStatus.PAUSED) "spec.detail.resume" else "spec.detail.pause",
                ),
            ),
            openEditor = iconAction(
                icon = SpecWorkflowIcons.OpenToolWindow,
                tooltip = SpecCodingBundle.message("spec.detail.openInEditor"),
            ),
            historyDiff = iconAction(
                icon = SpecWorkflowIcons.History,
                tooltip = SpecCodingBundle.message("spec.detail.historyDiff"),
            ),
            edit = iconAction(
                icon = if (editRequiresExplicitRevisionStart) customIcons.startRevision else SpecWorkflowIcons.Edit,
                tooltip = if (editRequiresExplicitRevisionStart) {
                    SpecCodingBundle.message("spec.detail.revision.start")
                } else {
                    SpecCodingBundle.message("spec.detail.edit")
                },
            ),
            save = iconAction(
                icon = customIcons.save,
                tooltip = SpecCodingBundle.message("spec.detail.save"),
            ),
            cancelEdit = iconAction(
                icon = SpecWorkflowIcons.Close,
                tooltip = SpecCodingBundle.message("spec.detail.cancel"),
            ),
            confirmGenerate = iconAction(
                icon = SpecWorkflowIcons.Execute,
                tooltip = ArtifactComposeActionUiText.clarificationConfirmLabel(composeMode),
            ),
            regenerateClarification = iconAction(
                icon = SpecWorkflowIcons.Refresh,
                tooltip = SpecCodingBundle.message("spec.detail.clarify.regenerate"),
            ),
            skipClarification = iconAction(
                icon = SpecWorkflowIcons.Forward,
                tooltip = SpecCodingBundle.message("spec.detail.clarify.skip"),
            ),
            cancelClarification = iconAction(
                icon = SpecWorkflowIcons.Close,
                tooltip = SpecCodingBundle.message("spec.detail.clarify.cancel"),
            ),
        )
    }

    private fun iconAction(icon: Icon, tooltip: String): SpecIconActionPresentation {
        return SpecIconActionPresentation(
            icon = icon,
            tooltip = tooltip,
        )
    }
}
