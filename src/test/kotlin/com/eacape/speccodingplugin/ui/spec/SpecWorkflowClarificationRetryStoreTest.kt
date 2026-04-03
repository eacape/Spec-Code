package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.ClarificationRetryState
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowClarificationRetryStoreTest {

    @Test
    fun `remember should merge with previous retry state and persist normalized payload`() {
        val recorder = RecordingPersistSink()
        val store = store(recorder)
        store.remember(
            request(
                workflowId = "wf-1",
                input = " seed input ",
                confirmedContext = " confirmed context ",
                questionsMarkdown = " ## Questions ",
                structuredQuestions = listOf(" Q1 ", "Q1"),
                clarificationRound = 2,
                lastError = "first error",
                confirmed = false,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(
                    RequirementsSectionId.USER_STORIES,
                    RequirementsSectionId.USER_STORIES,
                ),
            ),
        )
        recorder.calls.clear()

        val payload = store.remember(
            request(
                workflowId = " wf-1 ",
                input = " ",
                confirmedContext = null,
                lastError = " next error \r\n",
                confirmed = true,
            ),
        )

        assertEquals(
            ClarificationRetryPayload(
                input = "seed input",
                confirmedContext = "confirmed context",
                questionsMarkdown = "## Questions",
                structuredQuestions = listOf("Q1"),
                clarificationRound = 2,
                lastError = "next error",
                confirmed = true,
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = listOf(RequirementsSectionId.USER_STORIES),
            ),
            payload,
        )
        assertEquals(
            listOf(
                PersistCall(
                    workflowId = "wf-1",
                    state = payload?.toState(),
                ),
            ),
            recorder.calls,
        )
        assertSame(payload, store.current("wf-1"))
        assertTrue(store.hasInput("wf-1"))
    }

    @Test
    fun `remember should downgrade requirements repair follow up when no repair sections remain`() {
        val recorder = RecordingPersistSink()
        val store = store(recorder)

        val payload = store.remember(
            request(
                workflowId = "wf-2",
                input = "repair input",
                confirmedContext = "repair context",
                followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
                requirementsRepairSections = emptyList(),
            ),
        )

        assertEquals(ClarificationFollowUp.GENERATION, payload?.followUp)
        assertEquals(emptyList<RequirementsSectionId>(), payload?.requirementsRepairSections)
        assertEquals(
            listOf(
                PersistCall(
                    workflowId = "wf-2",
                    state = payload?.toState(),
                ),
            ),
            recorder.calls,
        )
    }

    @Test
    fun `remember should update in-memory state without persisting when persist flag is false`() {
        val recorder = RecordingPersistSink()
        val store = store(recorder)

        val payload = store.remember(
            request(
                workflowId = "wf-3",
                input = " draft input ",
                confirmedContext = " draft context ",
                persist = false,
            ),
        )

        assertEquals(payload, store.current("wf-3"))
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `clear should remove retry state and persist null`() {
        val recorder = RecordingPersistSink()
        val store = store(recorder)
        store.remember(
            request(
                workflowId = "wf-4",
                input = "seed input",
                confirmedContext = "seed context",
            ),
        )
        recorder.calls.clear()

        store.clear(" wf-4 ")

        assertNull(store.current("wf-4"))
        assertEquals(
            listOf(PersistCall(workflowId = "wf-4", state = null)),
            recorder.calls,
        )
    }

    @Test
    fun `syncFromWorkflow should mirror persisted retry state without triggering persistence`() {
        val recorder = RecordingPersistSink()
        val store = store(recorder)
        val retryState = ClarificationRetryState(
            input = "workflow input",
            confirmedContext = "workflow context",
            questionsMarkdown = "## Workflow Questions",
            structuredQuestions = listOf("Q1", "Q2"),
            clarificationRound = 3,
            lastError = "stored error",
            confirmed = true,
            followUp = ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR,
            requirementsRepairSections = listOf(RequirementsSectionId.ACCEPTANCE_CRITERIA),
        )

        val synced = store.syncFromWorkflow(
            workflow(
                id = "wf-5",
                clarificationRetryState = retryState,
            ),
        )

        assertEquals(retryState.toPayload(), synced)
        assertEquals(retryState.toPayload(), store.current("wf-5"))
        assertTrue(store.hasInput(" wf-5 "))

        val cleared = store.syncFromWorkflow(
            workflow(
                id = "wf-5",
                clarificationRetryState = null,
            ),
        )

        assertNull(cleared)
        assertNull(store.current("wf-5"))
        assertTrue(recorder.calls.isEmpty())
    }

    private fun store(
        recorder: RecordingPersistSink,
    ): SpecWorkflowClarificationRetryStore {
        return SpecWorkflowClarificationRetryStore { workflowId, state ->
            recorder.calls += PersistCall(workflowId, state)
        }
    }

    private fun request(
        workflowId: String,
        input: String,
        confirmedContext: String?,
        questionsMarkdown: String? = null,
        structuredQuestions: List<String>? = null,
        clarificationRound: Int? = null,
        lastError: String? = null,
        confirmed: Boolean? = null,
        followUp: ClarificationFollowUp? = null,
        requirementsRepairSections: List<RequirementsSectionId>? = null,
        persist: Boolean = true,
    ): SpecWorkflowClarificationRetryRememberRequest {
        return SpecWorkflowClarificationRetryRememberRequest(
            workflowId = workflowId,
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
            followUp = followUp,
            requirementsRepairSections = requirementsRepairSections,
            persist = persist,
        )
    }

    private fun workflow(
        id: String,
        clarificationRetryState: ClarificationRetryState?,
    ): SpecWorkflow {
        val metadata = SpecMetadata(
            title = "Workflow $id",
            description = "Spec for $id",
            createdAt = 1L,
            updatedAt = 2L,
        )
        return SpecWorkflow(
            id = id,
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to SpecDocument(
                    id = "$id-specify",
                    phase = SpecPhase.SPECIFY,
                    content = "spec",
                    metadata = metadata,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = metadata.title,
            description = metadata.description,
            clarificationRetryState = clarificationRetryState,
        )
    }

    private data class PersistCall(
        val workflowId: String,
        val state: ClarificationRetryState?,
    )

    private class RecordingPersistSink {
        val calls = mutableListOf<PersistCall>()
    }
}
