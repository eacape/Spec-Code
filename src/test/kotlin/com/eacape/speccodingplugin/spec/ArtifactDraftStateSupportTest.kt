package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtifactDraftStateSupportTest {

    @Test
    fun `deriveState should keep default skeleton unmaterialized`() {
        val state = ArtifactDraftStateSupport.deriveState(
            stageId = StageId.REQUIREMENTS,
            content = ArtifactDraftStateSupport.defaultSkeletonFor(StageId.REQUIREMENTS),
            hasMaterializationAudit = false,
        )

        assertEquals(ArtifactDraftState.UNMATERIALIZED, state)
    }

    @Test
    fun `deriveState should keep placeholder content unmaterialized until saved`() {
        val placeholderTasks = """
            # Implement Document

            ## Task List
            - [ ] TODO: Add implementation details.
        """.trimIndent()

        assertEquals(
            ArtifactDraftState.UNMATERIALIZED,
            ArtifactDraftStateSupport.deriveState(
                stageId = StageId.TASKS,
                content = placeholderTasks,
                hasMaterializationAudit = false,
            ),
        )
        assertEquals(
            ArtifactDraftState.MATERIALIZED,
            ArtifactDraftStateSupport.deriveState(
                stageId = StageId.TASKS,
                content = placeholderTasks,
                hasMaterializationAudit = true,
            ),
        )
    }

    @Test
    fun `initializeForStageStates should track only active composer artifacts`() {
        val states = ArtifactDraftStateSupport.initializeForStageStates(
            linkedMapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.IN_PROGRESS),
                StageId.DESIGN to StageState(active = true, status = StageProgress.NOT_STARTED),
                StageId.TASKS to StageState(active = false, status = StageProgress.NOT_STARTED),
                StageId.IMPLEMENT to StageState(active = true, status = StageProgress.NOT_STARTED),
                StageId.VERIFY to StageState(active = true, status = StageProgress.NOT_STARTED),
            ),
        )

        assertEquals(2, states.size)
        assertTrue(states.containsKey(StageId.REQUIREMENTS))
        assertTrue(states.containsKey(StageId.DESIGN))
        assertFalse(states.containsKey(StageId.TASKS))
        assertFalse(states.containsKey(StageId.VERIFY))
    }
}
