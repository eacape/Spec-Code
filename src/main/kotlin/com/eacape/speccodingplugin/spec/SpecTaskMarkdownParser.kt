package com.eacape.speccodingplugin.spec

import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager

object SpecTaskMarkdownParser {

    data class TaskMetadataLocation(
        val startLine: Int,
        val endLine: Int,
        val contentStartLine: Int?,
    )

    data class ParseIssue(
        val line: Int,
        val message: String,
        val fixHint: String? = null,
    )

    data class ParsedTaskEntry(
        val id: String,
        val title: String,
        val headingLine: Int,
        val metadataLocation: TaskMetadataLocation,
        val status: TaskStatus?,
        val priority: TaskPriority?,
        val dependsOn: List<String>,
        val relatedFiles: List<String>,
        val verificationResult: TaskVerificationResult?,
        val keyLineNumbers: Map<String, Int>,
    ) {
        fun lineForKey(key: String): Int {
            return keyLineNumbers[key] ?: metadataLocation.contentStartLine ?: metadataLocation.startLine
        }

        fun toStructuredTaskOrNull(): StructuredTask? {
            val resolvedStatus = status ?: return null
            val resolvedPriority = priority ?: return null
            return StructuredTask(
                id = id,
                title = title,
                status = resolvedStatus,
                priority = resolvedPriority,
                dependsOn = dependsOn,
                relatedFiles = relatedFiles,
                verificationResult = verificationResult,
            )
        }
    }

    data class ParsedTaskDocument(
        val tasks: List<ParsedTaskEntry>,
        val issues: List<ParseIssue>,
    )

    fun parse(markdown: String): ParsedTaskDocument {
        return parse(SpecMarkdownAstParser.parse(markdown))
    }

    internal fun parse(parsed: SpecMarkdownAstParser.ParsedDocument): ParsedTaskDocument {
        synchronized(parseCache) {
            parseCache[parsed.normalizedMarkdown]?.let { cached ->
                return cached
            }
        }
        ProgressIndicatorProvider.getGlobalProgressIndicator()?.text2 = "Parsing tasks.md structure"
        val lines = if (parsed.normalizedMarkdown.isEmpty()) {
            emptyList()
        } else {
            parsed.normalizedMarkdown.split('\n')
        }
        val specTaskFences = parsed.codeFences
            .filter { it.language.equals(SPEC_TASK_LANGUAGE, ignoreCase = true) }
            .sortedBy { it.location.startLine }
        val fencesByStartLine = specTaskFences.associateBy { it.location.startLine }
        val consumedFenceLines = mutableSetOf<Int>()
        val issues = mutableListOf<ParseIssue>()
        val tasks = mutableListOf<ParsedTaskEntry>()

        lines.forEachIndexed { index, rawLine ->
            ProgressManager.checkCanceled()
            reportProgress(
                detail = "Scanning tasks.md headings",
                completed = index,
                total = lines.size.coerceAtLeast(1),
            )
            val lineNumber = index + 1
            if (!TASK_HEADING_PREFIX.matches(rawLine)) {
                return@forEachIndexed
            }

            val immediateFence = fencesByStartLine[lineNumber + 1]
            val match = TASK_HEADING_REGEX.matchEntire(rawLine)
            if (match == null) {
                if (immediateFence == null) {
                    return@forEachIndexed
                }
                consumedFenceLines += immediateFence.location.startLine
                issues += ParseIssue(
                    line = lineNumber,
                    message = "Task headings must use the format `### T-001: Title`.",
                    fixHint = "Rename the heading to the canonical task syntax.",
                )
                return@forEachIndexed
            }

            if (immediateFence == null) {
                issues += ParseIssue(
                    line = lineNumber,
                    message = "Task heading ${match.groupValues[1]} must be followed immediately by a `spec-task` code block.",
                    fixHint = "Place a ```spec-task YAML block directly under the task heading.",
                )
                return@forEachIndexed
            }

            consumedFenceLines += immediateFence.location.startLine
            tasks += parseTaskEntry(
                taskId = match.groupValues[1],
                title = match.groupValues[2].trim(),
                headingLine = lineNumber,
                fence = immediateFence,
                issues = issues,
            )
        }

        specTaskFences
            .filterNot { consumedFenceLines.contains(it.location.startLine) }
            .forEach { fence ->
                issues += ParseIssue(
                    line = fence.location.startLine,
                    message = "`spec-task` code blocks must immediately follow a task heading.",
                    fixHint = "Move this code block below a `### T-001: Title` heading.",
                )
            }

        val parsedDocument = ParsedTaskDocument(
            tasks = tasks,
            issues = issues.sortedBy(ParseIssue::line),
        )
        synchronized(parseCache) {
            parseCache[parsed.normalizedMarkdown] = parsedDocument
        }
        return parsedDocument
    }

