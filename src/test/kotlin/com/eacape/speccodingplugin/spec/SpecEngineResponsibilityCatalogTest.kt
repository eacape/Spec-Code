package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SpecEngineResponsibilityCatalogTest {

    @Test
    fun `catalog should define mandatory SpecEngine seams`() {
        val slicesById = SpecEngineResponsibilityCatalog.slices.associateBy { it.id }

        assertNotNull(slicesById[SpecEngineResponsibilityCatalog.ResponsibilityId.WORKFLOW_LIFECYCLE])
        assertNotNull(slicesById[SpecEngineResponsibilityCatalog.ResponsibilityId.ARTIFACT_GENERATION])
        assertNotNull(slicesById[SpecEngineResponsibilityCatalog.ResponsibilityId.STAGE_TRANSITION_PREFLIGHT])
        assertNotNull(slicesById[SpecEngineResponsibilityCatalog.ResponsibilityId.TEMPLATE_SWITCH_AND_CLONE])
        assertNotNull(slicesById[SpecEngineResponsibilityCatalog.ResponsibilityId.HISTORY_AND_RECOVERY])

        slicesById.values.forEach { slice ->
            assertTrue(slice.summary.isNotBlank(), "Slice ${slice.id} summary must not be blank")
            assertTrue(slice.plannedTarget.isNotBlank(), "Slice ${slice.id} plannedTarget must not be blank")
            assertTrue(slice.currentCollaborators.isNotEmpty(), "Slice ${slice.id} collaborators must not be empty")
            assertTrue(slice.apiMethods.isNotEmpty(), "Slice ${slice.id} apiMethods must not be empty")
            assertTrue(slice.migrationGuardrail.isNotBlank(), "Slice ${slice.id} guardrail must not be blank")
        }
    }

    @Test
    fun `every public SpecEngine api should belong to exactly one responsibility slice`() {
        val assignments = SpecEngineResponsibilityCatalog.slices
            .flatMap { slice -> slice.apiMethods.map { methodName -> methodName to slice.id } }
            .groupBy({ it.first }, { it.second })

        val duplicatedAssignments = assignments
            .filterValues { ids -> ids.distinct().size > 1 }
            .mapValues { (_, ids) -> ids.distinct().sortedBy { it.name } }

        assertTrue(
            duplicatedAssignments.isEmpty(),
            "SpecEngine API methods assigned to multiple slices: $duplicatedAssignments",
        )

        val publicApiMethods = declaredPublicApiMethods()

        val missing = publicApiMethods - SpecEngineResponsibilityCatalog.coveredApiMethods
        val stale = SpecEngineResponsibilityCatalog.coveredApiMethods - publicApiMethods

        assertTrue(
            missing.isEmpty() && stale.isEmpty(),
            buildString {
                appendLine("SpecEngine responsibility catalog drift detected.")
                appendLine("Every public SpecEngine API should belong to exactly one responsibility slice.")
                if (missing.isNotEmpty()) {
                    appendLine("Missing methods: ${missing.sorted().joinToString(", ")}")
                }
                if (stale.isNotEmpty()) {
                    appendLine("Stale catalog methods: ${stale.sorted().joinToString(", ")}")
                }
            },
        )
    }

    @Test
    fun `catalog should document current stage transition methods as P0 extraction seam`() {
        val transitionSlice = SpecEngineResponsibilityCatalog.responsibilityFor("advanceWorkflow")
        assertEquals(
            SpecEngineResponsibilityCatalog.ResponsibilityId.STAGE_TRANSITION_PREFLIGHT,
            transitionSlice?.id,
        )
        assertEquals(SpecEngineResponsibilityCatalog.Priority.P0, transitionSlice?.priority)
        assertTrue(transitionSlice?.apiMethods?.contains("previewStageTransition") == true)
        assertTrue(transitionSlice?.apiMethods?.contains("completeWorkflow") == true)
    }

    private fun declaredPublicApiMethods(): Set<String> {
        val methodPattern = Regex("^ {4}(?:suspend )?fun ([A-Za-z0-9_]+)\\(", setOf(RegexOption.MULTILINE))
        val source = Files.readString(specEngineSource)
        return methodPattern.findAll(source)
            .map { match -> match.groupValues[1] }
            .toSet()
    }

    companion object {
        private val specEngineSource: Path = Path.of(
            "src",
            "main",
            "kotlin",
            "com",
            "eacape",
            "speccodingplugin",
            "spec",
            "SpecEngine.kt",
        )
    }
}
