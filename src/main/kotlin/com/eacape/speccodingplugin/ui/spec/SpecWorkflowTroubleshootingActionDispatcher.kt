package com.eacape.speccodingplugin.ui.spec

internal class SpecWorkflowTroubleshootingActionDispatcher(
    private val callbacks: Callbacks,
) {
    fun perform(action: SpecWorkflowTroubleshootingAction) {
        when (action) {
            is SpecWorkflowTroubleshootingAction.OpenBundledDemo -> callbacks.openBundledDemo()
            is SpecWorkflowTroubleshootingAction.OpenSettings -> callbacks.openSettings()
            is SpecWorkflowTroubleshootingAction.SelectEntry -> {
                callbacks.selectEntry(action.entry)
                callbacks.refreshAfterEntrySelection()
            }
        }
    }

    internal interface Callbacks {
        fun openSettings()

        fun openBundledDemo()

        fun selectEntry(entry: SpecWorkflowPrimaryEntry)

        fun refreshAfterEntrySelection()
    }
}
