package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class ChatSpecSidebarPanelTest {

    @Test
    fun `focus workflow should render preferred phase content`() {
        val workflow = workflowWithDocs(
            id = "spec-201",
            currentPhase = SpecPhase.DESIGN,
            docs = mapOf(
                SpecPhase.DESIGN to specDocument(
                    phase = SpecPhase.DESIGN,
                    content = "## Design\n\n- component: checkout",
                    valid = true,
                ),
            ),
        )
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    if (workflowId == workflow.id) Result.success(workflow) else Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflow.id) },
            )
        }

        runOnEdt {
            panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.DESIGN)
        }

        waitUntil {
            panel.currentFocusedWorkflowId() == workflow.id &&
                panel.currentContentForTest().contains("component: checkout")
        }
        val rendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(rendered.contains("component: checkout"))
        assertEquals(workflow.id, panel.currentFocusedWorkflowId())
    }

    @Test
    fun `refresh should fallback to latest workflow when no focus exists`() {
        val workflowA = workflowWithDocs(
            id = "spec-a",
            currentPhase = SpecPhase.SPECIFY,
            docs = mapOf(
                SpecPhase.SPECIFY to specDocument(
                    phase = SpecPhase.SPECIFY,
                    content = "workflow-a content",
                    valid = true,
                ),
            ),
        )
        val workflowB = workflowWithDocs(
            id = "spec-b",
            currentPhase = SpecPhase.IMPLEMENT,
            docs = mapOf(
                SpecPhase.IMPLEMENT to specDocument(
                    phase = SpecPhase.IMPLEMENT,
                    content = "workflow-b latest",
                    valid = false,
                ),
            ),
        )
        val store = mapOf(
            workflowA.id to workflowA,
            workflowB.id to workflowB,
        )
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    store[workflowId]?.let { Result.success(it) }
                        ?: Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflowA.id, workflowB.id) },
            )
        }

        runOnEdt {
            panel.refreshCurrentWorkflow()
        }

        waitUntil {
            panel.currentFocusedWorkflowId() == workflowB.id &&
                panel.currentContentForTest().contains("workflow-b latest")
        }
        val rendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(rendered.contains("workflow-b latest"))
        assertEquals(workflowB.id, panel.currentFocusedWorkflowId())
    }

    @Test
    fun `open current phase document should use workflow current phase`() {
        val workflow = workflowWithDocs(
            id = "spec-open-current",
            currentPhase = SpecPhase.DESIGN,
            docs = mapOf(
                SpecPhase.DESIGN to specDocument(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                ),
            ),
        )
        var openedWorkflowId: String? = null
        var openedPhase: SpecPhase? = null
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    if (workflowId == workflow.id) Result.success(workflow) else Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflow.id) },
                onOpenDocument = { workflowId, phase ->
                    openedWorkflowId = workflowId
                    openedPhase = phase
                },
            )
        }

        runOnEdt { panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.SPECIFY) }
        waitUntil { panel.currentFocusedWorkflowId() == workflow.id }
        runOnEdt { panel.triggerOpenCurrentPhaseDocumentForTest() }

        assertEquals(workflow.id, openedWorkflowId)
        assertEquals(SpecPhase.DESIGN, openedPhase)
    }

    @Test
    fun `open current phase document should fallback to latest workflow when no focus exists`() {
        val workflowA = workflowWithDocs(
            id = "spec-open-a",
            currentPhase = SpecPhase.SPECIFY,
            docs = mapOf(
                SpecPhase.SPECIFY to specDocument(
                    phase = SpecPhase.SPECIFY,
                    content = "a content",
                    valid = true,
                ),
            ),
        )
        val workflowB = workflowWithDocs(
            id = "spec-open-z",
            currentPhase = SpecPhase.IMPLEMENT,
            docs = mapOf(
                SpecPhase.IMPLEMENT to specDocument(
                    phase = SpecPhase.IMPLEMENT,
                    content = "b content",
                    valid = true,
                ),
            ),
        )
        val store = mapOf(
            workflowA.id to workflowA,
            workflowB.id to workflowB,
        )
        var openedWorkflowId: String? = null
        var openedPhase: SpecPhase? = null
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    store[workflowId]?.let { Result.success(it) }
                        ?: Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflowA.id, workflowB.id) },
                onOpenDocument = { workflowId, phase ->
                    openedWorkflowId = workflowId
                    openedPhase = phase
                },
            )
        }

        runOnEdt {
            panel.triggerOpenCurrentPhaseDocumentForTest()
        }

        waitUntil {
            openedWorkflowId == workflowB.id && openedPhase == SpecPhase.IMPLEMENT
        }
        assertEquals(workflowB.id, openedWorkflowId)
        assertEquals(SpecPhase.IMPLEMENT, openedPhase)
        assertEquals(workflowB.id, panel.currentFocusedWorkflowId())
    }

    @Test
    fun `sidebar should sanitize noisy markdown for specify design and implement`() {
        val workflow = workflowWithDocs(
            id = "spec-sanitize-all",
            currentPhase = SpecPhase.IMPLEMENT,
            docs = mapOf(
                SpecPhase.SPECIFY to specDocument(
                    phase = SpecPhase.SPECIFY,
                    content = """
                        Intro text before generated content.
                        <tool_call>
                        <tool_name>Write</tool_name>
                        <tool_input>{"file_path":"requirements.md"}</tool_input>
                        </tool_call>

                        ## Requirements
                        - scope: tooltip preview
                    """.trimIndent(),
                    valid = true,
                ),
                SpecPhase.DESIGN to specDocument(
                    phase = SpecPhase.DESIGN,
                    content = """{"content":"## Architecture\n\n- frontend: React\n\n## Stack\n\n- Kotlin"}""",
                    valid = true,
                ),
                SpecPhase.IMPLEMENT to specDocument(
                    phase = SpecPhase.IMPLEMENT,
                    content = """
                        ```markdown
                        ## Tasks
                        - [ ] Task 1: implement hover preview

                        ## Steps
                        1. Update the tree renderer
                        ```
                    """.trimIndent(),
                    valid = false,
                ),
            ),
        )
        val panel = runOnEdtResult {
            ChatSpecSidebarPanel(
                loadWorkflow = { workflowId ->
                    if (workflowId == workflow.id) Result.success(workflow) else Result.failure(IllegalStateException("missing"))
                },
                listWorkflows = { listOf(workflow.id) },
            )
        }

        runOnEdt { panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.SPECIFY) }
        waitUntil { panel.currentContentForTest().contains("scope: tooltip preview") }
        val specifyRendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(specifyRendered.contains("scope: tooltip preview"))
        assertFalse(specifyRendered.contains("<tool_call>", ignoreCase = true))

        runOnEdt { panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.DESIGN) }
        waitUntil { panel.currentContentForTest().contains("React") }
        val designRendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(designRendered.contains("React"))
        assertTrue(designRendered.contains("Kotlin"))
        assertFalse(designRendered.contains("\\n"))

        runOnEdt { panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.IMPLEMENT) }
        waitUntil { panel.currentContentForTest().contains("Task 1") }
        val implementRendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(implementRendered.contains("Task 1"))
        assertFalse(implementRendered.contains("```"))
    }

    private fun specDocument(
        phase: SpecPhase,
        content: String,
        valid: Boolean,
    ): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "${phase.displayName} doc",
            ),
            validationResult = ValidationResult(valid = valid),
        )
    }

    private fun workflowWithDocs(
        id: String,
        currentPhase: SpecPhase,
        docs: Map<SpecPhase, SpecDocument>,
    ): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = currentPhase,
            documents = docs,
            status = WorkflowStatus.IN_PROGRESS,
            title = id,
            description = "test workflow",
        )
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private fun <T> runOnEdtResult(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        return result!!
    }

    private fun waitUntil(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() <= deadline) {
            if (runOnEdtResult(condition)) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for sidebar state")
    }
}
