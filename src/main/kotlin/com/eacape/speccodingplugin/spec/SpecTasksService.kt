package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SpecTasksService(private val project: Project) {
    private val artifactService: SpecArtifactService by lazy { SpecArtifactService(project) }
    private val storage: SpecStorage by lazy { SpecStorage.getInstance(project) }

    data class TaskSection(
        val entry: SpecTaskMarkdownParser.ParsedTaskEntry,
        val sourceOrder: Int,
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val metadataFenceStartOffsetInSection: Int,
        val metadataFenceEndOffsetExclusiveInSection: Int,
        val metadataContentStartOffsetInSection: Int?,
        val metadataContentEndOffsetExclusiveInSection: Int?,
        val sectionMarkdown: String,
        val bodyMarkdown: String,
    ) {
        val task: StructuredTask?
            get() = entry.toStructuredTaskOrNull()
    }

    data class ParsedTasksDocument(
        val normalizedMarkdown: String,
        val preambleMarkdown: String,
        val taskSectionsInSourceOrder: List<TaskSection>,
        val trailingMarkdown: String,
        val issues: List<SpecTaskMarkdownParser.ParseIssue>,
    ) {
        val taskSectionsById: List<TaskSection>
            get() = taskSectionsInSourceOrder.sortedBy { it.entry.id }

        val tasksById: List<StructuredTask>
            get() = taskSectionsById.mapNotNull(TaskSection::task)

        fun renderStable(): String {
            if (taskSectionsInSourceOrder.isEmpty()) {
                return normalizedMarkdown
            }
            return buildString {
                append(preambleMarkdown)
                taskSectionsById.forEach { section ->
                    append(section.sectionMarkdown)
                }
                append(trailingMarkdown)
            }
        }
    }

    fun parseDocument(markdown: String): ParsedTasksDocument {
        val parsedMarkdown = SpecMarkdownAstParser.parse(markdown)
        synchronized(parsedDocumentCache) {
            parsedDocumentCache[parsedMarkdown.normalizedMarkdown]?.let { cached ->
                return cached
            }
        }
        ProgressIndicatorProvider.getGlobalProgressIndicator()?.text2 = "Resolving structured tasks"
        val parsedTasks = SpecTaskMarkdownParser.parse(parsedMarkdown)
        if (parsedTasks.tasks.isEmpty()) {
            val parsedDocument = ParsedTasksDocument(
                normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
                preambleMarkdown = parsedMarkdown.normalizedMarkdown,
                taskSectionsInSourceOrder = emptyList(),
                trailingMarkdown = "",
                issues = parsedTasks.issues,
            )
            synchronized(parsedDocumentCache) {
                parsedDocumentCache[parsedMarkdown.normalizedMarkdown] = parsedDocument
            }
            return parsedDocument
        }

        val lineTable = buildLineTable(parsedMarkdown.normalizedMarkdown)
        val codeFenceLineRanges = parsedMarkdown.codeFences.map { fence ->
            fence.location.startLine..fence.location.endLine
        }
        val headings = collectHeadings(
            normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
            codeFenceLineRanges = codeFenceLineRanges,
        )
        val specTaskFencesByStartLine = parsedMarkdown.codeFences
            .filter { fence ->
                fence.language.equals(SPEC_TASK_LANGUAGE, ignoreCase = true)
            }
            .associateBy { fence -> fence.location.startLine }

        val taskSectionsInSourceOrder = parsedTasks.tasks.mapIndexed { sourceOrder, taskEntry ->
            ProgressManager.checkCanceled()
            reportProgress(
                detail = "Building structured task sections",
                completed = sourceOrder,
                total = parsedTasks.tasks.size.coerceAtLeast(1),
            )
            val startOffset = lineTable.startOffsets.getOrElse(taskEntry.headingLine - 1) { 0 }
            val endOffsetExclusive = nextSectionStartOffset(
                taskEntry = taskEntry,
                headings = headings,
                lineTable = lineTable,
                fallbackEndOffset = parsedMarkdown.normalizedMarkdown.length,
            )
            val metadataFence = specTaskFencesByStartLine[taskEntry.metadataLocation.startLine]
            val bodyStartOffset = metadataFence?.location?.endOffsetExclusive ?: startOffset
            TaskSection(
                entry = taskEntry,
                sourceOrder = sourceOrder,
                startOffset = startOffset,
                endOffsetExclusive = endOffsetExclusive,
                metadataFenceStartOffsetInSection = metadataFence
                    ?.location
                    ?.startOffset
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset)
                    ?: 0,
                metadataFenceEndOffsetExclusiveInSection = metadataFence
                    ?.location
                    ?.endOffsetExclusive
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset)
                    ?: 0,
                metadataContentStartOffsetInSection = metadataFence
                    ?.contentLocation
                    ?.startOffset
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset),
                metadataContentEndOffsetExclusiveInSection = metadataFence
                    ?.contentLocation
                    ?.endOffsetExclusive
                    ?.minus(startOffset)
                    ?.coerceIn(0, endOffsetExclusive - startOffset),
                sectionMarkdown = parsedMarkdown.normalizedMarkdown.substring(startOffset, endOffsetExclusive),
                bodyMarkdown = parsedMarkdown.normalizedMarkdown.substring(
                    startIndex = bodyStartOffset.coerceAtMost(endOffsetExclusive),
                    endIndex = endOffsetExclusive,
                ),
            )
        }

        val parsedDocument = ParsedTasksDocument(
            normalizedMarkdown = parsedMarkdown.normalizedMarkdown,
            preambleMarkdown = parsedMarkdown.normalizedMarkdown.substring(0, taskSectionsInSourceOrder.first().startOffset),
            taskSectionsInSourceOrder = taskSectionsInSourceOrder,
            trailingMarkdown = parsedMarkdown.normalizedMarkdown.substring(taskSectionsInSourceOrder.last().endOffsetExclusive),
            issues = parsedTasks.issues,
        )
        synchronized(parsedDocumentCache) {
            parsedDocumentCache[parsedMarkdown.normalizedMarkdown] = parsedDocument
        }
        return parsedDocument
    }

    fun readTasksDocument(workflowId: String): ParsedTasksDocument? {
        return artifactService.readArtifact(workflowId, StageId.TASKS)?.let(::parseDocument)
    }

    fun parse(workflowId: String): List<StructuredTask> {
        return readTasksDocument(workflowId)?.tasksById.orEmpty()
    }

    fun stabilizeOutput(markdown: String): String {
        return parseDocument(markdown).renderStable()
    }

    fun stabilizeTaskArtifact(workflowId: String): ParsedTasksDocument? {
        val parsedDocument = readTasksDocument(workflowId) ?: return null
        val stableMarkdown = parsedDocument.renderStable()
        if (stableMarkdown != parsedDocument.normalizedMarkdown) {
            artifactService.writeArtifact(workflowId, StageId.TASKS, stableMarkdown)
        }
        return if (stableMarkdown == parsedDocument.normalizedMarkdown) {
            parsedDocument
        } else {
            parseDocument(stableMarkdown)
        }
    }

    fun addTask(
        workflowId: String,
        title: String,
        priority: TaskPriority,
        dependsOn: List<String> = emptyList(),
    ): StructuredTask {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "title cannot be blank." }

        val editableDocument = loadEditableDocument(workflowId)
        val nextTaskId = allocateNextTaskId(editableDocument.tasksById)
        val normalizedDependsOn = normalizeDependsOn(
            taskId = nextTaskId,
            dependsOn = dependsOn,
            existingTaskIds = editableDocument.tasksById.map(StructuredTask::id).toSet(),
        )
        val task = StructuredTask(
            id = nextTaskId,
            title = normalizedTitle,
            status = TaskStatus.PENDING,
            priority = priority,
            dependsOn = normalizedDependsOn,
            relatedFiles = emptyList(),
            verificationResult = null,
        )

        val updatedMarkdown = if (editableDocument.taskSectionsInSourceOrder.isEmpty()) {
            insertTaskSection(
                normalizedMarkdown = editableDocument.normalizedMarkdown,
                renderedTaskSection = renderTaskSection(task),
            )
        } else {
            buildString {
                append(editableDocument.preambleMarkdown)
                editableDocument.taskSectionsInSourceOrder.forEach { section ->
                    append(section.sectionMarkdown)
                }
                append(renderTaskSection(task))
                append(editableDocument.trailingMarkdown)
            }
        }

        val stableMarkdown = stabilizeOutput(updatedMarkdown)
        artifactService.writeArtifact(workflowId, StageId.TASKS, stableMarkdown)
        return parseDocument(stableMarkdown)
            .tasksById
            .first { existingTask -> existingTask.id == nextTaskId }
    }

    fun removeTask(workflowId: String, taskId: String) {
        val normalizedTaskId = normalizeTaskId(taskId)

        val editableDocument = loadEditableDocument(workflowId)
        val remainingSections = editableDocument.taskSectionsInSourceOrder
            .filterNot { section -> section.entry.id == normalizedTaskId }
        if (remainingSections.size == editableDocument.taskSectionsInSourceOrder.size) {
            throw MissingStructuredTaskError(normalizedTaskId)
        }

        val updatedMarkdown = buildString {
            append(editableDocument.preambleMarkdown)
            remainingSections.forEach { section ->
                append(section.sectionMarkdown)
            }
            append(editableDocument.trailingMarkdown)
        }
        artifactService.writeArtifact(
            workflowId,
            StageId.TASKS,
            stabilizeOutput(updatedMarkdown),
        )
    }

    fun transitionStatus(
        workflowId: String,
        taskId: String,
        to: TaskStatus,
        reason: String? = null,
        auditContext: Map<String, String> = emptyMap(),
    ) {
        val normalizedReason = reason
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        updateTaskMetadata(workflowId, taskId) { targetTask, editableDocument ->
            if (!targetTask.status.canTransitionTo(to)) {
                throw InvalidTaskStateTransitionError(targetTask.id, targetTask.status, to)
            }
            if (to == TaskStatus.CANCELLED) {
                val cancellationConstraint = SpecTaskDependencyRules.cancellationConstraint(
                    task = targetTask,
                    tasks = editableDocument.tasksById,
                )
                if (!cancellationConstraint.cancellable) {
                    throw TaskCancellationBlockedByDependentsError(
                        targetTask.id,
                        cancellationConstraint.blockingDependentTaskIds,
                    )
                }
            }
            val updatedTask = targetTask.copy(status = to)
            val details = linkedMapOf(
                "taskId" to targetTask.id,
                "title" to targetTask.title,
                "fromStatus" to targetTask.status.name,
                "toStatus" to to.name,
            )
            normalizedReason?.let { trimmedReason ->
                details["reason"] = trimmedReason
            }
            TaskMetadataUpdate(
                updatedTask = updatedTask,
                auditEventType = SpecAuditEventType.TASK_STATUS_CHANGED,
                auditDetails = mergeAuditDetails(details, auditContext),
            )
        }
    }

    fun updateDependsOn(
        workflowId: String,
        taskId: String,
        dependsOn: List<String>,
    ): StructuredTask {
        return updateTaskMetadata(workflowId, taskId) { targetTask, editableDocument ->
            val normalizedDependsOn = normalizeDependsOn(
                taskId = targetTask.id,
                dependsOn = dependsOn,
                existingTaskIds = editableDocument.tasksById.map(StructuredTask::id).toSet(),
            )
            TaskMetadataUpdate(
                updatedTask = targetTask.copy(dependsOn = normalizedDependsOn),
            )
        }
    }

    fun updateRelatedFiles(
        workflowId: String,
        taskId: String,
        files: List<String>,
        auditContext: Map<String, String> = emptyMap(),
    ): StructuredTask {
        return updateTaskMetadata(workflowId, taskId) { targetTask, _ ->
            val normalizedFiles = normalizeRelatedFiles(targetTask.id, files)
            TaskMetadataUpdate(
                updatedTask = targetTask.copy(relatedFiles = normalizedFiles),
                auditEventType = SpecAuditEventType.RELATED_FILES_UPDATED,
                auditDetails = mergeAuditDetails(
                    linkedMapOf(
                        "taskId" to targetTask.id,
                        "title" to targetTask.title,
                        "fileCount" to normalizedFiles.size.toString(),
                        "previousRelatedFiles" to targetTask.relatedFiles.joinToString(", "),
                        "relatedFiles" to normalizedFiles.joinToString(", "),
                    ),
                    auditContext,
                ),
            )
        }
    }

    fun updateVerificationResult(
        workflowId: String,
        taskId: String,
        verificationResult: TaskVerificationResult,
        auditContext: Map<String, String> = emptyMap(),
    ): StructuredTask {
        return updateTaskMetadata(workflowId, taskId) { targetTask, _ ->
            val normalizedVerificationResult = normalizeVerificationResult(
                taskId = targetTask.id,
                verificationResult = verificationResult,
            )
            val details = linkedMapOf(
                "taskId" to targetTask.id,
                "title" to targetTask.title,
                "action" to if (targetTask.verificationResult == null) "SET" else "UPDATED",
                "conclusion" to normalizedVerificationResult.conclusion.name,
                "runId" to normalizedVerificationResult.runId,
                "at" to normalizedVerificationResult.at,
            )
            targetTask.verificationResult?.let { previousVerificationResult ->
                details["previousConclusion"] = previousVerificationResult.conclusion.name
                details["previousRunId"] = previousVerificationResult.runId
                details["previousAt"] = previousVerificationResult.at
            }
            TaskMetadataUpdate(
                updatedTask = targetTask.copy(verificationResult = normalizedVerificationResult),
                auditEventType = SpecAuditEventType.TASK_VERIFICATION_RESULT_UPDATED,
                auditDetails = mergeAuditDetails(details, auditContext),
            )
        }
    }

    fun clearVerificationResult(
        workflowId: String,
        taskId: String,
        auditContext: Map<String, String> = emptyMap(),
    ): StructuredTask {
        return updateTaskMetadata(workflowId, taskId) { targetTask, _ ->
            val details = linkedMapOf(
                "taskId" to targetTask.id,
                "title" to targetTask.title,
                "action" to "CLEARED",
            )
            targetTask.verificationResult?.let { previousVerificationResult ->
                details["previousConclusion"] = previousVerificationResult.conclusion.name
                details["previousRunId"] = previousVerificationResult.runId
                details["previousAt"] = previousVerificationResult.at
            }
            TaskMetadataUpdate(
                updatedTask = targetTask.copy(verificationResult = null),
                auditEventType = SpecAuditEventType.TASK_VERIFICATION_RESULT_UPDATED,
                auditDetails = mergeAuditDetails(details, auditContext),
            )
        }
    }

    private data class TaskMetadataUpdate(
        val updatedTask: StructuredTask,
        val auditEventType: SpecAuditEventType? = null,
        val auditDetails: Map<String, String> = emptyMap(),
    )

    private fun updateTaskMetadata(
        workflowId: String,
        taskId: String,
        transform: (StructuredTask, ParsedTasksDocument) -> TaskMetadataUpdate,
    ): StructuredTask {
        val normalizedTaskId = normalizeTaskId(taskId)
        val editableDocument = loadEditableDocument(workflowId)
        val targetSection = editableDocument.taskSectionsInSourceOrder
            .firstOrNull { section -> section.entry.id == normalizedTaskId }
            ?: throw MissingStructuredTaskError(normalizedTaskId)
        val targetTask = targetSection.task
            ?: throw InvalidTasksArtifactEditError("task $normalizedTaskId is missing required spec-task metadata")
        val update = transform(targetTask, editableDocument)
        if (update.updatedTask == targetTask) {
            return targetTask
        }

        val updatedMarkdown = renderDocumentWithUpdatedTask(
            editableDocument = editableDocument,
            taskId = normalizedTaskId,
            task = update.updatedTask,
        )
        artifactService.writeArtifact(workflowId, StageId.TASKS, updatedMarkdown)
        update.auditEventType?.let { eventType ->
            storage.appendAuditEvent(
                workflowId = workflowId,
                eventType = eventType,
                details = update.auditDetails,
            ).getOrThrow()
        }
        return update.updatedTask
    }

    private fun renderDocumentWithUpdatedTask(
        editableDocument: ParsedTasksDocument,
        taskId: String,
        task: StructuredTask,
    ): String {
        return buildString {
            append(editableDocument.preambleMarkdown)
            editableDocument.taskSectionsInSourceOrder.forEach { section ->
                append(
                    if (section.entry.id == taskId) {
                        renderUpdatedTaskSection(section, task)
                    } else {
                        section.sectionMarkdown
                    },
                )
            }
            append(editableDocument.trailingMarkdown)
        }
    }

    private fun normalizeDependsOn(
        taskId: String,
        dependsOn: List<String>,
        existingTaskIds: Set<String>,
    ): List<String> {
        val normalizedDependencies = dependsOn
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::normalizeTaskId)
            .distinct()
            .sorted()

        if (taskId in normalizedDependencies) {
            throw TaskSelfDependencyError(taskId)
        }
        normalizedDependencies.firstOrNull { dependencyId -> dependencyId !in existingTaskIds }
            ?.let { missingDependencyId ->
                throw MissingTaskDependencyError(taskId, missingDependencyId)
            }
        return normalizedDependencies
    }

    private fun normalizeRelatedFiles(taskId: String, files: List<String>): List<String> {
        val projectRoot = resolveProjectRoot()
        return files
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { rawPath -> normalizeRelatedFile(taskId, rawPath, projectRoot) }
            .distinct()
            .sorted()
    }

    private fun mergeAuditDetails(
        base: LinkedHashMap<String, String>,
        extra: Map<String, String>,
    ): LinkedHashMap<String, String> {
        if (extra.isEmpty()) {
            return base
        }
        extra.entries
            .asSequence()
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .sortedBy { (key, _) -> key }
            .forEach { (key, value) ->
                base[key] = value
            }
        return base
    }

    private fun normalizeVerificationResult(
        taskId: String,
        verificationResult: TaskVerificationResult,
    ): TaskVerificationResult {
        val runId = verificationResult.runId.trim()
            .takeIf(String::isNotEmpty)
            ?: throw InvalidTaskVerificationResultError(taskId, "runId", "must be a non-blank string")
        val summary = verificationResult.summary.trim()
            .takeIf(String::isNotEmpty)
            ?: throw InvalidTaskVerificationResultError(taskId, "summary", "must be a non-blank string")
        val at = verificationResult.at.trim()
            .takeIf(String::isNotEmpty)
            ?: throw InvalidTaskVerificationResultError(taskId, "at", "must be a non-blank string")
        return verificationResult.copy(
            runId = runId,
            summary = summary,
            at = at,
        )
    }

    private fun resolveProjectRoot(): Path {
        val basePath = project.basePath
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw InvalidTasksArtifactEditError("project base path is unavailable for relatedFiles normalization")
        return try {
            Path.of(basePath).toAbsolutePath().normalize()
        } catch (error: InvalidPathException) {
            throw InvalidTasksArtifactEditError(
                "project base path is invalid for relatedFiles normalization: ${error.message ?: basePath}",
            )
        }
    }

    private fun normalizeRelatedFile(taskId: String, rawPath: String, projectRoot: Path): String {
        val trimmedPath = rawPath.trim()
        if (trimmedPath.any { character -> character == '\u0000' || character == '\r' || character == '\n' }) {
            throw InvalidTaskRelatedFileError(
                taskId,
                rawPath,
                "path must not contain line breaks or NUL characters",
            )
        }
        val unifiedPath = trimmedPath.replace('\\', '/')
        val resolvedPath = try {
            val candidate = if (isAbsolutePath(unifiedPath)) {
                Path.of(unifiedPath)
            } else {
                projectRoot.resolve(unifiedPath)
            }
            candidate.normalize().toAbsolutePath()
        } catch (error: InvalidPathException) {
            throw InvalidTaskRelatedFileError(taskId, rawPath, error.message)
        }
        if (!resolvedPath.startsWith(projectRoot)) {
            throw RelatedFileOutsideProjectRootError(taskId, rawPath, projectRoot.toString())
        }
        val relativePath = projectRoot.relativize(resolvedPath)
            .joinToString(separator = "/") { segment -> segment.toString() }
            .trim()
        if (relativePath.isEmpty() || relativePath == ".") {
            throw InvalidTaskRelatedFileError(taskId, rawPath, "path must point inside the project root")
        }
        return relativePath
    }

    private fun isAbsolutePath(path: String): Boolean {
        return path.startsWith('/') || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path) || path.startsWith("//")
    }

    private fun nextSectionStartOffset(
        taskEntry: SpecTaskMarkdownParser.ParsedTaskEntry,
        headings: List<HeadingBoundary>,
        lineTable: LineTable,
        fallbackEndOffset: Int,
    ): Int {
        val nextBoundaryLine = headings.firstOrNull { heading ->
            heading.lineNumber > taskEntry.headingLine &&
                (heading.level < TASK_HEADING_LEVEL || heading.isCanonicalTaskHeading)
        }?.lineNumber
        return if (nextBoundaryLine == null) {
            fallbackEndOffset
        } else {
            lineTable.startOffsets[nextBoundaryLine - 1]
        }
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
            return LineTable(lines = emptyList(), startOffsets = intArrayOf(0))
        }

        val lines = mutableListOf<String>()
        val startOffsets = mutableListOf(0)
        var lineStart = 0
        markdown.forEachIndexed { index, char ->
            if (char != '\n') {
                return@forEachIndexed
            }
            lines += markdown.substring(lineStart, index)
            lineStart = index + 1
            startOffsets += lineStart
        }
        lines += markdown.substring(lineStart)

        return LineTable(
            lines = lines,
            startOffsets = startOffsets.toIntArray(),
        )
    }

    private fun loadEditableDocument(workflowId: String): ParsedTasksDocument {
        val markdown = artifactService.readArtifact(workflowId, StageId.TASKS)
            ?.takeIf { content -> content.isNotBlank() }
            ?: EMPTY_TASKS_DOCUMENT
        val parsedDocument = parseDocument(markdown)
        if (parsedDocument.issues.isNotEmpty()) {
            val details = parsedDocument.issues
                .take(MAX_EDIT_ISSUES_TO_REPORT)
                .joinToString(separator = "; ") { issue ->
                    "line ${issue.line}: ${issue.message}"
                }
            throw InvalidTasksArtifactEditError(details)
        }

        parsedDocument.tasksById
            .groupingBy(StructuredTask::id)
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }
            ?.let { (duplicateTaskId, _) ->
                throw DuplicateTaskIdError(duplicateTaskId)
            }

        return parsedDocument
    }

    private fun allocateNextTaskId(tasks: List<StructuredTask>): String {
        val currentMaxSequence = tasks
            .maxOfOrNull { task -> task.id.removePrefix(TASK_ID_PREFIX).toInt() }
            ?: 0
        val nextSequence = currentMaxSequence + 1
        require(nextSequence <= MAX_TASK_SEQUENCE) {
            "Task id limit exceeded: ${TASK_ID_PREFIX}${nextSequence.toString().padStart(3, '0')}"
        }
        return TASK_ID_PREFIX + nextSequence.toString().padStart(3, '0')
    }

    private fun normalizeTaskId(taskId: String): String {
        val normalizedTaskId = taskId.trim().uppercase()
        require(TASK_ID_REGEX.matches(normalizedTaskId)) {
            "taskId must use the format T-001."
        }
        return normalizedTaskId
    }

    private fun renderUpdatedTaskSection(section: TaskSection, task: StructuredTask): String {
        val updatedMetadata = renderTaskMetadata(task).removeSuffix("\n")
        val metadataContentStart = section.metadataContentStartOffsetInSection
        val metadataContentEndExclusive = section.metadataContentEndOffsetExclusiveInSection
        if (metadataContentStart != null && metadataContentEndExclusive != null) {
            return buildString {
                append(section.sectionMarkdown.substring(0, metadataContentStart))
                append(updatedMetadata)
                append(section.sectionMarkdown.substring(metadataContentEndExclusive))
            }
        }

        return buildString {
            append(section.sectionMarkdown.substring(0, section.metadataFenceStartOffsetInSection))
            append(renderTaskMetadataFence(task))
            append(section.sectionMarkdown.substring(section.metadataFenceEndOffsetExclusiveInSection))
        }
    }

    private fun insertTaskSection(
        normalizedMarkdown: String,
        renderedTaskSection: String,
    ): String {
        val insertionBase = normalizedMarkdown.takeIf { markdown -> markdown.isNotBlank() } ?: EMPTY_TASKS_DOCUMENT
        val lineTable = buildLineTable(insertionBase)
        val insertionLine = findEmptyTaskInsertionLine(lineTable.lines)
        val insertionOffset = insertionLine
            ?.let { line -> lineTable.startOffsets[line - 1] }
            ?: insertionBase.length
        val prefix = insertionBase.substring(0, insertionOffset)
        val suffix = insertionBase.substring(insertionOffset)

        return buildString {
            append(prefix)
            appendTaskSectionSeparator(prefix)
            append(renderedTaskSection)
            append(suffix)
        }
    }

    private fun StringBuilder.appendTaskSectionSeparator(prefix: String) {
        if (prefix.isEmpty()) {
            return
        }
        if (!prefix.endsWith("\n")) {
            append('\n')
        }
        if (!prefix.endsWith("\n\n")) {
            append('\n')
        }
    }

    private fun findEmptyTaskInsertionLine(lines: List<String>): Int? {
        val taskListHeadingIndex = lines.indexOfFirst { line ->
            TASK_LIST_HEADING_REGEX.matches(line)
        }
        if (taskListHeadingIndex == -1) {
            return null
        }
        for (index in taskListHeadingIndex + 1 until lines.size) {
            val match = HEADING_REGEX.matchEntire(lines[index]) ?: continue
            if (match.groupValues[1].length <= TASK_LIST_HEADING_LEVEL) {
                return index + 1
            }
        }
        return null
    }

    private fun renderTaskSection(task: StructuredTask): String {
        return buildString {
            append("### ${task.id}: ${task.title}\n")
            append(renderTaskMetadataFence(task))
            append('\n')
        }
    }

    private fun renderTaskMetadataFence(task: StructuredTask): String {
        return buildString {
            append("```")
            append(SPEC_TASK_LANGUAGE)
            append('\n')
            append(renderTaskMetadata(task))
            append("```\n")
        }
    }

    private fun renderTaskMetadata(task: StructuredTask): String {
        return buildString {
            append("status: ${task.status}\n")
            append("priority: ${task.priority}\n")
            append(renderStringListField("dependsOn", task.dependsOn))
            append(renderStringListField("relatedFiles", task.relatedFiles))
            append(renderVerificationResultField(task.verificationResult))
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

    private data class LineTable(
        val lines: List<String>,
        val startOffsets: IntArray,
    )

    private data class HeadingBoundary(
        val lineNumber: Int,
        val level: Int,
        val isCanonicalTaskHeading: Boolean,
    )

    companion object {
        private const val SPEC_TASK_LANGUAGE = "spec-task"
        private const val TASK_HEADING_LEVEL = 3
        private const val TASK_LIST_HEADING_LEVEL = 2
        private const val TASK_ID_PREFIX = "T-"
        private const val MAX_TASK_SEQUENCE = 999
        private const val MAX_EDIT_ISSUES_TO_REPORT = 3
        private const val MAX_PARSED_DOCUMENT_CACHE_ENTRIES = 24
        private const val EMPTY_TASKS_DOCUMENT = "# Implement Document\n\n## Task List\n"
        private val HEADING_REGEX = Regex("""^\s{0,3}(#{1,6})\s+.*$""")
        private val CANONICAL_TASK_HEADING_REGEX = Regex("""^\s{0,3}###\s+T-\d{3}:\s+.+$""")
        private val TASK_ID_REGEX = Regex("""^T-\d{3}$""")
        private val TASK_LIST_HEADING_REGEX = Regex("""^\s{0,3}##\s+(Task\s+List|任务列表)\s*$""")
        private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("""^[A-Za-z]:/.*$""")

        fun getInstance(project: Project): SpecTasksService = project.service()
    }

    private val parsedDocumentCache =
        object : LinkedHashMap<String, ParsedTasksDocument>(MAX_PARSED_DOCUMENT_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedTasksDocument>?): Boolean {
                return size > MAX_PARSED_DOCUMENT_CACHE_ENTRIES
            }
        }

    private fun reportProgress(detail: String, completed: Int, total: Int) {
        val indicator = ProgressIndicatorProvider.getGlobalProgressIndicator() ?: return
        if (completed == 0 || completed == total - 1 || completed % 10 == 0) {
            indicator.isIndeterminate = false
            indicator.text2 = detail
            indicator.fraction = completed.toDouble() / total.toDouble()
        }
    }
}
