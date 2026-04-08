package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot

internal data class SpecWorkflowOnboardingPlan(
    val recommendedEntry: SpecWorkflowPrimaryEntry,
    val summary: String,
    val nextStep: String,
    val showSettingsShortcut: Boolean,
)

internal object SpecWorkflowOnboardingCoordinator {
    fun build(
        requestedTemplate: WorkflowTemplate,
        readiness: LocalEnvironmentReadinessSnapshot,
    ): SpecWorkflowOnboardingPlan {
        val requestedEntry = SpecWorkflowEntryPaths.primaryEntryForTemplate(requestedTemplate)
        val recommendedEntry = when {
            requestedEntry == SpecWorkflowPrimaryEntry.FULL_SPEC && readiness.fullSpecReady -> {
                SpecWorkflowPrimaryEntry.FULL_SPEC
            }

            requestedEntry == SpecWorkflowPrimaryEntry.QUICK_TASK && readiness.quickTaskReady -> {
                SpecWorkflowPrimaryEntry.QUICK_TASK
            }

            readiness.quickTaskReady -> SpecWorkflowPrimaryEntry.QUICK_TASK
            else -> SpecWorkflowPrimaryEntry.QUICK_TASK
        }

        val summaryKey = when {
            !readiness.quickTaskReady -> "spec.dialog.onboarding.summary.blocked"
            recommendedEntry == SpecWorkflowPrimaryEntry.FULL_SPEC -> "spec.dialog.onboarding.summary.fullSpec"
            else -> "spec.dialog.onboarding.summary.quickTask"
        }
        val nextStepKey = when {
            !readiness.quickTaskReady -> "spec.dialog.onboarding.next.blocked"
            readiness.fullSpecReady && recommendedEntry == SpecWorkflowPrimaryEntry.FULL_SPEC -> {
                "spec.dialog.onboarding.next.fullSpec"
            }

            readiness.fullSpecReady -> "spec.dialog.onboarding.next.quickTask"
            else -> "spec.dialog.onboarding.next.quickOnly"
        }

        return SpecWorkflowOnboardingPlan(
            recommendedEntry = recommendedEntry,
            summary = SpecCodingBundle.message(summaryKey),
            nextStep = SpecCodingBundle.message(nextStepKey),
            showSettingsShortcut = !readiness.fullSpecReady || !readiness.quickTaskReady,
        )
    }

    fun isEntryReady(
        entry: SpecWorkflowPrimaryEntry,
        readiness: LocalEnvironmentReadinessSnapshot,
    ): Boolean {
        return when (entry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> readiness.quickTaskReady
            SpecWorkflowPrimaryEntry.FULL_SPEC -> readiness.fullSpecReady
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> true
        }
    }

    fun blockedEntryValidationMessage(
        entry: SpecWorkflowPrimaryEntry,
        readiness: LocalEnvironmentReadinessSnapshot,
    ): String? {
        return when (entry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> {
                if (readiness.quickTaskReady) {
                    null
                } else {
                    SpecCodingBundle.message(
                        "spec.dialog.validation.quickTaskBlocked",
                        readiness.quickTaskCheck.detail,
                    )
                }
            }

            SpecWorkflowPrimaryEntry.FULL_SPEC -> {
                if (readiness.fullSpecReady) {
                    null
                } else {
                    SpecCodingBundle.message(
                        "spec.dialog.validation.fullSpecBlocked",
                        readiness.fullSpecCheck.detail,
                    )
                }
            }

            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> null
        }
    }
}
