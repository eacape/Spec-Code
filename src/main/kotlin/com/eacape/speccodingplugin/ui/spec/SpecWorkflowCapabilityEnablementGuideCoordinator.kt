package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot
import com.eacape.speccodingplugin.ui.settings.SettingsSidebarSection

internal enum class SpecWorkflowCapabilityEnablementTiming {
    NOW,
    NEXT,
    LATER,
    ;

    fun presentation(): String {
        val key = when (this) {
            NOW -> "spec.dialog.capabilityGuide.timing.now"
            NEXT -> "spec.dialog.capabilityGuide.timing.next"
            LATER -> "spec.dialog.capabilityGuide.timing.later"
        }
        return SpecCodingBundle.message(key)
    }
}

internal data class SpecWorkflowCapabilityEnablementGuideItem(
    val section: SettingsSidebarSection,
    val timing: SpecWorkflowCapabilityEnablementTiming,
    val detail: String,
) {
    fun title(): String = SpecCodingBundle.message(section.titleKey)
}

internal data class SpecWorkflowCapabilityEnablementGuide(
    val summary: String,
    val items: List<SpecWorkflowCapabilityEnablementGuideItem>,
)

internal object SpecWorkflowCapabilityEnablementGuideCoordinator {
    fun build(
        readiness: LocalEnvironmentReadinessSnapshot,
        tracking: SpecWorkflowFirstRunTrackingSnapshot,
    ): SpecWorkflowCapabilityEnablementGuide {
        val stage = when {
            tracking.createSuccessCount > 0 -> GuideStage.AFTER_FIRST_SUCCESS
            readiness.quickTaskReady -> GuideStage.PRE_FIRST_SUCCESS
            else -> GuideStage.BLOCKED
        }
        return SpecWorkflowCapabilityEnablementGuide(
            summary = SpecCodingBundle.message(
                when (stage) {
                    GuideStage.BLOCKED -> "spec.dialog.capabilityGuide.summary.blocked"
                    GuideStage.PRE_FIRST_SUCCESS -> "spec.dialog.capabilityGuide.summary.preFirstSuccess"
                    GuideStage.AFTER_FIRST_SUCCESS -> "spec.dialog.capabilityGuide.summary.afterFirstSuccess"
                },
            ),
            items = listOf(
                promptItem(stage),
                skillItem(stage),
                hookItem(stage),
                mcpItem(stage),
            ),
        )
    }

    private fun promptItem(stage: GuideStage): SpecWorkflowCapabilityEnablementGuideItem {
        val path = settingsPath(SettingsSidebarSection.PROMPTS)
        return SpecWorkflowCapabilityEnablementGuideItem(
            section = SettingsSidebarSection.PROMPTS,
            timing = if (stage == GuideStage.AFTER_FIRST_SUCCESS) {
                SpecWorkflowCapabilityEnablementTiming.NOW
            } else {
                SpecWorkflowCapabilityEnablementTiming.NEXT
            },
            detail = SpecCodingBundle.message(
                when (stage) {
                    GuideStage.BLOCKED -> "spec.dialog.capabilityGuide.prompts.blocked"
                    GuideStage.PRE_FIRST_SUCCESS -> "spec.dialog.capabilityGuide.prompts.preFirstSuccess"
                    GuideStage.AFTER_FIRST_SUCCESS -> "spec.dialog.capabilityGuide.prompts.afterFirstSuccess"
                },
                path,
            ),
        )
    }

    private fun skillItem(stage: GuideStage): SpecWorkflowCapabilityEnablementGuideItem {
        val path = settingsPath(SettingsSidebarSection.SKILLS)
        return SpecWorkflowCapabilityEnablementGuideItem(
            section = SettingsSidebarSection.SKILLS,
            timing = if (stage == GuideStage.AFTER_FIRST_SUCCESS) {
                SpecWorkflowCapabilityEnablementTiming.NEXT
            } else {
                SpecWorkflowCapabilityEnablementTiming.LATER
            },
            detail = SpecCodingBundle.message(
                when (stage) {
                    GuideStage.BLOCKED -> "spec.dialog.capabilityGuide.skills.blocked"
                    GuideStage.PRE_FIRST_SUCCESS -> "spec.dialog.capabilityGuide.skills.preFirstSuccess"
                    GuideStage.AFTER_FIRST_SUCCESS -> "spec.dialog.capabilityGuide.skills.afterFirstSuccess"
                },
                path,
            ),
        )
    }

    private fun hookItem(stage: GuideStage): SpecWorkflowCapabilityEnablementGuideItem {
        return experimentalItem(
            section = SettingsSidebarSection.HOOKS,
            stage = stage,
            blockedKey = "spec.dialog.capabilityGuide.hooks.blocked",
            readyKey = "spec.dialog.capabilityGuide.hooks.ready",
        )
    }

    private fun mcpItem(stage: GuideStage): SpecWorkflowCapabilityEnablementGuideItem {
        return experimentalItem(
            section = SettingsSidebarSection.MCP,
            stage = stage,
            blockedKey = "spec.dialog.capabilityGuide.mcp.blocked",
            readyKey = "spec.dialog.capabilityGuide.mcp.ready",
        )
    }

    private fun experimentalItem(
        section: SettingsSidebarSection,
        stage: GuideStage,
        blockedKey: String,
        readyKey: String,
    ): SpecWorkflowCapabilityEnablementGuideItem {
        val path = experimentalSettingsPath(section)
        return SpecWorkflowCapabilityEnablementGuideItem(
            section = section,
            timing = SpecWorkflowCapabilityEnablementTiming.LATER,
            detail = SpecCodingBundle.message(
                if (stage == GuideStage.BLOCKED) blockedKey else readyKey,
                path,
            ),
        )
    }

    private fun settingsPath(section: SettingsSidebarSection): String {
        return SpecCodingBundle.message(
            "spec.dialog.capabilityGuide.path.settings",
            SpecCodingBundle.message(section.titleKey),
        )
    }

    private fun experimentalSettingsPath(section: SettingsSidebarSection): String {
        return SpecCodingBundle.message(
            "spec.dialog.capabilityGuide.path.experimental",
            SpecCodingBundle.message("settings.experimental.toggle.show"),
            SpecCodingBundle.message(section.titleKey),
        )
    }

    private enum class GuideStage {
        BLOCKED,
        PRE_FIRST_SUCCESS,
        AFTER_FIRST_SUCCESS,
    }
}
