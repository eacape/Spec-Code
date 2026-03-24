package com.eacape.speccodingplugin.spec

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.Base64
import java.util.Comparator
import java.util.UUID

enum class SpecSnapshotConsistencyIssueKind {
    MISSING_METADATA,
    INVALID_METADATA,
    MISSING_ARTIFACT,
}

data class SpecSnapshotConsistencyIssue(
    val workflowId: String,
    val snapshotId: String,
    val snapshotPath: Path,
    val kind: SpecSnapshotConsistencyIssueKind,
    val artifactFileName: String? = null,
    val detail: String? = null,
)

/**
 * Spec 文档存储管理器
 * 负责 Spec 文档的文件系统存储和读取
 */
class SpecStorage(
    project: Project,
    private val atomicFileIO: AtomicFileIO = AtomicFileIO(),
    private val workspaceInitializer: SpecWorkspaceInitializer = SpecWorkspaceInitializer(project),
    private val lockManager: SpecFileLockManager = SpecFileLockManager(workspaceInitializer),
) {
    data class WorkflowMetadataWriteResult(
        val path: Path,
        val beforeSnapshotId: String,
        val afterSnapshotId: String,
    )

    private val logger = thisLogger()
    private val yamlCodec = SpecYamlCodec

    /**
     * 保存 Spec 文档
     */
    fun saveDocument(workflowId: String, document: SpecDocument): Result<Path> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val workflowDir = workspaceInitializer
                    .initializeWorkflowWorkspace(workflowId)
                    .workflowDir

                val filePath = workflowDir.resolve(document.phase.outputFileName)
                val content = formatDocument(document)
                val operationId = UUID.randomUUID().toString()
                val beforeSnapshot = captureWorkflowSnapshot(
                    workflowId = workflowId,
                    trigger = SpecSnapshotTrigger.DOCUMENT_SAVE_BEFORE,
                    operationId = operationId,
                    phase = document.phase,
                )

                atomicFileIO.writeString(filePath, content, StandardCharsets.UTF_8)
                val snapshotId = saveDocumentSnapshot(workflowId, document, content)
                val afterSnapshot = captureWorkflowSnapshot(
                    workflowId = workflowId,
                    trigger = SpecSnapshotTrigger.DOCUMENT_SAVE_AFTER,
                    operationId = operationId,
                    phase = document.phase,
                )
                appendAuditEntry(
                    eventType = SpecAuditEventType.DOCUMENT_SAVED,
                    workflowId = workflowId,
                    details = mapOf(
                        "phase" to document.phase.name,
                        "documentSnapshotId" to snapshotId,
                        "beforeSnapshotId" to beforeSnapshot.snapshotId,
                        "afterSnapshotId" to afterSnapshot.snapshotId,
                        "file" to document.phase.outputFileName,
                    ),
                )
                logger.info("Saved ${document.phase} document to $filePath")

                filePath
            }
        }
    }

    fun listDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
    ): List<SpecDocumentHistoryEntry> {
        val historyDir = getHistoryDirectory(workflowId, phase)
        if (!Files.exists(historyDir)) {
            return emptyList()
        }

        return Files.list(historyDir).use { stream ->
            val entries = mutableListOf<SpecDocumentHistoryEntry>()
            stream.forEach { path ->
                if (!Files.isRegularFile(path)) {
                    return@forEach
                }
                val name = path.fileName.toString()
                if (!name.endsWith(".md")) {
                    return@forEach
                }
                val snapshotId = name.removeSuffix(".md")
                val createdAt = snapshotId.toLongOrNull() ?: return@forEach
                entries.add(
                    SpecDocumentHistoryEntry(
                        snapshotId = snapshotId,
                        phase = phase,
                        createdAt = createdAt,
                    )
                )
            }
            entries.sortedByDescending { it.createdAt }
        }
    }

    fun loadDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<SpecDocument> {
        return runCatching {
            val snapshotPath = getHistoryDirectory(workflowId, phase).resolve("$snapshotId.md")
            if (!Files.exists(snapshotPath)) {
                throw IllegalStateException("Document snapshot not found: $snapshotPath")
            }

            val content = Files.readString(snapshotPath, StandardCharsets.UTF_8)
            parseDocument(workflowId, phase, content)
        }
    }

    fun deleteDocumentSnapshot(
        workflowId: String,
        phase: SpecPhase,
        snapshotId: String,
    ): Result<Unit> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val snapshotPath = getHistoryDirectory(workflowId, phase).resolve("$snapshotId.md")
                if (!Files.exists(snapshotPath)) {
                    throw IllegalStateException("Document snapshot not found: $snapshotPath")
                }
                Files.delete(snapshotPath)
                cleanupIfEmpty(getHistoryDirectory(workflowId, phase))
                appendAuditEntry(
                    eventType = SpecAuditEventType.SNAPSHOT_DELETED,
                    workflowId = workflowId,
                    details = mapOf(
                        "phase" to phase.name,
                        "snapshotId" to snapshotId,
                    ),
                )
            }
        }
    }

    fun pruneDocumentHistory(
        workflowId: String,
        phase: SpecPhase,
        keepLatest: Int,
    ): Result<Int> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                require(keepLatest >= 0) { "keepLatest must be >= 0" }
                val history = listDocumentHistory(workflowId, phase)
                val toDelete = history.drop(keepLatest)
                toDelete.forEach { entry ->
                    val path = getHistoryDirectory(workflowId, phase).resolve("${entry.snapshotId}.md")
                    if (Files.exists(path)) {
                        Files.delete(path)
                    }
                }
                cleanupIfEmpty(getHistoryDirectory(workflowId, phase))
                appendAuditEntry(
                    eventType = SpecAuditEventType.HISTORY_PRUNED,
                    workflowId = workflowId,
                    details = mapOf(
                        "phase" to phase.name,
                        "keepLatest" to keepLatest.toString(),
                        "pruned" to toDelete.size.toString(),
                    ),
                )
                toDelete.size
            }
        }
    }

    fun listWorkflowSnapshots(workflowId: String): List<SpecWorkflowSnapshotEntry> {
        val snapshotRoot = getWorkflowSnapshotsDirectory(workflowId)
        if (!Files.exists(snapshotRoot)) {
            return emptyList()
        }

        return Files.list(snapshotRoot).use { stream ->
            val snapshots = mutableListOf<SpecWorkflowSnapshotEntry>()
            stream.forEach { path ->
                if (!Files.isDirectory(path)) {
                    return@forEach
                }
                loadSnapshotEntry(path, workflowId)?.let { snapshots += it }
            }
            snapshots.sortedByDescending { it.createdAt }
        }
    }

    fun checkWorkflowSnapshotConsistency(workflowId: String): List<SpecSnapshotConsistencyIssue> {
        val snapshotRoot = getWorkflowDirectory(workflowId)
            .resolve(".history")
            .resolve("snapshots")
        if (!Files.isDirectory(snapshotRoot)) {
            return emptyList()
        }

        return Files.list(snapshotRoot).use { stream ->
            val issues = mutableListOf<SpecSnapshotConsistencyIssue>()
            stream
                .filter { Files.isDirectory(it) }
                .sorted(Comparator.comparing<Path, String> { it.fileName.toString() })
                .forEach { snapshotDir ->
                    val snapshotId = snapshotDir.fileName.toString()
                    val metadataPath = snapshotDir.resolve(SNAPSHOT_METADATA_FILE_NAME)
                    if (!Files.isRegularFile(metadataPath)) {
                        issues += SpecSnapshotConsistencyIssue(
                            workflowId = workflowId,
                            snapshotId = snapshotId,
                            snapshotPath = snapshotDir,
                            kind = SpecSnapshotConsistencyIssueKind.MISSING_METADATA,
                        )
                        return@forEach
                    }

                    val metadata = runCatching {
                        yamlCodec.decodeMap(Files.readString(metadataPath, StandardCharsets.UTF_8))
                    }.getOrElse { error ->
                        issues += SpecSnapshotConsistencyIssue(
                            workflowId = workflowId,
                            snapshotId = snapshotId,
                            snapshotPath = snapshotDir,
                            kind = SpecSnapshotConsistencyIssueKind.INVALID_METADATA,
                            detail = error.message,
                        )
                        return@forEach
                    }

                    parseStringList(metadata["files"])
                        .sorted()
                        .forEach { fileName ->
                            if (!Files.isRegularFile(snapshotDir.resolve(fileName))) {
                                issues += SpecSnapshotConsistencyIssue(
                                    workflowId = workflowId,
                                    snapshotId = snapshotId,
                                    snapshotPath = snapshotDir,
                                    kind = SpecSnapshotConsistencyIssueKind.MISSING_ARTIFACT,
                                    artifactFileName = fileName,
                                )
                            }
                        }
                }
            issues
        }
    }

    fun loadWorkflowSnapshot(
        workflowId: String,
        snapshotId: String,
    ): Result<SpecWorkflow> {
        return runCatching {
            val snapshotPath = getSnapshotDirectory(workflowId, snapshotId)
            if (!Files.exists(snapshotPath)) {
                throw IllegalStateException("Workflow snapshot not found: $snapshotPath")
            }
            parseSnapshotAsWorkflow(workflowId, snapshotId, snapshotPath)
        }
    }

    fun loadWorkflowSnapshotArtifact(
        workflowId: String,
        snapshotId: String,
        stageId: StageId,
    ): Result<String?> {
        return runCatching {
            val fileName = stageId.artifactFileName
                ?: throw IllegalArgumentException("Stage $stageId has no artifact file.")
            val snapshotPath = getSnapshotDirectory(workflowId, snapshotId)
            if (!Files.exists(snapshotPath)) {
                throw IllegalStateException("Workflow snapshot not found: $snapshotPath")
            }
            val artifactPath = snapshotPath.resolve(fileName)
            if (!Files.isRegularFile(artifactPath)) {
                null
            } else {
                Files.readString(artifactPath, StandardCharsets.UTF_8)
            }
        }
    }

    fun pinDeltaBaseline(
        workflowId: String,
        snapshotId: String,
        label: String? = null,
    ): Result<SpecDeltaBaselineRef> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val snapshotPath = getSnapshotDirectory(workflowId, snapshotId)
                if (!Files.exists(snapshotPath)) {
                    throw IllegalStateException("Workflow snapshot not found: $snapshotPath")
                }
                val baselineId = buildBaselineId()
                val createdAt = System.currentTimeMillis()
                val normalizedLabel = label?.trim()?.takeIf { it.isNotBlank() }
                val baseline = SpecDeltaBaselineRef(
                    baselineId = baselineId,
                    workflowId = workflowId,
                    snapshotId = snapshotId,
                    createdAt = createdAt,
                    label = normalizedLabel,
                )
                val metadataPath = getDeltaBaselinesDirectory(workflowId).resolve("$baselineId.yaml")
                atomicFileIO.writeString(
                    metadataPath,
                    yamlCodec.encodeMap(encodeDeltaBaseline(baseline)),
                    StandardCharsets.UTF_8,
                )
                val auditDetails = linkedMapOf(
                    "baselineId" to baseline.baselineId,
                    "snapshotId" to baseline.snapshotId,
                )
                baseline.label?.let { normalized -> auditDetails["label"] = normalized }
                appendAuditEntry(
                    eventType = SpecAuditEventType.DELTA_BASELINE_SELECTED,
                    workflowId = workflowId,
                    details = auditDetails,
                )
                baseline
            }
        }
    }

    fun listDeltaBaselines(workflowId: String): List<SpecDeltaBaselineRef> {
        val baselineRoot = getDeltaBaselinesDirectory(workflowId)
        if (!Files.exists(baselineRoot)) {
            return emptyList()
        }
        return Files.list(baselineRoot).use { stream ->
            val baselines = mutableListOf<SpecDeltaBaselineRef>()
            stream.forEach { path ->
                if (!Files.isRegularFile(path) || path.fileName.toString().endsWith(".yaml").not()) {
                    return@forEach
                }
                loadDeltaBaseline(path, workflowId)?.let { baselines += it }
            }
            baselines.sortedByDescending { it.createdAt }
        }
    }

    fun loadDeltaBaselineWorkflow(
        workflowId: String,
        baselineId: String,
    ): Result<SpecWorkflow> {
        return runCatching {
            val baselinePath = getDeltaBaselinesDirectory(workflowId).resolve("$baselineId.yaml")
            if (!Files.exists(baselinePath)) {
                throw IllegalStateException("Delta baseline not found: $baselinePath")
            }
            val baseline = loadDeltaBaseline(baselinePath, workflowId)
                ?: throw IllegalStateException("Invalid delta baseline metadata: $baselinePath")
            loadWorkflowSnapshot(baseline.workflowId, baseline.snapshotId).getOrThrow()
        }
    }

    fun loadDeltaBaselineArtifact(
        workflowId: String,
        baselineId: String,
        stageId: StageId,
    ): Result<String?> {
        return runCatching {
            val baselinePath = getDeltaBaselinesDirectory(workflowId).resolve("$baselineId.yaml")
            if (!Files.exists(baselinePath)) {
                throw IllegalStateException("Delta baseline not found: $baselinePath")
            }
            val baseline = loadDeltaBaseline(baselinePath, workflowId)
                ?: throw IllegalStateException("Invalid delta baseline metadata: $baselinePath")
            loadWorkflowSnapshotArtifact(baseline.workflowId, baseline.snapshotId, stageId).getOrThrow()
        }
    }

    fun loadConfigPinSnapshot(workflowId: String, configPinHash: String): Result<SpecConfigPin> {
        return runCatching {
            val normalizedHash = configPinHash.trim().lowercase()
            require(CONFIG_PIN_HASH_PATTERN.matches(normalizedHash)) {
                "Invalid config pin hash: $normalizedHash"
            }
            val workflowWorkspace = workspaceInitializer.initializeWorkflowWorkspace(workflowId)
            val snapshotPath = workflowWorkspace.configSnapshotsDir.resolve("$normalizedHash.yaml")
            require(Files.isRegularFile(snapshotPath)) {
                "Config pin snapshot not found for workflow $workflowId: $normalizedHash"
            }
            SpecConfigPin(
                hash = normalizedHash,
                snapshotYaml = Files.readString(snapshotPath, StandardCharsets.UTF_8),
            )
        }
    }

    /**
     * 加载 Spec 文档
     */
    fun loadDocument(workflowId: String, phase: SpecPhase): Result<SpecDocument> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            val filePath = workflowDir.resolve(phase.outputFileName)

            if (!Files.exists(filePath)) {
                throw IllegalStateException("Document not found: $filePath")
            }

            val content = Files.readString(filePath, StandardCharsets.UTF_8)
            parseDocument(workflowId, phase, content)
        }
    }

    /**
     * 保存工作流状态
     */
    fun saveWorkflow(workflow: SpecWorkflow): Result<Path> {
        return runCatching {
            persistWorkflowMetadata(
                workflow = workflow,
                eventType = SpecAuditEventType.WORKFLOW_SAVED,
            ).path
        }
    }

    fun saveWorkflowTransition(
        workflow: SpecWorkflow,
        eventType: SpecAuditEventType,
        details: Map<String, String> = emptyMap(),
    ): Result<WorkflowMetadataWriteResult> {
        return runCatching {
            persistWorkflowMetadata(
                workflow = workflow,
                eventType = eventType,
                extraAuditDetails = details,
            )
        }
    }

    fun saveConfigPinSnapshot(workflowId: String, configPin: SpecConfigPin): Result<Path> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val normalizedHash = configPin.hash.trim().lowercase()
                require(CONFIG_PIN_HASH_PATTERN.matches(normalizedHash)) {
                    "Invalid config pin hash: $normalizedHash"
                }
                val snapshotContent = configPin.snapshotYaml
                    .replace("\r\n", "\n")
                    .let { content -> if (content.endsWith("\n")) content else "$content\n" }
                val workflowWorkspace = workspaceInitializer.initializeWorkflowWorkspace(workflowId)
                val snapshotPath = workflowWorkspace.configSnapshotsDir.resolve("$normalizedHash.yaml")
                atomicFileIO.writeString(snapshotPath, snapshotContent, StandardCharsets.UTF_8)
                appendAuditEntry(
                    eventType = SpecAuditEventType.CONFIG_PINNED,
                    workflowId = workflowId,
                    details = mapOf(
                        "configPinHash" to normalizedHash,
                        "snapshotFile" to snapshotPath.fileName.toString(),
                    ),
                )
                snapshotPath
            }
        }
    }

    /**
     * 加载工作流状态
     */
    fun loadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            val metadataPath = workflowDir.resolve("workflow.yaml")

            if (!Files.exists(metadataPath)) {
                throw IllegalStateException("Workflow not found: $workflowId")
            }

            val metadata = Files.readString(metadataPath, StandardCharsets.UTF_8)
            parseWorkflowMetadata(workflowId, metadata)
        }
    }

    fun listWorkflowMetadata(): List<WorkflowMeta> {
        return listWorkflows()
            .mapNotNull { workflowId ->
                loadWorkflow(workflowId).getOrNull()?.toWorkflowMeta()
            }
            .sortedWith(
                compareByDescending<WorkflowMeta> { it.updatedAt }
                    .thenBy { it.workflowId },
            )
    }

    fun openWorkflow(workflowId: String): Result<WorkflowSnapshot> {
        return runCatching {
            val workflow = loadWorkflow(workflowId).getOrThrow()
            appendAuditEntry(
                eventType = SpecAuditEventType.WORKFLOW_OPENED,
                workflowId = workflow.id,
                details = mapOf(
                    "currentStage" to workflow.currentStage.name,
                    "status" to workflow.status.name,
                    "template" to workflow.template.name,
                ),
            )
            WorkflowSnapshot(
                meta = workflow.toWorkflowMeta(),
                workflow = workflow,
                documents = workflow.documents,
            )
        }
    }

    fun listWorkflowSources(workflowId: String): Result<List<WorkflowSourceAsset>> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                ensureWorkflowMetadataExists(workflowId)
                loadWorkflowSources(workflowId)
            }
        }
    }

    fun readWorkflowSourceText(
        workflowId: String,
        asset: WorkflowSourceAsset,
        charset: Charset = StandardCharsets.UTF_8,
    ): Result<String?> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val normalizedRelativePath = normalizeStoredRelativePath(asset.storedRelativePath)
                if (!isSupportedStoredRelativePath(normalizedRelativePath)) {
                    return@withWorkflowLock null
                }
                if (!isTextualWorkflowSource(asset.mediaType)) {
                    return@withWorkflowLock null
                }

                val workflowDir = getWorkflowDirectory(workflowId).toAbsolutePath().normalize()
                val sourcePath = workflowDir.resolve(normalizedRelativePath).normalize()
                if (!sourcePath.startsWith(workflowDir) || !Files.isRegularFile(sourcePath)) {
                    return@withWorkflowLock null
                }

                Files.readString(sourcePath, charset)
            }
        }
    }

    fun importWorkflowSource(
        workflowId: String,
        importedFromStage: StageId,
        importedFromEntry: String,
        sourcePath: Path,
    ): Result<WorkflowSourceAsset> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                ensureWorkflowMetadataExists(workflowId)
                val normalizedEntry = normalizeAuditValue(importedFromEntry)
                require(normalizedEntry.isNotBlank()) { "importedFromEntry cannot be blank" }

                val normalizedSourcePath = sourcePath.toAbsolutePath().normalize()
                require(Files.isRegularFile(normalizedSourcePath)) {
                    "Source file does not exist: $normalizedSourcePath"
                }

                val workspace = workspaceInitializer.initializeWorkflowWorkspace(workflowId)
                val existingAssets = loadWorkflowSources(workflowId)
                val originalFileName = normalizedSourcePath.fileName?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_WORKFLOW_SOURCE_STEM
                val sourceId = nextWorkflowSourceId(existingAssets)
                val storedFileName = buildStoredSourceFileName(sourceId, originalFileName)
                val storedRelativePath = normalizeStoredRelativePath(
                    workspace.workflowDir.relativize(workspace.sourcesDir.resolve(storedFileName)).toString(),
                )
                val sourceBytes = Files.readAllBytes(normalizedSourcePath)
                val asset = WorkflowSourceAsset(
                    sourceId = sourceId,
                    originalFileName = originalFileName,
                    storedRelativePath = storedRelativePath,
                    mediaType = detectSourceMediaType(normalizedSourcePath, originalFileName),
                    fileSize = sourceBytes.size.toLong(),
                    contentHash = sha256Hex(sourceBytes),
                    importedAt = Instant.now().toString(),
                    importedFromStage = importedFromStage,
                    importedFromEntry = normalizedEntry,
                )
                val targetPath = workspace.sourcesDir.resolve(storedFileName)

                try {
                    atomicFileIO.writeBytes(targetPath, sourceBytes)
                    persistWorkflowSources(
                        metadataPath = workspace.sourcesMetadataPath,
                        assets = existingAssets + asset,
                    )
                } catch (error: Exception) {
                    runCatching { Files.deleteIfExists(targetPath) }
                    throw error
                }

                appendAuditEntry(
                    eventType = SpecAuditEventType.SOURCE_IMPORTED,
                    workflowId = workflowId,
                    details = linkedMapOf(
                        "sourceId" to asset.sourceId,
                        "originalFileName" to asset.originalFileName,
                        "storedRelativePath" to asset.storedRelativePath,
                        "mediaType" to asset.mediaType,
                        "fileSize" to asset.fileSize.toString(),
                        "contentHash" to asset.contentHash,
                        "importedFromStage" to asset.importedFromStage.name,
                        "importedFromEntry" to asset.importedFromEntry,
                    ),
                )
                asset
            }
        }
    }

    fun listAuditEvents(workflowId: String): Result<List<SpecAuditEvent>> {
        return runCatching {
            lockManager.withAuditLogLock(workflowId) {
                val auditLogPath = getAuditLogPath(workflowId)
                if (!Files.isRegularFile(auditLogPath)) {
                    emptyList()
                } else {
                    SpecAuditLogCodec.decodeDocuments(Files.readString(auditLogPath, StandardCharsets.UTF_8))
                }
            }
        }
    }

    fun appendAuditEvent(
        workflowId: String,
        eventType: SpecAuditEventType,
        details: Map<String, String> = emptyMap(),
    ): Result<SpecAuditEvent> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val event = buildAuditEvent(
                    eventType = eventType,
                    workflowId = workflowId,
                    details = details,
                )
                lockManager.withAuditLogLock(workflowId) {
                    val auditLogPath = getAuditLogPath(workflowId)
                    Files.createDirectories(auditLogPath.parent)
                    val document = SpecAuditLogCodec.encodeDocument(event)
                    Files.writeString(
                        auditLogPath,
                        document,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                    )
                }
                event
            }
        }
    }

    /**
     * 列出所有工作流
     */
    fun listWorkflows(): List<String> {
        val specsDir = getSpecsDirectory()
        if (!Files.exists(specsDir)) {
            return emptyList()
        }

        return Files.list(specsDir).use { stream ->
            val workflows = mutableListOf<String>()
            stream.forEach { path ->
                if (Files.isDirectory(path)) {
                    workflows.add(path.fileName.toString())
                }
            }
            workflows
        }
    }

    /**
     * 删除工作流
     */
    fun deleteWorkflow(workflowId: String): Result<Unit> {
        return runCatching {
            lockManager.withWorkflowLock(workflowId) {
                val workflowDir = getWorkflowDirectory(workflowId)
                if (Files.exists(workflowDir)) {
                    val beforeSnapshot = captureWorkflowSnapshot(
                        workflowId = workflowId,
                        trigger = SpecSnapshotTrigger.WORKFLOW_DELETE_BEFORE,
                        operationId = UUID.randomUUID().toString(),
                    )
                    appendAuditEntry(
                        eventType = SpecAuditEventType.WORKFLOW_DELETED,
                        workflowId = workflowId,
                        details = mapOf("beforeSnapshotId" to beforeSnapshot.snapshotId),
                    )
                    Files.walk(workflowDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { Files.delete(it) }
                    logger.info("Deleted workflow: $workflowId")
                }
            }
        }
    }

    fun archiveWorkflow(workflow: SpecWorkflow): Result<SpecArchiveResult> {
        return runCatching {
            lockManager.withWorkflowLock(workflow.id) {
                require(workflow.status == WorkflowStatus.COMPLETED) {
                    "Only completed workflow can be archived: ${workflow.id}"
                }

                val workflowDir = getWorkflowDirectory(workflow.id)
                if (!Files.exists(workflowDir)) {
                    throw IllegalStateException("Workflow not found for archive: ${workflow.id}")
                }
                val beforeSnapshot = captureWorkflowSnapshot(
                    workflowId = workflow.id,
                    trigger = SpecSnapshotTrigger.WORKFLOW_ARCHIVE_BEFORE,
                    operationId = UUID.randomUUID().toString(),
                )

                val archiveRoot = getArchiveDirectory()
                Files.createDirectories(archiveRoot)
                val archivedAt = System.currentTimeMillis()
                val archiveId = "${workflow.id}-$archivedAt"
                val archivePath = archiveRoot.resolve(archiveId)
                try {
                    Files.move(workflowDir, archivePath, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(workflowDir, archivePath)
                }
                val auditLogPath = getAuditLogPath(archivePath)
                val readOnlySummaryBeforeAudit = applyArchiveReadOnlyPolicy(
                    archivePath = archivePath,
                    skipPaths = setOf(auditLogPath),
                )

                appendAuditEntry(
                    eventType = SpecAuditEventType.WORKFLOW_ARCHIVED,
                    workflowId = workflow.id,
                    details = mapOf(
                        "archiveId" to archiveId,
                        "archivedAt" to archivedAt.toString(),
                        "status" to workflow.status.name,
                        "phase" to workflow.currentPhase.name,
                        "title" to workflow.title,
                        "changeIntent" to workflow.changeIntent.name,
                        "beforeSnapshotId" to beforeSnapshot.snapshotId,
                        "readOnlyFiles" to readOnlySummaryBeforeAudit.filesMarkedReadOnly.toString(),
                        "readOnlyFailures" to readOnlySummaryBeforeAudit.failures.toString(),
                        "readOnlyPolicy" to "best-effort-file-attributes",
                    ),
                    auditLogPath = auditLogPath,
                )
                markPathReadOnly(auditLogPath)

                SpecArchiveResult(
                    workflowId = workflow.id,
                    archiveId = archiveId,
                    archivePath = archivePath,
                    auditLogPath = auditLogPath,
                    archivedAt = archivedAt,
                    readOnlySummary = readOnlySummaryBeforeAudit,
                )
            }
        }
    }

    fun initializeWorkspace(): Result<SpecWorkspacePaths> {
        return runCatching {
            workspaceInitializer.initializeProjectWorkspace()
        }
    }

    fun initializeWorkflowWorkspace(workflowId: String): Result<WorkflowWorkspacePaths> {
        return runCatching {
            workspaceInitializer.initializeWorkflowWorkspace(workflowId)
        }
    }

    /**
     * 获取工作流目录
     */
    private fun getWorkflowDirectory(workflowId: String): Path {
        return getSpecsDirectory().resolve(workflowId)
    }

    private fun getHistoryDirectory(workflowId: String, phase: SpecPhase): Path {
        return getWorkflowDirectory(workflowId)
            .resolve(".history")
            .resolve(phase.name.lowercase())
    }

    private fun getWorkflowSnapshotsDirectory(workflowId: String): Path {
        return workspaceInitializer
            .initializeWorkflowWorkspace(workflowId)
            .snapshotsDir
    }

    private fun getWorkflowSourcesMetadataPath(workflowId: String): Path {
        return workspaceInitializer
            .initializeWorkflowWorkspace(workflowId)
            .sourcesMetadataPath
    }

    private fun getDeltaBaselinesDirectory(workflowId: String): Path {
        return workspaceInitializer
            .initializeWorkflowWorkspace(workflowId)
            .baselinesDir
    }

    private fun getSnapshotDirectory(workflowId: String, snapshotId: String): Path {
        return getWorkflowSnapshotsDirectory(workflowId).resolve(snapshotId)
    }

    private fun getArchiveDirectory(): Path {
        return getSpecCodingDirectory().resolve("spec-archive")
    }

    private fun getAuditLogPath(workflowId: String): Path {
        return getAuditLogPath(getWorkflowDirectory(workflowId))
    }

    private fun getAuditLogPath(workflowRoot: Path): Path {
        return workflowRoot.resolve(".history").resolve("audit.yaml")
    }

    /**
     * 获取 Specs 根目录
     */
    private fun getSpecsDirectory(): Path {
        return getSpecCodingDirectory().resolve("specs")
    }

    private fun getSpecCodingDirectory(): Path {
        return workspaceInitializer.specCodingDirectory()
    }

    /**
     * 格式化文档内容
     */
    private fun formatDocument(document: SpecDocument): String {
        return buildString {
            appendLine("# ${document.metadata.title}")
            appendLine()
            appendLine("**阶段**: ${document.phase.displayName}")
            appendLine("**作者**: ${document.metadata.author}")
            appendLine("**创建时间**: ${formatTimestamp(document.metadata.createdAt)}")
            appendLine("**更新时间**: ${formatTimestamp(document.metadata.updatedAt)}")
            appendLine("**版本**: ${document.metadata.version}")

            if (document.metadata.tags.isNotEmpty()) {
                appendLine("**标签**: ${document.metadata.tags.joinToString(", ")}")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine(document.content)

            // 添加验证结果（如果有）
            document.validationResult?.let { validation ->
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## 验证结果")
                appendLine()
                appendLine("```")
                appendLine(validation.getSummary())
                appendLine("```")
            }
        }
    }

    /**
     * 解析文档内容
     */
    private fun parseDocument(workflowId: String, phase: SpecPhase, content: String): SpecDocument {
        // 简化版本：直接提取内容部分
        // 实际实现可以解析 YAML front matter
        val lines = content.lines()
        val contentStart = lines.indexOfFirst { it.trim() == "---" }
        val actualContent = if (contentStart >= 0 && contentStart < lines.size - 1) {
            lines.subList(contentStart + 1, lines.size).joinToString("\n")
        } else {
            content
        }

        return SpecDocument(
            id = "$workflowId-${phase.name.lowercase()}",
            phase = phase,
            content = actualContent.trim(),
            metadata = SpecMetadata(
                title = "${phase.displayName} Document",
                description = "Generated ${phase.displayName} document"
            )
        )
    }

    /**
     * 格式化工作流元数据
     */
    private fun formatWorkflowMetadata(workflow: SpecWorkflow): String {
        val metadata = linkedMapOf<String, Any?>(
            "schemaVersion" to SpecSchemaVersioning.CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION,
            "id" to workflow.id,
            "title" to workflow.title,
            "description" to workflow.description.replace("\n", " "),
            "changeIntent" to workflow.changeIntent.name,
            "template" to workflow.template.name,
            "currentPhase" to workflow.currentPhase.name,
            "currentStage" to workflow.currentStage.name,
            "status" to workflow.status.name,
            "verifyEnabled" to workflow.verifyEnabled,
            "createdAt" to workflow.createdAt,
            "updatedAt" to workflow.updatedAt,
            "stageStates" to encodeStageStates(workflow.stageStates),
            "artifactDraftStates" to encodeArtifactDraftStates(workflow.artifactDraftStates),
            "documents" to workflow.documents.entries
                .sortedBy { (phase, _) -> phase.name }
                .map { (phase, doc) ->
                    linkedMapOf<String, Any?>(
                        "phase" to phase.name,
                        "id" to doc.id,
                        "title" to doc.metadata.title,
                    )
                },
        )
        workflow.baselineWorkflowId?.takeIf { it.isNotBlank() }?.let { baseline ->
            metadata["baselineWorkflowId"] = baseline
        }
        workflow.configPinHash?.takeIf { it.isNotBlank() }?.let { configPinHash ->
            metadata["configPinHash"] = configPinHash
        }
        if (workflow.taskExecutionRuns.isNotEmpty()) {
            metadata["executionRuns"] = encodeTaskExecutionRuns(workflow.taskExecutionRuns)
        }
        workflow.clarificationRetryState?.let { retry ->
            metadata["clarificationRetryState"] = encodeClarificationRetryState(retry)
        }
        return yamlCodec.encodeMap(metadata)
    }

    /**
     * 解析工作流元数据
     */
    private fun parseWorkflowMetadata(workflowId: String, metadata: String): SpecWorkflow {
        val parsed = parsePersistedWorkflowMetadata(
            workflowId = workflowId,
            root = yamlCodec.decodeMap(metadata),
        )

        // 加载已有文档
        val documents = mutableMapOf<SpecPhase, SpecDocument>()
        for (phase in SpecPhase.entries) {
            loadDocument(workflowId, phase).getOrNull()?.let {
                documents[phase] = it
            }
        }
        val artifactDraftStates = ArtifactDraftStateSupport.resolvePersistedStates(
            persistedStates = parsed.artifactDraftStates,
            documents = documents,
            auditEvents = listAuditEvents(workflowId).getOrDefault(emptyList()),
        )

        return SpecWorkflow(
            id = workflowId,
            currentPhase = parsed.currentPhase,
            documents = documents,
            status = parsed.status,
            title = parsed.title,
            description = parsed.description,
            changeIntent = parsed.changeIntent,
            template = parsed.template,
            stageStates = parsed.stageStates,
            currentStage = parsed.currentStage,
            verifyEnabled = parsed.verifyEnabled,
            baselineWorkflowId = parsed.baselineWorkflowId,
            configPinHash = parsed.configPinHash,
            artifactDraftStates = artifactDraftStates,
            taskExecutionRuns = parsed.taskExecutionRuns,
            clarificationRetryState = parsed.clarificationRetryState,
            createdAt = parsed.createdAt,
            updatedAt = parsed.updatedAt
        )
    }

    private fun parsePersistedWorkflowMetadata(
        workflowId: String,
        root: Map<String, Any?>,
    ): ParsedWorkflowMetadata {
        val migrated = SpecSchemaVersioning.upgradeWorkflowMetadata(
            workflowId = workflowId,
            document = root,
        ).document
        val now = System.currentTimeMillis()
        val currentPhase = parseEnumValue(migrated["currentPhase"], SpecPhase.SPECIFY, SpecPhase.entries)
        val status = parseEnumValue(migrated["status"], WorkflowStatus.IN_PROGRESS, WorkflowStatus.entries)
        val changeIntent = parseEnumValue(migrated["changeIntent"], SpecChangeIntent.FULL, SpecChangeIntent.entries)
        val template = parseEnumValue(migrated["template"], WorkflowTemplate.FULL_SPEC, WorkflowTemplate.entries)
        val title = migrated["title"]?.toString()?.trim()?.ifBlank { workflowId } ?: workflowId
        val description = migrated["description"]?.toString()?.trim().orEmpty()
        val baselineWorkflowId = migrated["baselineWorkflowId"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val configPinHash = migrated["configPinHash"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val clarificationRetryState = migrated["clarificationRetryState"]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeClarificationRetryState)
        val artifactDraftStates = parseArtifactDraftStates(migrated["artifactDraftStates"])
        val taskExecutionRuns = parseTaskExecutionRuns(migrated["executionRuns"])
        val createdAt = parseLong(migrated["createdAt"], now)
        val updatedAt = parseLong(migrated["updatedAt"], now)
        val createdAtIso = Instant.ofEpochMilli(createdAt).toString()
        val updatedAtIso = Instant.ofEpochMilli(updatedAt).toString()
        val verifyEnabled = parseBoolean(migrated["verifyEnabled"], fallback = false)
        val fallbackCurrentStage = SpecSchemaVersioning.inferLegacyCurrentStage(
            template = template,
            currentPhase = currentPhase,
            verifyEnabled = verifyEnabled,
        )
        val requestedCurrentStage = parseEnumValue(
            raw = migrated["currentStage"],
            fallback = fallbackCurrentStage,
            candidates = StageId.entries,
        )
        val parsedStageStates = parseStageStates(migrated["stageStates"])
        val stageStates = if (parsedStageStates.isNotEmpty()) {
            parsedStageStates
        } else {
            buildLegacyStageStates(
                template = template,
                verifyEnabled = verifyEnabled,
                currentPhase = currentPhase,
                createdAtIso = createdAtIso,
                updatedAtIso = updatedAtIso,
            )
        }
        val currentStage = resolveCurrentStage(
            requested = requestedCurrentStage,
            stageStates = stageStates,
        )

        return ParsedWorkflowMetadata(
            currentPhase = currentPhase,
            status = status,
            changeIntent = changeIntent,
            template = template,
            title = title,
            description = description,
            baselineWorkflowId = baselineWorkflowId,
            configPinHash = configPinHash,
            artifactDraftStates = artifactDraftStates,
            taskExecutionRuns = taskExecutionRuns,
            clarificationRetryState = clarificationRetryState,
            createdAt = createdAt,
            updatedAt = updatedAt,
            verifyEnabled = verifyEnabled,
            currentStage = currentStage,
            stageStates = normalizeStageStates(
                stageStates = stageStates,
                currentStage = currentStage,
                fallbackEnteredAt = updatedAtIso,
            ),
        )
    }

    private data class ParsedWorkflowMetadata(
        val currentPhase: SpecPhase,
        val status: WorkflowStatus,
        val changeIntent: SpecChangeIntent,
        val template: WorkflowTemplate,
        val title: String,
        val description: String,
        val baselineWorkflowId: String?,
        val configPinHash: String?,
        val artifactDraftStates: Map<StageId, ArtifactDraftState>,
        val taskExecutionRuns: List<TaskExecutionRun>,
        val clarificationRetryState: ClarificationRetryState?,
        val createdAt: Long,
        val updatedAt: Long,
        val verifyEnabled: Boolean,
        val currentStage: StageId,
        val stageStates: Map<StageId, StageState>,
    )

    private fun parseLong(raw: Any?, fallback: Long): Long {
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun parseBoolean(raw: Any?, fallback: Boolean): Boolean {
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> fallback
            }

            else -> fallback
        }
    }

    private fun <E : Enum<E>> parseEnumValue(
        raw: Any?,
        fallback: E,
        candidates: Iterable<E>,
    ): E {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return fallback
        }
        return candidates.firstOrNull { candidate -> candidate.name == normalized } ?: fallback
    }

    private fun encodeClarificationRetryState(state: ClarificationRetryState): String {
        val structuredPayload = state.structuredQuestions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(STRUCTURED_QUESTION_DELIMITER.toString())
        val repairSectionsPayload = state.requirementsRepairSections
            .map(RequirementsSectionId::name)
            .distinct()
            .joinToString(STRUCTURED_QUESTION_DELIMITER.toString())
        return listOf(
            encodeBase64Url(state.input),
            encodeBase64Url(state.confirmedContext),
            encodeBase64Url(state.questionsMarkdown),
            encodeBase64Url(structuredPayload),
            state.clarificationRound.coerceAtLeast(1).toString(),
            encodeBase64Url(state.lastError.orEmpty()),
            if (state.confirmed) "1" else "0",
            state.followUp.name,
            encodeBase64Url(repairSectionsPayload),
        ).joinToString(RETRY_FIELD_DELIMITER.toString())
    }

    private fun decodeClarificationRetryState(payload: String): ClarificationRetryState? {
        if (payload.isBlank()) {
            return null
        }
        val parts = payload.split(RETRY_FIELD_DELIMITER)
        if (parts.size < 7) {
            return null
        }
        val input = decodeBase64Url(parts[0]) ?: return null
        val confirmedContext = decodeBase64Url(parts[1]) ?: return null
        val questionsMarkdown = decodeBase64Url(parts[2]) ?: return null
        val structuredPayload = decodeBase64Url(parts[3]).orEmpty()
        val clarificationRound = parts[4].toIntOrNull()?.coerceAtLeast(1) ?: 1
        val lastError = decodeBase64Url(parts[5])
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val confirmed = parts[6] == "1" || parts[6].equals("true", ignoreCase = true)
        val followUp = parts.getOrNull(7)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                ClarificationFollowUp.entries.firstOrNull { candidate -> candidate.name == raw }
            }
            ?: ClarificationFollowUp.GENERATION
        val requirementsRepairSections = decodeBase64Url(parts.getOrNull(8).orEmpty())
            .orEmpty()
            .split(STRUCTURED_QUESTION_DELIMITER)
            .mapNotNull { raw ->
                val normalized = raw.trim()
                RequirementsSectionId.entries.firstOrNull { section -> section.name == normalized }
            }
            .distinct()
        val structuredQuestions = structuredPayload
            .split(STRUCTURED_QUESTION_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (input.isBlank() && confirmedContext.isBlank() && questionsMarkdown.isBlank() && structuredQuestions.isEmpty()) {
            return null
        }
        return ClarificationRetryState(
            input = input,
            confirmedContext = confirmedContext,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = structuredQuestions,
            clarificationRound = clarificationRound,
            lastError = lastError,
            confirmed = confirmed,
            followUp = followUp,
            requirementsRepairSections = requirementsRepairSections,
        )
    }

    private fun encodeBase64Url(raw: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeBase64Url(raw: String): String? {
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(raw)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }

    private fun captureWorkflowSnapshot(
        workflowId: String,
        trigger: SpecSnapshotTrigger,
        operationId: String? = null,
        phase: SpecPhase? = null,
    ): SpecWorkflowSnapshotEntry {
        val workspace = workspaceInitializer.initializeWorkflowWorkspace(workflowId)
        val createdAt = System.currentTimeMillis()
        val snapshotId = buildSnapshotId(createdAt)
        val snapshotDir = workspace.snapshotsDir.resolve(snapshotId)
        Files.createDirectories(snapshotDir)

        val copiedFiles = mutableListOf<String>()
        SNAPSHOT_CAPTURE_FILE_NAMES.forEach { fileName ->
            val sourcePath = workspace.workflowDir.resolve(fileName)
            if (!Files.isRegularFile(sourcePath)) {
                return@forEach
            }
            val content = Files.readString(sourcePath, StandardCharsets.UTF_8)
            atomicFileIO.writeString(
                snapshotDir.resolve(fileName),
                content,
                StandardCharsets.UTF_8,
            )
            copiedFiles += fileName
        }

        val normalizedOperationId = operationId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val metadata = linkedMapOf<String, Any?>(
            "snapshotId" to snapshotId,
            "workflowId" to workflowId,
            "trigger" to trigger.name,
            "createdAt" to createdAt,
            "files" to copiedFiles.sorted(),
        )
        normalizedOperationId?.let { metadata["operationId"] = it }
        phase?.let { metadata["phase"] = it.name }
        atomicFileIO.writeString(
            snapshotDir.resolve(SNAPSHOT_METADATA_FILE_NAME),
            yamlCodec.encodeMap(metadata),
            StandardCharsets.UTF_8,
        )

        return SpecWorkflowSnapshotEntry(
            snapshotId = snapshotId,
            workflowId = workflowId,
            trigger = trigger,
            createdAt = createdAt,
            operationId = normalizedOperationId,
            phase = phase,
            files = copiedFiles.sorted(),
        )
    }

    private fun loadSnapshotEntry(snapshotDir: Path, fallbackWorkflowId: String): SpecWorkflowSnapshotEntry? {
        val metadataPath = snapshotDir.resolve(SNAPSHOT_METADATA_FILE_NAME)
        if (!Files.isRegularFile(metadataPath)) {
            return null
        }
        return runCatching {
            val snapshotId = snapshotDir.fileName.toString()
            val metadata = yamlCodec.decodeMap(Files.readString(metadataPath, StandardCharsets.UTF_8))
            val workflowId = metadata["workflowId"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: fallbackWorkflowId
            val trigger = parseEnumValue(
                raw = metadata["trigger"],
                fallback = SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER,
                candidates = SpecSnapshotTrigger.entries,
            )
            val operationId = metadata["operationId"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val phase = parseOptionalEnum(metadata["phase"], SpecPhase.entries)
            val createdAtFromId = snapshotId.substringBefore(SNAPSHOT_ID_DELIMITER)
                .toLongOrNull()
                ?: System.currentTimeMillis()
            val createdAt = parseLong(metadata["createdAt"], createdAtFromId)
            val files = parseStringList(metadata["files"]).ifEmpty {
                Files.list(snapshotDir).use { stream ->
                    stream
                        .filter { path ->
                            Files.isRegularFile(path) &&
                                path.fileName.toString() != SNAPSHOT_METADATA_FILE_NAME
                        }
                        .map { it.fileName.toString() }
                        .sorted()
                        .toList()
                }
            }
            SpecWorkflowSnapshotEntry(
                snapshotId = snapshotId,
                workflowId = workflowId,
                trigger = trigger,
                createdAt = createdAt,
                operationId = operationId,
                phase = phase,
                files = files,
            )
        }.getOrNull()
    }

    private fun parseSnapshotAsWorkflow(
        workflowId: String,
        snapshotId: String,
        snapshotDir: Path,
    ): SpecWorkflow {
        val documents = mutableMapOf<SpecPhase, SpecDocument>()
        for (phase in SpecPhase.entries) {
            val artifactPath = snapshotDir.resolve(phase.outputFileName)
            if (!Files.isRegularFile(artifactPath)) {
                continue
            }
            val content = Files.readString(artifactPath, StandardCharsets.UTF_8)
            documents[phase] = parseDocument(workflowId, phase, content)
        }

        val workflowMetadataPath = snapshotDir.resolve(WORKFLOW_METADATA_FILE_NAME)
        val metadata = if (Files.isRegularFile(workflowMetadataPath)) {
            yamlCodec.decodeMap(Files.readString(workflowMetadataPath, StandardCharsets.UTF_8))
        } else {
            emptyMap()
        }
        val parsed = parsePersistedWorkflowMetadata(
            workflowId = workflowId,
            root = metadata,
        )
        val snapshotWorkflowId = "$workflowId@snapshot:$snapshotId"
        return SpecWorkflow(
            id = snapshotWorkflowId,
            currentPhase = parsed.currentPhase,
            documents = documents,
            status = parsed.status,
            title = parsed.title,
            description = parsed.description,
            changeIntent = parsed.changeIntent,
            template = parsed.template,
            stageStates = parsed.stageStates,
            currentStage = parsed.currentStage,
            verifyEnabled = parsed.verifyEnabled,
            baselineWorkflowId = parsed.baselineWorkflowId,
            configPinHash = parsed.configPinHash,
            taskExecutionRuns = parsed.taskExecutionRuns,
            clarificationRetryState = parsed.clarificationRetryState,
            createdAt = parsed.createdAt,
            updatedAt = parsed.updatedAt,
        )
    }

    private fun encodeDeltaBaseline(ref: SpecDeltaBaselineRef): Map<String, Any?> {
        val metadata = linkedMapOf<String, Any?>(
            "baselineId" to ref.baselineId,
            "workflowId" to ref.workflowId,
            "snapshotId" to ref.snapshotId,
            "createdAt" to ref.createdAt,
        )
        ref.label?.let { metadata["label"] = it }
        return metadata
    }

    private fun loadDeltaBaseline(path: Path, fallbackWorkflowId: String): SpecDeltaBaselineRef? {
        return runCatching {
            val metadata = yamlCodec.decodeMap(Files.readString(path, StandardCharsets.UTF_8))
            val baselineId = metadata["baselineId"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: path.fileName.toString().removeSuffix(".yaml")
            val workflowId = metadata["workflowId"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: fallbackWorkflowId
            val snapshotId = metadata["snapshotId"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val createdAt = parseLong(metadata["createdAt"], 0L)
            val label = metadata["label"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            SpecDeltaBaselineRef(
                baselineId = baselineId,
                workflowId = workflowId,
                snapshotId = snapshotId,
                createdAt = createdAt,
                label = label,
            )
        }.getOrNull()
    }

    private fun parseStringList(raw: Any?): List<String> {
        if (raw !is List<*>) {
            return emptyList()
        }
        return raw
            .mapNotNull { value ->
                value?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .sorted()
    }

    private fun persistWorkflowSources(
        metadataPath: Path,
        assets: List<WorkflowSourceAsset>,
    ) {
        atomicFileIO.writeString(
            metadataPath,
            formatWorkflowSourcesMetadata(assets),
            StandardCharsets.UTF_8,
        )
    }

    private fun formatWorkflowSourcesMetadata(assets: List<WorkflowSourceAsset>): String {
        val metadata = linkedMapOf<String, Any?>(
            "schemaVersion" to WORKFLOW_SOURCE_SCHEMA_VERSION,
            "sources" to encodeWorkflowSources(assets),
        )
        return yamlCodec.encodeMap(metadata)
    }

    private fun loadWorkflowSources(workflowId: String): List<WorkflowSourceAsset> {
        val metadataPath = getWorkflowSourcesMetadataPath(workflowId)
        if (!Files.isRegularFile(metadataPath)) {
            return emptyList()
        }
        val metadata = yamlCodec.decodeMap(Files.readString(metadataPath, StandardCharsets.UTF_8))
        return parseWorkflowSources(metadata["sources"])
    }

    private fun encodeWorkflowSources(assets: List<WorkflowSourceAsset>): List<Map<String, Any?>> {
        return assets
            .sortedBy(WorkflowSourceAsset::sourceId)
            .map { asset ->
                linkedMapOf<String, Any?>(
                    "sourceId" to asset.sourceId,
                    "originalFileName" to asset.originalFileName,
                    "storedRelativePath" to asset.storedRelativePath,
                    "mediaType" to asset.mediaType,
                    "fileSize" to asset.fileSize,
                    "contentHash" to asset.contentHash,
                    "importedAt" to asset.importedAt,
                    "importedFromStage" to asset.importedFromStage.name,
                    "importedFromEntry" to asset.importedFromEntry,
                )
            }
    }

    private fun parseWorkflowSources(raw: Any?): List<WorkflowSourceAsset> {
        val items = raw as? List<*> ?: return emptyList()
        return items.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val sourceId = map["sourceId"]
                ?.toString()
                ?.trim()
                ?.uppercase()
                ?.takeIf { WORKFLOW_SOURCE_ID_PATTERN.matches(it) }
                ?: return@mapNotNull null
            val originalFileName = map["originalFileName"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val storedRelativePath = map["storedRelativePath"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeStoredRelativePath)
                ?.takeIf(::isSupportedStoredRelativePath)
                ?: return@mapNotNull null
            val mediaType = map["mediaType"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_SOURCE_MEDIA_TYPE
            val fileSize = parseLong(map["fileSize"], -1L)
            if (fileSize < 0L) {
                return@mapNotNull null
            }
            val contentHash = map["contentHash"]
                ?.toString()
                ?.trim()
                ?.lowercase()
                ?.takeIf { SOURCE_CONTENT_HASH_PATTERN.matches(it) }
                ?: return@mapNotNull null
            val importedAt = map["importedAt"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val importedFromStage = parseOptionalEnum(map["importedFromStage"], StageId.entries)
                ?: return@mapNotNull null
            val importedFromEntry = map["importedFromEntry"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: UNKNOWN_SOURCE_ENTRY
            WorkflowSourceAsset(
                sourceId = sourceId,
                originalFileName = originalFileName,
                storedRelativePath = storedRelativePath,
                mediaType = mediaType,
                fileSize = fileSize,
                contentHash = contentHash,
                importedAt = importedAt,
                importedFromStage = importedFromStage,
                importedFromEntry = importedFromEntry,
            )
        }.sortedBy(WorkflowSourceAsset::sourceId)
    }

    private fun <E : Enum<E>> parseOptionalEnum(
        raw: Any?,
        candidates: Iterable<E>,
    ): E? {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return candidates.firstOrNull { candidate -> candidate.name == normalized }
    }

    private fun ensureWorkflowMetadataExists(workflowId: String) {
        val metadataPath = getWorkflowDirectory(workflowId).resolve(WORKFLOW_METADATA_FILE_NAME)
        if (!Files.isRegularFile(metadataPath)) {
            throw IllegalStateException("Workflow not found: $workflowId")
        }
    }

    private fun nextWorkflowSourceId(existingAssets: List<WorkflowSourceAsset>): String {
        val nextSequence = existingAssets
            .mapNotNull { asset ->
                WORKFLOW_SOURCE_ID_PATTERN.matchEntire(asset.sourceId)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        return "%s%03d".format(WORKFLOW_SOURCE_ID_PREFIX, nextSequence)
    }

    private fun buildStoredSourceFileName(sourceId: String, originalFileName: String): String {
        val extension = sourceExtension(originalFileName)
        val baseName = originalFileName.substringBeforeLast('.', originalFileName)
        val slug = baseName
            .lowercase()
            .replace(NON_ALPHANUMERIC_SOURCE_CHAR_PATTERN, "-")
            .trim('-')
            .take(MAX_WORKFLOW_SOURCE_STEM_LENGTH)
            .ifBlank { DEFAULT_WORKFLOW_SOURCE_STEM }
        return buildString {
            append(sourceId)
            append('-')
            append(slug)
            extension?.let {
                append('.')
                append(it)
            }
        }
    }

    private fun sourceExtension(originalFileName: String): String? {
        val raw = originalFileName.substringAfterLast('.', "").lowercase()
        return raw.takeIf { it.isNotBlank() && SOURCE_EXTENSION_PATTERN.matches(it) }
    }

    private fun detectSourceMediaType(sourcePath: Path, originalFileName: String): String {
        return when (sourceExtension(originalFileName)) {
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> Files.probeContentType(sourcePath)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_SOURCE_MEDIA_TYPE
        }
    }

    private fun normalizeStoredRelativePath(raw: String): String {
        return raw.replace('\\', '/')
    }

    private fun isSupportedStoredRelativePath(relativePath: String): Boolean {
        val normalized = normalizeStoredRelativePath(relativePath).trim()
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("..")) {
            return false
        }
        return normalized.startsWith("$WORKFLOW_SOURCES_DIR_NAME/")
    }

    private fun isTextualWorkflowSource(mediaType: String): Boolean {
        val normalizedMediaType = mediaType.trim().lowercase()
        return normalizedMediaType.startsWith("text/") ||
            normalizedMediaType == "application/json" ||
            normalizedMediaType.endsWith("+json") ||
            normalizedMediaType == "application/xml" ||
            normalizedMediaType.endsWith("+xml") ||
            normalizedMediaType == "application/yaml" ||
            normalizedMediaType == "application/x-yaml"
    }

    private fun sha256Hex(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun encodeStageStates(stageStates: Map<StageId, StageState>): Map<String, Any?> {
        if (stageStates.isEmpty()) {
            return emptyMap()
        }
        val ordered = linkedMapOf<String, Any?>()
        StageId.entries.forEach { stageId ->
            val state = stageStates[stageId] ?: return@forEach
            ordered[stageId.name] = linkedMapOf<String, Any?>(
                "active" to state.active,
                "status" to state.status.name,
                "enteredAt" to state.enteredAt,
                "completedAt" to state.completedAt,
            )
        }
        return ordered
    }

    private fun encodeArtifactDraftStates(
        artifactDraftStates: Map<StageId, ArtifactDraftState>,
    ): Map<String, Any?> {
        if (artifactDraftStates.isEmpty()) {
            return emptyMap()
        }
        val ordered = linkedMapOf<String, Any?>()
        SpecPhase.entries
            .map(SpecPhase::toStageId)
            .forEach { stageId ->
                val state = artifactDraftStates[stageId] ?: return@forEach
                ordered[stageId.name] = state.name
            }
        return ordered
    }

    private fun parseStageStates(raw: Any?): Map<StageId, StageState> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val parsed = linkedMapOf<StageId, StageState>()
        map.forEach { (rawStageId, rawState) ->
            val stageId = StageId.entries.firstOrNull {
                it.name.equals(rawStageId?.toString()?.trim(), ignoreCase = true)
            } ?: return@forEach
            val stateMap = rawState as? Map<*, *> ?: return@forEach
            val active = parseBoolean(stateMap["active"], fallback = true)
            val status = parseEnumValue(
                raw = stateMap["status"],
                fallback = StageProgress.NOT_STARTED,
                candidates = StageProgress.entries,
            )
            val enteredAt = stateMap["enteredAt"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val completedAt = stateMap["completedAt"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            parsed[stageId] = StageState(
                active = active,
                status = status,
                enteredAt = enteredAt,
                completedAt = completedAt,
            )
        }
        return parsed
    }

    private fun parseArtifactDraftStates(raw: Any?): Map<StageId, ArtifactDraftState> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val parsed = linkedMapOf<StageId, ArtifactDraftState>()
        map.forEach { (rawStageId, rawState) ->
            val stageId = StageId.entries.firstOrNull {
                it.name.equals(rawStageId?.toString()?.trim(), ignoreCase = true)
            } ?: return@forEach
            val state = ArtifactDraftState.entries.firstOrNull {
                it.name.equals(rawState?.toString()?.trim(), ignoreCase = true)
            } ?: return@forEach
            parsed[stageId] = state
        }
        return parsed
    }

    private fun encodeTaskExecutionRuns(runs: List<TaskExecutionRun>): List<Map<String, Any?>> {
        return runs
            .sortedWith(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
            .map { run ->
                linkedMapOf<String, Any?>(
                    "runId" to run.runId,
                    "taskId" to run.taskId,
                    "status" to run.status.name,
                    "trigger" to run.trigger.name,
                    "startedAt" to run.startedAt,
                    "finishedAt" to run.finishedAt,
                    "summary" to run.summary,
                )
            }
    }

    private fun parseTaskExecutionRuns(raw: Any?): List<TaskExecutionRun> {
        val items = raw as? List<*> ?: return emptyList()
        return items.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val runId = map["runId"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val taskId = map["taskId"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val startedAt = map["startedAt"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val status = parseEnumValue(
                raw = map["status"],
                fallback = TaskExecutionRunStatus.WAITING_CONFIRMATION,
                candidates = TaskExecutionRunStatus.entries,
            )
            val trigger = parseEnumValue(
                raw = map["trigger"],
                fallback = ExecutionTrigger.SYSTEM_RECOVERY,
                candidates = ExecutionTrigger.entries,
            )
            val finishedAt = map["finishedAt"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val summary = map["summary"]
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            TaskExecutionRun(
                runId = runId,
                taskId = taskId,
                status = status,
                trigger = trigger,
                startedAt = startedAt,
                finishedAt = finishedAt,
                summary = summary,
            )
        }.sortedWith(compareBy<TaskExecutionRun> { it.startedAt }.thenBy { it.runId })
    }

    private fun buildLegacyStageStates(
        template: WorkflowTemplate,
        verifyEnabled: Boolean,
        currentPhase: SpecPhase,
        createdAtIso: String,
        updatedAtIso: String,
    ): Map<StageId, StageState> {
        val definition = WorkflowTemplates.definitionOf(template)
        val activeStages = definition.activeStages(
            verifyEnabled = verifyEnabled,
            implementEnabled = null,
        ).toSet()
        val trail = SpecSchemaVersioning.buildLegacyStageTrail(
            template = template,
            currentPhase = currentPhase,
            verifyEnabled = verifyEnabled,
        )
        val currentStage = trail.last()
        val states = linkedMapOf<StageId, StageState>()

        StageId.entries.forEach { stageId ->
            val active = activeStages.contains(stageId)
            val state = when {
                trail.dropLast(1).contains(stageId) -> StageState(
                    active = active,
                    status = StageProgress.DONE,
                    enteredAt = createdAtIso,
                    completedAt = updatedAtIso,
                )

                stageId == currentStage -> StageState(
                    active = active || stageId == currentStage,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = updatedAtIso,
                    completedAt = null,
                )

                else -> StageState(
                    active = active,
                    status = StageProgress.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                )
            }
            states[stageId] = state
        }
        return states
    }

    private fun resolveCurrentStage(
        requested: StageId,
        stageStates: Map<StageId, StageState>,
    ): StageId {
        if (stageStates.containsKey(requested)) {
            return requested
        }
        return stageStates.entries
            .firstOrNull { (_, state) -> state.status == StageProgress.IN_PROGRESS }
            ?.key
            ?: stageStates.entries
                .firstOrNull { (_, state) -> state.active }
                ?.key
            ?: requested
    }

    private fun normalizeStageStates(
        stageStates: Map<StageId, StageState>,
        currentStage: StageId,
        fallbackEnteredAt: String,
    ): Map<StageId, StageState> {
        val normalized = linkedMapOf<StageId, StageState>()
        StageId.entries.forEach { stageId ->
            val state = stageStates[stageId] ?: StageState(
                active = false,
                status = StageProgress.NOT_STARTED,
            )
            normalized[stageId] = if (stageId == currentStage) {
                state.copy(
                    active = true,
                    status = StageProgress.IN_PROGRESS,
                    enteredAt = state.enteredAt ?: fallbackEnteredAt,
                    completedAt = null,
                )
            } else {
                state
            }
        }
        return normalized
    }

    private fun buildSnapshotId(createdAt: Long): String {
        val randomSuffix = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(SNAPSHOT_RANDOM_SUFFIX_LENGTH)
        return "$createdAt$SNAPSHOT_ID_DELIMITER$randomSuffix"
    }

    private fun buildBaselineId(): String {
        val now = System.currentTimeMillis()
        val randomSuffix = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .take(BASELINE_RANDOM_SUFFIX_LENGTH)
        return "baseline-$now-$randomSuffix"
    }

    private fun saveDocumentSnapshot(
        workflowId: String,
        document: SpecDocument,
        formattedContent: String,
    ): String {
        val historyDir = getHistoryDirectory(workflowId, document.phase)
        Files.createDirectories(historyDir)
        val snapshotId = System.currentTimeMillis().toString()
        val snapshotPath = historyDir.resolve("$snapshotId.md")
        atomicFileIO.writeString(snapshotPath, formattedContent, StandardCharsets.UTF_8)
        return snapshotId
    }

    private fun cleanupIfEmpty(directory: Path) {
        if (!Files.exists(directory)) {
            return
        }
        Files.list(directory).use { stream ->
            if (!stream.findAny().isPresent) {
                Files.delete(directory)
            }
        }
    }

    private fun applyArchiveReadOnlyPolicy(
        archivePath: Path,
        skipPaths: Set<Path> = emptySet(),
    ): SpecArchiveReadOnlySummary {
        if (!Files.exists(archivePath)) {
            return SpecArchiveReadOnlySummary(filesMarkedReadOnly = 0, failures = 0)
        }
        val normalizedSkipPaths = skipPaths
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .toSet()
        var filesMarkedReadOnly = 0
        var failures = 0
        Files.walk(archivePath).use { stream ->
            stream.forEach { path ->
                if (!Files.isRegularFile(path)) {
                    return@forEach
                }
                val normalizedPath = path.toAbsolutePath().normalize()
                if (normalizedSkipPaths.contains(normalizedPath)) {
                    return@forEach
                }
                if (markPathReadOnly(path)) {
                    filesMarkedReadOnly += 1
                } else {
                    failures += 1
                }
            }
        }
        return SpecArchiveReadOnlySummary(
            filesMarkedReadOnly = filesMarkedReadOnly,
            failures = failures,
        )
    }

    private fun markPathReadOnly(path: Path): Boolean {
        if (!Files.exists(path)) {
            return false
        }
        var applied = false
        runCatching {
            Files.setAttribute(path, "dos:readonly", true)
            applied = true
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            runCatching {
                val permissions = Files.getPosixFilePermissions(path).toMutableSet()
                permissions.remove(PosixFilePermission.OWNER_WRITE)
                permissions.remove(PosixFilePermission.GROUP_WRITE)
                permissions.remove(PosixFilePermission.OTHERS_WRITE)
                Files.setPosixFilePermissions(path, permissions)
                applied = true
            }
        }
        runCatching {
            if (path.toFile().setWritable(false, false)) {
                applied = true
            }
        }
        runCatching {
            if (path.toFile().setReadOnly()) {
                applied = true
            }
        }
        return if (applied) {
            true
        } else {
            !Files.isWritable(path)
        }
    }

    private fun appendAuditEntry(
        eventType: SpecAuditEventType,
        workflowId: String,
        details: Map<String, String> = emptyMap(),
        auditLogPath: Path = getAuditLogPath(workflowId),
    ) {
        try {
            lockManager.withAuditLogLock(workflowId) {
                Files.createDirectories(auditLogPath.parent)
                val event = buildAuditEvent(
                    eventType = eventType,
                    workflowId = workflowId,
                    details = details,
                )
                val document = SpecAuditLogCodec.encodeDocument(event)
                Files.writeString(
                    auditLogPath,
                    document,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to append spec audit log entry", e)
        }
    }

    private fun persistWorkflowMetadata(
        workflow: SpecWorkflow,
        eventType: SpecAuditEventType,
        extraAuditDetails: Map<String, String> = emptyMap(),
    ): WorkflowMetadataWriteResult {
        return lockManager.withWorkflowLock(workflow.id) {
            val workflowDir = workspaceInitializer
                .initializeWorkflowWorkspace(workflow.id)
                .workflowDir

            val metadataPath = workflowDir.resolve(WORKFLOW_METADATA_FILE_NAME)
            val metadata = formatWorkflowMetadata(workflow)
            val operationId = UUID.randomUUID().toString()
            val beforeSnapshot = captureWorkflowSnapshot(
                workflowId = workflow.id,
                trigger = SpecSnapshotTrigger.WORKFLOW_SAVE_BEFORE,
                operationId = operationId,
            )

            atomicFileIO.writeString(metadataPath, metadata, StandardCharsets.UTF_8)

            val afterSnapshot = captureWorkflowSnapshot(
                workflowId = workflow.id,
                trigger = SpecSnapshotTrigger.WORKFLOW_SAVE_AFTER,
                operationId = operationId,
            )
            val auditDetails = linkedMapOf(
                "status" to workflow.status.name,
                "phase" to workflow.currentPhase.name,
                "changeIntent" to workflow.changeIntent.name,
                "beforeSnapshotId" to beforeSnapshot.snapshotId,
                "afterSnapshotId" to afterSnapshot.snapshotId,
            )
            workflow.configPinHash
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { configPinHash ->
                    auditDetails["configPinHash"] = configPinHash
                }
            extraAuditDetails.forEach { (key, value) ->
                auditDetails[key] = value
            }
            appendAuditEntry(
                eventType = eventType,
                workflowId = workflow.id,
                details = auditDetails,
            )
            logger.info("Saved workflow metadata to $metadataPath with event $eventType")
            WorkflowMetadataWriteResult(
                path = metadataPath,
                beforeSnapshotId = beforeSnapshot.snapshotId,
                afterSnapshotId = afterSnapshot.snapshotId,
            )
        }
    }

    private fun buildAuditEvent(
        eventType: SpecAuditEventType,
        workflowId: String,
        details: Map<String, String>,
    ): SpecAuditEvent {
        val timestamp = System.currentTimeMillis()
        val normalizedDetails = details.entries
            .mapNotNull { (key, value) ->
                val normalizedKey = normalizeAuditValue(key)
                if (normalizedKey.isBlank()) {
                    return@mapNotNull null
                }
                normalizedKey to normalizeAuditValue(value)
            }
            .toMap()
            .toSortedMap()
        return SpecAuditEvent(
            eventId = "event-${UUID.randomUUID()}",
            workflowId = normalizedWorkflowId(workflowId),
            eventType = eventType,
            occurredAtEpochMs = timestamp,
            occurredAt = Instant.ofEpochMilli(timestamp).toString(),
            actor = System.getProperty("user.name")?.trim()?.takeIf { it.isNotBlank() },
            details = normalizedDetails,
        )
    }

    private fun normalizedWorkflowId(workflowId: String): String {
        return normalizeAuditValue(workflowId).ifBlank { "unknown-workflow" }
    }

    private fun normalizeAuditValue(raw: String): String {
        return raw
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
    }

    companion object {
        private const val RETRY_FIELD_DELIMITER = ':'
        private const val STRUCTURED_QUESTION_DELIMITER = '\u001F'
        private const val WORKFLOW_METADATA_FILE_NAME = "workflow.yaml"
        private const val WORKFLOW_SOURCES_DIR_NAME = "sources"
        private const val WORKFLOW_SOURCES_METADATA_FILE_NAME = "sources.yaml"
        private const val WORKFLOW_SOURCE_SCHEMA_VERSION = 1
        private const val SNAPSHOT_METADATA_FILE_NAME = "snapshot.yaml"
        private const val SNAPSHOT_ID_DELIMITER = '-'
        private const val SNAPSHOT_RANDOM_SUFFIX_LENGTH = 10
        private const val BASELINE_RANDOM_SUFFIX_LENGTH = 8
        private val SNAPSHOT_CAPTURE_FILE_NAMES = buildSet {
            add(WORKFLOW_METADATA_FILE_NAME)
            add(WORKFLOW_SOURCES_METADATA_FILE_NAME)
            StageId.entries
                .mapNotNull(StageId::artifactFileName)
                .forEach(::add)
        }.toList()
        private val CONFIG_PIN_HASH_PATTERN = Regex("^[a-f0-9]{64}$")
        private val WORKFLOW_SOURCE_ID_PATTERN = Regex("""^SRC-(\d{3,})$""")
        private const val WORKFLOW_SOURCE_ID_PREFIX = "SRC-"
        private const val DEFAULT_WORKFLOW_SOURCE_STEM = "source"
        private const val MAX_WORKFLOW_SOURCE_STEM_LENGTH = 48
        private const val DEFAULT_SOURCE_MEDIA_TYPE = "application/octet-stream"
        private const val UNKNOWN_SOURCE_ENTRY = "UNKNOWN"
        private val SOURCE_EXTENSION_PATTERN = Regex("""^[a-z0-9]{1,10}$""")
        private val NON_ALPHANUMERIC_SOURCE_CHAR_PATTERN = Regex("""[^a-z0-9]+""")
        private val SOURCE_CONTENT_HASH_PATTERN = Regex("^[a-f0-9]{64}$")

        fun getInstance(project: Project): SpecStorage {
            return SpecStorage(project)
        }
    }
}
