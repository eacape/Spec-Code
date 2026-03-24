package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.JBColor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Color
import java.awt.Font
import java.text.Normalizer
import javax.swing.JEditorPane
import javax.swing.JTextPane
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * 基础 Markdown 渲染器
 * 将 Markdown 文本渲染到 JTextPane 的 StyledDocument 中
 *
 * 支持的语法：
 * - 代码块 (```language ... ```)
 * - 行内代码 (`code`)
 * - 粗体 (**text**)
 * - 斜体 (*text*)
 * - 标题 (# ~ ######)
 * - 无序列表 (- item)
 * - 有序列表 (1. item)
 * - 分隔线 (---)
 * - 表格 (| col | col |)
 * - 引用块 (> quote)
 */
object MarkdownRenderer {

    private const val CODE_FONT_FAMILY = "JetBrains Mono"
    private val CODE_BG_LIGHT = Color(238, 242, 248)
    private val CODE_BG_DARK = Color(52, 57, 66)
    private val CODE_FG_LIGHT = Color(86, 97, 118)
    private val CODE_FG_DARK = Color(197, 207, 224)
    private val BLOCK_CODE_BG_LIGHT = Color(241, 245, 251)
    private val BLOCK_CODE_BG_DARK = Color(38, 44, 52)
    private val BLOCK_CODE_FG_LIGHT = Color(44, 52, 63)
    private val BLOCK_CODE_FG_DARK = Color(216, 223, 234)
    private val BLOCKQUOTE_BORDER_LIGHT = Color(205, 216, 231)
    private val BLOCKQUOTE_BORDER_DARK = Color(88, 101, 118)
    private val BLOCKQUOTE_BG_LIGHT = Color(247, 249, 253)
    private val BLOCKQUOTE_BG_DARK = Color(43, 49, 58)
    private val BLOCKQUOTE_MARKER_FG_LIGHT = Color(110, 124, 145)
    private val BLOCKQUOTE_MARKER_FG_DARK = Color(156, 174, 201)
    private val CODE_LANG_FG_LIGHT = Color(104, 120, 142)
    private val CODE_LANG_FG_DARK = Color(148, 164, 187)
    private val LINK_FG_LIGHT = Color(45, 111, 214)
    private val LINK_FG_DARK = Color(124, 181, 255)

    const val LINK_TARGET_ATTRIBUTE: String = "specCoding.markdown.linkTarget"
    const val CHECKLIST_LINE_INDEX_ATTRIBUTE: String = "specCoding.markdown.checklistLineIndex"
    const val CHECKLIST_CHECKED_ATTRIBUTE: String = "specCoding.markdown.checklistChecked"

    fun extractLinkTarget(attributes: AttributeSet?): String? =
        attributes?.getAttribute(LINK_TARGET_ATTRIBUTE) as? String

    fun extractChecklistLineIndex(attributes: AttributeSet?): Int? =
        attributes?.getAttribute(CHECKLIST_LINE_INDEX_ATTRIBUTE) as? Int

    fun extractChecklistChecked(attributes: AttributeSet?): Boolean? =
        attributes?.getAttribute(CHECKLIST_CHECKED_ATTRIBUTE) as? Boolean

    fun render(textPane: JTextPane, markdown: String) {
        val proseFontFamily = textPane.font?.family ?: Font.SANS_SERIF
        val baseFontSize = resolveBaseFontSize(textPane)
        val normalizedMarkdown = normalizeLooseBlockMarkdownOutsideCodeFences(
            normalizeInlineMarkdownOutsideCodeFences(
                normalizeEscapedTableMarkdown(markdown),
            ),
        )
        val htmlTableRenderingAllowed = shouldUseHtmlTableRendering(markdown, normalizedMarkdown)
        if (htmlTableRenderingAllowed && containsMarkdownTable(normalizedMarkdown)) {
            if (renderWithMarkdownEngine(textPane, normalizedMarkdown, proseFontFamily, baseFontSize)) {
                return
            }
            val markdownForHtml = convertMarkdownTablesToHtmlBlocks(normalizedMarkdown)
            if (renderWithHtmlFallback(textPane, markdownForHtml, proseFontFamily, baseFontSize)) {
                return
            }
        }
        if (htmlTableRenderingAllowed && containsFramedPipeTableBlock(normalizedMarkdown)) {
            val markdownForHtml = convertLooseFramedPipeTablesToHtmlBlocks(normalizedMarkdown)
            if (renderWithHtmlFallback(textPane, markdownForHtml, proseFontFamily, baseFontSize)) {
                return
            }
        }
        if (htmlTableRenderingAllowed && containsAnyLoosePipeTableBlock(normalizedMarkdown)) {
            val markdownForHtml = convertAnyLoosePipeTablesToHtmlBlocks(normalizedMarkdown)
            if (renderWithHtmlFallback(textPane, markdownForHtml, proseFontFamily, baseFontSize)) {
                return
            }
        }
        if (htmlTableRenderingAllowed && containsRawHtmlTableBlock(markdown)) {
            if (renderWithMarkdownEngine(textPane, markdown, proseFontFamily, baseFontSize)) {
                return
            }
            if (renderWithHtmlFallback(textPane, markdown, proseFontFamily, baseFontSize)) {
                return
            }
        }

        ensurePlainTextMode(textPane)
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)

        val lines = normalizedMarkdown.lines()
        var i = 0
        var firstBlock = true

        while (i < lines.size) {
            val line = lines[i]
            val trimmedLine = line.trimStart()
            val headingMatch = HEADING_REGEX.matchEntire(line)

            // 检查代码块开始
            if (trimmedLine.startsWith("```")) {
                if (!firstBlock) insertNewline(doc)
                firstBlock = false
                i = renderCodeBlock(doc, lines, i, baseFontSize)
                continue
            }

            val tableBlock = parseTableBlock(lines, i)
            if (tableBlock != null) {
                if (!firstBlock) insertNewline(doc)
                firstBlock = false
                renderTable(doc, tableBlock, proseFontFamily, baseFontSize)
                i = tableBlock.nextIndex
                continue
            }

            val blockquoteBlock = parseBlockquoteBlock(lines, i)
            if (blockquoteBlock != null) {
                if (!firstBlock) insertNewline(doc)
                firstBlock = false
                renderBlockquote(doc, blockquoteBlock, proseFontFamily, baseFontSize)
                i = blockquoteBlock.nextIndex
                continue
            }

            if (!firstBlock) insertNewline(doc)
            firstBlock = false

            when {
                headingMatch != null -> {
                    val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                    renderHeading(doc, headingMatch.groupValues[2], level, proseFontFamily, baseFontSize)
                }
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") ->
                    renderListItem(
                        doc,
                        line,
                        ordered = false,
                        proseFontFamily = proseFontFamily,
                        baseFontSize = baseFontSize,
                        sourceLineIndex = i,
                    )
                ORDERED_LIST_REGEX.matches(trimmedLine) ->
                    renderListItem(
                        doc,
                        line,
                        ordered = true,
                        proseFontFamily = proseFontFamily,
                        baseFontSize = baseFontSize,
                        sourceLineIndex = i,
                    )
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" ->
                    renderHorizontalRule(doc, baseFontSize)
                line.isBlank() -> { /* 空行，newline 已在上面插入 */ }
                else -> renderInlineMarkdown(doc, line, proseFontFamily, baseFontSize)
            }

            i++
        }
    }

    /**
     * 渲染代码块
     * @return 下一行的索引
     */
    private fun renderCodeBlock(doc: StyledDocument, lines: List<String>, startIndex: Int, baseFontSize: Int): Int {
        val firstLine = lines[startIndex].trimStart()
        val language = firstLine.removePrefix("```").trim()

        val codeLines = mutableListOf<String>()
        var i = startIndex + 1
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("```")) {
                i++
                break
            }
            codeLines.add(lines[i])
            i++
        }

        val code = normalizeCodeBlockContent(codeLines.joinToString("\n"))

        // 语言标签
        if (language.isNotEmpty()) {
            val langAttrs = SimpleAttributeSet()
            StyleConstants.setFontSize(langAttrs, (baseFontSize - 1).coerceAtLeast(MIN_INLINE_FONT_SIZE))
            StyleConstants.setForeground(langAttrs, JBColor(CODE_LANG_FG_LIGHT, CODE_LANG_FG_DARK))
            StyleConstants.setSpaceAbove(langAttrs, 2f)
            doc.insertString(
                doc.length,
                SpecCodingBundle.message("chat.markdown.code.languageTag", language) + "\n",
                langAttrs,
            )
        }

        // 代码内容
        val codeAttrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(codeAttrs, CODE_FONT_FAMILY)
        StyleConstants.setFontSize(codeAttrs, baseFontSize)
        StyleConstants.setBackground(codeAttrs, JBColor(BLOCK_CODE_BG_LIGHT, BLOCK_CODE_BG_DARK))
        StyleConstants.setForeground(codeAttrs, JBColor(BLOCK_CODE_FG_LIGHT, BLOCK_CODE_FG_DARK))
        StyleConstants.setLeftIndent(codeAttrs, 10f)
        StyleConstants.setRightIndent(codeAttrs, 10f)
        StyleConstants.setLineSpacing(codeAttrs, 0.08f)
        StyleConstants.setSpaceAbove(codeAttrs, if (language.isEmpty()) 2f else 1f)
        StyleConstants.setSpaceBelow(codeAttrs, 4f)
        val codeText = if (code.isEmpty()) "\n" else "$code\n"
        doc.insertString(doc.length, codeText, codeAttrs)

        return i
    }

    private fun parseBlockquoteBlock(lines: List<String>, startIndex: Int): BlockquoteBlock? {
        val quoteLines = mutableListOf<String>()
        var index = startIndex
        while (index < lines.size) {
            val content = stripBlockquoteMarker(lines[index]) ?: break
            quoteLines += content
            index += 1
        }
        if (quoteLines.isEmpty()) return null
        return BlockquoteBlock(
            lines = quoteLines,
            nextIndex = index,
        )
    }

    private fun stripBlockquoteMarker(line: String): String? {
        val match = BLOCKQUOTE_LINE_REGEX.matchEntire(line) ?: return null
        return match.groupValues[1]
    }

    private fun parseTableBlock(lines: List<String>, startIndex: Int): TableBlock? {
        if (startIndex + 1 >= lines.size) return null
        val headerCells = splitTableCells(lines[startIndex])
        if (headerCells.size < MIN_TABLE_COLUMNS) return null

        val separatorCells = splitTableCells(lines[startIndex + 1])
        if (!isTableSeparatorRow(separatorCells)) {
            return parseRelaxedTableBlock(lines, startIndex, headerCells)
        }

        val rows = mutableListOf(headerCells)
        var index = startIndex + 2
        while (index < lines.size) {
            val rowCells = splitTableCells(lines[index])
            if (rowCells.size < MIN_TABLE_COLUMNS) break
            rows.add(rowCells)
            index += 1
        }

        return TableBlock(
            rows = normalizeTableRows(rows),
            nextIndex = index,
        )
    }

    private fun parseRelaxedTableBlock(
        lines: List<String>,
        startIndex: Int,
        headerCells: List<String>,
    ): TableBlock? {
        val rows = mutableListOf(headerCells)
        var index = startIndex + 1
        while (index < lines.size) {
            val rowCells = splitTableCells(lines[index])
            if (rowCells.size < MIN_TABLE_COLUMNS) break
            rows.add(rowCells)
            index += 1
        }

        if (rows.size < RELAXED_TABLE_MIN_ROWS) return null

        val expectedColumns = headerCells.size
        val columnAlignedRows = rows.count { row -> row.size == expectedColumns }
        if (columnAlignedRows < RELAXED_TABLE_MIN_ROWS) return null

        return TableBlock(
            rows = normalizeTableRows(rows),
            nextIndex = index,
        )
    }

    private fun splitTableCells(line: String): List<String> {
        val trimmed = normalizeTableDelimiterTokens(line).trim()
        if (!trimmed.contains('|')) return emptyList()

        var content = trimmed
        if (content.startsWith('|')) {
            content = content.substring(1)
        }
        if (content.endsWith('|')) {
            content = content.dropLast(1)
        }
        if (!content.contains('|')) return emptyList()

        val cells = mutableListOf<String>()
        val cellBuilder = StringBuilder()
        var escaped = false
        for (ch in content) {
            if (escaped) {
                cellBuilder.append(ch)
                escaped = false
                continue
            }

            when (ch) {
                '\\' -> escaped = true
                '|' -> {
                    cells.add(cellBuilder.toString().trim())
                    cellBuilder.setLength(0)
                }
                else -> cellBuilder.append(ch)
            }
        }
        if (escaped) {
            cellBuilder.append('\\')
        }
        cells.add(cellBuilder.toString().trim())
        return trimTrailingEmptyTableCells(cells)
    }

    private fun trimTrailingEmptyTableCells(cells: List<String>): List<String> {
        if (cells.isEmpty()) return cells
        val normalized = cells.toMutableList()
        while (normalized.size > MIN_TABLE_COLUMNS && normalized.last().isBlank()) {
            normalized.removeAt(normalized.lastIndex)
        }
        return normalized
    }

    private fun isTableSeparatorRow(cells: List<String>): Boolean {
        if (cells.size < MIN_TABLE_COLUMNS) return false
        return cells.all { cell ->
            TABLE_SEPARATOR_CELL_REGEX.matches(cell.trim())
        }
    }

    private fun normalizeTableRows(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return emptyList()
        val columnCount = rows.maxOf { it.size }.coerceAtLeast(MIN_TABLE_COLUMNS)
        return rows.map { row ->
            List(columnCount) { index ->
                row.getOrNull(index).orEmpty()
            }
        }
    }

    private fun normalizeEscapedTableMarkdown(markdown: String): String {
        if (!markdown.contains(ESCAPED_PIPE_TOKEN)) return markdown
        val lines = markdown.lines().toMutableList()
        var index = 0
        var inCodeFence = false

        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                index += 1
                continue
            }
            if (inCodeFence) {
                index += 1
                continue
            }

            val normalizedHeader = normalizeEscapedTableDataLine(lines[index])
            if (normalizedHeader == null) {
                index += 1
                continue
            }
            val separatorIndex = index + 1
            if (separatorIndex >= lines.size) {
                index += 1
                continue
            }
            val normalizedSeparator = normalizeEscapedTableSeparatorLine(lines[separatorIndex])
            if (normalizedSeparator == null) {
                index += 1
                continue
            }

            lines[index] = normalizedHeader
            lines[separatorIndex] = normalizedSeparator
            index = separatorIndex + 1
            while (index < lines.size) {
                val normalizedRow = normalizeEscapedTableDataLine(lines[index]) ?: break
                lines[index] = normalizedRow
                index += 1
            }
        }

        return lines.joinToString("\n")
    }

    private fun normalizeEscapedTableDataLine(line: String): String? {
        val normalized = normalizeTableDelimiterTokens(line)
        if (!normalized.contains('|')) return null
        val cells = splitTableCells(normalized)
        return if (cells.size >= MIN_TABLE_COLUMNS) normalized else null
    }

    private fun normalizeEscapedTableSeparatorLine(line: String): String? {
        val normalized = normalizeTableDelimiterTokens(line)
        val cells = splitTableCells(normalized)
        return if (isTableSeparatorRow(cells)) normalized else null
    }

    private fun normalizeInlineMarkdownOutsideCodeFences(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        val lines = markdown.lines().toMutableList()
        var inCodeFence = false
        var changed = false
        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (inCodeFence) continue
            val normalized = normalizeInlineMarkdownDelimiters(line)
            if (normalized != line) {
                lines[index] = normalized
                changed = true
            }
        }
        return if (changed) lines.joinToString("\n") else markdown
    }

    private fun normalizeLooseBlockMarkdownOutsideCodeFences(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        val lines = markdown.lines().toMutableList()
        var inCodeFence = false
        var changed = false
        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (inCodeFence) continue
            val normalized = normalizeLooseBlockMarkdownLine(line)
            if (normalized != line) {
                lines[index] = normalized
                changed = true
            }
        }
        return if (changed) lines.joinToString("\n") else markdown
    }

    private fun normalizeLooseBlockMarkdownLine(line: String): String {
        val headingNormalized = LOOSE_HEADING_REGEX.matchEntire(line)?.let { match ->
            "${match.groupValues[1]}${match.groupValues[2]} ${match.groupValues[3]}"
        } ?: line
        val headingOrdinalNormalized = normalizeHeadingOrdinalMarkerLine(headingNormalized)
        val orderedNormalized = normalizeLooseOrderedListLine(headingOrdinalNormalized)
        val unorderedNormalized = normalizeLooseUnorderedListLine(orderedNormalized)
        return normalizeCompactDashUnorderedListLine(unorderedNormalized)
    }

    private fun normalizeHeadingOrdinalMarkerLine(line: String): String {
        val keycapMatch = HEADING_KEYCAP_ORDINAL_REGEX.matchEntire(line)
        if (keycapMatch != null) {
            val number = keycapMatch.groupValues[2]
            val content = keycapMatch.groupValues[3]
            return "${keycapMatch.groupValues[1]}$number. $content"
        }

        val enclosedMatch = HEADING_ENCLOSED_ORDINAL_REGEX.matchEntire(line)
        if (enclosedMatch != null) {
            val enclosed = enclosedMatch.groupValues[2]
            val number = ENCLOSED_ORDINAL_MAP[enclosed] ?: return line
            val content = enclosedMatch.groupValues[3]
            return "${enclosedMatch.groupValues[1]}$number. $content"
        }

        return line
    }

    private fun normalizeLooseOrderedListLine(line: String): String {
        val match = LOOSE_ORDERED_LIST_REGEX.matchEntire(line) ?: return line
        val content = match.groupValues[4]
        if (content.firstOrNull()?.isDigit() == true) {
            // Keep versions like 1.2.3 untouched.
            return line
        }
        val normalizedDelimiter = when (match.groupValues[3]) {
            "、", "）", "．" -> "."
            else -> match.groupValues[3]
        }
        return "${match.groupValues[1]}${match.groupValues[2]}$normalizedDelimiter $content"
    }

    private fun normalizeLooseUnorderedListLine(line: String): String {
        val match = LOOSE_UNORDERED_LIST_REGEX.matchEntire(line) ?: return line
        return "${match.groupValues[1]}- ${match.groupValues[2]}"
    }

    private fun normalizeCompactDashUnorderedListLine(line: String): String {
        val match = LOOSE_DASH_UNORDERED_LIST_REGEX.matchEntire(line) ?: return line
        return "${match.groupValues[1]}- ${match.groupValues[2]}"
    }

    private fun normalizeTableDelimiterTokens(line: String): String {
        if (line.isEmpty()) return line
        var normalized = Normalizer.normalize(line, Normalizer.Form.NFKC)
            .replace(ESCAPED_PIPE_TOKEN, "|")
            .replace(FULLWIDTH_PIPE_CHAR, '|')
            .replace(INVISIBLE_TABLE_CHAR_REGEX, "")

        VERTICAL_BAR_ALIASES.forEach { alias ->
            normalized = normalized.replace(alias, '|')
        }
        HORIZONTAL_DASH_ALIASES.forEach { alias ->
            normalized = normalized.replace(alias, '-')
        }
        return normalized
    }

    private fun containsFramedPipeTableBlock(markdown: String): Boolean {
        val lines = markdown.lines()
        if (lines.size < RELAXED_TABLE_MIN_ROWS) return false
        var inCodeFence = false
        var framedCount = 0
        lines.forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                framedCount = 0
                return@forEach
            }
            if (inCodeFence) return@forEach
            if (isFramedPipeRow(rawLine)) {
                framedCount += 1
                if (framedCount >= RELAXED_TABLE_MIN_ROWS) return true
            } else {
                framedCount = 0
            }
        }
        return false
    }

    private fun convertLooseFramedPipeTablesToHtmlBlocks(markdown: String): String {
        val lines = markdown.lines()
        if (lines.isEmpty()) return markdown
        val output = mutableListOf<String>()
        var index = 0
        var inCodeFence = false
        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                output += lines[index]
                index += 1
                continue
            }
            if (!inCodeFence) {
                val tableBlock = parseLooseFramedPipeTableBlock(lines, index)
                if (tableBlock != null) {
                    output += renderTableHtmlBlock(tableBlock)
                    index = tableBlock.nextIndex
                    continue
                }
            }
            output += lines[index]
            index += 1
        }
        return output.joinToString("\n")
    }

    private fun parseLooseFramedPipeTableBlock(lines: List<String>, startIndex: Int): TableBlock? {
        if (!isFramedPipeRow(lines[startIndex])) return null
        val rawRows = mutableListOf<List<String>>()
        var index = startIndex
        while (index < lines.size && isFramedPipeRow(lines[index])) {
            val cells = splitTableCells(lines[index])
            if (cells.size < MIN_TABLE_COLUMNS) break
            rawRows += cells
            index += 1
        }
        if (rawRows.size < RELAXED_TABLE_MIN_ROWS) return null

        val targetColumns = rawRows
            .groupingBy { it.size.coerceAtLeast(MIN_TABLE_COLUMNS) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.coerceAtLeast(MIN_TABLE_COLUMNS)
            ?: MIN_TABLE_COLUMNS

        val normalizedRows = rawRows.map { row ->
            normalizeLooseRow(row, targetColumns)
        }
        return TableBlock(
            rows = normalizedRows,
            nextIndex = index,
        )
    }

    private fun normalizeLooseRow(row: List<String>, targetColumns: Int): List<String> {
        if (row.size == targetColumns) return row
        if (row.size > targetColumns) {
            val head = row.take(targetColumns - 1)
            val tail = row.drop(targetColumns - 1).joinToString(" | ").trim()
            return head + tail
        }
        return row + List(targetColumns - row.size) { "" }
    }

    private fun isFramedPipeRow(line: String): Boolean {
        val normalized = normalizeTableDelimiterTokens(line).trim()
        if (normalized.length < 3) return false
        if (!normalized.startsWith('|') || !normalized.endsWith('|')) return false
        return normalized.count { it == '|' } >= MIN_TABLE_COLUMNS + 1
    }

    private fun containsAnyLoosePipeTableBlock(markdown: String): Boolean {
        val lines = markdown.lines()
        if (lines.size < RELAXED_TABLE_MIN_ROWS) return false
        var inCodeFence = false
        var looseCount = 0
        lines.forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                looseCount = 0
                return@forEach
            }
            if (inCodeFence) return@forEach
            if (isLoosePipeRow(rawLine)) {
                looseCount += 1
                if (looseCount >= RELAXED_TABLE_MIN_ROWS) return true
            } else {
                looseCount = 0
            }
        }
        return false
    }

    private fun convertAnyLoosePipeTablesToHtmlBlocks(markdown: String): String {
        val lines = markdown.lines()
        if (lines.isEmpty()) return markdown
        val output = mutableListOf<String>()
        var index = 0
        var inCodeFence = false
        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                output += lines[index]
                index += 1
                continue
            }
            if (!inCodeFence) {
                val tableBlock = parseAnyLoosePipeTableBlock(lines, index)
                if (tableBlock != null) {
                    output += renderTableHtmlBlock(tableBlock)
                    index = tableBlock.nextIndex
                    continue
                }
            }
            output += lines[index]
            index += 1
        }
        return output.joinToString("\n")
    }

    private fun parseAnyLoosePipeTableBlock(lines: List<String>, startIndex: Int): TableBlock? {
        if (!isLoosePipeRow(lines[startIndex])) return null
        val rawRows = mutableListOf<List<String>>()
        var index = startIndex
        while (index < lines.size && isLoosePipeRow(lines[index])) {
            val cells = splitTableCells(lines[index])
            if (cells.size < MIN_TABLE_COLUMNS) break
            rawRows += cells
            index += 1
        }
        if (rawRows.size < RELAXED_TABLE_MIN_ROWS) return null

        val targetColumns = rawRows
            .groupingBy { it.size.coerceAtLeast(MIN_TABLE_COLUMNS) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.coerceAtLeast(MIN_TABLE_COLUMNS)
            ?: MIN_TABLE_COLUMNS
        if (targetColumns < MIN_TABLE_COLUMNS) return null

        val normalizedRows = rawRows.map { row -> normalizeLooseRow(row, targetColumns) }
        return TableBlock(
            rows = normalizedRows,
            nextIndex = index,
        )
    }

    private fun isLoosePipeRow(line: String): Boolean {
        val normalized = normalizeTableDelimiterTokens(line).trim()
        if (normalized.length < 3) return false
        if (normalized.count { it == '|' } < MIN_TABLE_COLUMNS) return false
        val cells = splitTableCells(normalized)
        return cells.size >= MIN_TABLE_COLUMNS
    }

    private fun convertMarkdownTablesToHtmlBlocks(markdown: String): String {
        val lines = markdown.lines()
        if (lines.isEmpty()) return markdown

        val output = mutableListOf<String>()
        var index = 0
        var inCodeFence = false
        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                output += lines[index]
                index += 1
                continue
            }
            if (!inCodeFence) {
                val tableBlock = parseTableBlock(lines, index)
                if (tableBlock != null) {
                    output += renderTableHtmlBlock(tableBlock)
                    index = tableBlock.nextIndex
                    continue
                }
            }
            output += lines[index]
            index += 1
        }
        return output.joinToString("\n")
    }

    private fun renderTableHtmlBlock(tableBlock: TableBlock): String {
        val rows = tableBlock.rows
        if (rows.isEmpty()) return ""
        val header = rows.first()
        val body = rows.drop(1)
        return buildString {
            append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\" style=\"border-collapse:collapse;width:100%;\">")
            append("<thead><tr>")
            header.forEach { cell ->
                append("<th align=\"left\" valign=\"top\">")
                append(escapeHtml(cell))
                append("</th>")
            }
            append("</tr></thead>")
            if (body.isNotEmpty()) {
                append("<tbody>")
                body.forEach { row ->
                    append("<tr>")
                    row.forEach { cell ->
                        append("<td align=\"left\" valign=\"top\">")
                        append(escapeHtml(cell))
                        append("</td>")
                    }
                    append("</tr>")
                }
                append("</tbody>")
            }
            append("</table>")
        }
    }

    private fun escapeHtml(text: String): String {
        return buildString(text.length) {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun containsMarkdownTable(markdown: String): Boolean {
        val lines = markdown.lines()
        if (lines.size < 2) return false
        for (index in 0 until lines.lastIndex) {
            if (parseTableBlock(lines, index) != null) {
                return true
            }
        }
        return false
    }

    private fun containsRawHtmlTableBlock(markdown: String): Boolean {
        val lines = normalizedLines(markdown)
        var inCodeFence = false
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                index += 1
                continue
            }
            if (!inCodeFence && collectRawHtmlTableBlock(lines, index) != null) {
                return true
            }
            index += 1
        }
        return false
    }

    internal fun shouldUseHtmlTableRendering(markdown: String): Boolean {
        val normalizedMarkdown = normalizeLooseBlockMarkdownOutsideCodeFences(
            normalizeInlineMarkdownOutsideCodeFences(
                normalizeEscapedTableMarkdown(markdown),
            ),
        )
        return shouldUseHtmlTableRendering(markdown, normalizedMarkdown)
    }

    // Swing HTML layout can freeze the EDT on large tables, so degrade to the plain
    // StyledDocument renderer once table content exceeds a conservative complexity budget.
    private fun shouldUseHtmlTableRendering(
        markdown: String,
        normalizedMarkdown: String,
    ): Boolean {
        if (normalizedMarkdown.length > MAX_HTML_TABLE_SOURCE_CHARS) {
            return false
        }

        val pipeMetrics = collectPipeTableMetrics(normalizedMarkdown)
        if (pipeMetrics.likelyTableLineCount > MAX_HTML_TABLE_LINES) {
            return false
        }
        if (pipeMetrics.maxLineLength > MAX_HTML_TABLE_LINE_CHARS) {
            return false
        }
        if (pipeMetrics.maxRows > MAX_HTML_TABLE_ROWS) {
            return false
        }
        if (pipeMetrics.maxColumns > MAX_HTML_TABLE_COLUMNS) {
            return false
        }
        if (pipeMetrics.maxCellChars > MAX_HTML_TABLE_CELL_CHARS) {
            return false
        }
        if (pipeMetrics.totalCellChars > MAX_HTML_TABLE_TOTAL_CELL_CHARS) {
            return false
        }

        val rawHtmlMetrics = collectRawHtmlTableMetrics(markdown)
        if (rawHtmlMetrics.maxBlockChars > MAX_HTML_TABLE_SOURCE_CHARS) {
            return false
        }
        if (rawHtmlMetrics.maxRows > MAX_HTML_TABLE_ROWS) {
            return false
        }

        return true
    }

    private fun collectPipeTableMetrics(markdown: String): PipeTableMetrics {
        val lines = markdown.lines()
        var index = 0
        var inCodeFence = false
        var likelyTableLineCount = 0
        var maxLineLength = 0
        var maxRows = 0
        var maxColumns = 0
        var totalCellChars = 0
        var maxCellChars = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                index += 1
                continue
            }
            if (inCodeFence) {
                index += 1
                continue
            }

            if (splitTableCells(line).size >= MIN_TABLE_COLUMNS) {
                likelyTableLineCount += 1
                maxLineLength = maxOf(maxLineLength, line.length)
            }

            val tableBlock = parseTableBlock(lines, index)
            if (tableBlock != null) {
                maxRows = maxOf(maxRows, tableBlock.rows.size)
                val columnCount = tableBlock.rows.maxOfOrNull { row -> row.size } ?: 0
                maxColumns = maxOf(maxColumns, columnCount)
                tableBlock.rows.forEach { row ->
                    row.forEach { cell ->
                        val length = cell.length
                        totalCellChars += length
                        maxCellChars = maxOf(maxCellChars, length)
                    }
                }
                index = tableBlock.nextIndex
                continue
            }

            index += 1
        }

        return PipeTableMetrics(
            likelyTableLineCount = likelyTableLineCount,
            maxLineLength = maxLineLength,
            maxRows = maxRows,
            maxColumns = maxColumns,
            totalCellChars = totalCellChars,
            maxCellChars = maxCellChars,
        )
    }

    private fun collectRawHtmlTableMetrics(markdown: String): RawHtmlTableMetrics {
        val lines = normalizedLines(markdown)
        var index = 0
        var inCodeFence = false
        var maxBlockChars = 0
        var maxRows = 0

        while (index < lines.size) {
            val trimmed = lines[index].trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                index += 1
                continue
            }
            if (inCodeFence) {
                index += 1
                continue
            }

            val tableBlock = collectRawHtmlTableBlock(lines, index)
            if (tableBlock != null) {
                maxBlockChars = maxOf(maxBlockChars, tableBlock.html.length)
                val rowCount = TABLE_ROW_OPEN_TAG_REGEX.findAll(tableBlock.html).count()
                maxRows = maxOf(maxRows, rowCount)
                index = tableBlock.nextIndex
                continue
            }

            index += 1
        }

        return RawHtmlTableMetrics(
            maxBlockChars = maxBlockChars,
            maxRows = maxRows,
        )
    }

    private fun renderWithHtmlFallback(
        textPane: JTextPane,
        markdown: String,
        proseFontFamily: String,
        baseFontSize: Int,
    ): Boolean {
        val bodyHtml = runCatching { convertBasicMarkdownToHtml(markdown) }
            .getOrNull()
            ?.let(::ensureLegacyTableMarkup)
            ?.trim()
            .orEmpty()
        if (bodyHtml.isBlank()) return false
        return renderHtmlBody(
            textPane = textPane,
            bodyHtml = bodyHtml,
            proseFontFamily = proseFontFamily,
            baseFontSize = baseFontSize,
        )
    }

    private fun renderWithMarkdownEngine(
        textPane: JTextPane,
        markdown: String,
        proseFontFamily: String,
        baseFontSize: Int,
    ): Boolean {
        val bodyHtml = runCatching {
            val flavour = GFMFlavourDescriptor()
            val markdownTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, markdownTree, flavour).generateHtml()
        }.getOrNull()
            ?.let(::ensureLegacyTableMarkup)
            ?.trim()
            .orEmpty()
        if (bodyHtml.isBlank()) return false
        if (!bodyHtml.contains("<table", ignoreCase = true)) return false

        return renderHtmlBody(
            textPane = textPane,
            bodyHtml = bodyHtml,
            proseFontFamily = proseFontFamily,
            baseFontSize = baseFontSize,
        )
    }

    private fun renderHtmlBody(
        textPane: JTextPane,
        bodyHtml: String,
        proseFontFamily: String,
        baseFontSize: Int,
    ): Boolean {
        val htmlCandidates = listOf(
            wrapMarkdownHtml(bodyHtml, proseFontFamily, baseFontSize),
            wrapBasicHtml(bodyHtml, proseFontFamily, baseFontSize),
            "<html><body>$bodyHtml</body></html>",
        )
        htmlCandidates.forEach { html ->
            val success = runCatching {
                textPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                if (!textPane.contentType.equals(HTML_CONTENT_TYPE, ignoreCase = true)) {
                    textPane.contentType = HTML_CONTENT_TYPE
                }
                textPane.text = html
                textPane.caretPosition = 0
            }.isSuccess
            if (success) return true
        }
        return false
    }

    private fun convertBasicMarkdownToHtml(markdown: String): String {
        val lines = normalizedLines(markdown)
        val html = StringBuilder()
        val codeBuffer = StringBuilder()
        var inCodeFence = false
        var inUnorderedList = false
        var inOrderedList = false
        var inParagraph = false

        fun closeParagraph() {
            if (inParagraph) {
                html.append("</p>")
                inParagraph = false
            }
        }

        fun closeLists() {
            if (inUnorderedList) {
                html.append("</ul>")
                inUnorderedList = false
            }
            if (inOrderedList) {
                html.append("</ol>")
                inOrderedList = false
            }
        }

        fun closeTextScopes() {
            closeParagraph()
            closeLists()
        }

        var lineIndex = 0
        while (lineIndex < lines.size) {
            val rawLine = lines[lineIndex]
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                closeTextScopes()
                if (inCodeFence) {
                    html.append("<pre><code>")
                    html.append(escapeHtml(codeBuffer.toString()).trimEnd('\n'))
                    html.append("</code></pre>")
                    codeBuffer.setLength(0)
                    inCodeFence = false
                } else {
                    inCodeFence = true
                }
                lineIndex += 1
                continue
            }

            if (inCodeFence) {
                codeBuffer.append(line).append('\n')
                lineIndex += 1
                continue
            }

            val rawHtmlTableBlock = collectRawHtmlTableBlock(lines, lineIndex)
            if (rawHtmlTableBlock != null) {
                closeTextScopes()
                html.append(rawHtmlTableBlock.html)
                lineIndex = rawHtmlTableBlock.nextIndex
                continue
            }

            val blockquoteBlock = parseBlockquoteBlock(lines, lineIndex)
            if (blockquoteBlock != null) {
                closeTextScopes()
                val innerMarkdown = blockquoteBlock.lines.joinToString("\n")
                html.append("<blockquote>")
                html.append(convertBasicMarkdownToHtml(innerMarkdown))
                html.append("</blockquote>")
                lineIndex = blockquoteBlock.nextIndex
                continue
            }

            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                closeTextScopes()
                html.append("<hr/>")
                lineIndex += 1
                continue
            }

            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (headingMatch != null) {
                closeTextScopes()
                val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                html.append("<h").append(level).append(">")
                html.append(renderInlineHtml(headingMatch.groupValues[2]))
                html.append("</h").append(level).append(">")
                lineIndex += 1
                continue
            }

            val unorderedMatch = UNORDERED_LIST_HTML_REGEX.matchEntire(trimmed)
            if (unorderedMatch != null) {
                closeParagraph()
                if (inOrderedList) {
                    html.append("</ol>")
                    inOrderedList = false
                }
                if (!inUnorderedList) {
                    html.append("<ul>")
                    inUnorderedList = true
                }
                html.append("<li>")
                html.append(renderInlineHtml(unorderedMatch.groupValues[1]))
                html.append("</li>")
                lineIndex += 1
                continue
            }

            val orderedMatch = ORDERED_LIST_HTML_REGEX.matchEntire(trimmed)
            if (orderedMatch != null) {
                closeParagraph()
                if (inUnorderedList) {
                    html.append("</ul>")
                    inUnorderedList = false
                }
                if (!inOrderedList) {
                    html.append("<ol>")
                    inOrderedList = true
                }
                html.append("<li>")
                html.append(renderInlineHtml(orderedMatch.groupValues[1]))
                html.append("</li>")
                lineIndex += 1
                continue
            }

            if (trimmed.isBlank()) {
                closeTextScopes()
                lineIndex += 1
                continue
            }

            closeLists()
            if (!inParagraph) {
                html.append("<p>")
                inParagraph = true
            } else {
                html.append("<br/>")
            }
            html.append(renderInlineHtml(trimmed))
            lineIndex += 1
        }

        if (inCodeFence) {
            html.append("<pre><code>")
            html.append(escapeHtml(codeBuffer.toString()).trimEnd('\n'))
            html.append("</code></pre>")
        }
        if (inParagraph) html.append("</p>")
        if (inUnorderedList) html.append("</ul>")
        if (inOrderedList) html.append("</ol>")
        return html.toString()
    }

    private fun collectRawHtmlTableBlock(lines: List<String>, startIndex: Int): HtmlTableBlock? {
        val trimmedStartLine = lines[startIndex].trim()
        if (!trimmedStartLine.startsWith("<table", ignoreCase = true)) return null

        val blockLines = mutableListOf<String>()
        var openTableCount = 0
        var closeTableCount = 0
        for (index in startIndex until lines.size) {
            val line = lines[index].trimEnd()
            blockLines += line
            openTableCount += TABLE_OPEN_TAG_REGEX.findAll(line).count()
            closeTableCount += TABLE_CLOSE_TAG_REGEX.findAll(line).count()
            if (openTableCount > 0 && closeTableCount >= openTableCount) {
                return HtmlTableBlock(
                    html = blockLines.joinToString("\n"),
                    nextIndex = index + 1,
                )
            }
        }
        return null
    }

    private fun renderInlineHtml(text: String): String {
        val normalized = normalizeInlineMarkdownDelimiters(text)
        return buildString {
            tokenizeInline(normalized).forEach { token ->
                when (token) {
                    is InlineToken.Bold -> {
                        append("<strong>")
                        append(escapeHtml(token.text))
                        append("</strong>")
                    }
                    is InlineToken.Italic -> {
                        append("<em>")
                        append(escapeHtml(token.text))
                        append("</em>")
                    }
                    is InlineToken.InlineCode -> {
                        append("<code>")
                        append(escapeHtml(token.text))
                        append("</code>")
                    }
                    is InlineToken.Link -> {
                        append("<a href=\"")
                        append(escapeHtmlAttribute(token.target))
                        append("\">")
                        append(escapeHtml(token.text))
                        append("</a>")
                    }
                    is InlineToken.Plain -> append(escapeHtml(token.text))
                }
            }
        }
    }

    private fun wrapMarkdownHtml(bodyHtml: String, proseFontFamily: String, baseFontSize: Int): String {
        val fontFamily = escapeCssFontFamily(proseFontFamily)
        val codeFontFamily = escapeCssFontFamily(CODE_FONT_FAMILY)
        val bodyFg = toCssColor(JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
        val tableBorder = toCssColor(JBColor(Color(183, 196, 214), Color(96, 108, 122)))
        val tableHeaderBg = toCssColor(JBColor(Color(242, 246, 252), Color(53, 60, 70)))
        val inlineCodeBg = toCssColor(JBColor(CODE_BG_LIGHT, CODE_BG_DARK))
        val inlineCodeFg = toCssColor(JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
        val blockCodeBg = toCssColor(JBColor(BLOCK_CODE_BG_LIGHT, BLOCK_CODE_BG_DARK))
        val blockCodeFg = toCssColor(JBColor(BLOCK_CODE_FG_LIGHT, BLOCK_CODE_FG_DARK))
        val blockquoteBorder = toCssColor(JBColor(BLOCKQUOTE_BORDER_LIGHT, BLOCKQUOTE_BORDER_DARK))
        val blockquoteBg = toCssColor(JBColor(BLOCKQUOTE_BG_LIGHT, BLOCKQUOTE_BG_DARK))
        val linkFg = toCssColor(JBColor(LINK_FG_LIGHT, LINK_FG_DARK))
        val css = """
            html, body { margin: 0; padding: 0; background: transparent; }
            body {
                font-family: '$fontFamily';
                line-height: 1.72;
                color: $bodyFg;
            }
            p, ul, ol, table, pre, blockquote { margin: 0 0 10px 0; }
            ul, ol { padding-left: 20px; }
            li { margin: 0 0 5px 0; }
            li:last-child { margin-bottom: 0; }
            blockquote {
                margin: 0 0 10px 0;
                padding: 6px 10px;
                border-left: 3px solid $blockquoteBorder;
                background: $blockquoteBg;
            }
            blockquote > :last-child { margin-bottom: 0; }
            a {
                color: $linkFg;
                text-decoration: underline;
            }
            table {
                border-collapse: collapse;
                border-spacing: 0;
                width: 100%;
                table-layout: fixed;
                border: 1px solid $tableBorder;
            }
            th, td {
                border: 1px solid $tableBorder;
                padding: 4px 7px;
                text-align: left;
                vertical-align: top;
                line-height: 1.58;
                word-break: break-word;
                overflow-wrap: anywhere;
            }
            th { background: $tableHeaderBg; font-weight: 700; }
            code {
                font-family: '$codeFontFamily';
                background: $inlineCodeBg;
                color: $inlineCodeFg;
                border-radius: 4px;
                padding: 1px 4px;
            }
            pre {
                font-family: '$codeFontFamily';
                background: $blockCodeBg;
                color: $blockCodeFg;
                border-radius: 8px;
                padding: 8px 10px 12px 10px;
                overflow-x: auto;
            }
            pre code {
                background: transparent;
                color: inherit;
                padding: 0;
            }
        """.trimIndent()
        return "<html><head><style>$css</style></head><body>$bodyHtml</body></html>"
    }

    private fun wrapBasicHtml(bodyHtml: String, proseFontFamily: String, baseFontSize: Int): String {
        val fontFamily = escapeCssFontFamily(proseFontFamily)
        val bodyFg = toCssColor(JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
        return "<html><body style=\"font-family:'$fontFamily';line-height:1.72;color:$bodyFg;\">$bodyHtml</body></html>"
    }

    private fun ensureLegacyTableMarkup(bodyHtml: String): String {
        if (!bodyHtml.contains("<table", ignoreCase = true)) return bodyHtml
        return TABLE_OPEN_TAG_REGEX.replace(bodyHtml) { match ->
            val rawAttrs = match.groupValues.getOrNull(1).orEmpty()
            val attrs = rawAttrs.trim()
            val attrPrefix = if (attrs.isBlank()) "" else " $attrs"
            val hasBorder = TABLE_BORDER_ATTR_REGEX.containsMatchIn(rawAttrs)
            val hasCellSpacing = TABLE_CELL_SPACING_ATTR_REGEX.containsMatchIn(rawAttrs)
            val hasCellPadding = TABLE_CELL_PADDING_ATTR_REGEX.containsMatchIn(rawAttrs)
            val hasStyle = TABLE_STYLE_ATTR_REGEX.containsMatchIn(rawAttrs)
            buildString {
                append("<table")
                append(attrPrefix)
                if (!hasBorder) append(" border=\"1\"")
                if (!hasCellSpacing) append(" cellspacing=\"0\"")
                if (!hasCellPadding) append(" cellpadding=\"4\"")
                if (!hasStyle) append(" style=\"border-collapse:collapse;width:100%;\"")
                append(">")
            }
        }
    }

    private fun escapeCssFontFamily(fontFamily: String): String {
        return fontFamily.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun toCssColor(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private fun escapeHtmlAttribute(value: String): String {
        return escapeHtml(value)
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun normalizedLines(text: String): List<String> {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
    }

    private fun ensurePlainTextMode(textPane: JTextPane) {
        if (!textPane.contentType.equals(PLAIN_TEXT_CONTENT_TYPE, ignoreCase = true)) {
            textPane.contentType = PLAIN_TEXT_CONTENT_TYPE
        }
    }

    private fun renderBlockquote(
        doc: StyledDocument,
        blockquoteBlock: BlockquoteBlock,
        proseFontFamily: String,
        baseFontSize: Int,
    ) {
        val paragraphStart = doc.length
        blockquoteBlock.lines.forEachIndexed { index, line ->
            val markerAttrs = SimpleAttributeSet().apply {
                applyProseTextAttrs(this, proseFontFamily, baseFontSize)
                StyleConstants.setBold(this, true)
                StyleConstants.setForeground(this, JBColor(BLOCKQUOTE_MARKER_FG_LIGHT, BLOCKQUOTE_MARKER_FG_DARK))
            }
            doc.insertString(doc.length, "$BLOCKQUOTE_MARKER ", markerAttrs)
            if (line.isNotEmpty()) {
                renderInlineMarkdown(doc, line, proseFontFamily, baseFontSize)
            }
            if (index < blockquoteBlock.lines.lastIndex) {
                insertNewline(doc)
            }
        }

        val length = (doc.length - paragraphStart).coerceAtLeast(1)
        val paragraphAttrs = SimpleAttributeSet().apply {
            StyleConstants.setLeftIndent(this, BLOCKQUOTE_LEFT_INDENT)
            StyleConstants.setSpaceAbove(this, BLOCKQUOTE_SPACE_ABOVE)
            StyleConstants.setSpaceBelow(this, BLOCKQUOTE_SPACE_BELOW)
        }
        doc.setParagraphAttributes(paragraphStart, length, paragraphAttrs, false)
    }

    private fun renderTable(
        doc: StyledDocument,
        tableBlock: TableBlock,
        proseFontFamily: String,
        baseFontSize: Int,
    ) {
        val rows = tableBlock.rows
        if (rows.isEmpty()) return
        val header = rows.first().map(::normalizeTableCellForPipe)
        val body = rows.drop(1).map { row -> row.map(::normalizeTableCellForPipe) }

        val baseAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, proseFontFamily)
            StyleConstants.setFontSize(this, baseFontSize)
            StyleConstants.setLineSpacing(this, PROSE_LINE_SPACING)
            StyleConstants.setForeground(this, JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
            StyleConstants.setLeftIndent(this, 4f)
            StyleConstants.setSpaceAbove(this, 1f)
            StyleConstants.setSpaceBelow(this, 1f)
        }
        val headerAttrs = SimpleAttributeSet(baseAttrs).apply {
            StyleConstants.setBold(this, true)
        }
        val separatorAttrs = SimpleAttributeSet(baseAttrs).apply {
            StyleConstants.setForeground(this, JBColor(CODE_LANG_FG_LIGHT, CODE_LANG_FG_DARK))
        }

        doc.insertString(doc.length, buildPipeRow(header), headerAttrs)
        insertNewline(doc)
        doc.insertString(
            doc.length,
            buildPipeRow(List(header.size) { "---" }),
            separatorAttrs,
        )
        body.forEach { row ->
            insertNewline(doc)
            doc.insertString(doc.length, buildPipeRow(row), baseAttrs)
        }
    }

    private fun buildPipeRow(cells: List<String>): String {
        return cells.joinToString(
            separator = " | ",
            prefix = "| ",
            postfix = " |",
        )
    }

    private fun normalizeTableCellForPipe(cell: String): String {
        return cell
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("|", "\\|")
            .trim()
    }

    private fun normalizeCodeBlockContent(raw: String): String {
        return raw
            .replace("\t", "    ")
            .trimEnd('\n', '\r')
    }

    /**
     * 渲染标题
     */
    private fun renderHeading(
        doc: StyledDocument,
        text: String,
        level: Int,
        proseFontFamily: String,
        baseFontSize: Int,
    ) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setBold(attrs, true)
        val fontSize = when (level) {
            1 -> baseFontSize + 5
            2 -> baseFontSize + 3
            3 -> baseFontSize + 2
            4 -> baseFontSize + 1
            else -> baseFontSize
        }
        applyProseTextAttrs(attrs, proseFontFamily, fontSize)
        doc.insertString(doc.length, text, attrs)
    }

    /**
     * 渲染列表项
     */
    private fun renderListItem(
        doc: StyledDocument,
        line: String,
        ordered: Boolean,
        proseFontFamily: String,
        baseFontSize: Int,
        sourceLineIndex: Int,
    ) {
        val listParagraphStart = doc.length
        val indent = line.length - line.trimStart().length
        val prefix = "  ".repeat(indent / 2)

        val bulletAttrs = SimpleAttributeSet()
        applyProseTextAttrs(bulletAttrs, proseFontFamily, baseFontSize)

        val trimmed = line.trimStart()
        val orderedMatch = if (ordered) ORDERED_LIST_REGEX.matchEntire(trimmed) else null
        val bullet = if (ordered) {
            val num = orderedMatch?.groupValues?.get(1)?.ifBlank { "1" } ?: trimmed.substringBefore(".")
            "$prefix$num. "
        } else {
            "$prefix  \u2022 "
        }

        doc.insertString(doc.length, bullet, bulletAttrs)

        // 列表项内容（支持行内 Markdown）
        val content = if (ordered) {
            orderedMatch?.groupValues?.get(2) ?: trimmed.substringAfter(". ", "")
        } else {
            trimmed.removePrefix("- ").removePrefix("* ")
        }
        val checklistItem = parseChecklistItem(content)
        if (checklistItem != null) {
            doc.remove(listParagraphStart, doc.length - listParagraphStart)
            doc.insertString(doc.length, if (ordered) bullet else "$prefix  ", bulletAttrs)

            val checkboxAttrs = SimpleAttributeSet()
            applyProseTextAttrs(checkboxAttrs, proseFontFamily, baseFontSize)
            StyleConstants.setBold(checkboxAttrs, true)
            doc.insertString(doc.length, if (checklistItem.checked) "\u2611 " else "\u2610 ", checkboxAttrs)
            renderInlineMarkdown(doc, checklistItem.content, proseFontFamily, baseFontSize)
            applyChecklistParagraphMetadata(
                doc = doc,
                paragraphStart = listParagraphStart,
                lineIndex = sourceLineIndex,
                checked = checklistItem.checked,
            )
        } else {
            renderInlineMarkdown(doc, content, proseFontFamily, baseFontSize)
        }
        applyListParagraphSpacing(doc, listParagraphStart)
    }

    private fun applyListParagraphSpacing(doc: StyledDocument, paragraphStart: Int) {
        val length = (doc.length - paragraphStart).coerceAtLeast(1)
        val paragraphAttrs = SimpleAttributeSet()
        StyleConstants.setSpaceAbove(paragraphAttrs, LIST_ITEM_SPACE_ABOVE)
        StyleConstants.setSpaceBelow(paragraphAttrs, LIST_ITEM_SPACE_BELOW)
        doc.setParagraphAttributes(paragraphStart, length, paragraphAttrs, false)
    }

    private fun applyChecklistParagraphMetadata(
        doc: StyledDocument,
        paragraphStart: Int,
        lineIndex: Int,
        checked: Boolean,
    ) {
        val length = (doc.length - paragraphStart).coerceAtLeast(1)
        val paragraphAttrs = SimpleAttributeSet()
        paragraphAttrs.addAttribute(CHECKLIST_LINE_INDEX_ATTRIBUTE, lineIndex)
        paragraphAttrs.addAttribute(CHECKLIST_CHECKED_ATTRIBUTE, checked)
        doc.setParagraphAttributes(paragraphStart, length, paragraphAttrs, false)
    }

    private fun parseChecklistItem(content: String): ChecklistItem? {
        val normalized = content.trimStart()
        val match = CHECKLIST_ITEM_REGEX.matchEntire(normalized) ?: return null
        return ChecklistItem(
            checked = match.groupValues[1].equals("x", ignoreCase = true),
            content = match.groupValues[2],
        )
    }

    /**
     * 渲染分隔线
     */
    private fun renderHorizontalRule(doc: StyledDocument, baseFontSize: Int) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, JBColor.GRAY)
        StyleConstants.setFontSize(attrs, (baseFontSize - 1).coerceAtLeast(MIN_INLINE_FONT_SIZE))
        doc.insertString(doc.length, "\u2500".repeat(40), attrs)
    }

    /**
     * 渲染行内 Markdown（粗体、斜体、行内代码）
     */
    private fun renderInlineMarkdown(doc: StyledDocument, text: String, proseFontFamily: String, baseFontSize: Int) {
        val tokens = tokenizeInline(normalizeInlineMarkdownDelimiters(text))
        for (token in tokens) {
            when (token) {
                is InlineToken.Bold -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setBold(attrs, true)
                    applyProseTextAttrs(attrs, proseFontFamily, baseFontSize)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Italic -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setItalic(attrs, true)
                    applyProseTextAttrs(attrs, proseFontFamily, baseFontSize)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.InlineCode -> {
                    val attrs = SimpleAttributeSet()
                    StyleConstants.setFontFamily(attrs, CODE_FONT_FAMILY)
                    StyleConstants.setFontSize(attrs, (baseFontSize - 1).coerceAtLeast(MIN_INLINE_FONT_SIZE))
                    StyleConstants.setLineSpacing(attrs, PROSE_LINE_SPACING)
                    StyleConstants.setBackground(attrs, JBColor(CODE_BG_LIGHT, CODE_BG_DARK))
                    StyleConstants.setForeground(attrs, JBColor(CODE_FG_LIGHT, CODE_FG_DARK))
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Link -> {
                    val attrs = SimpleAttributeSet()
                    applyProseTextAttrs(attrs, proseFontFamily, baseFontSize)
                    StyleConstants.setForeground(attrs, JBColor(LINK_FG_LIGHT, LINK_FG_DARK))
                    StyleConstants.setUnderline(attrs, true)
                    attrs.addAttribute(LINK_TARGET_ATTRIBUTE, token.target)
                    doc.insertString(doc.length, token.text, attrs)
                }
                is InlineToken.Plain -> {
                    val attrs = SimpleAttributeSet()
                    applyProseTextAttrs(attrs, proseFontFamily, baseFontSize)
                    doc.insertString(doc.length, token.text, attrs)
                }
            }
        }
    }

    private fun applyProseTextAttrs(attrs: SimpleAttributeSet, proseFontFamily: String, fontSize: Int) {
        StyleConstants.setFontFamily(attrs, proseFontFamily)
        StyleConstants.setFontSize(attrs, fontSize)
        StyleConstants.setLineSpacing(attrs, PROSE_LINE_SPACING)
    }

    private fun resolveBaseFontSize(textPane: JTextPane): Int {
        val raw = textPane.font?.size ?: DEFAULT_BASE_FONT_SIZE
        return raw.coerceIn(MIN_BASE_FONT_SIZE, MAX_BASE_FONT_SIZE)
    }

    private fun insertNewline(doc: StyledDocument) {
        doc.insertString(doc.length, "\n", SimpleAttributeSet())
    }

    /**
     * 规范化常见的行内强调分隔符变体，避免全角星号或零宽字符导致 `**...**` 无法解析。
     */
    private fun normalizeInlineMarkdownDelimiters(text: String): String {
        if (text.isEmpty()) return text
        var changed = false
        val normalized = buildString(text.length) {
            text.forEach { ch ->
                when {
                    INLINE_MARKDOWN_IGNORED_CHARS.contains(ch) -> {
                        changed = true
                    }
                    INLINE_MARKDOWN_ASTERISK_ALIASES.contains(ch) -> {
                        if (ch != '*') changed = true
                        append('*')
                    }
                    ch == INLINE_MARKDOWN_NBSP -> {
                        changed = true
                        append(' ')
                    }
                    else -> append(ch)
                }
            }
        }
        var compacted = normalized
        if (compacted.contains('*')) {
            val strippedVariationSelectors = ASTERISK_ADJACENT_VARIATION_SELECTOR_REGEX.replace(compacted, "")
            if (strippedVariationSelectors != compacted) {
                compacted = strippedVariationSelectors
                changed = true
            }
            val collapsed = STAR_PAIR_WITH_SPACES_REGEX.replace(compacted, "**")
            if (collapsed != compacted) {
                compacted = collapsed
                changed = true
            }
        }
        return if (changed) compacted else text
    }

    /**
     * 将行内文本分词为 Token 列表
     */
    private fun tokenizeInline(text: String): List<InlineToken> {
        val tokens = mutableListOf<InlineToken>()
        var pos = 0

        while (pos < text.length) {
            // Markdown 链接 [label](target)
            if (text[pos] == '[' && (pos == 0 || text[pos - 1] != '!')) {
                val link = parseInlineLink(text, pos)
                if (link != null) {
                    tokens.add(link.first)
                    pos = link.second
                    continue
                }
            }

            // 行内代码 `...`
            if (text[pos] == '`') {
                val end = text.indexOf('`', pos + 1)
                if (end > pos) {
                    tokens.add(InlineToken.InlineCode(text.substring(pos + 1, end)))
                    pos = end + 1
                    continue
                }
            }

            // 粗体 **...**
            if (pos + 1 < text.length && text[pos] == '*' && text[pos + 1] == '*') {
                val end = text.indexOf("**", pos + 2)
                if (end > pos) {
                    tokens.add(InlineToken.Bold(text.substring(pos + 2, end)))
                    pos = end + 2
                    continue
                }
            }

            // 斜体 *...*
            if (text[pos] == '*') {
                val end = text.indexOf('*', pos + 1)
                if (end > pos && !(pos + 1 < text.length && text[pos + 1] == '*')) {
                    tokens.add(InlineToken.Italic(text.substring(pos + 1, end)))
                    pos = end + 1
                    continue
                }
            }

            // 普通文本：收集到下一个特殊字符
            val nextSpecial = findNextSpecial(text, pos + 1)
            tokens.add(InlineToken.Plain(text.substring(pos, nextSpecial)))
            pos = nextSpecial
        }

        return tokens
    }

    private fun findNextSpecial(text: String, from: Int): Int {
        for (i in from until text.length) {
            if (text[i] == '`' || text[i] == '*' || text[i] == '[') return i
        }
        return text.length
    }

    private fun parseInlineLink(text: String, start: Int): Pair<InlineToken.Link, Int>? {
        if (text.getOrNull(start) != '[') return null
        val labelEnd = text.indexOf(']', start + 1)
        if (labelEnd <= start) return null
        if (text.getOrNull(labelEnd + 1) != '(') return null

        val targetEnd = findInlineLinkTargetEnd(text, labelEnd + 2)
        if (targetEnd <= labelEnd + 2) return null

        val label = text.substring(start + 1, labelEnd)
        val target = text.substring(labelEnd + 2, targetEnd).trim()
        if (label.isBlank() || target.isBlank()) return null
        return InlineToken.Link(text = label, target = target) to (targetEnd + 1)
    }

    private fun findInlineLinkTargetEnd(text: String, start: Int): Int {
        var nestedParens = 0
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> escaped = true
                '(' -> nestedParens += 1
                ')' -> {
                    if (nestedParens == 0) {
                        return index
                    }
                    nestedParens -= 1
                }
            }
        }
        return -1
    }

    private val HEADING_REGEX = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val LOOSE_HEADING_REGEX = Regex("""^(\s{0,3})(#{2,6})([^\s#].*)$""")
    private val HEADING_KEYCAP_ORDINAL_REGEX = Regex("""^(\s{0,3}#{1,6}\s+)([0-9])(?:\uFE0F)?\u20E3\s*(\S.*)$""")
    private val HEADING_ENCLOSED_ORDINAL_REGEX = Regex("""^(\s{0,3}#{1,6}\s+)([①②③④⑤⑥⑦⑧⑨⑩])\s*(\S.*)$""")
    private val LOOSE_ORDERED_LIST_REGEX = Regex("""^(\s*)(\d+)([.)、）．])([^\s].*)$""")
    private val LOOSE_UNORDERED_LIST_REGEX = Regex("""^(\s*)[•●·・▪◦‣]\s*(\S.*)$""")
    private val LOOSE_DASH_UNORDERED_LIST_REGEX = Regex("""^(\s*)-([^\s-].*)$""")
    private val ORDERED_LIST_REGEX = Regex("""^(\d+)[.)]\s+(.*)$""")
    private val CHECKLIST_ITEM_REGEX = Regex("""^\[( |x|X)]\s+(.*)$""")
    private val BLOCKQUOTE_LINE_REGEX = Regex("""^\s{0,3}>\s?(.*)$""")
    private val TABLE_OPEN_TAG_REGEX = Regex("""<table\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val TABLE_CLOSE_TAG_REGEX = Regex("""</table\s*>""", RegexOption.IGNORE_CASE)
    private val TABLE_ROW_OPEN_TAG_REGEX = Regex("""<tr\b""", RegexOption.IGNORE_CASE)
    private val TABLE_BORDER_ATTR_REGEX = Regex("""\bborder\s*=""", RegexOption.IGNORE_CASE)
    private val TABLE_CELL_SPACING_ATTR_REGEX = Regex("""\bcellspacing\s*=""", RegexOption.IGNORE_CASE)
    private val TABLE_CELL_PADDING_ATTR_REGEX = Regex("""\bcellpadding\s*=""", RegexOption.IGNORE_CASE)
    private val TABLE_STYLE_ATTR_REGEX = Regex("""\bstyle\s*=""", RegexOption.IGNORE_CASE)
    private val TABLE_SEPARATOR_CELL_REGEX = Regex("""^:?-{3,}:?$""")
    private val UNORDERED_LIST_HTML_REGEX = Regex("""^[-*]\s+(.+)$""")
    private val ORDERED_LIST_HTML_REGEX = Regex("""^\d+[.)]\s+(.+)$""")
    private const val MIN_TABLE_COLUMNS = 2
    private const val RELAXED_TABLE_MIN_ROWS = 3
    private const val ESCAPED_PIPE_TOKEN = "\\|"
    private const val FULLWIDTH_PIPE_CHAR = '｜'
    private val VERTICAL_BAR_ALIASES = charArrayOf(
        '│', '┃', '¦', '∣', '┆', '┇', '╎', '╏', '┊', '┋',
        '❘', '❙', '❚', 'ǀ', '⎪', '⏐', '￨', '︱', '︲', '丨',
    )
    private val HORIZONTAL_DASH_ALIASES = charArrayOf(
        '—', '–', '―', '−', '─', '━', '﹣', '－', '‑', '‒', '﹘',
    )
    private val INVISIBLE_TABLE_CHAR_REGEX = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060]")
    private val INLINE_MARKDOWN_ASTERISK_ALIASES = charArrayOf(
        '*', '＊', '﹡', '∗', '⁎', '✱', '✲', '✳', '✻', '❇', '٭',
    )
    private val INLINE_MARKDOWN_IGNORED_CHARS = charArrayOf(
        '\u200B', '\u200C', '\u200D', '\uFEFF', '\u2060',
    )
    private val ASTERISK_ADJACENT_VARIATION_SELECTOR_REGEX = Regex("""(?<=\*)[\uFE0E\uFE0F]+|[\uFE0E\uFE0F]+(?=\*)""")
    private const val INLINE_MARKDOWN_NBSP = '\u00A0'
    private val STAR_PAIR_WITH_SPACES_REGEX = Regex("""\*\s+\*""")
    private const val PLAIN_TEXT_CONTENT_TYPE = "text/plain"
    private const val HTML_CONTENT_TYPE = "text/html"
    private const val MIN_INLINE_FONT_SIZE = 9
    private const val DEFAULT_BASE_FONT_SIZE = 11
    private const val MIN_BASE_FONT_SIZE = 9
    private const val MAX_BASE_FONT_SIZE = 36
    private const val MAX_HTML_TABLE_SOURCE_CHARS = 5000
    private const val MAX_HTML_TABLE_LINES = 18
    private const val MAX_HTML_TABLE_ROWS = 18
    private const val MAX_HTML_TABLE_COLUMNS = 8
    private const val MAX_HTML_TABLE_LINE_CHARS = 760
    private const val MAX_HTML_TABLE_TOTAL_CELL_CHARS = 5000
    private const val MAX_HTML_TABLE_CELL_CHARS = 900
    private const val PROSE_LINE_SPACING = 0.52f
    private const val LIST_ITEM_SPACE_ABOVE = 1.8f
    private const val LIST_ITEM_SPACE_BELOW = 1.8f
    private const val BLOCKQUOTE_MARKER = "\u258e"
    private const val BLOCKQUOTE_LEFT_INDENT = 10f
    private const val BLOCKQUOTE_SPACE_ABOVE = 1.6f
    private const val BLOCKQUOTE_SPACE_BELOW = 1.6f
    private val ENCLOSED_ORDINAL_MAP = mapOf(
        "①" to "1",
        "②" to "2",
        "③" to "3",
        "④" to "4",
        "⑤" to "5",
        "⑥" to "6",
        "⑦" to "7",
        "⑧" to "8",
        "⑨" to "9",
        "⑩" to "10",
    )

    private data class TableBlock(
        val rows: List<List<String>>,
        val nextIndex: Int,
    )

    private data class ChecklistItem(
        val checked: Boolean,
        val content: String,
    )

    private data class BlockquoteBlock(
        val lines: List<String>,
        val nextIndex: Int,
    )

    private data class HtmlTableBlock(
        val html: String,
        val nextIndex: Int,
    )

    private data class PipeTableMetrics(
        val likelyTableLineCount: Int,
        val maxLineLength: Int,
        val maxRows: Int,
        val maxColumns: Int,
        val totalCellChars: Int,
        val maxCellChars: Int,
    )

    private data class RawHtmlTableMetrics(
        val maxBlockChars: Int,
        val maxRows: Int,
    )

    private sealed class InlineToken {
        abstract val text: String
        data class Plain(override val text: String) : InlineToken()
        data class Bold(override val text: String) : InlineToken()
        data class Italic(override val text: String) : InlineToken()
        data class InlineCode(override val text: String) : InlineToken()
        data class Link(
            override val text: String,
            val target: String,
        ) : InlineToken()
    }
}
