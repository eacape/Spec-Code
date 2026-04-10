package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate

internal class SpecWorkflowRuntimeTroubleshootingActionCallbacks(
    private val openSettingsAction: () -> Unit,
    private val openBundledDemoAction: () -> Unit,
    private val openCreateWorkflowDialog: (WorkflowTemplate) -> Unit,
) : SpecWorkflowTroubleshootingActionDispatcher.Callbacks {

    override fun openSettings() {
        openSettingsAction()
    }

    override fun openBundledDemo() {
        openBundledDemoAction()
    }

    override fun selectEntry(entry: SpecWorkflowPrimaryEntry) {
        openCreateWorkflowDialog(
            SpecWorkflowEntryPaths.templateForPrimaryEntry(
                entry = entry,
                availableTemplates = SpecWorkflowEntryPaths.prioritizedTemplates(),
            ),
        )
    }

    override fun refreshAfterEntrySelection() = Unit
}
