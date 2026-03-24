package com.eacape.speccodingplugin.spec

import java.time.Instant

internal data class SpecClarificationWritebackResult(
    val content: String,
    val sectionTitle: String,
    val confirmedCount: Int,
    val notApplicableCount: Int,
    val summary: String,
)

internal object SpecClarificationWriteback {
    fun apply(
        phase: SpecPhase,
        existingContent: String,
        payload: ConfirmedClarificationPayload,
        atMillis: Long,
    ): SpecClarificationWritebackResult? {
        val normalizedContext = normalizeContent(payload.confirmedContext)
        if (normalizedContext.isBlank()) {
            return null
        }

        val resolution = resolve(payload)
        val bulletLines = buildBulletLines(resolution)
        if (bulletLines.isEmpty()) {
            return null
        }

        val entryBlock = buildEntryBlock(
            bulletLines = bulletLines,
            atMillis = atMillis,
        )
        val merged = mergeSection(
            content = normalizeContent(existingContent),
            preferredTitle = preferredSectionTitle(phase),
            alternativeTitle = alternativeSectionTitle(phase),
            entryBlock = entryBlock,
            bulletLines = bulletLines,
        )
        return SpecClarificationWritebackResult(
            content = merged.updatedContent,
            sectionTitle = merged.sectionTitle,
            confirmedCount = resolution.confirmed.size + resolution.narrative.size,
            notApplicableCount = resolution.notApplicable.size,
            summary = bulletLines.joinToString(" | "),
        )
    }

    private data class ResolvedClarification(
        val confirmed: List<Pair<String, String?>>,
        val notApplicable: List<String>,
        val narrative: List<String>,
    )

    private data class MergedSection(
        val updatedContent: String,
        val sectionTitle: String,
    )

    private enum class ClarificationContextSection {
        CONFIRMED,
        NOT_APPLICABLE,
        OTHER,
    }

    private fun resolve(payload: ConfirmedClarificationPayload): ResolvedClarification {
        val structuredQuestions = payload.structuredQuestions
            .map(::normalizeContent)
            .filter { it.isNotBlank() }
        if (structuredQuestions.isEmpty()) {
            return ResolvedClarification(
                confirmed = emptyList(),
                notApplicable = emptyList(),
                narrative = extractNarrativePoints(payload.confirmedContext),
            )
        }

        val lines = normalizeContent(payload.confirmedContext).lines()
        val lineSections = mapContextSections(lines)
        val confirmed = mutableListOf<Pair<String, String?>>()
        val notApplicable = mutableListOf<String>()
        val normalizedContext = normalizeComparableText(payload.confirmedContext)

        structuredQuestions.forEach { question ->
            val normalizedQuestion = normalizeComparableText(question)
            if (normalizedQuestion.isBlank()) {
                return@forEach
            }

            val lineIndex = lines.indexOfFirst { line ->
                normalizeComparableText(line).contains(normalizedQuestion)
            }
            val normalizedLine = if (lineIndex >= 0) normalizeComparableText(lines[lineIndex]) else null
            val detail = extractChecklistDetail(lines, lineIndex).ifBlank { null }
            val section = if (lineIndex >= 0) {
                lineSections[lineIndex] ?: ClarificationContextSection.OTHER
            } else {
                ClarificationContextSection.OTHER
            }

            when {
                normalizedLine != null && normalizedLine.contains("[x]") -> {
                    confirmed += question to detail
                }

                normalizedLine != null && (normalizedLine.contains("[ ]") || normalizedLine.contains("[]")) -> {
                    notApplicable += question
                }

                section == ClarificationContextSection.NOT_APPLICABLE -> {
                    notApplicable += question
                }

                section == ClarificationContextSection.CONFIRMED -> {
                    confirmed += question to detail
                }

                normalizedContext.contains(normalizedQuestion) -> {
                    confirmed += question to detail
                }
            }
        }

        return if (confirmed.isEmpty() && notApplicable.isEmpty()) {
            ResolvedClarification(
                confirmed = emptyList(),
                notApplicable = emptyList(),
                narrative = extractNarrativePoints(payload.confirmedContext),
            )
        } else {
            ResolvedClarification(
                confirmed = confirmed.distinctBy { it.first to normalizeContent(it.second.orEmpty()) },
                notApplicable = notApplicable.distinct(),
                narrative = emptyList(),
            )
        }
    }

    private fun buildBulletLines(resolution: ResolvedClarification): List<String> {
        val confirmedLines = resolution.confirmed.map { (question, detail) ->
            val label = normalizeQuestionLabel(question)
            if (detail.isNullOrBlank()) {
                "- $label"
            } else {
                "- $label: ${normalizeContent(detail)}"
            }
        }
        val narrativeLines = resolution.narrative.map { point -> "- $point" }
        val notApplicableLines = resolution.notApplicable.map { question ->
            "- ${normalizeQuestionLabel(question)}: $NOT_APPLICABLE_TEXT"
        }
        return confirmedLines + narrativeLines + notApplicableLines
    }

    private fun buildEntryBlock(
        bulletLines: List<String>,
        atMillis: Long,
    ): String {
        return buildString {
            appendLine("### ${Instant.ofEpochMilli(atMillis)}")
            bulletLines.forEach(::appendLine)
        }.trimEnd()
    }

