package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase

internal enum class SpecDetailClarificationQuestionDecision {
    UNDECIDED,
    CONFIRMED,
    NOT_APPLICABLE,
}

internal data class SpecDetailClarificationFormState(
    val phase: SpecPhase,
    val input: String,
    val questionsMarkdown: String,
    val structuredQuestions: List<String> = emptyList(),
    val questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision> = emptyMap(),
    val questionDetails: Map<Int, String> = emptyMap(),
) {
    val checklistMode: Boolean
        get() = structuredQuestions.isNotEmpty()

    fun progress(): SpecDetailClarificationChecklistProgress {
        return SpecDetailClarificationChecklistProgress(
            confirmedCount = questionDecisions.values.count { it == SpecDetailClarificationQuestionDecision.CONFIRMED },
            notApplicableCount = questionDecisions.values.count { it == SpecDetailClarificationQuestionDecision.NOT_APPLICABLE },
            totalCount = structuredQuestions.size,
        )
    }

    fun confirmedEntries(): List<SpecDetailClarificationResolvedQuestion> {
        return questionDecisions.entries
            .asSequence()
            .filter { it.value == SpecDetailClarificationQuestionDecision.CONFIRMED }
            .sortedBy { it.key }
            .mapNotNull { (index, _) ->
                val question = structuredQuestions.getOrNull(index)?.trim().orEmpty()
                if (question.isBlank()) {
                    null
                } else {
                    SpecDetailClarificationResolvedQuestion(
                        index = index,
                        question = question,
                        detail = normalizeDetail(questionDetails[index].orEmpty()),
                    )
                }
            }
            .toList()
    }

    fun notApplicableQuestions(): List<String> {
        return questionDecisions.entries
            .asSequence()
            .filter { it.value == SpecDetailClarificationQuestionDecision.NOT_APPLICABLE }
            .sortedBy { it.key }
            .mapNotNull { (index, _) -> structuredQuestions.getOrNull(index)?.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun firstMissingConfirmedQuestion(): String? {
        return confirmedEntries()
            .firstOrNull { it.detail.isBlank() }
            ?.question
    }

    fun resolvedActiveDetailIndex(activeDetailIndex: Int?): Int? {
        val confirmedIndexes = confirmedEntries().map { it.index }
        return when {
            confirmedIndexes.isEmpty() -> null
            activeDetailIndex in confirmedIndexes -> activeDetailIndex
            else -> confirmedIndexes.first()
        }
    }

    fun withDecision(
        index: Int,
        decision: SpecDetailClarificationQuestionDecision,
        activeDetailIndex: Int?,
    ): SpecDetailClarificationMutation? {
        if (!checklistMode || index !in structuredQuestions.indices) {
            return null
        }
        val nextDecisions = questionDecisions.toMutableMap()
        val nextDetails = questionDetails.toMutableMap()
        val nextActiveIndex = when (decision) {
            SpecDetailClarificationQuestionDecision.UNDECIDED -> {
                nextDecisions.remove(index)
                nextDetails.remove(index)
                if (activeDetailIndex == index) {
                    firstConfirmedIndex(nextDecisions)
                } else {
                    activeDetailIndex
                }
            }
            SpecDetailClarificationQuestionDecision.CONFIRMED -> {
                nextDecisions[index] = decision
                index
            }
            SpecDetailClarificationQuestionDecision.NOT_APPLICABLE -> {
                nextDecisions[index] = decision
                nextDetails.remove(index)
                if (activeDetailIndex == index) {
                    firstConfirmedIndex(nextDecisions)
                } else {
                    activeDetailIndex
                }
            }
        }
        return SpecDetailClarificationMutation(
            state = copy(
                questionDecisions = nextDecisions,
                questionDetails = nextDetails,
            ),
            activeDetailIndex = nextActiveIndex,
        )
    }

    fun withConfirmedDetail(
        index: Int,
        detail: String,
        activeDetailIndex: Int?,
    ): SpecDetailClarificationMutation? {
        if (!checklistMode || index !in structuredQuestions.indices) {
            return null
        }
        if (questionDecisions[index] != SpecDetailClarificationQuestionDecision.CONFIRMED) {
            return null
        }
        val nextDetails = questionDetails.toMutableMap()
        val normalizedDetail = normalizeDetail(detail)
        if (normalizedDetail.isBlank()) {
            nextDetails.remove(index)
        } else {
            nextDetails[index] = normalizedDetail
        }
        return SpecDetailClarificationMutation(
            state = copy(questionDetails = nextDetails),
            activeDetailIndex = index.takeIf { questionDecisions[index] == SpecDetailClarificationQuestionDecision.CONFIRMED }
                ?: activeDetailIndex,
        )
    }

    fun confirmedContext(text: SpecDetailClarificationText): String {
        val confirmedEntries = confirmedEntries()
        val notApplicableQuestions = notApplicableQuestions()
        if (confirmedEntries.isEmpty() && notApplicableQuestions.isEmpty()) {
            return ""
        }
        return buildString {
            if (confirmedEntries.isNotEmpty()) {
                appendLine("**${text.confirmedTitle}**")
                confirmedEntries.forEach { entry ->
                    appendLine("- ${entry.question}")
                    if (entry.detail.isNotBlank()) {
                        appendLine("  - ${text.detailPrefix}: ${entry.detail}")
                    }
                }
            }
            if (notApplicableQuestions.isNotEmpty()) {
                if (confirmedEntries.isNotEmpty()) {
                    appendLine()
                }
                appendLine("**${text.notApplicableTitle}**")
                notApplicableQuestions.forEach { question ->
                    appendLine("- $question")
                }
            }
        }.trimEnd()
    }

    fun previewMarkdown(text: SpecDetailClarificationText): String {
        val confirmedEntries = confirmedEntries()
        val notApplicableQuestions = notApplicableQuestions()
        if (confirmedEntries.isEmpty() && notApplicableQuestions.isEmpty()) {
            return ""
        }
        return buildString {
            if (confirmedEntries.isNotEmpty()) {
                appendLine("**${text.confirmedTitle}**")
                confirmedEntries.forEach { entry ->
                    if (entry.detail.isNotBlank()) {
                        val escapedDetail = entry.detail.replace('`', '\'')
                        appendLine("- ${entry.question}  `${text.detailPrefix}: $escapedDetail`")
                    } else {
                        appendLine("- ${entry.question}")
                    }
                }
            }
            if (notApplicableQuestions.isNotEmpty()) {
                if (confirmedEntries.isNotEmpty()) {
                    appendLine()
                }
                appendLine("**${text.notApplicableTitle}**")
                notApplicableQuestions.forEach { question ->
                    appendLine("- $question")
                }
            }
        }.trimEnd()
    }

    companion object {
        fun draft(
            phase: SpecPhase,
            input: String,
            questionsMarkdown: String,
            suggestedDetails: String,
            structuredQuestions: List<String>,
            text: SpecDetailClarificationText,
        ): SpecDetailClarificationMutation {
            val normalizedQuestions = structuredQuestions
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val inferredDecisions = inferQuestionDecisions(
                structuredQuestions = normalizedQuestions,
                confirmedContext = suggestedDetails,
                text = text,
            )
            val inferredDetails = inferQuestionDetails(
                structuredQuestions = normalizedQuestions,
                confirmedContext = suggestedDetails,
                questionDecisions = inferredDecisions,
            )
            val state = SpecDetailClarificationFormState(
                phase = phase,
                input = input,
                questionsMarkdown = questionsMarkdown,
                structuredQuestions = normalizedQuestions,
                questionDecisions = inferredDecisions,
                questionDetails = inferredDetails,
            )
            return SpecDetailClarificationMutation(
                state = state,
                activeDetailIndex = state.resolvedActiveDetailIndex(null),
            )
        }

        private fun inferQuestionDecisions(
            structuredQuestions: List<String>,
            confirmedContext: String,
            text: SpecDetailClarificationText,
        ): Map<Int, SpecDetailClarificationQuestionDecision> {
            if (structuredQuestions.isEmpty()) {
                return emptyMap()
            }
            val lines = confirmedContext
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
            val normalizedContext = normalizeComparableText(confirmedContext)
            if (normalizedContext.isBlank()) {
                return emptyMap()
            }
            val lineSections = mapContextSections(lines, text)
            return structuredQuestions.mapIndexedNotNull { index, question ->
                val normalizedQuestion = normalizeComparableText(question)
                if (normalizedQuestion.isBlank()) {
                    return@mapIndexedNotNull null
                }
                val lineIndex = lines.indexOfFirst { line ->
                    normalizeComparableText(line).contains(normalizedQuestion)
                }
                val normalizedLine = if (lineIndex >= 0) normalizeComparableText(lines[lineIndex]) else null
                val section = lineSections[lineIndex] ?: ClarificationContextSection.OTHER
                when {
                    normalizedLine != null && normalizedLine.contains("[x]") ->
                        index to SpecDetailClarificationQuestionDecision.CONFIRMED
                    normalizedLine != null && (normalizedLine.contains("[ ]") || normalizedLine.contains("[]")) ->
                        index to SpecDetailClarificationQuestionDecision.NOT_APPLICABLE
                    section == ClarificationContextSection.NOT_APPLICABLE ->
                        index to SpecDetailClarificationQuestionDecision.NOT_APPLICABLE
                    section == ClarificationContextSection.CONFIRMED ->
                        index to SpecDetailClarificationQuestionDecision.CONFIRMED
                    normalizedContext.contains(normalizedQuestion) ->
                        index to SpecDetailClarificationQuestionDecision.CONFIRMED
                    else -> null
                }
            }.toMap()
        }

        private fun inferQuestionDetails(
            structuredQuestions: List<String>,
            confirmedContext: String,
            questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision>,
        ): Map<Int, String> {
            if (structuredQuestions.isEmpty() || questionDecisions.isEmpty()) {
                return emptyMap()
            }
            val lines = confirmedContext
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
            if (lines.isEmpty()) {
                return emptyMap()
            }
            return questionDecisions.entries
                .asSequence()
                .filter { it.value == SpecDetailClarificationQuestionDecision.CONFIRMED }
                .mapNotNull { (index, _) ->
                    val normalizedQuestion = normalizeComparableText(structuredQuestions.getOrNull(index).orEmpty())
                    if (normalizedQuestion.isBlank()) {
                        return@mapNotNull null
                    }
                    val questionLineIndex = lines.indexOfFirst { line ->
                        normalizeComparableText(line).contains(normalizedQuestion)
                    }
                    if (questionLineIndex < 0) {
                        return@mapNotNull null
                    }
                    val detail = extractChecklistDetail(lines, questionLineIndex)
                    if (detail.isBlank()) {
                        null
                    } else {
                        index to detail
                    }
                }
                .toMap()
        }

        private fun extractChecklistDetail(lines: List<String>, questionLineIndex: Int): String {
            for (lineIndex in (questionLineIndex + 1) until lines.size) {
                val trimmed = lines[lineIndex].trim()
                if (trimmed.isBlank()) {
                    continue
                }
                if (trimmed.startsWith("#")) {
                    break
                }
                if (trimmed.startsWith("- ") && !DETAIL_LINE_REGEX.containsMatchIn(trimmed)) {
                    break
                }
                val detailMatch = DETAIL_LINE_REGEX.find(trimmed)
                if (detailMatch != null) {
                    return detailMatch.groupValues[2].trim()
                }
            }
            return ""
        }

        private fun mapContextSections(
            lines: List<String>,
            text: SpecDetailClarificationText,
        ): Map<Int, ClarificationContextSection> {
            val sectionByLine = mutableMapOf<Int, ClarificationContextSection>()
            var current = ClarificationContextSection.OTHER
            lines.forEachIndexed { index, line ->
                val normalized = normalizeComparableText(line)
                when {
                    normalized.isBlank() -> Unit
                    text.confirmedSectionMarkers.any { marker -> normalized.contains(normalizeComparableText(marker)) } -> {
                        current = ClarificationContextSection.CONFIRMED
                    }
                    text.notApplicableSectionMarkers.any { marker -> normalized.contains(normalizeComparableText(marker)) } -> {
                        current = ClarificationContextSection.NOT_APPLICABLE
                    }
                }
                sectionByLine[index] = current
            }
            return sectionByLine
        }

        private fun normalizeComparableText(value: String): String {
            return value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lowercase()
                .replace(Regex("\\s+"), "")
        }

        private fun normalizeDetail(detail: String): String {
            return detail
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun firstConfirmedIndex(
            questionDecisions: Map<Int, SpecDetailClarificationQuestionDecision>,
        ): Int? {
            return questionDecisions.entries
                .asSequence()
                .filter { it.value == SpecDetailClarificationQuestionDecision.CONFIRMED }
                .map { it.key }
                .sorted()
                .firstOrNull()
        }

        private enum class ClarificationContextSection {
            CONFIRMED,
            NOT_APPLICABLE,
            OTHER,
        }

        private val DETAIL_LINE_REGEX = Regex(
            "^-\\s*(detail|details|补充|说明)\\s*[:：]\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )
    }
}

internal data class SpecDetailClarificationResolvedQuestion(
    val index: Int,
    val question: String,
    val detail: String,
)

internal data class SpecDetailClarificationChecklistProgress(
    val confirmedCount: Int,
    val notApplicableCount: Int,
    val totalCount: Int,
)

internal data class SpecDetailClarificationMutation(
    val state: SpecDetailClarificationFormState,
    val activeDetailIndex: Int?,
)

internal data class SpecDetailClarificationText(
    val confirmedTitle: String,
    val notApplicableTitle: String,
    val detailPrefix: String,
    val confirmedSectionMarkers: List<String>,
    val notApplicableSectionMarkers: List<String>,
)