    private fun parseTaskEntry(
        taskId: String,
        title: String,
        headingLine: Int,
        fence: SpecMarkdownAstParser.CodeFence,
        issues: MutableList<ParseIssue>,
    ): ParsedTaskEntry {
        val keyLineNumbers = collectKeyLineNumbers(fence)
        val blockStartLine = fence.contentLocation?.startLine ?: fence.location.startLine
        val rawMetadata = runCatching { SpecYamlCodec.decodeMap(fence.content) }
            .getOrElse { error ->
                issues += ParseIssue(
                    line = blockStartLine,
                    message = "Task $taskId has invalid `spec-task` YAML: ${error.message ?: "unknown parse failure"}",
                    fixHint = "Use a plain YAML mapping with status/priority/dependsOn/relatedFiles/verificationResult.",
                )
                emptyMap()
            }

        if (rawMetadata.isEmpty()) {
            issues += ParseIssue(
                line = blockStartLine,
                message = "Task $taskId must define `spec-task` YAML fields.",
                fixHint = "Add status, priority, dependsOn, relatedFiles, and verificationResult.",
            )
        }

        val unknownKeys = rawMetadata.keys - ALLOWED_TASK_KEYS
        unknownKeys.sorted().forEach { key ->
            issues += ParseIssue(
                line = keyLineNumbers[key] ?: blockStartLine,
                message = "Task $taskId uses unsupported `spec-task` field `$key`.",
                fixHint = "Remove `$key`; only status, priority, dependsOn, relatedFiles, and verificationResult are allowed.",
            )
        }

        val missingKeys = REQUIRED_TASK_KEYS - rawMetadata.keys
        missingKeys.sorted().forEach { key ->
            issues += ParseIssue(
                line = blockStartLine,
                message = "Task $taskId is missing required `spec-task` field `$key`.",
                fixHint = "Add `$key` to the task metadata block.",
            )
        }

        val status = parseEnumField<TaskStatus>(
            taskId = taskId,
            fieldName = "status",
            rawValue = rawMetadata["status"],
            line = keyLineNumbers["status"] ?: blockStartLine,
            issues = issues,
        )
        val priority = parseEnumField<TaskPriority>(
            taskId = taskId,
            fieldName = "priority",
            rawValue = rawMetadata["priority"],
            line = keyLineNumbers["priority"] ?: blockStartLine,
            issues = issues,
        )
        val dependsOn = parseStringListField(
            taskId = taskId,
            fieldName = "dependsOn",
            rawValue = rawMetadata["dependsOn"],
            line = keyLineNumbers["dependsOn"] ?: blockStartLine,
            issues = issues,
        )
        val relatedFiles = parseStringListField(
            taskId = taskId,
            fieldName = "relatedFiles",
            rawValue = rawMetadata["relatedFiles"],
            line = keyLineNumbers["relatedFiles"] ?: blockStartLine,
            issues = issues,
        )
        val verificationResult = parseVerificationResult(
            taskId = taskId,
            rawValue = rawMetadata["verificationResult"],
            line = keyLineNumbers["verificationResult"] ?: blockStartLine,
            issues = issues,
        )

        dependsOn
            .filterNot(TASK_ID_REGEX::matches)
            .distinct()
            .sorted()
            .forEach { dependencyId ->
                issues += ParseIssue(
                    line = keyLineNumbers["dependsOn"] ?: blockStartLine,
                    message = "Task $taskId depends on invalid task id `$dependencyId`.",
                    fixHint = "Use task ids in the format T-001 inside dependsOn.",
                )
            }

        return ParsedTaskEntry(
            id = taskId,
            title = title,
            headingLine = headingLine,
            metadataLocation = TaskMetadataLocation(
                startLine = fence.location.startLine,
                endLine = fence.location.endLine,
                contentStartLine = fence.contentLocation?.startLine,
            ),
            status = status,
            priority = priority,
            dependsOn = dependsOn,
            relatedFiles = relatedFiles,
            verificationResult = verificationResult,
            keyLineNumbers = keyLineNumbers,
        )
    }

