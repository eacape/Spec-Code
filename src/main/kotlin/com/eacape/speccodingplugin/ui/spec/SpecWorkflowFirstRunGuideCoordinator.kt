package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.spec.WorkflowTemplates
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot

internal data class SpecWorkflowFirstRunGuide(
    val summary: String,
    val steps: List<String>,
)

internal object SpecWorkflowFirstRunGuideCoordinator {
    fun build(
        selectedEntry: SpecWorkflowPrimaryEntry,
        template: WorkflowTemplate,
        readiness: LocalEnvironmentReadinessSnapshot,
    ): SpecWorkflowFirstRunGuide {
        return when (selectedEntry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> {
                if (readiness.quickTaskReady) {
                    quickTaskGuide()
                } else {
                    blockedGuide()
                }
            }

            SpecWorkflowPrimaryEntry.FULL_SPEC -> when {
                readiness.fullSpecReady -> fullSpecGuide()
                readiness.quickTaskReady -> fullSpecFallbackGuide()
                else -> blockedGuide()
            }

            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> {
                if (readiness.quickTaskReady) {
                    advancedGuide(template)
                } else {
                    blockedGuide()
                }
            }
        }
    }

    private fun quickTaskGuide(): SpecWorkflowFirstRunGuide {
        val artifact = firstVisibleArtifact(WorkflowTemplate.QUICK_TASK)
        return SpecWorkflowFirstRunGuide(
            summary = SpecCodingBundle.message("spec.dialog.firstRun.quickTask.summary"),
            steps = listOf(
                SpecCodingBundle.message("spec.dialog.firstRun.quickTask.step.scope"),
                SpecCodingBundle.message("spec.dialog.firstRun.quickTask.step.start", artifact),
                SpecCodingBundle.message("spec.dialog.firstRun.quickTask.step.finish"),
            ),
        )
    }

    private fun fullSpecGuide(): SpecWorkflowFirstRunGuide {
        val artifact = firstVisibleArtifact(WorkflowTemplate.FULL_SPEC)
        return SpecWorkflowFirstRunGuide(
            summary = SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.summary"),
            steps = listOf(
                SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.step.scope"),
                SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.step.start", artifact),
                SpecCodingBundle.message("spec.dialog.firstRun.fullSpec.step.finish"),
            ),
        )
    }

    private fun fullSpecFallbackGuide(): SpecWorkflowFirstRunGuide {
        val artifact = firstVisibleArtifact(WorkflowTemplate.QUICK_TASK)
        return SpecWorkflowFirstRunGuide(
            summary = SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.summary"),
            steps = listOf(
                SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.step.switch"),
                SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.step.start", artifact),
                SpecCodingBundle.message("spec.dialog.firstRun.fullSpecBlocked.step.return"),
            ),
        )
    }

    private fun advancedGuide(template: WorkflowTemplate): SpecWorkflowFirstRunGuide {
        val artifact = firstVisibleArtifact(template)
        val templateLabel = SpecWorkflowOverviewPresenter.templateLabel(template)
        return SpecWorkflowFirstRunGuide(
            summary = SpecCodingBundle.message("spec.dialog.firstRun.advanced.summary"),
            steps = listOf(
                SpecCodingBundle.message("spec.dialog.firstRun.advanced.step.confirm"),
                SpecCodingBundle.message(
                    "spec.dialog.firstRun.advanced.step.start",
                    templateLabel,
                    artifact,
                ),
                SpecCodingBundle.message("spec.dialog.firstRun.advanced.step.finish"),
            ),
        )
    }

    private fun blockedGuide(): SpecWorkflowFirstRunGuide {
        val artifact = firstVisibleArtifact(WorkflowTemplate.QUICK_TASK)
        return SpecWorkflowFirstRunGuide(
            summary = SpecCodingBundle.message("spec.dialog.firstRun.blocked.summary"),
            steps = listOf(
                SpecCodingBundle.message("spec.dialog.firstRun.blocked.step.fix"),
                SpecCodingBundle.message("spec.dialog.firstRun.blocked.step.retry"),
                SpecCodingBundle.message("spec.dialog.firstRun.blocked.step.expect", artifact),
            ),
        )
    }

    private fun firstVisibleArtifact(template: WorkflowTemplate): String {
        if (template == WorkflowTemplate.DIRECT_IMPLEMENT) {
            return StageId.TASKS.artifactFileName.orEmpty()
        }
        return WorkflowTemplates
            .definitionOf(template)
            .stagePlan
            .firstNotNullOfOrNull { item -> item.id.artifactFileName }
            ?: StageId.TASKS.artifactFileName.orEmpty()
    }
}
