package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import java.awt.event.MouseEvent
import javax.swing.JTextPane

internal class SpecDetailPreviewPanePresenter(
    private val pane: JTextPane,
    isEditing: () -> Boolean,
    hasClarificationState: () -> Boolean,
    private val currentWorkflow: () -> SpecWorkflow?,
    private val resolveRevisionLockedPhase: (SpecWorkflow) -> SpecPhase? = { null },
    onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit,
    onWorkflowUpdated: (SpecWorkflow) -> Unit,
    onRefreshButtonStates: (SpecWorkflow) -> Unit,
    renderMarkdown: (JTextPane, String) -> Unit = MarkdownRenderer::render,
) {
    private var sourceText: String = ""

    private val checklistInteractionController = SpecDetailPreviewChecklistInteractionController(
        pane = pane,
        isEditing = isEditing,
        hasClarificationState = hasClarificationState,
        currentWorkflow = currentWorkflow,
        onSaveDocument = onSaveDocument,
        onWorkflowUpdated = onWorkflowUpdated,
        onRefreshButtonStates = onRefreshButtonStates,
    )

    private val markdownRendererAdapter = SpecDetailPreviewMarkdownRendererAdapter(
        pane = pane,
        updateInteraction = checklistInteractionController::updateInteraction,
        refreshCursor = { checklistInteractionController.refreshCursor(null) },
        renderMarkdown = renderMarkdown,
    )

    fun render(plan: SpecDetailPreviewMarkdownPlan) {
        sourceText = markdownRendererAdapter.render(plan)
    }

    fun renderContent(content: String, interactivePhase: SpecPhase? = null) {
        render(
            SpecDetailPreviewMarkdownCoordinator.buildPlan(
                content = content,
                interactivePhase = interactivePhase,
                revisionLockedPhase = currentWorkflow()?.let(resolveRevisionLockedPhase),
            ),
        )
    }

    fun handleToggleRequested(event: MouseEvent) {
        checklistInteractionController.handleToggleRequested(event)
    }

    fun refreshCursor(event: MouseEvent?) {
        checklistInteractionController.refreshCursor(event)
    }

    fun toggleLine(lineIndex: Int?) {
        checklistInteractionController.toggleLine(lineIndex)
    }

    fun reset() {
        sourceText = ""
        checklistInteractionController.reset()
        pane.text = ""
    }

    fun currentSourceText(): String = sourceText
}
