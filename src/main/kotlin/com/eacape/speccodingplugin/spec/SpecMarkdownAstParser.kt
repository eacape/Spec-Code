package com.eacape.speccodingplugin.spec

import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal object SpecMarkdownAstParser {

    data class SourceLocation(
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val startLine: Int,
        val endLine: Int,
    )

    data class CodeFence(
        val language: String?,
        val content: String,
        val location: SourceLocation,
        val contentLocation: SourceLocation?,
    )

    data class ParsedDocument(
        val normalizedMarkdown: String,
        val root: ASTNode,
        val codeFences: List<CodeFence>,
        private val lineIndex: LineIndex,
    ) {
        fun lineOfOffset(offset: Int): Int = lineIndex.lineOfOffset(offset)

        fun locationOf(node: ASTNode): SourceLocation {
            return lineIndex.location(node.startOffset, node.endOffset)
        }
    }

    fun parse(markdown: String): ParsedDocument {
        val normalizedMarkdown = normalizeLineEndings(markdown)
        synchronized(parseCache) {
            parseCache[normalizedMarkdown]?.let { cached ->
                return cached
            }
        }
        ProgressIndicatorProvider.getGlobalProgressIndicator()?.text2 = "Parsing markdown structure"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(normalizedMarkdown)
        val lineIndex = LineIndex(normalizedMarkdown)
        val codeFences = collectCodeFences(root, normalizedMarkdown, lineIndex)
        val parsedDocument = ParsedDocument(
            normalizedMarkdown = normalizedMarkdown,
            root = root,
            codeFences = codeFences,
            lineIndex = lineIndex,
        )
        synchronized(parseCache) {
            parseCache[normalizedMarkdown] = parsedDocument
        }
        return parsedDocument
    }

    private fun collectCodeFences(
        root: ASTNode,
        markdown: String,
        lineIndex: LineIndex,
    ): List<CodeFence> {
        val nodes = mutableListOf<ASTNode>()
        collectNodesByType(root, MarkdownElementTypes.CODE_FENCE, nodes)
        return nodes
            .sortedBy { it.startOffset }
            .map { node -> toCodeFence(node, markdown, lineIndex) }
    }

    private fun collectNodesByType(node: ASTNode, type: IElementType, sink: MutableList<ASTNode>) {
        ProgressManager.checkCanceled()
        if (node.type == type) {
            sink += node
        }
        node.children.forEach { child ->
            collectNodesByType(child, type, sink)
        }
    }

    private fun toCodeFence(node: ASTNode, markdown: String, lineIndex: LineIndex): CodeFence {
        val language = node.children
            .firstOrNull { child -> child.type == MarkdownTokenTypes.FENCE_LANG }
            ?.getTextInNode(markdown)
            ?.toString()
            ?.trim()
            ?.ifBlank { null }

        val contentNodes = node.children.filter { child ->
            child.type == MarkdownTokenTypes.CODE_FENCE_CONTENT
        }

        val rawContent = if (contentNodes.isNotEmpty()) {
            contentNodes.joinToString(separator = "\n") { child -> child.getTextInNode(markdown).toString() }
        } else {
            extractContentFromFenceText(node.getTextInNode(markdown).toString())
        }
        val content = normalizeExtractedContent(rawContent)

        val contentLocation = when {
            content.isEmpty() -> null
            contentNodes.isNotEmpty() -> lineIndex.location(
                contentNodes.first().startOffset,
                contentNodes.last().endOffset,
            )

            else -> inferContentLocationFromText(node, markdown, lineIndex)
        }

        return CodeFence(
            language = language,
            content = content,
            location = lineIndex.location(node.startOffset, node.endOffset),
            contentLocation = contentLocation,
        )
    }

    private fun extractContentFromFenceText(fenceText: String): String {
        val normalized = normalizeLineEndings(fenceText)
        val lines = normalized.split('\n')
        if (lines.size <= 1) {
            return ""
        }
        val hasClosingFence = lines.last()
            .trimStart()
            .let { line -> line.startsWith("```") || line.startsWith("~~~") }
        val endIndexExclusive = if (hasClosingFence) lines.lastIndex else lines.size
        if (endIndexExclusive <= 1) {
            return ""
        }
        return lines.subList(1, endIndexExclusive).joinToString("\n")
    }

    private fun inferContentLocationFromText(
        node: ASTNode,
        markdown: String,
        lineIndex: LineIndex,
    ): SourceLocation? {
        val fenceText = node.getTextInNode(markdown).toString()
        val firstBreak = fenceText.indexOf('\n')
        val lastBreak = fenceText.lastIndexOf('\n')
        if (firstBreak < 0 || lastBreak <= firstBreak) {
            return null
        }
        val contentStartOffset = node.startOffset + firstBreak + 1
        val contentEndOffsetExclusive = node.startOffset + lastBreak
        if (contentEndOffsetExclusive <= contentStartOffset) {
            return null
        }
        return lineIndex.location(contentStartOffset, contentEndOffsetExclusive)
    }

    private fun normalizeExtractedContent(rawContent: String): String {
        return normalizeLineEndings(rawContent).removeSuffix("\n")
    }

    private fun normalizeLineEndings(markdown: String): String {
        return markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    class LineIndex(markdown: String) {
        private val sourceLength = markdown.length
        private val lineStartOffsets = buildLineStarts(markdown)

        fun lineOfOffset(offset: Int): Int {
            if (lineStartOffsets.isEmpty()) {
                return 1
            }
            val normalizedOffset = normalizeOffset(offset)
            val index = lineStartOffsets.binarySearch(normalizedOffset)
            return if (index >= 0) {
                index + 1
            } else {
                val insertionPoint = -index - 1
                insertionPoint.coerceAtLeast(1)
            }
        }

        fun location(startOffset: Int, endOffsetExclusive: Int): SourceLocation {
            val safeStart = startOffset.coerceIn(0, sourceLength)
            val safeEnd = endOffsetExclusive.coerceIn(safeStart, sourceLength)
            val endLineProbe = when {
                sourceLength == 0 -> 0
                safeEnd <= safeStart -> if (safeStart == sourceLength) sourceLength - 1 else safeStart
                else -> safeEnd - 1
            }
            return SourceLocation(
                startOffset = safeStart,
                endOffsetExclusive = safeEnd,
                startLine = lineOfOffset(safeStart),
                endLine = lineOfOffset(endLineProbe),
            )
        }

        private fun normalizeOffset(offset: Int): Int {
            if (sourceLength == 0) {
                return 0
            }
            return offset.coerceIn(0, sourceLength - 1)
        }

        private fun buildLineStarts(markdown: String): IntArray {
            if (markdown.isEmpty()) {
                return intArrayOf(0)
            }
            val starts = ArrayList<Int>()
            starts += 0
            markdown.forEachIndexed { index, char ->
                if (char == '\n' && index + 1 <= markdown.length) {
                    starts += index + 1
                }
            }
            return starts.toIntArray()
        }
    }

    private const val MAX_CACHE_ENTRIES = 32

    private val parseCache = object : LinkedHashMap<String, ParsedDocument>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedDocument>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }
}
