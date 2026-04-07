package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Cursor
import javax.swing.JTextPane

class SpecDetailPreviewChecklistInteractionControllerTest {

    @Test
    fun `toggleLine should save checklist update and restore cursor after success`() {
        val pane = JTextPane()
        var savedPhase: SpecPhase? = null
        var savedContent: String? = null
        var pendingSave: ((Result<SpecWorkflow>) -> Unit)? = null
        var currentWorkflow: SpecWorkflow? = workflow("wf-preview-checklist-current")
        var updatedWorkflow: SpecWorkflow? = null
        val expectedUpdatedWorkflow = workflow("wf-preview-checklist-updated")
        val controller = createController(
            pane = pane,
            currentWorkflow = { currentWorkflow },
            onSaveDocument = { phase, content, onDone ->
                savedPhase = phase
                savedContent = content
                pendingSave = onDone
            },
            onWorkflowUpdated = {
                updatedWorkflow = it
                currentWorkflow = it
            },
        )

        controller.updateInteraction(interaction())
        controller.toggleLine(1)

        assertEquals(SpecPhase.IMPLEMENT, savedPhase)
        assertEquals(
            """
            ### T-002: rollout
            - [x] Ship fix
            - [x] Verify smoke
            """.trimIndent(),
            savedContent,
        )
        assertEquals(Cursor.WAIT_CURSOR, pane.cursor.type)

        pendingSave?.invoke(Result.success(expectedUpdatedWorkflow))

        assertSame(expectedUpdatedWorkflow, updatedWorkflow)
        assertEquals(Cursor.DEFAULT_CURSOR, pane.cursor.type)
    }

    @Test
    fun `toggleLine should ignore additional requests while save is in flight`() {
        val pane = JTextPane()
        var saveCalls = 0
        val controller = createController(
            pane = pane,
            onSaveDocument = { _, _, _ -> saveCalls += 1 },
        )

        controller.updateInteraction(interaction())
        controller.toggleLine(1)
        controller.toggleLine(2)

        assertEquals(1, saveCalls)
        assertEquals(Cursor.WAIT_CURSOR, pane.cursor.type)
    }

    @Test
    fun `failed save should refresh button states for current workflow`() {
        val pane = JTextPane()
        var pendingSave: ((Result<SpecWorkflow>) -> Unit)? = null
        val currentWorkflow = workflow("wf-preview-checklist-current")
        var refreshedWorkflow: SpecWorkflow? = null
        var updatedWorkflow: SpecWorkflow? = null
        val controller = createController(
            pane = pane,
            currentWorkflow = { currentWorkflow },
            onSaveDocument = { _, _, onDone -> pendingSave = onDone },
            onWorkflowUpdated = { updatedWorkflow = it },
            onRefreshButtonStates = { refreshedWorkflow = it },
        )

        controller.updateInteraction(interaction())
        controller.toggleLine(1)
        pendingSave?.invoke(Result.failure(IllegalStateException("save failed")))

        assertNull(updatedWorkflow)
        assertSame(currentWorkflow, refreshedWorkflow)
        assertEquals(Cursor.DEFAULT_CURSOR, pane.cursor.type)
    }

    @Test
    fun `reset should clear pending interaction and restore default cursor`() {
        val pane = JTextPane()
        var saveCalls = 0
        val controller = createController(
            pane = pane,
            onSaveDocument = { _, _, _ -> saveCalls += 1 },
        )

        controller.updateInteraction(interaction())
        controller.toggleLine(1)
        controller.reset()
        controller.toggleLine(1)

        assertEquals(1, saveCalls)
        assertEquals(Cursor.DEFAULT_CURSOR, pane.cursor.type)
    }

    private fun createController(
        pane: JTextPane,
        currentWorkflow: () -> SpecWorkflow? = { workflow("wf-preview-checklist-current") },
        onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit,
        onWorkflowUpdated: (SpecWorkflow) -> Unit = {},
        onRefreshButtonStates: (SpecWorkflow) -> Unit = {},
    ): SpecDetailPreviewChecklistInteractionController {
        return SpecDetailPreviewChecklistInteractionController(
            pane = pane,
            isEditing = { false },
            hasClarificationState = { false },
            currentWorkflow = currentWorkflow,
            onSaveDocument = onSaveDocument,
            onWorkflowUpdated = onWorkflowUpdated,
            onRefreshButtonStates = onRefreshButtonStates,
        )
    }

    private fun interaction(): SpecDetailPreviewChecklistInteractionPlan {
        return SpecDetailPreviewChecklistInteractionPlan(
            phase = SpecPhase.IMPLEMENT,
            content = """
                ### T-002: rollout
                - [ ] Ship fix
                - [x] Verify smoke
            """.trimIndent(),
        )
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Preview Checklist Interaction Controller",
            description = "preview checklist interaction controller test",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
