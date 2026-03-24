package com.eacape.speccodingplugin.ui.spec

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SpecWorkflowSelectionServiceTest : BasePlatformTestCase() {

    fun `test service tracks workflow selection events`() {
        val service = SpecWorkflowSelectionService.getInstance(project)

        assertNull(service.currentWorkflowId())

        project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC).onWorkflowChanged(
            SpecWorkflowChangedEvent(
                workflowId = "wf-123",
                reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
            ),
        )

        assertEquals("wf-123", service.currentWorkflowId())
    }

    fun `test blank workflow ids are normalized to null`() {
        val service = SpecWorkflowSelectionService.getInstance(project)

        project.messageBus.syncPublisher(SpecWorkflowChangedListener.TOPIC).onWorkflowChanged(
            SpecWorkflowChangedEvent(
                workflowId = "   ",
                reason = SpecWorkflowChangedListener.REASON_WORKFLOW_SELECTED,
            ),
        )

        assertNull(service.currentWorkflowId())
    }
}
