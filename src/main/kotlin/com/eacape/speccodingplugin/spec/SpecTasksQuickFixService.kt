package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.file.Path

data class SpecTasksQuickFixResult(
    val workflowId: String,
    val tasksDocumentPath: Path,
    val changed: Boolean,
    val issuesBefore: List<SpecTaskMarkdownParser.ParseIssue>,
    val issuesAfter: List<SpecTaskMarkdownParser.ParseIssue>,
)

class SpecTasksQuickFixService(
    project: Project,
    private val storage: SpecStorage = SpecStorage.getInstance(project),
    private val artifactService: SpecArtifactService = SpecArtifactService(project),
    private val tasksService: SpecTasksService = SpecTasksService(project),
) {

    fun repairTasksArtifact(
        workflowId: String,
        trigger: String = TRIGGER_EDITOR_POPUP,
    ): SpecTasksQuickFixResult {
        val normalizedWorkflowId = workflowId.trim()
        require(normalizedWorkflowId.isNotEmpty()) { "workflowId cannot be blank." }

        val existing = artifactService.readArtifact(normalizedWorkflowId, StageId.TASKS)
            ?: throw IllegalStateException("tasks.md not found for workflow: $normalizedWorkflowId")
        val original = normalizeLineEndings(existing)
        val issuesBefore = SpecTaskMarkdownParser.parse(original).issues

        val repaired = repairMarkdown(original)
        val stabilized = normalizeLineEndings(tasksService.stabilizeOutput(repaired))
        val issuesAfter = SpecTaskMarkdownParser.parse(stabilized).issues

        val changed = stabilized != original
        val tasksDocumentPath = if (changed) {
            artifactService.writeArtifact(normalizedWorkflowId, StageId.TASKS, stabilized).also {
                storage.appendAuditEvent(
                    workflowId = normalizedWorkflowId,
                    eventType = SpecAuditEventType.TASKS_ARTIFACT_REPAIRED,
                    details = linkedMapOf(
                        "trigger" to trigger,
                        "file" to StageId.TASKS.artifactFileName.orEmpty(),
                        "issuesBefore" to issuesBefore.size.toString(),
                        "issuesAfter" to issuesAfter.size.toString(),
                    ),
                ).getOrThrow()
            }
        } else {
            artifactService.locateArtifact(normalizedWorkflowId, StageId.TASKS)
        }

        return SpecTasksQuickFixResult(
            workflowId = normalizedWorkflowId,
            tasksDocumentPath = tasksDocumentPath,
            changed = changed,
            issuesBefore = issuesBefore,
            issuesAfter = issuesAfter,
        )
    }

    private fun repairMarkdown(markdown: String): String {
        val endsWithNewline = markdown.endsWith("\n")
        val lines = markdown
            .trimEnd('\n')
            .split('\n')
            .toMutableList()
        if (endsWithNewline) {
            lines += ""
        }
        val existingIds = collectTaskIds(lines)
        var nextSequence = existingIds.mapNotNull(::taskSequenceOrNull).maxOrNull() ?: 0

        fun allocateNextId(): String {
            while (true) {
                nextSequence += 1
                val candidate = "T-" + nextSequence.toString().padStart(3, '0')
                if (candidate !in existingIds) {
                    existingIds += candidate
                    return candidate
                }
            }
        }

        var index = 0
        while (index < lines.size) {
            if (isSpecTaskFenceStartLine(lines[index])) {
                index = ensureHeadingBeforeFence(lines, index, ::allocateNextId)
            }
            index += 1
        }

        index = 0
        while (index < lines.size) {
            if (CANONICAL_TASK_HEADING_REGEX.matches(lines[index])) {
                index = ensureFenceAfterHeading(lines, index)
            }
            index += 1
        }

        index = 0
        while (index < lines.size) {
            if (isSpecTaskFenceStartLine(lines[index])) {
                index = sanitizeSpecTaskFence(lines, index)
            }
            index += 1
        }

        return lines.joinToString("\n")
    }

    private fun ensureHeadingBeforeFence(
        lines: MutableList<String>,
        fenceStart: Int,
        allocateId: () -> String,
    ): Int {
        var fenceIndex = fenceStart

        while (fenceIndex > 0 && lines[fenceIndex - 1].isBlank()) {
            val candidate = fenceIndex - 2
            if (candidate >= 0 && TASK_HEADING_PREFIX_REGEX.matches(lines[candidate])) {
                lines.removeAt(fenceIndex - 1)
                fenceIndex -= 1
            } else {
                break
            }
        }

        if (fenceIndex == 0) {
            lines.add(0, canonicalHeading(allocateId(), "TODO"))
            return fenceIndex + 1
        }

        val above = lines[fenceIndex - 1]
        if (CANONICAL_TASK_HEADING_REGEX.matches(above)) {
            return fenceIndex
        }
        if (TASK_HEADING_PREFIX_REGEX.matches(above)) {
            lines[fenceIndex - 1] = canonicalizeHeading(above, allocateId)
            return fenceIndex
        }

        lines.add(fenceIndex, canonicalHeading(allocateId(), "TODO"))
        return fenceIndex + 1
    }

    private fun ensureFenceAfterHeading(lines: MutableList<String>, headingIndex: Int): Int {
        val fenceTargetIndex = headingIndex + 1
        if (fenceTargetIndex < lines.size && isSpecTaskFenceStartLine(lines[fenceTargetIndex])) {
            return headingIndex
        }

        var searchIndex = fenceTargetIndex
        while (searchIndex < lines.size) {
            if (CANONICAL_TASK_HEADING_REGEX.matches(lines[searchIndex])) {
                break
            }
            if (isSpecTaskFenceStartLine(lines[searchIndex])) {
                val blockStart = searchIndex
                val blockEnd = findFenceEndIndex(lines, blockStart)
                val blockLines = lines.subList(blockStart, blockEnd + 1).toList()
                lines.subList(blockStart, blockEnd + 1).clear()
                lines.addAll(fenceTargetIndex, blockLines)
                return headingIndex
            }
            searchIndex += 1
        }

        lines.addAll(fenceTargetIndex, defaultSpecTaskFenceLines())
        return headingIndex
    }

    private fun sanitizeSpecTaskFence(lines: MutableList<String>, fenceStart: Int): Int {
        lines[fenceStart] = "```spec-task"
        var fenceEnd = findFenceEndIndex(lines, fenceStart)
        lines[fenceEnd] = "```"

        val rawContent = lines.subList(fenceStart + 1, fenceEnd).joinToString("\n")
        val sanitized = sanitizeTaskMetadata(rawContent)
        val renderedLines = renderTaskMetadataLines(sanitized).toMutableList()

        lines.subList(fenceStart + 1, fenceEnd).clear()
        lines.addAll(fenceStart + 1, renderedLines)

        fenceEnd = fenceStart + 1 + renderedLines.size
        lines[fenceEnd] = "```"
        return fenceEnd
    }

    private data class SanitizedTaskMetadata(
        val status: TaskStatus,
        val priority: TaskPriority,
        val dependsOn: List<String>,
        val relatedFiles: List<String>,
        val verificationResult: TaskVerificationResult?,
    )

    private fun sanitizeTaskMetadata(raw: String): SanitizedTaskMetadata {
        val decoded = runCatching { SpecYamlCodec.decodeMap(raw) }.getOrElse { emptyMap() }

        val status = (decoded["status"] as? String)
            ?.trim()
            ?.uppercase()
            ?.let { rawStatus -> TaskStatus.entries.firstOrNull { it.name == rawStatus } }
            ?: TaskStatus.PENDING

        val priority = (decoded["priority"] as? String)
            ?.trim()
            ?.uppercase()
            ?.let { rawPriority -> TaskPriority.entries.firstOrNull { it.name == rawPriority } }
            ?: TaskPriority.P1

        return SanitizedTaskMetadata(
            status = status,
            priority = priority,
            dependsOn = normalizeTaskIdList(decoded["dependsOn"]),
            relatedFiles = normalizePathList(decoded["relatedFiles"]),
            verificationResult = sanitizeVerificationResult(decoded["verificationResult"]),
        )
    }

    private fun sanitizeVerificationResult(raw: Any?): TaskVerificationResult? {
        if (raw == null) {
            return null
        }
        val map = raw as? Map<*, *> ?: return null
        val normalizedMap = map.entries.associate { (key, value) -> key.toString() to value }

        val conclusion = (normalizedMap["conclusion"] as? String)
            ?.trim()
            ?.uppercase()
            ?.let { rawConclusion -> VerificationConclusion.entries.firstOrNull { it.name == rawConclusion } }
            ?: return null

        val runId = (normalizedMap["runId"] as? String)?.trim().orEmpty()
        val summary = (normalizedMap["summary"] as? String)?.trim().orEmpty()
        val at = (normalizedMap["at"] as? String)?.trim().orEmpty()

        if (runId.isBlank() || summary.isBlank() || at.isBlank()) {
            return null
        }

        return TaskVerificationResult(
            conclusion = conclusion,
            runId = runId,
            summary = summary,
            at = at,
        )
    }

    private fun normalizeTaskIdList(raw: Any?): List<String> {
        val values = when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is List<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }

        return values
            .map { value -> value.trim().uppercase() }
            .filter { value -> value.isNotBlank() }
            .mapNotNull { value -> normalizeTaskIdOrNull(value) }
            .distinct()
            .sorted()
    }

    private fun normalizePathList(raw: Any?): List<String> {
        val values = when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is List<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }

        return values
            .map { value -> value.trim().replace('\\', '/') }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun normalizeTaskIdOrNull(raw: String): String? {
        val match = TASK_ID_FUZZY_REGEX.find(raw) ?: return null
        val sequence = match.groupValues[1].toIntOrNull() ?: return null
        if (sequence <= 0) {
            return null
        }
        return "T-" + sequence.toString().padStart(3, '0')
    }

    private fun findFenceEndIndex(lines: MutableList<String>, startIndex: Int): Int {
        var index = startIndex + 1
        while (index < lines.size) {
            if (isFenceDelimiterLine(lines[index])) {
                return index
            }
            index += 1
        }
        lines.add("```")
        return lines.lastIndex
    }

    private fun collectTaskIds(lines: List<String>): MutableSet<String> {
        val ids = mutableSetOf<String>()
        lines.forEach { line ->
            if (TASK_HEADING_PREFIX_REGEX.matches(line)) {
                normalizeTaskIdOrNull(line)?.let { ids += it }
            }
        }
        return ids
    }

    private fun taskSequenceOrNull(taskId: String): Int? {
        if (!taskId.startsWith("T-")) return null
        return taskId.removePrefix("T-").toIntOrNull()
    }

    private fun canonicalHeading(taskId: String, title: String): String {
        val normalizedTitle = title.trim().ifBlank { "TODO" }
        return "### $taskId: $normalizedTitle"
    }

    private fun canonicalizeHeading(raw: String, allocateId: () -> String): String {
        val headingText = raw.replaceFirst(HEADING_PREFIX_TRIM_REGEX, "").trim()
        val id = normalizeTaskIdOrNull(headingText) ?: allocateId()
        val title = headingText
            .replaceFirst(TASK_ID_FUZZY_REGEX, "")
            .trim()
            .trimStart(':', '-', '—')
            .trim()
            .ifBlank { "TODO" }
        return canonicalHeading(id, title)
    }

    private fun defaultSpecTaskFenceLines(): List<String> {
        return listOf(
            "```spec-task",
            "status: ${TaskStatus.PENDING.name}",
            "priority: ${TaskPriority.P1.name}",
            "dependsOn: []",
            "relatedFiles: []",
            "verificationResult: null",
            "```",
        )
    }

    private fun renderTaskMetadataLines(metadata: SanitizedTaskMetadata): List<String> {
        val buffer = buildString {
            append("status: ${metadata.status.name}\n")
            append("priority: ${metadata.priority.name}\n")
            append(renderStringListField("dependsOn", metadata.dependsOn))
            append(renderStringListField("relatedFiles", metadata.relatedFiles))
            append(renderVerificationResultField(metadata.verificationResult))
        }
        return buffer.removeSuffix("\n").split('\n')
    }

    private fun renderStringListField(fieldName: String, values: List<String>): String {
        if (values.isEmpty()) {
            return "$fieldName: []\n"
        }
        return buildString {
            append("$fieldName:\n")
            values.forEach { value ->
                append("  - ")
                append(value)
                append('\n')
            }
        }
    }

    private fun renderVerificationResultField(verificationResult: TaskVerificationResult?): String {
        if (verificationResult == null) {
            return "verificationResult: null\n"
        }
        return SpecYamlCodec.encodeMap(
            linkedMapOf(
                "verificationResult" to linkedMapOf(
                    "conclusion" to verificationResult.conclusion.name,
                    "runId" to verificationResult.runId,
                    "summary" to verificationResult.summary,
                    "at" to verificationResult.at,
                ),
            ),
        )
    }

    private fun isSpecTaskFenceStartLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            val suffix = trimmed.trimStart('`').trim()
            return suffix.equals("spec-task", ignoreCase = true)
        }
        if (trimmed.startsWith("~~~")) {
            val suffix = trimmed.trimStart('~').trim()
            return suffix.equals("spec-task", ignoreCase = true)
        }
        return false
    }

    private fun isFenceDelimiterLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("```") || trimmed.startsWith("~~~")
    }

    private fun normalizeLineEndings(markdown: String): String {
        return markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    companion object {
        const val TRIGGER_EDITOR_POPUP: String = "editor-popup"
        const val TRIGGER_GATE_QUICK_FIX: String = "gate-quick-fix"

        private val CANONICAL_TASK_HEADING_REGEX = Regex("""^\s{0,3}###\s+(T-\d{3}):\s+(.+?)\s*$""")
        private val TASK_HEADING_PREFIX_REGEX = Regex("""^\s{0,3}###\s+.*$""")
        private val HEADING_PREFIX_TRIM_REGEX = Regex("""^\s{0,3}###\s+""")
        private val TASK_ID_FUZZY_REGEX = Regex("""(?i)\bT-(\d{1,3})\b""")
    }
}
