package com.eacape.speccodingplugin.spec

internal object SpecTaskPlanningGroundingSupport {
    private data class HeadingBoundary(
        val lineNumber: Int,
        val level: Int,
        val isCanonicalTaskHeading: Boolean,
    )

    private data class LineTable(
        val lines: List<String>,
    )

    private data class CandidateFile(
        val path: String,
        val signals: Set<CodeContextCandidateSignal>,
    )

    private data class TaskPlanningProfile(
        val tokens: Set<String>,
        val testLike: Boolean,
        val uiLike: Boolean,
        val docLike: Boolean,
        val configLike: Boolean,
    )

    private data class ScoredCandidate(
        val candidate: CandidateFile,
        val score: Int,
        val overlapCount: Int,
        val categoryBoost: Int,
    )

    internal data class RelatedFilesSuggestion(
        val files: List<String>,
        val reason: String?,
    )

    fun enrichTasksMarkdown(
        markdown: String,
        codeContextPack: CodeContextPack?,
    ): String {
        if (markdown.isBlank()) {
            return markdown.trim()
        }

        val parsedMarkdown = SpecMarkdownAstParser.parse(markdown)
        val parsedTasks = SpecTaskMarkdownParser.parse(parsedMarkdown)
        if (parsedTasks.tasks.isEmpty()) {
            return parsedMarkdown.normalizedMarkdown.trimEnd()
        }

        val lineTable = buildLineTable(parsedMarkdown.normalizedMarkdown)
        val codeFenceLineRanges = parsedMarkdown.codeFences.map { fence ->
            fence.location.startLine..fence.location.endLine
        }
        val headings = collectHeadings(
            normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
            codeFenceLineRanges = codeFenceLineRanges,
        )
        val fencesByStartLine = parsedMarkdown.codeFences
            .filter { fence -> fence.language.equals(SPEC_TASK_LANGUAGE, ignoreCase = true) }
            .associateBy { fence -> fence.location.startLine }
        val workingLines = lineTable.lines.toMutableList()

        parsedTasks.tasks
            .sortedByDescending { entry -> entry.headingLine }
            .forEach { entry ->
                val status = entry.status ?: return@forEach
                val priority = entry.priority ?: return@forEach
                val fence = fencesByStartLine[entry.metadataLocation.startLine] ?: return@forEach
                val nextBoundaryLine = headings.firstOrNull { heading ->
                    heading.lineNumber > entry.headingLine &&
                        (heading.level < TASK_HEADING_LEVEL || heading.isCanonicalTaskHeading)
                }?.lineNumber ?: (lineTable.lines.size + 1)
                val bodyLines = lineTable.lines.subList(
                    fence.location.endLine.coerceAtMost(lineTable.lines.size),
                    (nextBoundaryLine - 1).coerceAtMost(lineTable.lines.size),
                )
                val bodyText = bodyLines.joinToString("\n")
                val suggestion = suggestRelatedFiles(
                    taskTitle = entry.title,
                    taskBody = bodyText,
                    codeContextPack = codeContextPack,
                )
                val resolvedRelatedFiles = if (entry.relatedFiles.isEmpty()) {
                    suggestion.files
                } else {
                    entry.relatedFiles
                }
                val replacementMetadata = renderTaskMetadata(
                    status = status,
                    priority = priority,
                    dependsOn = entry.dependsOn,
                    relatedFiles = resolvedRelatedFiles,
                    verificationResult = entry.verificationResult,
                )

                val contentStartIndex = (fence.contentLocation?.startLine ?: (fence.location.startLine + 1)) - 1
                val contentEndIndex = (fence.contentLocation?.endLine ?: contentStartIndex + 1) - 1
                val oldContentCount = (contentEndIndex - contentStartIndex + 1).coerceAtLeast(0)
                workingLines.subList(contentStartIndex, contentEndIndex + 1).clear()
                workingLines.addAll(contentStartIndex, replacementMetadata)

                val delta = replacementMetadata.size - oldContentCount
                val reasonLineIndices = ((fence.location.endLine + 1) until nextBoundaryLine)
                    .map { lineNumber -> lineNumber - 1 }
                    .filter { lineIndex ->
                        lineTable.lines.getOrNull(lineIndex)?.let(::isRelatedFilesReasonLine) == true
                    }
                    .map { lineIndex -> lineIndex + delta }
                    .sortedDescending()

                if (resolvedRelatedFiles.isNotEmpty()) {
                    reasonLineIndices.forEach { lineIndex ->
                        if (lineIndex in workingLines.indices) {
                            workingLines.removeAt(lineIndex)
                        }
                    }
                    return@forEach
                }

                if (entry.relatedFiles.isNotEmpty()) {
                    return@forEach
                }

                if (reasonLineIndices.isEmpty()) {
                    val noteLine = buildRelatedFilesReasonLine(
                        suggestion.reason ?: defaultEmptyReason(codeContextPack),
                    )
                    val insertionIndex = (fence.location.endLine + delta).coerceIn(0, workingLines.size)
                    workingLines.add(insertionIndex, noteLine)
                }
            }

        return workingLines.joinToString(separator = "\n").trimEnd()
    }

