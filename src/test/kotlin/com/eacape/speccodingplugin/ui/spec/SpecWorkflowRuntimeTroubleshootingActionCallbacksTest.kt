package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecWorkflowRuntimeTroubleshootingActionCallbacksTest {

    @Test
    fun `openSettings should delegate to settings action`() {
        val events = mutableListOf<String>()
        val callbacks = callbacks(events)

        callbacks.openSettings()

        assertEquals(listOf("settings"), events)
    }

    @Test
    fun `openBundledDemo should delegate to demo action`() {
        val events = mutableListOf<String>()
        val callbacks = callbacks(events)

        callbacks.openBundledDemo()

        assertEquals(listOf("demo"), events)
    }

    @Test
    fun `selectEntry should open create workflow dialog with matching template`() {
        val events = mutableListOf<String>()
        val callbacks = callbacks(events)

        callbacks.selectEntry(SpecWorkflowPrimaryEntry.FULL_SPEC)

        assertEquals(listOf("create:${WorkflowTemplate.FULL_SPEC}"), events)
    }

    private fun callbacks(events: MutableList<String>): SpecWorkflowRuntimeTroubleshootingActionCallbacks {
        return SpecWorkflowRuntimeTroubleshootingActionCallbacks(
            openSettingsAction = {
                events += "settings"
            },
            openBundledDemoAction = {
                events += "demo"
            },
            openCreateWorkflowDialog = { template ->
                events += "create:$template"
            },
        )
    }
}
