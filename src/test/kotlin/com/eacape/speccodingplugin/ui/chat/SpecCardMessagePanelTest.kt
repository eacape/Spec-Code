package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class SpecCardMessagePanelTest {

    @Test
    fun `spec card should support preview collapse and expand`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-1001",
            phase = SpecPhase.DESIGN,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Auth Flow",
            revision = 1001L,
            sourceCommand = "/spec generate auth flow",
        )
        val document = (1..20).joinToString("\n") { "line-$it" }
        val panel = runOnEdtResult {
            SpecCardMessagePanel(
                metadata = metadata,
                cardMarkdown = "## Spec Card",
                initialDocumentContent = document,
            )
        }

        val beforeExpand = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(beforeExpand.contains("line-1"))
        assertTrue(beforeExpand.contains("......"))
        assertFalse(beforeExpand.contains("line-20"))

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.workflow.toggle.expand") }
        assertNotNull(expandButton)
        runOnEdt { expandButton!!.doClick() }

        val afterExpand = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
        assertTrue(afterExpand.contains("line-20"))
    }

    @Test
    fun `collapsed preview should show first fifteen lines without h1 heading and end with ellipsis`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-1001b",
            phase = SpecPhase.SPECIFY,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Preview Trim",
            revision = 1010L,
            sourceCommand = "/spec generate preview trim",
        )
        val document = buildString {
            appendLine("# 一级标题")
            (1..20).forEach { index -> appendLine("line-$index") }
        }.trimEnd()

        val panel = runOnEdtResult {
            SpecCardMessagePanel(
                metadata = metadata,
                cardMarkdown = "## Spec Card",
                initialDocumentContent = document,
            )
        }

        val collapsed = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertFalse(collapsed.contains("一级标题"))
        assertTrue(collapsed.contains("line-14"))
        assertFalse(collapsed.contains("line-15"))
        assertTrue(collapsed.contains("......"))
    }

    @Test
    fun `spec card should sanitize noisy content before render`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-1001c",
            phase = SpecPhase.IMPLEMENT,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Sanitized Preview",
            revision = 1011L,
            sourceCommand = "/spec generate sanitize",
        )
        val noisyDocument = """{"content":"## 任务列表\n\n- [ ] Task 1: 清洗规格展示\n\n## 实现步骤\n\n1. 渲染前净化内容"}"""

        val panel = runOnEdtResult {
            SpecCardMessagePanel(
                metadata = metadata,
                cardMarkdown = "## Spec Card",
                initialDocumentContent = noisyDocument,
            )
        }

        val rendered = collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }

        assertTrue(rendered.contains("任务列表"))
        assertTrue(rendered.contains("实现步骤"))
        assertFalse(rendered.contains("\\n"))
    }

    @Test
    fun `spec card actions should trigger callbacks`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-1002",
            phase = SpecPhase.IMPLEMENT,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Session Persist",
            revision = 2002L,
            sourceCommand = "/spec status",
        )
        var continued = false
        var deleted = false
        var openedTab = false
        var openedDocument = false
        var focusedSidebar = false

        val panel = runOnEdtResult {
            SpecCardMessagePanel(
                metadata = metadata,
                cardMarkdown = "## Spec Card",
                initialDocumentContent = "content",
                onContinueMessage = { continued = true },
                onDeleteMessage = { deleted = true },
                onOpenSpecTab = { openedTab = true },
                onOpenDocument = { openedDocument = true },
                onFocusSpecSidebar = { focusedSidebar = true },
            )
        }

        val openTabText = SpecCodingBundle.message("toolwindow.spec.quick.open")
        val sidebarText = SpecCodingBundle.message("toolwindow.spec.card.action.sidebar")
        val openDocText = SpecCodingBundle.message("chat.workflow.action.openFile.short")
        val continueTip = SpecCodingBundle.message("chat.message.continue")
        val deleteTip = SpecCodingBundle.message("chat.message.delete")

        val buttons = collectDescendants(panel).filterIsInstance<JButton>().toList()
        val openTabButton = buttons.firstOrNull { it.text == openTabText }
        val sidebarButton = buttons.firstOrNull { it.text == sidebarText }
        val openDocButton = buttons.firstOrNull { it.text == openDocText }
        val continueButton = buttons.firstOrNull { it.toolTipText == continueTip }
        val deleteButton = buttons.firstOrNull { it.toolTipText == deleteTip }

        assertNotNull(openTabButton)
        assertNotNull(sidebarButton)
        assertNotNull(openDocButton)
        assertNotNull(continueButton)
        assertNotNull(deleteButton)

        runOnEdt {
            openTabButton!!.doClick()
            sidebarButton!!.doClick()
            openDocButton!!.doClick()
            continueButton!!.doClick()
            deleteButton!!.doClick()
        }

        assertTrue(openedTab)
        assertTrue(focusedSidebar)
        assertTrue(openedDocument)
        assertTrue(continued)
        assertTrue(deleted)
    }

    @Test
    fun `spec card should support edit save and next phase callbacks`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-1003",
            phase = SpecPhase.DESIGN,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checkout Flow",
            revision = 3003L,
            sourceCommand = "/spec generate checkout flow",
        )
        var savedContent: String? = null
        var nextTriggered = false

        val panel = runOnEdtResult {
            SpecCardMessagePanel(
                metadata = metadata,
                cardMarkdown = "## Spec Card",
                initialDocumentContent = "initial content",
                onSaveDocument = { meta, edited, _ ->
                    savedContent = edited
                    Result.success(
                        SpecCardPanelSnapshot(
                            metadata = meta.copy(revision = meta.revision + 1),
                            cardMarkdown = "## Updated Card",
                            documentContent = "$edited\n\nsaved from callback",
                        )
                    )
                },
                onAdvancePhase = { nextTriggered = true },
            )
        }

        val nextText = SpecCodingBundle.message("toolwindow.spec.card.action.next")
        val editText = SpecCodingBundle.message("toolwindow.spec.card.action.edit")
        val saveText = SpecCodingBundle.message("toolwindow.spec.card.action.save")

        val nextButton = collectDescendants(panel).filterIsInstance<JButton>().firstOrNull { it.text == nextText }
        val editButton = collectDescendants(panel).filterIsInstance<JButton>().firstOrNull { it.text == editText }
        assertNotNull(nextButton)
        assertNotNull(editButton)

        runOnEdt { nextButton!!.doClick() }
        assertTrue(nextTriggered)

        runOnEdt { editButton!!.doClick() }
        val editor = collectDescendants(panel).filterIsInstance<JTextArea>().firstOrNull()
        assertNotNull(editor)
        runOnEdt {
            editor!!.text = "edited from test"
        }

        waitUntil {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .any { it.text == saveText }
        }
        runOnEdt {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .first { it.text == saveText }
                .doClick()
        }

        waitUntil { !savedContent.isNullOrBlank() }
        assertTrue(savedContent == "edited from test")
        waitUntil {
            collectDescendants(panel)
                .filterIsInstance<JTextPane>()
                .any { it.text.contains("saved from callback") }
        }
    }

    @Test
    fun `spec card should expose force save action when revision conflict occurs`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-1004",
            phase = SpecPhase.DESIGN,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Payment Retry",
            revision = 4004L,
            sourceCommand = "/spec generate payment retry",
        )
        var saveAttempts = 0
        var forceSaveCalled = false

        val panel = runOnEdtResult {
            SpecCardMessagePanel(
                metadata = metadata,
                cardMarkdown = "## Spec Card",
                initialDocumentContent = "initial content",
                onSaveDocument = { meta, edited, force ->
                    saveAttempts += 1
                    if (!force) {
                        Result.failure(
                            SpecCardSaveConflictException(
                                latestContent = "latest remote content line",
                                expectedRevision = meta.revision,
                                actualRevision = meta.revision + 99,
                            )
                        )
                    } else {
                        forceSaveCalled = true
                        Result.success(
                            SpecCardPanelSnapshot(
                                metadata = meta.copy(revision = meta.revision + 100),
                                cardMarkdown = "## Forced Save Card",
                                documentContent = "$edited\n\nforce saved",
                            )
                        )
                    }
                },
            )
        }

        val editText = SpecCodingBundle.message("toolwindow.spec.card.action.edit")
        val saveText = SpecCodingBundle.message("toolwindow.spec.card.action.save")
        val forceSaveText = SpecCodingBundle.message("toolwindow.spec.card.action.forceSave")
        val showDiffText = SpecCodingBundle.message("toolwindow.spec.card.action.showDiff")
        runOnEdt {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .first { it.text == editText }
                .doClick()
        }
        val editor = collectDescendants(panel).filterIsInstance<JTextArea>().firstOrNull()
        assertNotNull(editor)
        runOnEdt { editor!!.text = "edited after conflict" }

        runOnEdt {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .first { it.text == saveText }
                .doClick()
        }
        waitUntil {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .any { it.text == forceSaveText }
        }
        waitUntil {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .any { it.text == showDiffText }
        }

        runOnEdt {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .first { it.text == showDiffText }
                .doClick()
        }
        waitUntil {
            collectDescendants(panel)
                .filterIsInstance<JTextArea>()
                .any { !it.isEditable && it.text.contains("edited after conflict") }
        }

        runOnEdt {
            collectDescendants(panel)
                .filterIsInstance<JButton>()
                .first { it.text == forceSaveText }
                .doClick()
        }

        waitUntil { forceSaveCalled }
        assertTrue(saveAttempts >= 2)
        waitUntil {
            collectDescendants(panel)
                .filterIsInstance<JTextPane>()
                .any { it.text.contains("force saved") }
        }
    }

    private fun collectDescendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        val container = component as? Container ?: return@sequence
        container.components.forEach { child ->
            yieldAll(collectDescendants(child))
        }
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

    private fun waitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val met = runOnEdtResult { condition() }
            if (met) return
            Thread.sleep(25)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }
}
