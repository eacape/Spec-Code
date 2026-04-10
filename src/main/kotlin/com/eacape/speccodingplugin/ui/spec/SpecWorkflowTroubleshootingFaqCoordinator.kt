package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.ui.LocalEnvironmentCheck
import com.eacape.speccodingplugin.ui.LocalEnvironmentCheckSeverity
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot
import com.eacape.speccodingplugin.ui.settings.SettingsSidebarSection

internal data class SpecWorkflowTroubleshootingFaqItem(
    val question: String,
    val answer: String,
)

internal data class SpecWorkflowTroubleshootingFaq(
    val summary: String,
    val items: List<SpecWorkflowTroubleshootingFaqItem>,
    val actions: List<SpecWorkflowTroubleshootingAction>,
)

internal sealed interface SpecWorkflowTroubleshootingAction {
    val label: String

    data class OpenSettings(
        override val label: String,
    ) : SpecWorkflowTroubleshootingAction

    data class OpenBundledDemo(
        override val label: String,
    ) : SpecWorkflowTroubleshootingAction

    data class SelectEntry(
        val entry: SpecWorkflowPrimaryEntry,
        override val label: String,
    ) : SpecWorkflowTroubleshootingAction
}

internal object SpecWorkflowTroubleshootingFaqCoordinator {
    fun build(
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        template: WorkflowTemplate,
    ): SpecWorkflowTroubleshootingFaq {
        return SpecWorkflowTroubleshootingFaq(
            summary = summary(readiness, tracking),
            items = listOf(
                environmentItem(readiness, template),
                permissionItem(readiness),
                executionItem(readiness, tracking, template),
                contextItem(readiness, template),
            ),
            actions = actions(readiness, tracking, template),
        )
    }

    private fun summary(
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
    ): String {
        val key = when {
            !readiness.quickTaskReady -> "spec.dialog.troubleshooting.summary.blocked"
            tracking.createAttemptCount > 0 && tracking.createSuccessCount == 0 ->
                "spec.dialog.troubleshooting.summary.retry"

            else -> "spec.dialog.troubleshooting.summary.ready"
        }
        return SpecCodingBundle.message(key)
    }

    private fun environmentItem(
        readiness: LocalEnvironmentReadinessSnapshot,
        template: WorkflowTemplate,
    ): SpecWorkflowTroubleshootingFaqItem {
        val answer = when {
            !readiness.quickTaskReady -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.environment.blocked",
                    readiness.quickTaskCheck.detail,
                    settingsPath(SettingsSidebarSection.BASIC),
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
                )
            }

