package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate

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
}