    private fun collectKeyLineNumbers(fence: SpecMarkdownAstParser.CodeFence): Map<String, Int> {
        val firstLine = fence.contentLocation?.startLine ?: fence.location.startLine
        return fence.content
            .lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                ProgressManager.checkCanceled()
                val match = YAML_KEY_REGEX.matchEntire(rawLine) ?: return@mapIndexedNotNull null
                match.groupValues[1] to (firstLine + index)
            }
            .toMap(linkedMapOf())
    }

    private inline fun <reified T : Enum<T>> parseEnumField(
        taskId: String,
        fieldName: String,
        rawValue: Any?,
        line: Int,
        issues: MutableList<ParseIssue>,
    ): T? {
        if (rawValue == null) {
            return null
        }
        val rawText = rawValue as? String
        if (rawText == null) {
            issues += ParseIssue(
                line = line,
                message = "Task $taskId field `$fieldName` must be a string enum value.",
                fixHint = "Set `$fieldName` to one of ${enumValues<T>().joinToString()}.",
            )
            return null
        }
        return enumValues<T>().firstOrNull { it.name == rawText }
            ?: run {
                issues += ParseIssue(
                    line = line,
                    message = "Task $taskId field `$fieldName` has unsupported value `$rawText`.",
                    fixHint = "Use one of ${enumValues<T>().joinToString()}.",
                )
                null
            }
    }

    private fun parseStringListField(
        taskId: String,
        fieldName: String,
        rawValue: Any?,
        line: Int,
        issues: MutableList<ParseIssue>,
    ): List<String> {
        if (rawValue == null) {
            return emptyList()
        }
        val normalizedList = when (rawValue) {
            is List<*> -> rawValue.takeIf { list -> list.all { item -> item is String } }
                ?.filterIsInstance<String>()

            is Map<*, *> -> rawValue.entries
                .takeIf { entries -> entries.all { (_, value) -> value is String } }
                ?.map { (_, value) -> value as String }

            else -> null
        }
        if (normalizedList != null) {
            return normalizedList
        }
        issues += ParseIssue(
            line = line,
            message = "Task $taskId field `$fieldName` must be a YAML string list.",
            fixHint = "Represent `$fieldName` as a YAML sequence, for example `$fieldName: []`.",
        )
        return emptyList()
    }

    private fun parseVerificationResult(
        taskId: String,
        rawValue: Any?,
        line: Int,
        issues: MutableList<ParseIssue>,
    ): TaskVerificationResult? {
        if (rawValue == null) {
            return null
        }
        val rawMap = rawValue as? Map<*, *>
        if (rawMap == null) {
            issues += ParseIssue(
                line = line,
                message = "Task $taskId field `verificationResult` must be null or a YAML mapping.",
                fixHint = "Set `verificationResult` to null or provide conclusion/runId/summary/at.",
            )
            return null
        }

        val verificationMap = rawMap.entries.associate { (rawKey, rawEntryValue) ->
            rawKey.toString() to rawEntryValue
        }
        val unknownKeys = verificationMap.keys - ALLOWED_VERIFICATION_KEYS
        unknownKeys.sorted().forEach { key ->
            issues += ParseIssue(
                line = line,
                message = "Task $taskId uses unsupported verificationResult field `$key`.",
                fixHint = "Only conclusion, runId, summary, and at are allowed inside verificationResult.",
            )
        }
        val missingKeys = REQUIRED_VERIFICATION_KEYS - verificationMap.keys
        missingKeys.sorted().forEach { key ->
            issues += ParseIssue(
                line = line,
                message = "Task $taskId verificationResult is missing `$key`.",
                fixHint = "Populate `$key` inside verificationResult or set verificationResult to null.",
            )
        }

        val conclusion = parseEnumField<VerificationConclusion>(
            taskId = taskId,
            fieldName = "verificationResult.conclusion",
            rawValue = verificationMap["conclusion"],
            line = line,
            issues = issues,
        )
        val runId = parseRequiredString(
            taskId = taskId,
            fieldName = "verificationResult.runId",
            rawValue = verificationMap["runId"],
            line = line,
            issues = issues,
        )
        val summary = parseRequiredString(
            taskId = taskId,
            fieldName = "verificationResult.summary",
            rawValue = verificationMap["summary"],
            line = line,
            issues = issues,
        )
        val at = parseRequiredString(
            taskId = taskId,
            fieldName = "verificationResult.at",
            rawValue = verificationMap["at"],
            line = line,
            issues = issues,
        )

        return if (conclusion != null && runId != null && summary != null && at != null) {
            TaskVerificationResult(
                conclusion = conclusion,
                runId = runId,
                summary = summary,
                at = at,
            )
        } else {
            null
        }
    }

    private fun parseRequiredString(
        taskId: String,
        fieldName: String,
        rawValue: Any?,
        line: Int,
        issues: MutableList<ParseIssue>,
    ): String? {
        if (rawValue == null) {
            return null
        }
        val rawText = rawValue as? String
        if (rawText.isNullOrBlank()) {
            issues += ParseIssue(
                line = line,
                message = "Task $taskId field `$fieldName` must be a non-blank string.",
                fixHint = "Provide a string value for `$fieldName`.",
            )
            return null
        }
        return rawText
    }

    private val TASK_HEADING_PREFIX = Regex("""^\s{0,3}###\s+.*$""")
    private val TASK_HEADING_REGEX = Regex("""^\s{0,3}###\s+(T-\d{3}):\s+(.+?)\s*$""")
    private val TASK_ID_REGEX = Regex("""T-\d{3}""")
    private val YAML_KEY_REGEX = Regex("""^\s*([A-Za-z][A-Za-z0-9]*)\s*:.*$""")

    private const val SPEC_TASK_LANGUAGE = "spec-task"

    private val ALLOWED_TASK_KEYS = setOf(
        "status",
        "priority",
        "dependsOn",
        "relatedFiles",
        "verificationResult",
    )
    private val REQUIRED_TASK_KEYS = ALLOWED_TASK_KEYS
    private val ALLOWED_VERIFICATION_KEYS = setOf(
        "conclusion",
        "runId",
        "summary",
        "at",
    )
    private val REQUIRED_VERIFICATION_KEYS = ALLOWED_VERIFICATION_KEYS

    private const val MAX_CACHE_ENTRIES = 32

    private val parseCache = object : LinkedHashMap<String, ParsedTaskDocument>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedTaskDocument>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private fun reportProgress(detail: String, completed: Int, total: Int) {
        val indicator = ProgressIndicatorProvider.getGlobalProgressIndicator() ?: return
        if (completed == 0 || completed == total - 1 || completed % 20 == 0) {
            indicator.isIndeterminate = false
            indicator.text2 = detail
            indicator.fraction = completed.toDouble() / total.toDouble()
        }
    }
}