    internal fun suggestRelatedFiles(
        taskTitle: String,
        taskBody: String,
        codeContextPack: CodeContextPack?,
    ): RelatedFilesSuggestion {
        val candidatePool = buildCandidatePool(codeContextPack)
        if (candidatePool.isEmpty()) {
            return RelatedFilesSuggestion(
                files = emptyList(),
                reason = defaultEmptyReason(codeContextPack),
            )
        }

        val profile = buildTaskPlanningProfile("$taskTitle\n$taskBody")
        val scoredCandidates = candidatePool
            .map { candidate -> scoreCandidate(candidate, profile) }
            .sortedWith(
                compareByDescending<ScoredCandidate> { scored -> scored.score }
                    .thenBy { scored -> scored.candidate.path },
            )
        val topScore = scoredCandidates.firstOrNull()?.score ?: 0
        val reliableCandidates = if (topScore >= MIN_RELIABLE_SCORE) {
            scoredCandidates
                .takeWhile { scored -> scored.score >= (topScore - MAX_SCORE_SPREAD) }
                .take(MAX_SUGGESTED_RELATED_FILES)
        } else {
            emptyList()
        }

        if (reliableCandidates.isNotEmpty()) {
            return RelatedFilesSuggestion(
                files = reliableCandidates.map { scored -> scored.candidate.path },
                reason = null,
            )
        }

        val fallback = scoredCandidates.firstOrNull()
        if (
            fallback != null &&
            candidatePool.size == 1 &&
            fallback.score >= MIN_SINGLE_CANDIDATE_SCORE &&
            fallback.candidate.signals.any { signal -> signal in STRONG_SIGNALS }
        ) {
            return RelatedFilesSuggestion(
                files = listOf(fallback.candidate.path),
                reason = null,
            )
        }

        return RelatedFilesSuggestion(
            files = emptyList(),
            reason = when {
                codeContextPack == null || !codeContextPack.hasAutoContext() -> defaultEmptyReason(codeContextPack)
                else -> "candidate files were available, but none could be matched to this task with enough confidence; confirm the affected files before execution."
            },
        )
    }

    internal fun buildRelatedFilesReasonLine(reason: String): String {
        return "$RELATED_FILES_REASON_PREFIX$reason"
    }

    private fun defaultEmptyReason(codeContextPack: CodeContextPack?): String {
        return when {
            codeContextPack == null || !codeContextPack.hasAutoContext() ->
                "no reliable local code signals were available during planning; confirm the affected files before execution."

            else ->
                "no reliable file-level mapping was available for this task during planning; confirm the affected files before execution."
        }
    }

    private fun buildCandidatePool(codeContextPack: CodeContextPack?): List<CandidateFile> {
        if (codeContextPack == null) {
            return emptyList()
        }
        val signalsByPath = linkedMapOf<String, MutableSet<CodeContextCandidateSignal>>()

        fun register(
            path: String,
            signal: CodeContextCandidateSignal,
        ) {
            val normalized = path.trim()
            if (normalized.isEmpty()) {
                return
            }
            signalsByPath.getOrPut(normalized) { linkedSetOf() }.add(signal)
        }

        codeContextPack.explicitFileHints.forEach { path ->
            register(path, CodeContextCandidateSignal.EXPLICIT_SELECTION)
        }
        codeContextPack.confirmedRelatedFiles.forEach { path ->
            register(path, CodeContextCandidateSignal.CONFIRMED_RELATED_FILE)
        }
        codeContextPack.candidateFiles.forEach { candidate ->
            val normalized = candidate.path.trim()
            if (normalized.isEmpty()) {
                return@forEach
            }
            signalsByPath.getOrPut(normalized) { linkedSetOf() }.addAll(candidate.signals)
        }
        val changeSignal = when (codeContextPack.changeSummary.source) {
            CodeChangeSource.VCS_STATUS -> CodeContextCandidateSignal.VCS_CHANGE
            CodeChangeSource.WORKSPACE_CANDIDATES -> CodeContextCandidateSignal.WORKSPACE_CANDIDATE
            CodeChangeSource.NONE -> null
        }
        if (changeSignal != null) {
            codeContextPack.changeSummary.files.forEach { file ->
                register(file.path, changeSignal)
            }
        }

        return signalsByPath.entries.map { (path, signals) ->
            CandidateFile(
                path = path,
                signals = signals.toSet(),
            )
        }
    }

