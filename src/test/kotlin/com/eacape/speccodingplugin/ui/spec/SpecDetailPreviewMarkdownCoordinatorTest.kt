package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecDetailPreviewMarkdownCoordinatorTest {

    @Test
    fun `buildPlan should keep raw checklist markdown interactive when content is already displayable`() {
        val content = """
            ### T-002: rollout
            - [ ] Ship fix
            - [x] Verify smoke
        """.trimIndent()

        val plan = SpecDetailPreviewMarkdownCoordinator.buildPlan(
            content = content,
            interactivePhase = SpecPhase.IMPLEMENT,
            revisionLockedPhase = null,
        )

        assertEquals(content, plan.displayContent)
        assertEquals(SpecPhase.IMPLEMENT, plan.checklistInteraction?.phase)
        assertEquals(content, plan.checklistInteraction?.content)
    }

    @Test
    fun `buildPlan should disable checklist interaction when revision is locked`() {
        val content = """
            - [ ] Ship fix
            - [x] Verify smoke
        """.trimIndent()

        val plan = SpecDetailPreviewMarkdownCoordinator.buildPlan(
            content = content,
            interactivePhase = SpecPhase.IMPLEMENT,
            revisionLockedPhase = SpecPhase.IMPLEMENT,
        )

        assertEquals(content, plan.displayContent)
        assertNull(plan.checklistInteraction)
    }

    @Test
    fun `buildPlan should prefer sanitized payload when raw content is wrapped json`() {
        val plan = SpecDetailPreviewMarkdownCoordinator.buildPlan(
            content = """{"content":"# Tasks\\n\\n- [ ] Ship fix"}""",
            interactivePhase = SpecPhase.IMPLEMENT,
            revisionLockedPhase = null,
        )

        assertEquals("# Tasks\n\n- [ ] Ship fix", plan.displayContent)
        assertNull(plan.checklistInteraction)
    }

    @Test
    fun `buildPlan should decode escaped newlines before rendering`() {
        val plan = SpecDetailPreviewMarkdownCoordinator.buildPlan(
            content = "\"# Tasks\\n\\n- [ ] Ship fix\\n- [x] Verify smoke\"",
            interactivePhase = SpecPhase.IMPLEMENT,
            revisionLockedPhase = null,
        )

        assertEquals("# Tasks\n\n- [ ] Ship fix\n- [x] Verify smoke", plan.displayContent)
        assertNull(plan.checklistInteraction)
    }

    @Test
    fun `toggleChecklistLine should toggle markdown checklist markers and ignore invalid lines`() {
        val content = """
            ### T-002: rollout
            - [ ] Ship fix
            - [x] Verify smoke
        """.trimIndent()

        assertEquals(
            """
            ### T-002: rollout
            - [x] Ship fix
            - [x] Verify smoke
            """.trimIndent(),
            SpecDetailPreviewMarkdownCoordinator.toggleChecklistLine(content, 1),
        )
        assertEquals(
            """
            ### T-002: rollout
            - [ ] Ship fix
            - [ ] Verify smoke
            """.trimIndent(),
            SpecDetailPreviewMarkdownCoordinator.toggleChecklistLine(content, 2),
        )
        assertNull(SpecDetailPreviewMarkdownCoordinator.toggleChecklistLine(content, 0))
        assertNull(SpecDetailPreviewMarkdownCoordinator.toggleChecklistLine(content, 9))
    }
}
