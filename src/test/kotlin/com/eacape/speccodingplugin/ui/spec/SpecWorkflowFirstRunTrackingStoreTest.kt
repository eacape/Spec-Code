package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecWorkflowFirstRunTrackingStoreTest {

    @Test
    fun `default snapshot should start empty`() {
        val store = SpecWorkflowFirstRunTrackingStore()

        val snapshot = store.snapshot()

        assertEquals(0, snapshot.createAttemptCount)
        assertEquals(0, snapshot.createSuccessCount)
        assertNull(snapshot.lastAttemptTemplate)
        assertNull(snapshot.lastSuccessTemplate)
        assertNull(snapshot.lastSuccessWorkflowId)
        assertNull(snapshot.lastSuccessArtifactFileName)
        assertNull(snapshot.lastAttemptAt)
        assertNull(snapshot.lastSuccessAt)
        assertNull(snapshot.firstAttemptAt)
        assertNull(snapshot.firstSuccessAt)
    }

    @Test
    fun `record workflow create success should preserve first-run timing while updating latest workflow state`() {
        val store = SpecWorkflowFirstRunTrackingStore()

        store.recordWorkflowCreateAttempt(
            template = WorkflowTemplate.QUICK_TASK,
            timestampMillis = 1_000L,
        )
        store.recordWorkflowCreateSuccess(
            workflowId = "wf-001",
            template = WorkflowTemplate.QUICK_TASK,
            timestampMillis = 2_000L,
        )
        store.recordWorkflowCreateAttempt(
            template = WorkflowTemplate.FULL_SPEC,
            timestampMillis = 3_000L,
        )
        store.recordWorkflowCreateSuccess(
            workflowId = "wf-002",
            template = WorkflowTemplate.FULL_SPEC,
            timestampMillis = 8_000L,
        )

        val snapshot = store.snapshot()
        assertEquals(2, snapshot.createAttemptCount)
        assertEquals(2, snapshot.createSuccessCount)
        assertEquals(1_000L, snapshot.firstAttemptAt)
        assertEquals(2_000L, snapshot.firstSuccessAt)
        assertEquals(WorkflowTemplate.FULL_SPEC, snapshot.lastAttemptTemplate)
        assertEquals(WorkflowTemplate.FULL_SPEC, snapshot.lastSuccessTemplate)
        assertEquals("wf-002", snapshot.lastSuccessWorkflowId)
        assertEquals("requirements.md", snapshot.lastSuccessArtifactFileName)
        assertEquals(3_000L, snapshot.lastAttemptAt)
        assertEquals(8_000L, snapshot.lastSuccessAt)
    }

    @Test
    fun `loadState should normalize invalid legacy values`() {
        val store = SpecWorkflowFirstRunTrackingStore()

        store.loadState(
            SpecWorkflowFirstRunTrackingStore.FirstRunTrackingState(
                createAttemptCount = -2,
                createSuccessCount = 5,
                lastAttemptTemplate = "UNKNOWN_TEMPLATE",
                lastSuccessTemplate = WorkflowTemplate.FULL_SPEC.name,
                lastSuccessWorkflowId = "   ",
                lastSuccessArtifactFileName = "",
                lastAttemptAt = -10L,
                lastSuccessAt = 3_000L,
                updatedAt = -1L,
            ),
        )

        val snapshot = store.snapshot()
        assertEquals(5, snapshot.createAttemptCount)
        assertEquals(5, snapshot.createSuccessCount)
        assertNull(snapshot.lastAttemptTemplate)
        assertEquals(WorkflowTemplate.FULL_SPEC, snapshot.lastSuccessTemplate)
        assertNull(snapshot.lastSuccessWorkflowId)
        assertNull(snapshot.lastSuccessArtifactFileName)
        assertEquals(3_000L, snapshot.firstAttemptAt)
        assertEquals(3_000L, snapshot.firstSuccessAt)
        assertEquals(3_000L, snapshot.lastAttemptAt)
        assertEquals(3_000L, snapshot.lastSuccessAt)
    }
}
