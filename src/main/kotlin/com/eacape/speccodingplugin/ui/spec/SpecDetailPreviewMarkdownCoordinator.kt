package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase

internal data class SpecDetailPreviewChecklistInteractionPlan(
    val phase: SpecPhase,
    val content: String,
)

internal data class SpecDetailPreviewMarkdownPlan(
    val displayContent: String,
    val checklistInteraction: SpecDetailPreviewChecklistInteractionPlan? = null,
)

internal object SpecDetailPreviewMarkdownCoordinator {

    fun buildPlan(
        content: String,
        interactivePhase: SpecPhase? = null,
        revisionLockedPhase: SpecPhase? = null,
    ): SpecDetailPreviewMarkdownPlan {
        val normalizedRaw = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val sanitized = SpecMarkdownSanitizer.sanitize(normalizedRaw)
        val displayContent = choosePreviewContent(
            rawContent = normalizedRaw,
            sanitizedContent = sanitized,
        )
        return SpecDetailPreviewMarkdownPlan(
            displayContent = displayContent,
            checklistInteraction = if (
                interactivePhase != null &&
                displayContent == normalizedRaw &&
                revisionLockedPhase != interactivePhase
            ) {
                SpecDetailPreviewChecklistInteractionPlan(
                    phase = interactivePhase,
                    content = normalizedRaw,
                )
            } else {
                null
            },
        )
    }

    fun toggleChecklistLine(content: String, lineIndex: Int): String? {
        val lines = content.lines().toMutableList()
        if (lineIndex !in lines.indices) {
            return null
        }
        val match = PREVIEW_CHECKLIST_LINE_REGEX.matchEntire(lines[lineIndex]) ?: return null
        val toggledMarker = if (match.groupValues[2].equals("x", ignoreCase = true)) " " else "x"
        lines[lineIndex] = buildString {
            append(match.groupValues[1])
            append('[')
            append(toggledMarker)
            append(']')
            append(match.groupValues[3])
        }
        return lines.joinToString("\n")
    }

    private fun choosePreviewContent(rawContent: String, sanitizedContent: String): String {
        val raw = rawContent.trim()
        val sanitized = sanitizedContent.trim()
        if (raw.isBlank()) return sanitized
        if (sanitized.isBlank()) return raw

        if (shouldUseSanitizedPreview(raw)) {
            return sanitized
        }
        if (shouldPreferRawPreview(raw, sanitized)) {
            return raw
        }
        return sanitized
    }

    private fun shouldUseSanitizedPreview(rawContent: String): Boolean {
        if (TOOL_NOISE_MARKER_REGEX.containsMatchIn(rawContent)) return true
        val trimmed = rawContent.trimStart()
        if (trimmed.startsWith("{") && trimmed.contains("\"content\"")) return true
        val escapedNewlineCount = ESCAPED_NEWLINE_REGEX.findAll(rawContent).count()
        val realNewlineCount = rawContent.count { it == '\n' }
        return escapedNewlineCount >= 2 && escapedNewlineCount > realNewlineCount
    }

    private fun shouldPreferRawPreview(rawContent: String, sanitizedContent: String): Boolean {
        if (!CODE_FENCE_MARKER_REGEX.containsMatchIn(rawContent)) return false

        val rawLooksDocument = rawContent
            .lineSequence()
            .take(MAX_PREVIEW_DOC_SCAN_LINES)
            .any { line ->
                val trimmed = line.trim()
                HEADING_LINE_REGEX.matches(trimmed) || LIST_OR_CHECKBOX_LINE_REGEX.matches(trimmed)
            }
        if (!rawLooksDocument) return false

        val sanitizedLooksDocument = sanitizedContent
            .lineSequence()
            .take(MAX_PREVIEW_DOC_SCAN_LINES)
            .any { line ->
                val trimmed = line.trim()
                HEADING_LINE_REGEX.matches(trimmed) || LIST_OR_CHECKBOX_LINE_REGEX.matches(trimmed)
            }
        if (sanitizedLooksDocument) return false

        val ratio = sanitizedContent.length.toDouble() / rawContent.length.toDouble()
        return ratio <= PREVIEW_SANITIZE_COLLAPSE_RATIO
    }

    private val CODE_FENCE_MARKER_REGEX = Regex("```")
    private val HEADING_LINE_REGEX = Regex("""^\s{0,3}#{1,6}\s+\S+""")
    private val LIST_OR_CHECKBOX_LINE_REGEX = Regex("""^\s*(?:[-*]\s+\S+|\d+\.\s+\S+|-?\s*\[[ xX]\]\s+\S+)""")
    private val PREVIEW_CHECKLIST_LINE_REGEX = Regex("""^(\s*(?:[-*]|\d+[.)])\s*)\[( |x|X)](\s+.*)$""")
    private val TOOL_NOISE_MARKER_REGEX = Regex(
        pattern = """<tool_|"tool_(?:calls?|name|input)"|plan_file_path""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    private val ESCAPED_NEWLINE_REGEX = Regex("""\\n|\\r\\n""")
    private const val MAX_PREVIEW_DOC_SCAN_LINES = 60
    private const val PREVIEW_SANITIZE_COLLAPSE_RATIO = 0.85
}
