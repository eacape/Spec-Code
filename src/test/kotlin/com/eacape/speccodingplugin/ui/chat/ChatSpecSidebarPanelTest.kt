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

        runOnEdt {
            panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.SPECIFY)
            panel.triggerOpenCurrentPhaseDocumentForTest()
        }

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
                        先把计划写好：
                        <tool_call>
                        <tool_name>Write</tool_name>
                        <tool_input>{"file_path":"requirements.md"}</tool_input>
                        </tool_call>
                        
                        ## 需求文档
                        ### 功能需求
                        - 节点名称悬浮显示
                    """.trimIndent(),
                    valid = true,
                ),
                SpecPhase.DESIGN to specDocument(
                    phase = SpecPhase.DESIGN,
                    content = """{"content":"## 架构设计\n\n- 前端: React\n\n## 技术选型\n\n- Kotlin"}""",
                    valid = true,
                ),
                SpecPhase.IMPLEMENT to specDocument(
                    phase = SpecPhase.IMPLEMENT,
                    content = """
                        ```markdown
                        ## 任务列表
                        - [ ] Task 1: 实现 hover 展示
                        
                        ## 实现步骤
                        1. 更新节点渲染组件
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
        val specifyRendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(specifyRendered.contains("需求文档"))
        assertFalse(specifyRendered.contains("<tool_call>", ignoreCase = true))

        runOnEdt { panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.DESIGN) }
        val designRendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(designRendered.contains("架构设计"))
        assertFalse(designRendered.contains("\\n"))

        runOnEdt { panel.focusWorkflow(workflow.id, preferredPhase = SpecPhase.IMPLEMENT) }
        val implementRendered = runOnEdtResult { panel.currentContentForTest() }
        assertTrue(implementRendered.contains("任务列表"))
        assertTrue(implementRendered.contains("实现步骤"))
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
}
