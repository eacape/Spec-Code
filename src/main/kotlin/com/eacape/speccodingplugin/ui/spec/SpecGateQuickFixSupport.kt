package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.GateQuickFixDescriptor
import com.eacape.speccodingplugin.spec.GateQuickFixKind
import com.eacape.speccodingplugin.spec.MissingRequirementsSectionsQuickFixPayload
import com.eacape.speccodingplugin.spec.RequirementsSectionSupport
import com.eacape.speccodingplugin.spec.Violation

internal object SpecGateQuickFixSupport {

    data class Presentation(
        val descriptor: GateQuickFixDescriptor,
        val title: String,
        val detail: String? = null,
        val enabled: Boolean = descriptor.enabled,
        val disabledReason: String? = descriptor.disabledReason,
    ) {
        fun popupText(): String {
            val detailSuffix = detail?.takeIf(String::isNotBlank)?.let { value -> " - $value" }.orEmpty()
            val disabledSuffix = if (!enabled && !disabledReason.isNullOrBlank()) {
                " (${SpecCodingBundle.message("spec.toolwindow.gate.quickFix.disabled", disabledReason)})"
            } else {
                ""
            }
            return title + detailSuffix + disabledSuffix
        }
    }

    fun presentations(violation: Violation): List<Presentation> {
        return violation.quickFixes.map(::presentation)
    }

    fun summary(violation: Violation): String? {
        val items = presentations(violation)
        if (items.isEmpty()) {
            return null
        }
        return items.joinToString(" · ") { item -> item.title }
    }

    fun searchableText(violation: Violation): String {
        return buildString {
            presentations(violation).forEachIndexed { index, item ->
                if (index > 0) {
                    append(' ')
                }
                append(item.title)
                item.detail?.let { detail ->
                    append(' ')
                    append(detail)
                }
                item.disabledReason?.let { disabledReason ->
                    append(' ')
                    append(disabledReason)
                }
            }
        }
    }

    private fun presentation(descriptor: GateQuickFixDescriptor): Presentation {
        val detail = requirementsSectionDetail(descriptor.payload as? MissingRequirementsSectionsQuickFixPayload)
        return when (descriptor.kind) {
            GateQuickFixKind.REPAIR_REQUIREMENTS_ARTIFACT -> Presentation(
                descriptor = descriptor,
                title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.repairRequirements"),
            )

            GateQuickFixKind.REPAIR_TASKS_ARTIFACT -> Presentation(
                descriptor = descriptor,
                title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.repairTasks"),
            )

            GateQuickFixKind.AI_FILL_MISSING_REQUIREMENTS_SECTIONS -> Presentation(
                descriptor = descriptor,
                title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill"),
                detail = detail,
            )

            GateQuickFixKind.CLARIFY_THEN_FILL_REQUIREMENTS_SECTIONS -> Presentation(
                descriptor = descriptor,
                title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.clarifyThenFill"),
                detail = detail,
            )

            GateQuickFixKind.OPEN_FOR_MANUAL_EDIT -> Presentation(
                descriptor = descriptor,
                title = SpecCodingBundle.message("spec.toolwindow.gate.quickFix.manualEdit"),
                detail = detail,
            )
        }
    }

    private fun requirementsSectionDetail(payload: MissingRequirementsSectionsQuickFixPayload?): String? {
        val sections = payload?.missingSections.orEmpty()
        if (sections.isEmpty()) {
            return null
        }
        return SpecCodingBundle.message(
            "spec.toolwindow.gate.quickFix.missingSections",
            RequirementsSectionSupport.describeSections(sections),
        )
    }
}