    private fun buildTaskPlanningProfile(taskText: String): TaskPlanningProfile {
        val raw = taskText.lowercase()
        val normalized = normalizeComparableText(taskText)
        return TaskPlanningProfile(
            tokens = extractComparableTokens(normalized),
            testLike = TEST_TASK_REGEX.containsMatchIn(raw),
            uiLike = UI_TASK_REGEX.containsMatchIn(raw),
            docLike = DOC_TASK_REGEX.containsMatchIn(raw),
            configLike = CONFIG_TASK_REGEX.containsMatchIn(raw),
        )
    }

    private fun scoreCandidate(
        candidate: CandidateFile,
        profile: TaskPlanningProfile,
    ): ScoredCandidate {
        val comparablePath = normalizeComparableText(candidate.path)
        val pathTokens = extractComparableTokens(comparablePath)
        val overlapCount = profile.tokens.intersect(pathTokens).size
        var score = candidate.signals.sumOf { signal -> SIGNAL_WEIGHT[signal] ?: 0 }
        score += overlapCount * TOKEN_OVERLAP_WEIGHT

        val categoryBoost = buildCategoryBoost(
            comparablePath = comparablePath,
            profile = profile,
        )
        score += categoryBoost

        if (!profile.testLike && isTestPath(candidate.path) && overlapCount == 0) {
            score -= TEST_PATH_PENALTY
        }
        if (!profile.docLike && isMarkdownPath(candidate.path) && overlapCount == 0) {
            score -= DOC_PATH_PENALTY
        }

        return ScoredCandidate(
            candidate = candidate,
            score = score,
            overlapCount = overlapCount,
            categoryBoost = categoryBoost,
        )
    }

    private fun buildCategoryBoost(
        comparablePath: String,
        profile: TaskPlanningProfile,
    ): Int {
        var boost = 0
        if (profile.testLike && isComparableTestPath(comparablePath)) {
            boost += TEST_TASK_BOOST
        }
        if (profile.uiLike && isComparableUiPath(comparablePath)) {
            boost += UI_TASK_BOOST
        }
        if (profile.docLike && isComparableDocPath(comparablePath)) {
            boost += DOC_TASK_BOOST
        }
        if (profile.configLike && isComparableConfigPath(comparablePath)) {
            boost += CONFIG_TASK_BOOST
        }
        return boost
    }

    private fun renderTaskMetadata(
        status: TaskStatus,
        priority: TaskPriority,
        dependsOn: List<String>,
        relatedFiles: List<String>,
        verificationResult: TaskVerificationResult?,
    ): List<String> {
        return buildList {
            add("status: ${status.name}")
            add("priority: ${priority.name}")
            addAll(renderStringListField("dependsOn", dependsOn))
            addAll(renderStringListField("relatedFiles", relatedFiles))
            addAll(renderVerificationResult(verificationResult))
        }
    }

    private fun renderStringListField(
        key: String,
        values: List<String>,
    ): List<String> {
        if (values.isEmpty()) {
            return listOf("$key: []")
        }
        return buildList {
            add("$key:")
            values.forEach { value ->
                add("  - $value")
            }
        }
    }

    private fun renderVerificationResult(verificationResult: TaskVerificationResult?): List<String> {
        if (verificationResult == null) {
            return listOf("verificationResult: null")
        }
        return listOf(
            "verificationResult:",
            "  conclusion: ${verificationResult.conclusion.name}",
            "  runId: ${verificationResult.runId}",
            "  summary: ${verificationResult.summary}",
            "  at: ${verificationResult.at}",
        )
    }

    private fun isRelatedFilesReasonLine(line: String): Boolean {
        return line.trim().startsWith(RELATED_FILES_REASON_MARKER, ignoreCase = true)
    }

    private fun collectHeadings(
        normalizedMarkdown: String,
        codeFenceLineRanges: List<IntRange>,
    ): List<HeadingBoundary> {
        return buildLineTable(normalizedMarkdown).lines.mapIndexedNotNull { index, line ->
            val lineNumber = index + 1
            if (codeFenceLineRanges.any { range -> lineNumber in range }) {
                return@mapIndexedNotNull null
            }
            val match = HEADING_REGEX.matchEntire(line) ?: return@mapIndexedNotNull null
            HeadingBoundary(
                lineNumber = lineNumber,
                level = match.groupValues[1].length,
                isCanonicalTaskHeading = CANONICAL_TASK_HEADING_REGEX.matches(line),
            )
        }
    }

    private fun buildLineTable(markdown: String): LineTable {
        if (markdown.isEmpty()) {
            return LineTable(lines = emptyList())
        }
        return LineTable(lines = markdown.split('\n'))
    }