    private fun mergeSection(
        content: String,
        preferredTitle: String,
        alternativeTitle: String,
        entryBlock: String,
        bulletLines: List<String>,
    ): MergedSection {
        val normalized = content.trimEnd()
        val sectionTitles = listOf(preferredTitle, alternativeTitle)
        val headingMatches = HEADING_REGEX.findAll(normalized).toList()
        val targetHeading = headingMatches.firstOrNull { match ->
            val headingTitle = match.groupValues[1].trim()
            sectionTitles.any { title -> headingTitle.equals(title, ignoreCase = true) }
        }

        if (targetHeading == null) {
            val updatedContent = if (normalized.isBlank()) {
                "## $preferredTitle\n\n$entryBlock"
            } else {
                "$normalized\n\n## $preferredTitle\n\n$entryBlock"
            }
            return MergedSection(
                updatedContent = updatedContent,
                sectionTitle = preferredTitle,
            )
        }

        val targetTitle = targetHeading.groupValues[1].trim()
        val nextHeading = headingMatches.firstOrNull { it.range.first > targetHeading.range.first }
        val sectionEnd = nextHeading?.range?.first ?: normalized.length
        val sectionBlock = normalized.substring(targetHeading.range.first, sectionEnd)
        if (bulletLines.all(sectionBlock::contains)) {
            return MergedSection(
                updatedContent = normalized,
                sectionTitle = targetTitle,
            )
        }

        val insertionPrefix = normalized.substring(0, sectionEnd).trimEnd()
        val suffix = normalized.substring(sectionEnd).trimStart('\n')
        val merged = buildString {
            append(insertionPrefix)
            append("\n\n")
            append(entryBlock)
            if (suffix.isNotBlank()) {
                append("\n\n")
                append(suffix)
            }
        }.trimEnd()
        return MergedSection(
            updatedContent = merged,
            sectionTitle = targetTitle,
        )
    }

    private fun extractNarrativePoints(confirmedContext: String): List<String> {
        val lines = normalizeContent(confirmedContext).lines()
        val points = mutableListOf<String>()
        val current = mutableListOf<String>()

        fun flush() {
            if (current.isEmpty()) {
                return
            }
            val point = current.joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (point.isNotBlank()) {
                points += point
            }
            current.clear()
        }

        lines.forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) {
                flush()
                return@forEach
            }
            if (isClarificationSectionMarker(trimmed)) {
                flush()
                return@forEach
            }

            val stripped = trimmed
                .replaceFirst(Regex("""^[-*]\s+"""), "")
                .replaceFirst(Regex("""^\d+\.\s+"""), "")
                .replace("**", "")
                .trim()
            if (stripped.isNotBlank()) {
                current += stripped
            }
        }
        flush()
        return points.distinct()
    }

    private fun extractChecklistDetail(lines: List<String>, questionLineIndex: Int): String {
        if (questionLineIndex < 0) {
            return ""
        }

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
                return detailMatch.groupValues[1].trim()
            }
        }
        return ""
    }

    private fun mapContextSections(lines: List<String>): Map<Int, ClarificationContextSection> {
        val sectionByLine = mutableMapOf<Int, ClarificationContextSection>()
        var current = ClarificationContextSection.OTHER
        lines.forEachIndexed { index, line ->
            val normalized = normalizeComparableText(line)
            when {
                normalized.isBlank() -> Unit
                CONFIRMED_SECTION_MARKERS.any { marker ->
                    normalized.contains(normalizeComparableText(marker))
                } -> {
                    current = ClarificationContextSection.CONFIRMED
                }

                NOT_APPLICABLE_SECTION_MARKERS.any { marker ->
                    normalized.contains(normalizeComparableText(marker))
                } -> {
                    current = ClarificationContextSection.NOT_APPLICABLE
                }
            }
            sectionByLine[index] = current
        }
        return sectionByLine
    }

    private fun isClarificationSectionMarker(value: String): Boolean {
        val normalized = normalizeComparableText(value)
        return CONFIRMED_SECTION_MARKERS.any { marker ->
            normalized.contains(normalizeComparableText(marker))
        } || NOT_APPLICABLE_SECTION_MARKERS.any { marker ->
            normalized.contains(normalizeComparableText(marker))
        }
    }

    private fun preferredSectionTitle(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> "Clarifications"
            SpecPhase.DESIGN,
            SpecPhase.IMPLEMENT,
            -> "Decisions"
        }
    }

    private fun alternativeSectionTitle(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> "Decisions"
            SpecPhase.DESIGN,
            SpecPhase.IMPLEMENT,
            -> "Clarifications"
        }
    }

    private fun normalizeQuestionLabel(question: String): String {
        return normalizeContent(question)
            .removeSuffix("?")
            .removeSuffix("\uFF1F")
            .removeSuffix(":")
            .trim()
    }

    private fun normalizeComparableText(value: String): String {
        return normalizeContent(value)
            .lowercase()
            .replace(Regex("\\s+"), "")
    }

    private fun normalizeContent(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private const val NOT_APPLICABLE_TEXT = "Not applicable for this change."
    private val HEADING_REGEX = Regex("""(?m)^##\s+(.+?)\s*$""")
    private val DETAIL_LINE_REGEX = Regex(
        pattern = """^-\s+(?:detail|details|\u8be6\u60c5)\s*:\s*(.+)$""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val CONFIRMED_SECTION_MARKERS = listOf(
        "confirmed clarification points",
        "\u5df2\u786e\u8ba4\u6f84\u6e05\u9879",
    )
    private val NOT_APPLICABLE_SECTION_MARKERS = listOf(
        "not applicable clarification points",
        "\u4e0d\u9002\u7528\u6f84\u6e05\u9879",
    )
}
