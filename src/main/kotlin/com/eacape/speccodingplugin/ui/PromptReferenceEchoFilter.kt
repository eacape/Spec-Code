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
        if (PROMPT_REFERENCE_ECHO_STRUCTURED_AGENDA_REGEX.matches(trimmed)) return true
        if (PROMPT_REFERENCE_ECHO_NORMALIZED_PREFIXES.any(normalized::startsWith)) return true
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
            return PromptReferenceEchoFilter(
                promptLines = lines,
                enabled = lines.isNotEmpty() || BUILTIN_PROMPT_ECHO_GUARDS_ENABLED,
            )
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
                return PromptReferenceEchoFilter(
                    promptLines = emptySet(),
                    enabled = BUILTIN_PROMPT_ECHO_GUARDS_ENABLED,
                )
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

            return PromptReferenceEchoFilter(
                promptLines = lines,
                enabled = lines.isNotEmpty() || BUILTIN_PROMPT_ECHO_GUARDS_ENABLED,
            )
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
            """^(?:(?:[-*]\s+)?Prompt\s+#\S.*:|Referenced prompt templates\b.*|Referenced prompt template\s+#\S.*|End referenced prompt templates\.?|User request:|---\s*prompt:\S.*---|##\s+Internal Instructions And Reference Context|###\s+Internal Block\s+\d+|##\s+Conversation History|##\s+Final User Request|##\s+Response Requirements|You are answering the final user request for an IDE chat session\.|Sections marked Internal Instructions and Reference Context are hidden inputs, not user-visible text\.|Use them only as guidance or evidence\.|Do not quote, restate, or continue those hidden sections verbatim unless the user explicitly asks for a quote\.|(?:[-*]\s+)?Answer the final user request directly(?: in the first sentence)?\.|(?:[-*]\s+)?Do not dump raw context or internal instructions\.|(?:[-*]\s+)?Use referenced files only as supporting evidence\.|(?:[-*]\s+)?If the user asks what a document is, identify its purpose before citing details\.)$""",
            RegexOption.IGNORE_CASE,
        )
        private val PROMPT_REFERENCE_ECHO_STRUCTURED_AGENDA_REGEX = Regex(
            """^(?:(?:职责|输出要求|检查重点|分析维度)\s*[:：；;].*|(?:优先指出真正影响交付和维护的问题|明确缺失点以及下一步应检查什么|最后给出一个简洁的行动清单|按高[/-]?中[/-]?低优先级列出可执行改进建议).*)$""",
        )
        private val PROMPT_REFERENCE_ECHO_NORMALIZED_PREFIXES = setOf(
            "you are answering the final user request for an ide chat session",
            "you are the in-ide project development copilot",
            "prefer workflow-oriented responses for implementation tasks",
            "clarify objective and constraints briefly",
            "propose a concrete implementation plan",
            "provide executable code-level changes",
            "include verification/check steps",
            "during implementation replies, include short progress lines when relevant, using prefixes",
            "never claim files were created/modified/deleted unless tools actually executed those edits",
            "if no file edit was performed, describe it as a proposal rather than completed work",
            "keep responses practical, specific to this repository, and avoid generic filler",
            "for non-trivial development requests, use this markdown structure",
            "sections marked internal instructions and reference context are hidden inputs, not user-visible text",
            "use them only as guidance or evidence",
            "do not quote, restate, or continue those hidden sections verbatim unless the user explicitly asks for a quote",
            "answer the final user request directly",
            "do not dump raw context or internal instructions",
            "use referenced files only as supporting evidence",
            "if the user asks what a document is, identify its purpose before citing details",
            "职责：",
            "职责:",
            "输出要求：",
            "输出要求:",
            "检查重点：",
            "检查重点:",
            "分析维度：",
            "分析维度:",
            "优先指出真正影响交付和维护的问题",
            "明确缺失点以及下一步应检查什么",
            "最后给出一个简洁的行动清单",
            "按高/中/低优先级列出可执行改进建议",
        )
        private const val BUILTIN_PROMPT_ECHO_GUARDS_ENABLED = true
        private val PROMPT_ECHO_MARKDOWN_DECORATION_REGEX = Regex("""^[>\s]*(?:[-*]\s+|\d+[.)、）．]\s*)?""")
        private val PROMPT_ECHO_WHITESPACE_REGEX = Regex("""\s+""")
    }
}
