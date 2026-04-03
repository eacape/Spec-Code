package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.ClarificationRetryState
import com.eacape.speccodingplugin.spec.RequirementsSectionId
import com.eacape.speccodingplugin.spec.SpecWorkflow

internal data class SpecWorkflowClarificationRetryRememberRequest(
    val workflowId: String,
    val input: String,
    val confirmedContext: String?,
    val questionsMarkdown: String? = null,
    val structuredQuestions: List<String>? = null,
    val clarificationRound: Int? = null,
    val lastError: String? = null,
    val confirmed: Boolean? = null,
    val followUp: ClarificationFollowUp? = null,
    val requirementsRepairSections: List<RequirementsSectionId>? = null,
    val persist: Boolean = true,
)

internal class SpecWorkflowClarificationRetryStore(
    private val persistState: (workflowId: String, state: ClarificationRetryState?) -> Unit,
) {

    private val pendingByWorkflowId = mutableMapOf<String, ClarificationRetryPayload>()

    fun current(workflowId: String?): ClarificationRetryPayload? {
        val normalizedWorkflowId = workflowId?.trim().orEmpty()
        if (normalizedWorkflowId.isBlank()) {
            return null
        }
        return pendingByWorkflowId[normalizedWorkflowId]
    }

    fun hasInput(workflowId: String?): Boolean {
        return current(workflowId)?.input?.isNotBlank() == true
    }

    fun remember(request: SpecWorkflowClarificationRetryRememberRequest): ClarificationRetryPayload? {
        val workflowId = request.workflowId.trim()
        if (workflowId.isBlank()) {
            return null
        }

        val previous = pendingByWorkflowId[workflowId]
        val normalizedInput = normalizeRetryText(request.input)
        val normalizedContext = request.confirmedContext?.let(::normalizeRetryText)
        val normalizedQuestions = request.questionsMarkdown?.let(::normalizeRetryText)
        val normalizedError = request.lastError
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val mergedInput = normalizedInput.ifBlank { previous?.input.orEmpty() }
        val mergedContext = normalizedContext ?: previous?.confirmedContext.orEmpty()
        val mergedQuestions = normalizedQuestions ?: previous?.questionsMarkdown.orEmpty()
        val mergedStructuredQuestions = request.structuredQuestions
            ?.map(::normalizeRetryText)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?: previous?.structuredQuestions.orEmpty()
        val mergedRound = request.clarificationRound ?: previous?.clarificationRound ?: 1
        val mergedError = normalizedError ?: previous?.lastError
        val mergedConfirmed = request.confirmed ?: previous?.confirmed ?: false
        val mergedFollowUp = request.followUp ?: previous?.followUp ?: ClarificationFollowUp.GENERATION
        val mergedRequirementsRepairSections = request.requirementsRepairSections
            ?.distinct()
            ?: previous?.requirementsRepairSections.orEmpty()

        if (
            mergedInput.isBlank() &&
            mergedContext.isBlank() &&
            mergedQuestions.isBlank() &&
            mergedStructuredQuestions.isEmpty()
        ) {
            pendingByWorkflowId.remove(workflowId)
            if (request.persist) {
                persistState(workflowId, null)
            }
            return null
        }

        val payload = ClarificationRetryPayload(
            input = mergedInput,
            confirmedContext = mergedContext,
            questionsMarkdown = mergedQuestions,
            structuredQuestions = mergedStructuredQuestions,
            clarificationRound = mergedRound,
            lastError = mergedError,
            confirmed = mergedConfirmed,
            followUp = if (
                mergedFollowUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR &&
                mergedRequirementsRepairSections.isNotEmpty()
            ) {
                ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR
            } else {
                ClarificationFollowUp.GENERATION
            },
            requirementsRepairSections = if (
                mergedFollowUp == ClarificationFollowUp.REQUIREMENTS_SECTION_REPAIR &&
                mergedRequirementsRepairSections.isNotEmpty()
            ) {
                mergedRequirementsRepairSections
            } else {
                emptyList()
            },
        )
        pendingByWorkflowId[workflowId] = payload
        if (request.persist) {
            persistState(workflowId, payload.toState())
        }
        return payload
    }

    fun clear(workflowId: String, persist: Boolean = true) {
        val normalizedWorkflowId = workflowId.trim()
        if (normalizedWorkflowId.isBlank()) {
            return
        }
        pendingByWorkflowId.remove(normalizedWorkflowId)
        if (persist) {
            persistState(normalizedWorkflowId, null)
        }
    }

    fun syncFromWorkflow(workflow: SpecWorkflow): ClarificationRetryPayload? {
        val workflowId = workflow.id.trim()
        if (workflowId.isBlank()) {
            return null
        }
        val payload = workflow.clarificationRetryState?.toPayload()
        if (payload == null) {
            pendingByWorkflowId.remove(workflowId)
            return null
        }
        pendingByWorkflowId[workflowId] = payload
        return payload
    }
}
