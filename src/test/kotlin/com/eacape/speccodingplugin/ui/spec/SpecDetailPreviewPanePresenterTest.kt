package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Cursor
import javax.swing.JTextPane

class SpecDetailPreviewPanePresenterTest {

    @Test
    fun `render should store current source text and reset should clear pane state`() {
        val pane = JTextPane().apply {
            text = "stale"
            cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        }
        val presenter = createPresenter(
            pane = pane,
            renderMarkdown = { target, content ->
                target.text = "rendered:$content"
            },
        )

        presenter.render(
            SpecDetailPreviewMarkdownPlan(
                displayContent = "# Tasks",
                checklistInteraction = null,
            ),
        )

        assertEquals("# Tasks", presenter.currentSourceText())
        assertEquals("rendered:# Tasks", pane.text)

        presenter.reset()

        assertEquals("", presenter.currentSourceText())
        assertEquals("", pane.text)
        assertEquals(Cursor.DEFAULT_CURSOR, pane.cursor.type)
    }

    @Test
    fun `toggleLine should reuse rendered checklist interaction and forward successful save`() {
        val pane = JTextPane()
        var savedPhase: SpecPhase? = null
        var savedContent: String? = null
        var updatedWorkflow: SpecWorkflow? = null
        val expectedUpdatedWorkflow = workflow("wf-preview-pane-updated")
        val presenter = createPresenter(
            pane = pane,
            onSaveDocument = { phase, content, onDone ->
                savedPhase = phase
                savedContent = content
                onDone(Result.success(expectedUpdatedWorkflow))
            },
            onWorkflowUpdated = { updatedWorkflow = it },
        )

        presenter.render(
            SpecDetailPreviewMarkdownPlan(
                displayContent = """
                    ### T-002: rollout
                    - [ ] Ship fix
                    - [x] Verify smoke
                """.trimIndent(),
                checklistInteraction = SpecDetailPreviewChecklistInteractionPlan(
                    phase = SpecPhase.IMPLEMENT,
                    content = """
                        ### T-002: rollout
                        - [ ] Ship fix
                        - [x] Verify smoke
                    """.trimIndent(),
                ),
            ),
        )
        presenter.toggleLine(1)

        assertEquals(SpecPhase.IMPLEMENT, savedPhase)
        assertEquals(
            """
            ### T-002: rollout
            - [x] Ship fix
            - [x] Verify smoke
            """.trimIndent(),
            savedContent,
        )
        assertSame(expectedUpdatedWorkflow, updatedWorkflow)
        assertEquals(Cursor.DEFAULT_CURSOR, pane.cursor.type)
    }

    private fun createPresenter(
        pane: JTextPane,
        onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit = { _, _, _ -> },
        onWorkflowUpdated: (SpecWorkflow) -> Unit = {},
        renderMarkdown: (JTextPane, String) -> Unit = { target, content -> target.text = content },
    ): SpecDetailPreviewPanePresenter {
        return SpecDetailPreviewPanePresenter(
            pane = pane,
            isEditing = { false },
            hasClarificationState = { false },
            currentWorkflow = { workflow("wf-preview-pane-current") },
            onSaveDocument = onSaveDocument,
            onWorkflowUpdated = onWorkflowUpdated,
            onRefreshButtonStates = {},
            renderMarkdown = renderMarkdown,
        )
    }

    private fun workflow(id: String): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Preview Pane Presenter",
            description = "preview pane presenter test",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }
}
