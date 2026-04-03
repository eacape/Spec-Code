package com.eacape.speccodingplugin.ui.spec

internal data class SpecDetailClarificationInlineSegment(
    val text: String,
    val bold: Boolean = false,
    val inlineCode: Boolean = false,
)

internal object SpecDetailClarificationInlineMarkdownParser {

    fun parse(
        text: String,
        collapseWhitespace: Boolean = false,
    ): List<SpecDetailClarificationInlineSegment> {
        val source = if (collapseWhitespace) {
            normalizeWhitespace(text)
        } else {
            text
        }
        if (source.isBlank()) {
            return emptyList()
        }

        val segments = mutableListOf<SpecDetailClarificationInlineSegment>()
        var cursor = 0
        while (cursor < source.length) {
            if (
                cursor + 1 < source.length &&
                source[cursor] == '*' &&
                source[cursor + 1] == '*'
            ) {
                val end = source.indexOf("**", cursor + 2)
                if (end > cursor + 1) {
                    val boldText = source.substring(cursor + 2, end)
                    if (boldText.isNotEmpty()) {
                        segments += SpecDetailClarificationInlineSegment(
                            text = boldText,
                            bold = true,
                        )
                    }
                    cursor = end + 2
                    continue
                }
            }

            if (source[cursor] == '`') {
                val delimiterLength = countBacktickDelimiterLength(source, cursor)
                val delimiter = "`".repeat(delimiterLength)
                val end = source.indexOf(delimiter, cursor + delimiterLength)
                if (end >= cursor + delimiterLength) {
                    val codeText = source.substring(cursor + delimiterLength, end)
                    if (codeText.isNotEmpty()) {
                        segments += SpecDetailClarificationInlineSegment(
                            text = codeText,
                            inlineCode = true,
                        )
                    }
                    cursor = end + delimiterLength
                    continue
                }
            }

            val nextSpecial = findNextSpecial(source, cursor + 1)
            segments += SpecDetailClarificationInlineSegment(
                text = source.substring(cursor, nextSpecial),
            )
            cursor = nextSpecial
        }

        return segments
            .filter { it.text.isNotEmpty() }
            .mergeAdjacentSegments()
    }

    fun normalizeWhitespace(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun findNextSpecial(text: String, from: Int): Int {
        for (index in from until text.length) {
            if (text[index] == '*' || text[index] == '`') {
                return index
            }
        }
        return text.length
    }

    private fun countBacktickDelimiterLength(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length && text[cursor] == '`') {
            cursor++
        }
        return (cursor - start).coerceAtLeast(1)
    }

    private fun List<SpecDetailClarificationInlineSegment>.mergeAdjacentSegments(): List<SpecDetailClarificationInlineSegment> {
        if (isEmpty()) return this
        val merged = mutableListOf<SpecDetailClarificationInlineSegment>()
        forEach { segment ->
            val last = merged.lastOrNull()
            if (last != null && last.bold == segment.bold && last.inlineCode == segment.inlineCode) {
                merged[merged.lastIndex] = last.copy(text = last.text + segment.text)
            } else {
                merged += segment
            }
        }
        return merged
    }
}