    private fun extractComparableTokens(text: String): Set<String> {
        return TOKEN_REGEX.findAll(text)
            .map { match -> match.value }
            .filter { token -> token.length >= MIN_TOKEN_LENGTH }
            .toSet()
    }

    private fun normalizeComparableText(text: String): String {
        return text
            .replace(CAMEL_CASE_BOUNDARY_REGEX, "$1 $2")
            .replace(PATH_SEPARATOR_REGEX, " ")
            .replace(NON_TOKEN_CHAR_REGEX, " ")
            .lowercase()
    }

    private fun isTestPath(path: String): Boolean = isComparableTestPath(normalizeComparableText(path))

    private fun isMarkdownPath(path: String): Boolean = isComparableDocPath(normalizeComparableText(path))

    private fun isComparableTestPath(comparablePath: String): Boolean {
        return comparablePath.contains("src test") ||
            comparablePath.contains(" test ") ||
            comparablePath.endsWith(" test kt") ||
            comparablePath.endsWith(" tests kt")
    }

    private fun isComparableUiPath(comparablePath: String): Boolean {
        return comparablePath.contains(" ui ") ||
            comparablePath.contains(" panel ") ||
            comparablePath.contains(" dialog ") ||
            comparablePath.contains(" popup ") ||
            comparablePath.contains(" toolwindow ") ||
            comparablePath.contains(" view ")
    }

    private fun isComparableDocPath(comparablePath: String): Boolean {
        return comparablePath.endsWith(" md") || comparablePath.contains(" readme ")
    }

    private fun isComparableConfigPath(comparablePath: String): Boolean {
        return comparablePath.contains(" gradle ") ||
            comparablePath.contains(" settings ") ||
            comparablePath.contains(" plugin xml") ||
            comparablePath.contains(" config ") ||
            comparablePath.contains(" properties")
    }

    internal const val RELATED_FILES_REASON_PREFIX = "- relatedFiles reason: "

    private const val RELATED_FILES_REASON_MARKER = "- relatedfiles reason:"
    private const val SPEC_TASK_LANGUAGE = "spec-task"
    private const val TASK_HEADING_LEVEL = 3
    private const val MAX_SUGGESTED_RELATED_FILES = 3
    private const val MIN_RELIABLE_SCORE = 40
    private const val MIN_SINGLE_CANDIDATE_SCORE = 28
    private const val MAX_SCORE_SPREAD = 12
    private const val TOKEN_OVERLAP_WEIGHT = 14
    private const val TEST_TASK_BOOST = 28
    private const val UI_TASK_BOOST = 18
    private const val DOC_TASK_BOOST = 16
    private const val CONFIG_TASK_BOOST = 14
    private const val TEST_PATH_PENALTY = 6
    private const val DOC_PATH_PENALTY = 4
    private const val MIN_TOKEN_LENGTH = 2

    private val STRONG_SIGNALS = setOf(
        CodeContextCandidateSignal.EXPLICIT_SELECTION,
        CodeContextCandidateSignal.CONFIRMED_RELATED_FILE,
        CodeContextCandidateSignal.VCS_CHANGE,
    )

    private val SIGNAL_WEIGHT = mapOf(
        CodeContextCandidateSignal.EXPLICIT_SELECTION to 42,
        CodeContextCandidateSignal.CONFIRMED_RELATED_FILE to 38,
        CodeContextCandidateSignal.VCS_CHANGE to 32,
        CodeContextCandidateSignal.WORKSPACE_CANDIDATE to 24,
        CodeContextCandidateSignal.KEY_PROJECT_FILE to 12,
    )

    private val HEADING_REGEX = Regex("""^(#{1,6})\s+\S.*$""")
    private val CANONICAL_TASK_HEADING_REGEX = Regex("""^###\s+T-\d{3}:\s+\S.*$""")
    private val CAMEL_CASE_BOUNDARY_REGEX = Regex("""([a-z0-9])([A-Z])""")
    private val PATH_SEPARATOR_REGEX = Regex("""[/\\._-]+""")
    private val NON_TOKEN_CHAR_REGEX = Regex("""[^a-zA-Z0-9]+""")
    private val TOKEN_REGEX = Regex("""[a-z0-9]+""")
    private val TEST_TASK_REGEX = Regex("""\b(test|tests|verify|verification|regression|assert)\b|测试|验证|回归""")
    private val UI_TASK_REGEX = Regex("""\b(ui|panel|dialog|popup|toolwindow|view)\b|界面|面板|弹窗|窗口""")
    private val DOC_TASK_REGEX = Regex("""\b(doc|docs|readme|markdown|spec|requirements|design|tasks)\b|文档|规格|需求|设计|任务""")
    private val CONFIG_TASK_REGEX = Regex("""\b(config|configuration|gradle|settings|plugin|properties)\b|配置""")
}
