package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class SpecStageTransitionCoordinatorContractTest {

    @Test
    fun `spec engine should delegate stage transition orchestration to coordinator`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/spec/SpecEngine.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val stageTransitionCoordinator: SpecStageTransitionCoordinator by lazy"))
        assertTrue(source.contains("return stageTransitionCoordinator.advanceWorkflow(workflowId, confirmWarnings)"))
        assertTrue(source.contains("return stageTransitionCoordinator.jumpToStage(workflowId, targetStage, confirmWarnings)"))
        assertTrue(source.contains("return stageTransitionCoordinator.rollbackToStage(workflowId, targetStage)"))
        assertTrue(source.contains("return stageTransitionCoordinator.previewStageTransition(workflowId, transitionType, targetStage)"))
        assertTrue(source.contains("return stageTransitionCoordinator.completeWorkflow(workflowId)"))

        assertFalse(source.contains("private fun performStageTransition("))
        assertFalse(source.contains("private fun prepareStageTransition("))
        assertFalse(source.contains("private fun evaluateTransitionGate("))
        assertFalse(source.contains("private fun buildAdvanceCompletionViolations("))
        assertFalse(source.contains("private fun markCurrentStageCompleted("))
    }
}
