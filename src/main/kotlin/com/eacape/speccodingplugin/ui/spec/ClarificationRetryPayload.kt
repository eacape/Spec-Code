package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.ConfirmedClarificationPayload
import com.eacape.speccodingplugin.spec.ClarificationFollowUp
import com.eacape.speccodingplugin.spec.RequirementsSectionId

internal data class ClarificationRetryPayload(
    val input: String,
    val confirmedContext: String,
    val questionsMarkdown: String,
    val structuredQuestions: List<String>,
    val clarificationRound: Int,
    val lastError: String?,
    val confirmed: Boolean,
    val followUp: ClarificationFollowUp,
    val requirementsRepairSections: List<RequirementsSectionId>,
)

internal fun normalizeRetryText(value: String): String {
    return value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
}

internal fun ClarificationRetryPayload?.toWritebackPayload(
    confirmedContext: String? = null,
): ConfirmedClarificationPayload? {
    val context = normalizeRetryText(confirmedContext ?: this?.confirmedContext.orEmpty())
    if (context.isBlank()) {
        return null
    }
    return ConfirmedClarificationPayload(
        confirmedContext = context,
        questionsMarkdown = this?.questionsMarkdown.orEmpty(),
        structuredQuestions = this?.structuredQuestions.orEmpty(),
        clarificationRound = this?.clarificationRound ?: 1,
    )
}
