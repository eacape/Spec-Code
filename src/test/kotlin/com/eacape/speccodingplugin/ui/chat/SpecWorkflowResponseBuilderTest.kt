package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowResponseBuilderTest {

    @Test
    fun `build phase transition response should include inserted hint for spec back when template inserted`() {
        val response = SpecWorkflowResponseBuilder.buildPhaseTransitionResponse(
            workflowId = "spec-123",
            phaseDisplayName = "Specify",
            template = "## Requirements\n- Objective:",
            advanced = false,
            templateInserted = true,
        )

        val parsed = WorkflowSectionParser.parse(response)
        assertEquals(3, parsed.sections.size)
        assertEquals(WorkflowSectionParser.SectionKind.PLAN, parsed.sections[0].kind)
        assertEquals(WorkflowSectionParser.SectionKind.EXECUTE, parsed.sections[1].kind)
        assertEquals(WorkflowSectionParser.SectionKind.VERIFY, parsed.sections[2].kind)

        val insertedHint = SpecCodingBundle.message("toolwindow.spec.command.phaseTemplate.inserted")
        assertTrue(response.contains(insertedHint))
        assertTrue(response.contains("/workflow generate <input>"))
        assertTrue(response.contains("/workflow status"))
    }

    @Test
    fun `build phase transition response should not include inserted hint when template not inserted`() {
        val response = SpecWorkflowResponseBuilder.buildPhaseTransitionResponse(
            workflowId = "spec-456",
            phaseDisplayName = "Design",
            template = "## Design\n- Architecture:",
            advanced = false,
            templateInserted = false,
        )

        val insertedHint = SpecCodingBundle.message("toolwindow.spec.command.phaseTemplate.inserted")
        assertFalse(response.contains(insertedHint))
        assertTrue(response.contains("/workflow generate <input>"))
        assertTrue(response.contains("## ${SpecCodingBundle.message("chat.workflow.section.execute")}"))
    }
}
