package com.eacape.speccodingplugin.ui.chat

/**
 * 解析助手输出中的 Plan/Execute/Verify 结构化区块。
 */
internal object WorkflowSectionParser {

    enum class SectionKind {
        PLAN,
        EXECUTE,
        VERIFY,
    }

    data class Section(
        val kind: SectionKind,
        val content: String,
    )

    data class ParseResult(
        val sections: List<Section>,
        val remainingText: String,
    )

    fun parse(content: String): ParseResult {
        if (content.isBlank()) {
            return ParseResult(
                sections = emptyList(),
                remainingText = "",
            )
        }

        val sections = mutableListOf<Section>()
        val remaining = StringBuilder()
        val lines = content.lines()
        var currentKind: SectionKind? = null
        val currentBuffer = StringBuilder()

        fun flushCurrent() {
            val kind = currentKind ?: return
            val body = currentBuffer.toString().trim()
            if (body.isNotBlank()) {
                sections += Section(kind = kind, content = body)
            }
            currentKind = null
            currentBuffer.clear()
        }

        lines.forEach { line ->
            val markdownHeadingTitle = parseHeadingTitle(line)
            if (markdownHeadingTitle != null) {
                val markdownKind = mapHeadingToKind(markdownHeadingTitle)
                if (markdownKind != null) {
                    flushCurrent()
                    currentKind = markdownKind
                    return@forEach
                }
                flushCurrent()
                remaining.appendLine(line)
                return@forEach
            }

            val plainHeadingTitle = parsePlainHeadingTitle(line)
            if (plainHeadingTitle != null) {
                val plainKind = mapHeadingToKind(plainHeadingTitle)
                if (plainKind != null) {
                    flushCurrent()
                    currentKind = plainKind
                    return@forEach
                }
            }

            if (currentKind != null) {
                currentBuffer.appendLine(line)
            } else {
                remaining.appendLine(line)
            }
        }

        flushCurrent()

        return ParseResult(
            sections = sections,
            remainingText = remaining.toString().trimEnd(),
        )
    }

    private fun parseHeadingTitle(line: String): String? {
        val match = HEADING_REGEX.find(line.trim()) ?: return null
        return match.groupValues[1].trim()
    }

    private fun parsePlainHeadingTitle(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("-") || trimmed.startsWith("*")) return null
        if (LIST_PREFIX_REGEX.containsMatchIn(trimmed)) return null

        val unwrapped = trimmed
            .removePrefix("**")
            .removeSuffix("**")
            .trim()
            .trimEnd(':', '：')
            .trim()
        if (unwrapped.isBlank()) return null
        if (!PLAIN_HEADING_REGEX.matches(unwrapped)) return null
        return unwrapped
    }

    private fun mapHeadingToKind(rawTitle: String): SectionKind? {
        val normalized = rawTitle
            .trim()
            .trimEnd(':', '：')
            .lowercase()

        return when {
            normalized in PLAN_TITLES -> SectionKind.PLAN
            normalized in EXECUTE_TITLES -> SectionKind.EXECUTE
            normalized in VERIFY_TITLES -> SectionKind.VERIFY
            else -> null
        }
    }

    private val HEADING_REGEX = Regex("""^##+(?:\s+|(?=\S))(.+)$""")
    private val LIST_PREFIX_REGEX = Regex("""^\d+[.)]\s+""")
    private val PLAIN_HEADING_REGEX = Regex("""^[A-Za-z\u4e00-\u9fa5][A-Za-z\u4e00-\u9fa5\s]{0,20}$""")
    private val PLAN_TITLES = setOf("plan", "planning", "计划", "规划")
    private val EXECUTE_TITLES = setOf("execute", "execution", "implement", "执行", "实施")
    private val VERIFY_TITLES = setOf("verify", "verification", "test", "验证", "测试")
}
