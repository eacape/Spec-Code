package com.eacape.speccodingplugin.ui.spec

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
