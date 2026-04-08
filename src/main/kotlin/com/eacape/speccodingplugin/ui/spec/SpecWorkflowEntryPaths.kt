package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate

internal enum class SpecWorkflowPrimaryEntry {
    QUICK_TASK,
    FULL_SPEC,
    ADVANCED_TEMPLATE,
}

internal object SpecWorkflowEntryPaths {
    private val primaryTemplates = linkedSetOf(
        WorkflowTemplate.QUICK_TASK,
        WorkflowTemplate.FULL_SPEC,
    )

    fun prioritizedTemplates(
        templates: Collection<WorkflowTemplate> = WorkflowTemplate.entries,
    ): List<WorkflowTemplate> {
        val ordered = linkedSetOf<WorkflowTemplate>()
        primaryTemplates
            .filter { templates.contains(it) }
            .forEach(ordered::add)
        templates
            .filterNot(primaryTemplates::contains)
            .forEach(ordered::add)
        return ordered.toList()
    }

    fun resolveDefaultTemplate(
        preferredTemplate: WorkflowTemplate?,
        configuredDefault: WorkflowTemplate?,
    ): WorkflowTemplate {
        return preferredTemplate
            ?: configuredDefault?.takeIf(primaryTemplates::contains)
            ?: WorkflowTemplate.QUICK_TASK
    }

    fun isPrimaryTemplate(template: WorkflowTemplate): Boolean {
        return primaryTemplates.contains(template)
    }

    fun isAdvancedTemplate(template: WorkflowTemplate): Boolean {
        return !isPrimaryTemplate(template)
    }

    fun advancedTemplates(
        templates: Collection<WorkflowTemplate> = WorkflowTemplate.entries,
    ): List<WorkflowTemplate> {
        return prioritizedTemplates(templates).filter(::isAdvancedTemplate)
    }

    fun primaryEntryForTemplate(template: WorkflowTemplate): SpecWorkflowPrimaryEntry {
        return when (template) {
            WorkflowTemplate.QUICK_TASK -> SpecWorkflowPrimaryEntry.QUICK_TASK
            WorkflowTemplate.FULL_SPEC -> SpecWorkflowPrimaryEntry.FULL_SPEC
            else -> SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE
        }
    }

    fun templateForPrimaryEntry(
        entry: SpecWorkflowPrimaryEntry,
        advancedTemplate: WorkflowTemplate? = null,
        availableTemplates: Collection<WorkflowTemplate> = WorkflowTemplate.entries,
    ): WorkflowTemplate {
        return when (entry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> WorkflowTemplate.QUICK_TASK
            SpecWorkflowPrimaryEntry.FULL_SPEC -> WorkflowTemplate.FULL_SPEC
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> {
                advancedTemplate
                    ?.takeIf(::isAdvancedTemplate)
                    ?.takeIf(availableTemplates::contains)
                    ?: advancedTemplates(availableTemplates).firstOrNull()
                    ?: WorkflowTemplate.QUICK_TASK
            }
        }
    }
}
