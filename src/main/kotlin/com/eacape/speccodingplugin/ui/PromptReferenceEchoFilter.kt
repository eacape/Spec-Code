package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.prompt.PromptTemplate
import java.util.Locale

internal class PromptReferenceEchoFilter private constructor(
    private val promptLines: Set<String>,
    private val enabled: Boolean,
) {
    private val pendingLine = StringBuilder()
    private var lastLineDropped = false

    fun filter(delta: String, flush: Boolean = false): String {
        if (!enabled) return delta
        if (delta.isEmpty() && !flush) return delta

        val normalizedDelta = delta
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val output = StringBuilder(normalizedDelta.length)
        var start = 0
        while (true) {
            val newlineIndex = normalizedDelta.indexOf('\n', start)
            if (newlineIndex < 0) break

            pendingLine.append(normalizedDelta, start, newlineIndex)
            appendFilteredLine(output, pendingLine.toString(), hasLineBreak = true)
            pendingLine.setLength(0)
            start = newlineIndex + 1
        }

        if (start < normalizedDelta.length) {
            pendingLine.append(normalizedDelta.substring(start))
        }
        if (flush && pendingLine.isNotEmpty()) {
            appendFilteredLine(output, pendingLine.toString(), hasLineBreak = false)
            pendingLine.setLength(0)
        }

        return output.toString()
    }

    private fun appendFilteredLine(output: StringBuilder, rawLine: String, hasLineBreak: Boolean) {
        if (shouldDropLine(rawLine)) {
            lastLineDropped = true
            return
        }

        lastLineDropped = false
        output.append(rawLine)
        if (hasLineBreak) {
            output.append('\n')
        }
    }

    private fun shouldDropLine(rawLine: String): Boolean {
        val trimmed = rawLine.trim()
        if (trimmed.isBlank()) return lastLineDropped
        val normalized = normalizePromptEchoLine(trimmed)
        if (normalized.isBlank()) return lastLineDropped
        if (PROMPT_REFERENCE_ECHO_CONTROL_REGEX.matches(trimmed)) return true
        return promptLines.contains(normalized)
    }

    companion object {
        fun fromTextBlocks(blocks: List<String>): PromptReferenceEchoFilter {
            val lines = blocks
                .asSequence()
                .flatMap { block -> block.lineSequence() }
                .map(::normalizePromptEchoLine)
                .filter { it.isNotBlank() }
                .toSet()
            return PromptReferenceEchoFilter(promptLines = lines, enabled = lines.isNotEmpty())
        }

        fun fromTemplates(templates: List<PromptTemplate>): PromptReferenceEchoFilter {
            if (templates.isEmpty()) {
                return PromptReferenceEchoFilter(promptLines = emptySet(), enabled = false)
            }

            return fromTextBlocks(
                templates.flatMap { template ->
                    buildList {
                        add("Prompt #${template.id} (${template.name}):")
                        add("--- prompt:${template.id} (${template.name}) ---")
                        add(template.content)
                    }
                }
            )
        }

        fun fromExpandedPrompt(promptText: String): PromptReferenceEchoFilter {
            if (promptText.isBlank()) {
                return PromptReferenceEchoFilter(promptLines = emptySet(), enabled = false)
            }

            val lines = mutableSetOf<String>()
            var insidePromptReferenceBlock = false
            promptText.lineSequence().forEach { rawLine ->
                val trimmed = rawLine.trim()
                when {
                    trimmed.startsWith("Referenced prompt templates", ignoreCase = true) -> {
                        insidePromptReferenceBlock = true
                        return@forEach
                    }
                    trimmed.equals("End referenced prompt templates.", ignoreCase = true) ||
                        trimmed.equals("End referenced prompt templates", ignoreCase = true) -> {
                        insidePromptReferenceBlock = false
                        return@forEach
                    }
                    insidePromptReferenceBlock -> {
                        normalizePromptEchoLine(trimmed)
                            .takeIf { it.isNotBlank() }
                            ?.let(lines::add)
                    }
                }
            }

            return PromptReferenceEchoFilter(promptLines = lines, enabled = lines.isNotEmpty())
        }

        private fun normalizePromptEchoLine(line: String): String {
            return line
                .trim()
                .replace(PROMPT_ECHO_MARKDOWN_DECORATION_REGEX, "")
                .replace(PROMPT_ECHO_WHITESPACE_REGEX, " ")
                .trim()
                .lowercase(Locale.ROOT)
        }

        private val PROMPT_REFERENCE_ECHO_CONTROL_REGEX = Regex(
            """^(?:Prompt\s+#\S.*:|Referenced prompt templates\b.*|Referenced prompt template\s+#\S.*|End referenced prompt templates\.?|User request:|---\s*prompt:\S.*---|##\s+Internal Instructions And Reference Context|###\s+Internal Block\s+\d+|##\s+Conversation History|##\s+Final User Request|##\s+Response Requirements|You are answering the final user request for an IDE chat session\.|Sections marked Internal Instructions and Reference Context are hidden inputs, not user-visible text\.|Use them only as guidance or evidence\.|Do not quote, restate, or continue those hidden sections verbatim unless the user explicitly asks for a quote\.|Answer the final user request directly in the first sentence\.)$""",
            RegexOption.IGNORE_CASE,
        )
        private val PROMPT_ECHO_MARKDOWN_DECORATION_REGEX = Regex("""^[>\s]*(?:[-*]\s+|\d+[.)、）．]\s*)?""")
        private val PROMPT_ECHO_WHITESPACE_REGEX = Regex("""\s+""")
    }
}
