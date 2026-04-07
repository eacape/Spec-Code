package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.text.SimpleAttributeSet

class SpecDetailPreviewChecklistInteractionFacadeTest {

    @Test
    fun `resolveLineIndex should ignore empty and invalid hit tests`() {
        assertNull(
            SpecDetailPreviewChecklistInteractionFacade.resolveLineIndex(
                SpecDetailPreviewChecklistHitTest(
                    documentLength = 0,
                    documentPosition = 0,
                    paragraphAttributes = checklistAttributes(1),
                ),
            ),
        )
        assertNull(
            SpecDetailPreviewChecklistInteractionFacade.resolveLineIndex(
                SpecDetailPreviewChecklistHitTest(
                    documentLength = 4,
                    documentPosition = null,
                    paragraphAttributes = checklistAttributes(1),
                ),
            ),
        )
        assertNull(
            SpecDetailPreviewChecklistInteractionFacade.resolveLineIndex(
                SpecDetailPreviewChecklistHitTest(
                    documentLength = 4,
                    documentPosition = -1,
                    paragraphAttributes = checklistAttributes(1),
                ),
            ),
        )
        assertNull(
            SpecDetailPreviewChecklistInteractionFacade.resolveLineIndex(
                SpecDetailPreviewChecklistHitTest(
                    documentLength = 4,
                    documentPosition = 4,
                    paragraphAttributes = checklistAttributes(1),
                ),
            ),
        )
    }

    @Test
    fun `resolveLineIndex should read checklist line index from paragraph attributes`() {
        val lineIndex = SpecDetailPreviewChecklistInteractionFacade.resolveLineIndex(
            SpecDetailPreviewChecklistHitTest(
                documentLength = 12,
                documentPosition = 5,
                paragraphAttributes = checklistAttributes(2),
            ),
        )

        assertEquals(2, lineIndex)
    }

    @Test
    fun `cursorKind should use hit-tested checklist row`() {
        val kind = SpecDetailPreviewChecklistInteractionFacade.cursorKind(
            hitTest = SpecDetailPreviewChecklistHitTest(
                documentLength = 12,
                documentPosition = 5,
                paragraphAttributes = checklistAttributes(1),
            ),
            interaction = interaction(),
            isEditing = false,
            hasClarificationState = false,
            isSaving = false,
        )

        assertEquals(SpecDetailPreviewChecklistCursorKind.HAND, kind)
    }

    @Test
    fun `buildTogglePlan should use hit-tested checklist row`() {
        val plan = SpecDetailPreviewChecklistInteractionFacade.buildTogglePlan(
            hitTest = SpecDetailPreviewChecklistHitTest(
                documentLength = 12,
                documentPosition = 5,
                paragraphAttributes = checklistAttributes(1),
            ),
            interaction = interaction(),
            isEditing = false,
            hasClarificationState = false,
            isSaving = false,
        )

        assertTrue(plan is SpecDetailPreviewChecklistTogglePlan.Save)
        plan as SpecDetailPreviewChecklistTogglePlan.Save
        assertEquals(SpecPhase.IMPLEMENT, plan.phase)
        assertEquals(
            """
            ### T-002: rollout
            - [x] Ship fix
            - [x] Verify smoke
            """.trimIndent(),
            plan.updatedContent,
        )
    }

    @Test
    fun `buildTogglePlan should ignore hit tests without checklist attributes`() {
        val plan = SpecDetailPreviewChecklistInteractionFacade.buildTogglePlan(
            hitTest = SpecDetailPreviewChecklistHitTest(
                documentLength = 12,
                documentPosition = 5,
                paragraphAttributes = SimpleAttributeSet(),
            ),
            interaction = interaction(),
            isEditing = false,
            hasClarificationState = false,
            isSaving = false,
        )

        assertTrue(plan is SpecDetailPreviewChecklistTogglePlan.Ignore)
    }

    private fun checklistAttributes(lineIndex: Int): SimpleAttributeSet {
        return SimpleAttributeSet().apply {
            addAttribute(MarkdownRenderer.CHECKLIST_LINE_INDEX_ATTRIBUTE, lineIndex)
        }
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
}