            template == WorkflowTemplate.FULL_SPEC && !readiness.fullSpecReady -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.environment.fullSpecBlocked",
                    readiness.fullSpecCheck.detail,
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
                    settingsPath(SettingsSidebarSection.BASIC),
                )
            }

            else -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.environment.ready",
                    SpecWorkflowOverviewPresenter.templateLabel(template),
                    settingsPath(SettingsSidebarSection.BASIC),
                )
            }
        }
        return item("spec.dialog.troubleshooting.environment.question", answer)
    }

    private fun permissionItem(readiness: LocalEnvironmentReadinessSnapshot): SpecWorkflowTroubleshootingFaqItem {
        val diagnostics = buildPermissionDiagnostics(readiness)
        val answer = if (diagnostics.isNotEmpty()) {
            SpecCodingBundle.message(
                "spec.dialog.troubleshooting.permission.issue",
                diagnostics.joinToString(SpecCodingBundle.message("local.setup.list.delimiter")),
                settingsPath(SettingsSidebarSection.BASIC),
            )
        } else {
            SpecCodingBundle.message(
                "spec.dialog.troubleshooting.permission.ready",
                settingsPath(SettingsSidebarSection.BASIC),
            )
        }
        return item("spec.dialog.troubleshooting.permission.question", answer)
    }

    private fun executionItem(
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        template: WorkflowTemplate,
    ): SpecWorkflowTroubleshootingFaqItem {
        val fallbackTemplate = if (template == WorkflowTemplate.FULL_SPEC && !readiness.fullSpecReady) {
            WorkflowTemplate.QUICK_TASK
        } else {
            template
        }
        val artifact = SpecWorkflowFirstRunTrackingStore.firstVisibleArtifact(fallbackTemplate)
        val demoAction = SpecCodingBundle.message("spec.dialog.demo.open")
        val target = formatDuration(SpecWorkflowFirstRunTrackingStore.FIRST_VISIBLE_ARTIFACT_TARGET_MILLIS)
        val answer = when {
            tracking.createSuccessCount > 0 &&
                tracking.lastSuccessArtifactFileName != null &&
                tracking.lastSuccessWorkflowId != null -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.execution.success",
                    tracking.lastSuccessArtifactFileName,
                    tracking.lastSuccessWorkflowId,
                    demoAction,
                )
            }

            tracking.createAttemptCount > 0 -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.execution.retry",
                    artifact,
                    target,
                    demoAction,
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
                )
            }

            else -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.execution.pending",
                    artifact,
                    demoAction,
                )
            }
        }
        return item("spec.dialog.troubleshooting.execution.question", answer)
    }

    private fun contextItem(
        readiness: LocalEnvironmentReadinessSnapshot,
        template: WorkflowTemplate,
    ): SpecWorkflowTroubleshootingFaqItem {
        val answer = when {
            !readiness.quickTaskReady -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.context.blocked",
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
                )
            }

            template == WorkflowTemplate.QUICK_TASK && readiness.fullSpecReady -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.context.quickTask",
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC),
                    settingsPath(SettingsSidebarSection.PROMPTS),
                    SpecCodingBundle.message(SettingsSidebarSection.MCP.titleKey),
                )
            }

            template == WorkflowTemplate.FULL_SPEC && !readiness.fullSpecReady -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.context.fullSpecBlocked",
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
                )
            }

            template == WorkflowTemplate.FULL_SPEC -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.context.fullSpecReady",
                    settingsPath(SettingsSidebarSection.PROMPTS),
                    SpecCodingBundle.message(SettingsSidebarSection.HOOKS.titleKey),
                    SpecCodingBundle.message(SettingsSidebarSection.MCP.titleKey),
                )
            }

            else -> {
                SpecCodingBundle.message(
                    "spec.dialog.troubleshooting.context.advanced",
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC),
                )
            }
        }
        return item("spec.dialog.troubleshooting.context.question", answer)
    }

    private fun actions(
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
        template: WorkflowTemplate,
    ): List<SpecWorkflowTroubleshootingAction> {
        val openSettings = actionOpenSettings()
        val openDemo = actionOpenBundledDemo()
        val permissionDiagnostics = buildPermissionDiagnostics(readiness)
        return when {
            !readiness.quickTaskReady -> listOf(
                openSettings,
                openDemo,
            )

            permissionDiagnostics.isNotEmpty() -> listOf(
                openSettings,
                openDemo,
            )

            template == WorkflowTemplate.FULL_SPEC && !readiness.fullSpecReady -> listOf(
                actionSelectEntry(WorkflowTemplate.QUICK_TASK),
                openSettings,
            )

            tracking.createAttemptCount > 0 && tracking.createSuccessCount == 0 -> if (template == WorkflowTemplate.QUICK_TASK) {
                listOf(
                    openDemo,
                    openSettings,
                )
            } else {
                listOf(
                    actionSelectEntry(WorkflowTemplate.QUICK_TASK),
                    openDemo,
                )
            }

            template == WorkflowTemplate.QUICK_TASK && readiness.fullSpecReady -> listOf(
                actionSelectEntry(WorkflowTemplate.FULL_SPEC),
                openDemo,
            )

            template == WorkflowTemplate.FULL_SPEC -> listOf(
                openDemo,
            )

            else -> listOf(
                actionSelectEntry(WorkflowTemplate.QUICK_TASK),
                openDemo,
            )
        }
    }

    private fun actionOpenSettings(): SpecWorkflowTroubleshootingAction.OpenSettings {
        return SpecWorkflowTroubleshootingAction.OpenSettings(
            label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openSettings"),
        )
    }

    private fun actionOpenBundledDemo(): SpecWorkflowTroubleshootingAction.OpenBundledDemo {
        return SpecWorkflowTroubleshootingAction.OpenBundledDemo(
            label = SpecCodingBundle.message("spec.dialog.troubleshooting.action.openBundledDemo"),
        )
    }

    private fun actionSelectEntry(template: WorkflowTemplate): SpecWorkflowTroubleshootingAction.SelectEntry {
        val entry = SpecWorkflowEntryPaths.primaryEntryForTemplate(template)
        val key = when (entry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> "spec.dialog.troubleshooting.action.switchToQuickTask"
            SpecWorkflowPrimaryEntry.FULL_SPEC -> "spec.dialog.troubleshooting.action.switchToFullSpec"
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> "spec.dialog.troubleshooting.action.openSettings"
        }
        return SpecWorkflowTroubleshootingAction.SelectEntry(
            entry = entry,
            label = SpecCodingBundle.message(key),
        )
    }

    private fun buildPermissionDiagnostics(readiness: LocalEnvironmentReadinessSnapshot): List<String> {
        return buildList {
            addIfRelevant(findDetailCheck(readiness, "local.setup.check.workspace"))
            addIfRelevant(findDetailCheck(readiness, "local.setup.check.cliPath"))
            addIfRelevant(findDetailCheck(readiness, "local.setup.check.cli"))
        }.distinct()
    }

    private fun MutableList<String>.addIfRelevant(check: LocalEnvironmentCheck?) {
        val candidate = check ?: return
        if (candidate.severity != LocalEnvironmentCheckSeverity.READY || looksLikePermissionIssue(candidate.detail)) {
            add(candidate.label + ": " + candidate.detail)
        }
    }

    private fun findDetailCheck(
        readiness: LocalEnvironmentReadinessSnapshot,
        labelKey: String,
    ): LocalEnvironmentCheck? {
        val label = SpecCodingBundle.message(labelKey)
        return readiness.detailChecks.firstOrNull { it.label == label }
    }

    private fun looksLikePermissionIssue(detail: String): Boolean {
        val normalized = detail.lowercase()
        return normalized.contains("access denied") ||
            normalized.contains("permission denied") ||
            normalized.contains("not writable") ||
            detail.contains("权限") ||
            detail.contains("不可写")
    }

    private fun item(questionKey: String, answer: String): SpecWorkflowTroubleshootingFaqItem {
        return SpecWorkflowTroubleshootingFaqItem(
            question = SpecCodingBundle.message(questionKey),
            answer = answer,
        )
    }

    private fun settingsPath(section: SettingsSidebarSection): String {
        return SpecCodingBundle.message(
            "spec.dialog.capabilityGuide.path.settings",
            SpecCodingBundle.message(section.titleKey),
        )
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }
}
