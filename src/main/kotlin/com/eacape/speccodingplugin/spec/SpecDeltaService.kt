package com.eacape.speccodingplugin.spec

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class SpecDeltaService(private val project: Project) {
    private var _specEngineOverride: SpecEngine? = null
    private var _storageOverride: SpecStorage? = null
    private var _artifactServiceOverride: SpecArtifactService? = null
    private var _workspaceCandidateFilesProviderOverride: (() -> List<String>)? = null
    private var _codeChangeSummaryProviderOverride: ((Path) -> CodeChangeSummary?)? = null
    private var _atomicFileIOOverride: AtomicFileIO? = null

    private val specEngine: SpecEngine
        get() = _specEngineOverride ?: SpecEngine.getInstance(project)

    private val storage: SpecStorage
        get() = _storageOverride ?: SpecStorage.getInstance(project)

    private val artifactService: SpecArtifactService by lazy {
        _artifactServiceOverride ?: SpecArtifactService(project)
    }

    private val atomicFileIO: AtomicFileIO
        get() = _atomicFileIOOverride ?: AtomicFileIO()

    private val workspaceCandidateFilesProvider: () -> List<String>
        get() = _workspaceCandidateFilesProviderOverride ?: {
            runCatching {
                SpecRelatedFilesService.getInstance(project).snapshotWorkspaceCandidatePaths()
            }.getOrDefault(emptyList())
        }

    private val codeChangeSummaryProvider: (Path) -> CodeChangeSummary?
        get() = _codeChangeSummaryProviderOverride ?: { projectRoot ->
            GitStatusCodeChangeSummaryProvider(projectRoot).collect()
        }

    internal constructor(
        project: Project,
        specEngine: SpecEngine,
        storage: SpecStorage,
        artifactService: SpecArtifactService = SpecArtifactService(project),
        workspaceCandidateFilesProvider: () -> List<String> = { emptyList() },
        codeChangeSummaryProvider: (Path) -> CodeChangeSummary? = { _ -> null },
        atomicFileIO: AtomicFileIO = AtomicFileIO(),
    ) : this(project) {
        _specEngineOverride = specEngine
        _storageOverride = storage
        _artifactServiceOverride = artifactService
        _workspaceCandidateFilesProviderOverride = workspaceCandidateFilesProvider
        _codeChangeSummaryProviderOverride = codeChangeSummaryProvider
        _atomicFileIOOverride = atomicFileIO
    }

    fun compareByWorkflowId(
        baselineWorkflowId: String,
        targetWorkflowId: String,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadWorkflow(baselineWorkflowId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            val workspaceCandidateFiles = workspaceCandidateFilesProvider()
            val delta = SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
                baselineVerificationContent = artifactService.readArtifact(baselineWorkflowId, StageId.VERIFY),
                targetVerificationContent = artifactService.readArtifact(targetWorkflowId, StageId.VERIFY),
                workspaceCandidateFiles = workspaceCandidateFiles,
                codeChangeSummary = collectCodeChangeSummary(workspaceCandidateFiles),
            ).withComparisonContext(
                SpecDeltaComparisonContext(
                    baselineKind = SpecDeltaBaselineKind.WORKFLOW,
                    baselineWorkflowId = baselineWorkflowId,
                    targetWorkflowId = targetWorkflowId,
                ),
            )
            appendBaselineSelectionAudit(
                targetWorkflowId = targetWorkflowId,
                baselineKind = "WORKFLOW",
                baselineWorkflowId = baselineWorkflowId,
                delta = delta,
            )
            delta
        }
    }

    fun compareBySnapshot(
        workflowId: String,
        snapshotId: String,
        targetWorkflowId: String = workflowId,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadWorkflowSnapshot(workflowId, snapshotId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            val workspaceCandidateFiles = workspaceCandidateFilesProvider()
            val delta = SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
                baselineVerificationContent = storage.loadWorkflowSnapshotArtifact(
                    workflowId = workflowId,
                    snapshotId = snapshotId,
                    stageId = StageId.VERIFY,
                ).getOrThrow(),
                targetVerificationContent = artifactService.readArtifact(targetWorkflowId, StageId.VERIFY),
                workspaceCandidateFiles = workspaceCandidateFiles,
                codeChangeSummary = collectCodeChangeSummary(workspaceCandidateFiles),
            ).withComparisonContext(
                SpecDeltaComparisonContext(
                    baselineKind = SpecDeltaBaselineKind.SNAPSHOT,
                    baselineWorkflowId = workflowId,
                    targetWorkflowId = targetWorkflowId,
                    snapshotId = snapshotId,
                ),
            )
            appendBaselineSelectionAudit(
                targetWorkflowId = targetWorkflowId,
                baselineKind = "SNAPSHOT",
                baselineWorkflowId = workflowId,
                snapshotId = snapshotId,
                delta = delta,
            )
            delta
        }
    }

    fun compareByDeltaBaseline(
        workflowId: String,
        baselineId: String,
        targetWorkflowId: String = workflowId,
    ): Result<SpecWorkflowDelta> {
        return runCatching {
            val baseline = specEngine.loadDeltaBaselineWorkflow(workflowId, baselineId).getOrThrow()
            val target = specEngine.loadWorkflow(targetWorkflowId).getOrThrow()
            val workspaceCandidateFiles = workspaceCandidateFilesProvider()
            val delta = SpecDeltaCalculator.compareWorkflows(
                baselineWorkflow = baseline,
                targetWorkflow = target,
                baselineVerificationContent = storage.loadDeltaBaselineArtifact(
                    workflowId = workflowId,
                    baselineId = baselineId,
                    stageId = StageId.VERIFY,
                ).getOrThrow(),
                targetVerificationContent = artifactService.readArtifact(targetWorkflowId, StageId.VERIFY),
                workspaceCandidateFiles = workspaceCandidateFiles,
                codeChangeSummary = collectCodeChangeSummary(workspaceCandidateFiles),
            ).withComparisonContext(
                SpecDeltaComparisonContext(
                    baselineKind = SpecDeltaBaselineKind.PINNED_BASELINE,
                    baselineWorkflowId = workflowId,
                    targetWorkflowId = targetWorkflowId,
                    baselineId = baselineId,
                ),
            )
            appendBaselineSelectionAudit(
                targetWorkflowId = targetWorkflowId,
                baselineKind = "PINNED_BASELINE",
                baselineWorkflowId = workflowId,
                baselineId = baselineId,
                delta = delta,
            )
            delta
        }
    }

    private fun collectCodeChangeSummary(workspaceCandidateFiles: List<String>): CodeChangeSummary {
        val normalizedWorkspaceCandidateFiles = workspaceCandidateFiles
            .mapNotNull(::normalizePath)
            .distinct()
            .sorted()
        val projectRoot = resolveProjectRoot()
        val providerSummary = projectRoot?.let(codeChangeSummaryProvider)
        if (providerSummary != null) {
            return providerSummary
        }
        if (normalizedWorkspaceCandidateFiles.isNotEmpty()) {
            return CodeChangeSummary(
                source = CodeChangeSource.WORKSPACE_CANDIDATES,
                files = normalizedWorkspaceCandidateFiles.map { path ->
                    CodeChangeFile(
                        path = path,
                        status = CodeChangeFileStatus.UNKNOWN,
                    )
                },
                summary = "Git working tree was unavailable; using workspace candidate files as the code change summary.",
                available = true,
            )
        }
        return CodeChangeSummary.unavailable(
            "No Git working tree or workspace candidate changes were detected.",
        )
    }

    private fun resolveProjectRoot(): Path? {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) {
            return null
        }
        return runCatching { Path.of(basePath).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun normalizePath(rawPath: String): String? {
        val normalized = rawPath.trim()
            .replace('\\', '/')
            .trim('/')
        return normalized.takeIf { path ->
            path.isNotEmpty() &&
                EXCLUDED_DIFF_PATH_PREFIXES.none { prefix -> path.startsWith(prefix) }
        }
    }

    fun exportMarkdown(report: SpecWorkflowDelta): String {
        return SpecDeltaReportExporter.exportMarkdown(report)
    }

    fun exportHtml(report: SpecWorkflowDelta): String {
        return SpecDeltaReportExporter.exportHtml(report)
    }

    fun exportReport(
        report: SpecWorkflowDelta,
        format: SpecDeltaExportFormat,
    ): Result<SpecDeltaExportResult> {
        return runCatching {
            val basePath = project.basePath
                ?: throw IllegalStateException("Project base path is not available")
            val exportDir = Path.of(basePath, ".spec-coding", "exports", "delta")
            Files.createDirectories(exportDir)
            val fileName = buildExportFileName(report, format)
            val target = exportDir.resolve(fileName)
            val content = when (format) {
                SpecDeltaExportFormat.MARKDOWN -> exportMarkdown(report)
                SpecDeltaExportFormat.HTML -> exportHtml(report)
            }
            atomicFileIO.writeString(target, content, StandardCharsets.UTF_8)
            appendDeltaExportAudit(report, format, target)
            SpecDeltaExportResult(
                workflowId = report.targetWorkflowId,
                format = format,
                fileName = fileName,
                filePath = target,
            )
        }
    }

    private fun appendBaselineSelectionAudit(
        targetWorkflowId: String,
        baselineKind: String,
        baselineWorkflowId: String,
        delta: SpecWorkflowDelta,
        snapshotId: String? = null,
        baselineId: String? = null,
    ) {
        val details = linkedMapOf(
            "action" to "COMPARE",
            "baselineKind" to baselineKind,
            "baselineWorkflowId" to baselineWorkflowId,
            "targetWorkflowId" to targetWorkflowId,
            "artifactChangeCount" to delta.artifactDeltas.count { artifact -> artifact.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "taskChangeCount" to delta.taskSummary.changes.size.toString(),
            "codeChangeCount" to delta.codeChangesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "relatedFileChangeCount" to delta.relatedFilesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "verificationChanged" to delta.verificationSummary.hasChanges().toString(),
        )
        snapshotId?.let { normalizedSnapshotId ->
            details["snapshotId"] = normalizedSnapshotId
        }
        baselineId?.let { normalizedBaselineId ->
            details["baselineId"] = normalizedBaselineId
        }
        storage.appendAuditEvent(
            workflowId = targetWorkflowId,
            eventType = SpecAuditEventType.DELTA_BASELINE_SELECTED,
            details = details,
        ).getOrThrow()
    }

    private fun appendDeltaExportAudit(
        report: SpecWorkflowDelta,
        format: SpecDeltaExportFormat,
        target: Path,
    ) {
        val comparisonContext = report.effectiveComparisonContext()
        val projectRoot = project.basePath?.let(Path::of)
        val exportPath = projectRoot?.let { root ->
            runCatching { root.relativize(target).toString().replace('\\', '/') }
                .getOrElse { target.toString().replace('\\', '/') }
        } ?: target.toString().replace('\\', '/')
        val details = linkedMapOf(
            "action" to "EXPORT",
            "format" to format.name,
            "mediaType" to format.mediaType,
            "file" to exportPath,
            "baselineKind" to comparisonContext.baselineKind.name,
            "baselineWorkflowId" to comparisonContext.baselineWorkflowId,
            "targetWorkflowId" to comparisonContext.targetWorkflowId,
            "artifactChangeCount" to report.artifactDeltas.count { artifact -> artifact.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "taskChangeCount" to report.taskSummary.changes.size.toString(),
            "codeChangeCount" to report.codeChangesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "relatedFileChangeCount" to report.relatedFilesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED }.toString(),
            "verificationChanged" to report.verificationSummary.hasChanges().toString(),
        )
        comparisonContext.snapshotId?.let { snapshotId ->
            details["snapshotId"] = snapshotId
        }
        comparisonContext.baselineId?.let { baselineId ->
            details["baselineId"] = baselineId
        }
        storage.appendAuditEvent(
            workflowId = report.targetWorkflowId,
            eventType = SpecAuditEventType.DELTA_EXPORTED,
            details = details,
        ).getOrThrow()
    }

    private fun buildExportFileName(
        report: SpecWorkflowDelta,
        format: SpecDeltaExportFormat,
    ): String {
        val context = report.effectiveComparisonContext()
        val baselineToken = when (context.baselineKind) {
            SpecDeltaBaselineKind.WORKFLOW -> "workflow-${context.baselineWorkflowId}"
            SpecDeltaBaselineKind.SNAPSHOT -> "snapshot-${context.baselineWorkflowId}-${context.snapshotId ?: "unknown"}"
            SpecDeltaBaselineKind.PINNED_BASELINE -> "baseline-${context.baselineWorkflowId}-${context.baselineId ?: "unknown"}"
        }
        return "spec-delta-${sanitizeExportToken(context.targetWorkflowId)}-${sanitizeExportToken(baselineToken)}.${format.fileExtension}"
    }

    private fun sanitizeExportToken(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    companion object {
        fun getInstance(project: Project): SpecDeltaService = project.service()
    }
}

private fun SpecWorkflowDelta.withComparisonContext(
    context: SpecDeltaComparisonContext,
): SpecWorkflowDelta {
    return copy(comparisonContext = context)
}

private fun SpecWorkflowDelta.effectiveComparisonContext(): SpecDeltaComparisonContext {
    return comparisonContext ?: SpecDeltaComparisonContext(
        baselineKind = SpecDeltaBaselineKind.WORKFLOW,
        baselineWorkflowId = baselineWorkflowId,
        targetWorkflowId = targetWorkflowId,
    )
}

private object SpecDeltaReportExporter {
    private const val REPORT_SCHEMA_VERSION = 2

    fun exportMarkdown(report: SpecWorkflowDelta): String {
        val metadata = SpecYamlCodec.encodeMap(buildReplayMetadata(report)).trimEnd()
        val context = report.effectiveComparisonContext()
        val changedArtifacts = report.artifactDeltas.filter { artifact -> artifact.status != SpecDeltaStatus.UNCHANGED }
        val changedCodeFiles = report.codeChangesSummary.files.filter { file -> file.status != SpecDeltaStatus.UNCHANGED }
        val changedRelatedFiles = report.relatedFilesSummary.files.filter { file -> file.status != SpecDeltaStatus.UNCHANGED }
        return buildString {
            appendLine("---")
            appendLine(metadata)
            appendLine("---")
            appendLine()
            appendLine("# Spec Delta Report")
            appendLine()
            appendLine("## Summary")
            appendLine("- Baseline: ${formatMarkdownComparisonContext(context)}")
            appendLine("- Target workflow: `${report.targetWorkflowId}`")
            appendLine("- Has changes: `${report.hasChanges()}`")
            appendLine("- Artifact counts: `${formatArtifactCounts(report)}`")
            appendLine("- Task changes: `${report.taskSummary.changes.size}`")
            appendLine("- Code change files: `${changedCodeFiles.size}`")
            appendLine("- Related file changes: `${changedRelatedFiles.size}`")
            appendLine("- Verification changed: `${report.verificationSummary.hasChanges()}`")
            appendLine()
            appendLine("## Artifact Summary")
            appendLine("| Artifact | File | Status | + | - |")
            appendLine("| --- | --- | --- | ---: | ---: |")
            report.artifactDeltas.forEach { artifact ->
                appendLine(
                    "| ${escapeMarkdownCell(artifact.artifact.displayName)} | `${artifact.artifact.fileName}` | ${artifact.status.name} | ${artifact.addedLineCount} | ${artifact.removedLineCount} |",
                )
            }
            appendLine()
            appendLine("## Artifact Diffs")
            if (changedArtifacts.isEmpty()) {
                appendLine("No textual artifact changes detected.")
            } else {
                changedArtifacts.forEach { artifact ->
                    appendLine("### `${artifact.artifact.fileName}` · ${artifact.status.name}")
                    appendLine()
                    appendLine("```diff")
                    appendLine(buildUnifiedDiffText(artifact))
                    appendLine("```")
                    appendLine()
                }
            }
            appendLine("## Task Changes")
            appendLine("- Added: ${formatMarkdownList(report.taskSummary.addedTaskIds)}")
            appendLine("- Removed: ${formatMarkdownList(report.taskSummary.removedTaskIds)}")
            appendLine("- Completed: ${formatMarkdownList(report.taskSummary.completedTaskIds)}")
            appendLine("- Cancelled: ${formatMarkdownList(report.taskSummary.cancelledTaskIds)}")
            appendLine("- Status changed: ${formatMarkdownList(report.taskSummary.statusChangedTaskIds)}")
            appendLine("- Metadata changed: ${formatMarkdownList(report.taskSummary.metadataChangedTaskIds)}")
            if (!report.taskSummary.hasChanges()) {
                appendLine()
                appendLine("No task changes detected.")
            } else {
                appendLine()
                report.taskSummary.changes.forEach { change ->
                    appendLine("### `${change.taskId}` · ${change.status.name} · ${escapeMarkdownText(change.title)}")
                    appendLine("- Changed fields: ${formatMarkdownList(change.changedFields)}")
                    appendLine("- Baseline: ${escapeMarkdownText(formatTaskSnapshot(change.baselineTask))}")
                    appendLine("- Target: ${escapeMarkdownText(formatTaskSnapshot(change.targetTask))}")
                    appendLine()
                }
            }
            appendLine("## Code Changes Summary")
            appendLine("- Baseline reference: ${formatMarkdownComparisonContext(context)}")
            appendLine("- Source: `${report.codeChangesSummary.source.name}`")
            appendLine("- Summary: ${escapeMarkdownText(report.codeChangesSummary.summary.ifBlank { "No code change summary was collected." })}")
            appendLine("- Workspace candidates: ${formatMarkdownList(report.codeChangesSummary.workspaceCandidateFiles)}")
            if (report.codeChangesSummary.degradationReasons.isNotEmpty()) {
                report.codeChangesSummary.degradationReasons.forEach { reason ->
                    appendLine("- Degraded: ${escapeMarkdownText(reason)}")
                }
            }
            if (changedCodeFiles.isEmpty()) {
                appendLine()
                appendLine("No code changes detected.")
            } else {
                appendLine()
                appendLine("| Path | Status | Workspace | Baseline Tasks | Target Tasks | + | - | Hints |")
                appendLine("| --- | --- | --- | --- | --- | ---: | ---: | --- |")
                changedCodeFiles.forEach { file ->
                    appendLine(
                        "| `${file.path}` | ${file.status.name} | ${escapeMarkdownCell(formatWorkspaceStatus(file.workspaceStatus, file.presentInWorkspaceDiff, file.presentInWorkspaceCandidates))} | ${escapeMarkdownCell(formatPlainList(file.baselineTaskIds))} | ${escapeMarkdownCell(formatPlainList(file.targetTaskIds))} | ${file.addedLineCount} | ${file.removedLineCount} | ${escapeMarkdownCell(formatCodeChangeHints(file))} |",
                    )
                }
            }
            appendLine()
            appendLine("## Related Files Summary")
            appendLine("- Workspace candidates: ${formatMarkdownList(report.relatedFilesSummary.workspaceCandidateFiles)}")
            if (changedRelatedFiles.isEmpty()) {
                appendLine()
                appendLine("No related file changes detected.")
            } else {
                appendLine()
                appendLine("| Path | Status | Baseline Tasks | Target Tasks | Workspace |")
                appendLine("| --- | --- | --- | --- | --- |")
                changedRelatedFiles.forEach { file ->
                    appendLine(
                        "| `${file.path}` | ${file.status.name} | ${escapeMarkdownCell(formatPlainList(file.baselineTaskIds))} | ${escapeMarkdownCell(formatPlainList(file.targetTaskIds))} | ${if (file.presentInWorkspace) "yes" else "no"} |",
                    )
                }
            }
            appendLine()
            appendLine("## Verification Summary")
            appendLine("- Baseline artifact: ${escapeMarkdownText(formatVerificationArtifactSummary(report.verificationSummary.baselineArtifact))}")
            appendLine("- Target artifact: ${escapeMarkdownText(formatVerificationArtifactSummary(report.verificationSummary.targetArtifact))}")
            appendLine("- Task verification result changes: `${report.verificationSummary.taskResultChanges.size}`")
            if (report.verificationSummary.taskResultChanges.isEmpty()) {
                appendLine()
                appendLine("No task verification changes detected.")
            } else {
                appendLine()
                appendLine("| Task | Status | Baseline | Target |")
                appendLine("| --- | --- | --- | --- |")
                report.verificationSummary.taskResultChanges.forEach { change ->
                    appendLine(
                        "| `${change.taskId}` | ${change.status.name} | ${escapeMarkdownCell(formatVerificationResult(change.baselineResult))} | ${escapeMarkdownCell(formatVerificationResult(change.targetResult))} |",
                    )
                }
            }
        }.trimEnd() + "\n"
    }

    fun exportHtml(report: SpecWorkflowDelta): String {
        val context = report.effectiveComparisonContext()
        val changedArtifacts = report.artifactDeltas.filter { artifact -> artifact.status != SpecDeltaStatus.UNCHANGED }
        val changedCodeFiles = report.codeChangesSummary.files.filter { file -> file.status != SpecDeltaStatus.UNCHANGED }
        val changedRelatedFiles = report.relatedFilesSummary.files.filter { file -> file.status != SpecDeltaStatus.UNCHANGED }
        val metadata = escapeHtml(SpecYamlCodec.encodeMap(buildReplayMetadata(report)).trimEnd())
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"utf-8\">")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("  <title>${escapeHtml("Spec Delta Report - ${report.targetWorkflowId}")}</title>")
            appendLine("  <style>")
            appendLine("    :root { color-scheme: light; --bg: #f7f4ee; --panel: #fffdf8; --ink: #1f252d; --muted: #5d6773; --border: #d8d0c2; --added: #2f7d32; --modified: #1b6db3; --removed: #b23a2b; --unchanged: #6b7280; }")
            appendLine("    body { margin: 0; padding: 32px; background: linear-gradient(180deg, #efe8db 0%, #f7f4ee 100%); color: var(--ink); font: 14px/1.6 Georgia, 'Noto Serif', serif; }")
            appendLine("    main { max-width: 1180px; margin: 0 auto; display: grid; gap: 24px; }")
            appendLine("    section { background: var(--panel); border: 1px solid var(--border); border-radius: 16px; padding: 20px 22px; box-shadow: 0 12px 30px rgba(31, 37, 45, 0.06); }")
            appendLine("    h1, h2, h3 { margin: 0 0 12px; font-family: 'Segoe UI', 'PingFang SC', sans-serif; }")
            appendLine("    h1 { font-size: 28px; }")
            appendLine("    h2 { font-size: 18px; }")
            appendLine("    h3 { font-size: 15px; }")
            appendLine("    p, li { color: var(--muted); }")
            appendLine("    ul { margin: 0; padding-left: 20px; }")
            appendLine("    table { width: 100%; border-collapse: collapse; }")
            appendLine("    th, td { border-bottom: 1px solid var(--border); padding: 10px 8px; text-align: left; vertical-align: top; }")
            appendLine("    th { font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); }")
            appendLine("    code, pre { font-family: 'JetBrains Mono', 'Cascadia Code', monospace; }")
            appendLine("    pre { margin: 0; background: #201a17; color: #f7f4ee; border-radius: 12px; padding: 14px 16px; overflow-x: auto; white-space: pre-wrap; }")
            appendLine("    .status { display: inline-block; border-radius: 999px; padding: 2px 10px; font: 11px/1.8 'Segoe UI', sans-serif; letter-spacing: 0.08em; text-transform: uppercase; border: 1px solid currentColor; }")
            appendLine("    .status-added { color: var(--added); }")
            appendLine("    .status-modified { color: var(--modified); }")
            appendLine("    .status-removed { color: var(--removed); }")
            appendLine("    .status-unchanged { color: var(--unchanged); }")
            appendLine("    .artifact-diff { display: grid; gap: 12px; }")
            appendLine("    .meta { display: grid; gap: 8px; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <main>")
            appendLine("    <section>")
            appendLine("      <h1>Spec Delta Report</h1>")
            appendLine("      <div class=\"meta\">")
            appendLine("        <p><strong>Baseline:</strong> ${escapeHtml(formatHtmlComparisonContext(context))}</p>")
            appendLine("        <p><strong>Target workflow:</strong> <code>${escapeHtml(report.targetWorkflowId)}</code></p>")
            appendLine("        <p><strong>Has changes:</strong> <code>${report.hasChanges()}</code></p>")
            appendLine("      </div>")
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Replay Metadata</h2>")
            appendLine("      <pre>$metadata</pre>")
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Summary</h2>")
            appendLine("      <ul>")
            appendLine("        <li>Artifact counts: <code>${escapeHtml(formatArtifactCounts(report))}</code></li>")
            appendLine("        <li>Task changes: <code>${report.taskSummary.changes.size}</code></li>")
            appendLine("        <li>Code change files: <code>${changedCodeFiles.size}</code></li>")
            appendLine("        <li>Related file changes: <code>${changedRelatedFiles.size}</code></li>")
            appendLine("        <li>Verification changed: <code>${report.verificationSummary.hasChanges()}</code></li>")
            appendLine("      </ul>")
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Artifact Summary</h2>")
            appendLine("      <table>")
            appendLine("        <thead><tr><th>Artifact</th><th>File</th><th>Status</th><th>Added</th><th>Removed</th></tr></thead>")
            appendLine("        <tbody>")
            report.artifactDeltas.forEach { artifact ->
                appendLine(
                    "          <tr><td>${escapeHtml(artifact.artifact.displayName)}</td><td><code>${escapeHtml(artifact.artifact.fileName)}</code></td><td>${statusBadge(artifact.status)}</td><td>${artifact.addedLineCount}</td><td>${artifact.removedLineCount}</td></tr>",
                )
            }
            appendLine("        </tbody>")
            appendLine("      </table>")
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Artifact Diffs</h2>")
            if (changedArtifacts.isEmpty()) {
                appendLine("      <p>No textual artifact changes detected.</p>")
            } else {
                changedArtifacts.forEach { artifact ->
                    appendLine("      <article class=\"artifact-diff\">")
                    appendLine("        <h3><code>${escapeHtml(artifact.artifact.fileName)}</code> ${statusBadge(artifact.status)}</h3>")
                    appendLine("        <pre>${escapeHtml(buildUnifiedDiffText(artifact))}</pre>")
                    appendLine("      </article>")
                }
            }
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Task Changes</h2>")
            appendLine("      <ul>")
            appendLine("        <li>Added: ${escapeHtml(formatPlainList(report.taskSummary.addedTaskIds))}</li>")
            appendLine("        <li>Removed: ${escapeHtml(formatPlainList(report.taskSummary.removedTaskIds))}</li>")
            appendLine("        <li>Completed: ${escapeHtml(formatPlainList(report.taskSummary.completedTaskIds))}</li>")
            appendLine("        <li>Cancelled: ${escapeHtml(formatPlainList(report.taskSummary.cancelledTaskIds))}</li>")
            appendLine("        <li>Status changed: ${escapeHtml(formatPlainList(report.taskSummary.statusChangedTaskIds))}</li>")
            appendLine("        <li>Metadata changed: ${escapeHtml(formatPlainList(report.taskSummary.metadataChangedTaskIds))}</li>")
            appendLine("      </ul>")
            if (!report.taskSummary.hasChanges()) {
                appendLine("      <p>No task changes detected.</p>")
            } else {
                report.taskSummary.changes.forEach { change ->
                    appendLine("      <article>")
                    appendLine("        <h3><code>${escapeHtml(change.taskId)}</code> ${statusBadge(change.status)} ${escapeHtml(change.title)}</h3>")
                    appendLine("        <p><strong>Changed fields:</strong> ${escapeHtml(formatPlainList(change.changedFields))}</p>")
                    appendLine("        <p><strong>Baseline:</strong> ${escapeHtml(formatTaskSnapshot(change.baselineTask))}</p>")
                    appendLine("        <p><strong>Target:</strong> ${escapeHtml(formatTaskSnapshot(change.targetTask))}</p>")
                    appendLine("      </article>")
                }
            }
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Code Changes Summary</h2>")
            appendLine("      <ul>")
            appendLine("        <li>Baseline reference: ${escapeHtml(formatHtmlComparisonContext(context))}</li>")
            appendLine("        <li>Source: <code>${report.codeChangesSummary.source.name}</code></li>")
            appendLine("        <li>Summary: ${escapeHtml(report.codeChangesSummary.summary.ifBlank { "No code change summary was collected." })}</li>")
            appendLine("        <li>Workspace candidates: ${escapeHtml(formatPlainList(report.codeChangesSummary.workspaceCandidateFiles))}</li>")
            appendLine("      </ul>")
            if (report.codeChangesSummary.degradationReasons.isNotEmpty()) {
                appendLine("      <ul>")
                report.codeChangesSummary.degradationReasons.forEach { reason ->
                    appendLine("        <li>Degraded: ${escapeHtml(reason)}</li>")
                }
                appendLine("      </ul>")
            }
            if (changedCodeFiles.isEmpty()) {
                appendLine("      <p>No code changes detected.</p>")
            } else {
                appendLine("      <table>")
                appendLine("        <thead><tr><th>Path</th><th>Status</th><th>Workspace</th><th>Baseline tasks</th><th>Target tasks</th><th>Added</th><th>Removed</th><th>Hints</th></tr></thead>")
                appendLine("        <tbody>")
                changedCodeFiles.forEach { file ->
                    appendLine(
                        "          <tr><td><code>${escapeHtml(file.path)}</code></td><td>${statusBadge(file.status)}</td><td>${escapeHtml(formatWorkspaceStatus(file.workspaceStatus, file.presentInWorkspaceDiff, file.presentInWorkspaceCandidates))}</td><td>${escapeHtml(formatPlainList(file.baselineTaskIds))}</td><td>${escapeHtml(formatPlainList(file.targetTaskIds))}</td><td>${file.addedLineCount}</td><td>${file.removedLineCount}</td><td>${escapeHtml(formatCodeChangeHints(file))}</td></tr>",
                    )
                }
                appendLine("        </tbody>")
                appendLine("      </table>")
            }
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Related Files Summary</h2>")
            appendLine("      <p><strong>Workspace candidates:</strong> ${escapeHtml(formatPlainList(report.relatedFilesSummary.workspaceCandidateFiles))}</p>")
            if (changedRelatedFiles.isEmpty()) {
                appendLine("      <p>No related file changes detected.</p>")
            } else {
                appendLine("      <table>")
                appendLine("        <thead><tr><th>Path</th><th>Status</th><th>Baseline tasks</th><th>Target tasks</th><th>Workspace</th></tr></thead>")
                appendLine("        <tbody>")
                changedRelatedFiles.forEach { file ->
                    appendLine(
                        "          <tr><td><code>${escapeHtml(file.path)}</code></td><td>${statusBadge(file.status)}</td><td>${escapeHtml(formatPlainList(file.baselineTaskIds))}</td><td>${escapeHtml(formatPlainList(file.targetTaskIds))}</td><td>${if (file.presentInWorkspace) "yes" else "no"}</td></tr>",
                    )
                }
                appendLine("        </tbody>")
                appendLine("      </table>")
            }
            appendLine("    </section>")
            appendLine("    <section>")
            appendLine("      <h2>Verification Summary</h2>")
            appendLine("      <ul>")
            appendLine("        <li>Baseline artifact: ${escapeHtml(formatVerificationArtifactSummary(report.verificationSummary.baselineArtifact))}</li>")
            appendLine("        <li>Target artifact: ${escapeHtml(formatVerificationArtifactSummary(report.verificationSummary.targetArtifact))}</li>")
            appendLine("        <li>Task verification result changes: <code>${report.verificationSummary.taskResultChanges.size}</code></li>")
            appendLine("      </ul>")
            if (report.verificationSummary.taskResultChanges.isEmpty()) {
                appendLine("      <p>No task verification changes detected.</p>")
            } else {
                appendLine("      <table>")
                appendLine("        <thead><tr><th>Task</th><th>Status</th><th>Baseline</th><th>Target</th></tr></thead>")
                appendLine("        <tbody>")
                report.verificationSummary.taskResultChanges.forEach { change ->
                    appendLine(
                        "          <tr><td><code>${escapeHtml(change.taskId)}</code></td><td>${statusBadge(change.status)}</td><td>${escapeHtml(formatVerificationResult(change.baselineResult))}</td><td>${escapeHtml(formatVerificationResult(change.targetResult))}</td></tr>",
                    )
                }
                appendLine("        </tbody>")
                appendLine("      </table>")
            }
            appendLine("    </section>")
            appendLine("  </main>")
            appendLine("</body>")
            appendLine("</html>")
        }.trimEnd() + "\n"
    }

    private fun buildReplayMetadata(report: SpecWorkflowDelta): Map<String, Any?> {
        val context = report.effectiveComparisonContext()
        val artifactCounts = linkedMapOf(
            "added" to report.count(SpecDeltaStatus.ADDED),
            "modified" to report.count(SpecDeltaStatus.MODIFIED),
            "removed" to report.count(SpecDeltaStatus.REMOVED),
            "unchanged" to report.count(SpecDeltaStatus.UNCHANGED),
        )
        val taskCounts = linkedMapOf(
            "changed" to report.taskSummary.changes.size,
            "added" to report.taskSummary.addedTaskIds.size,
            "removed" to report.taskSummary.removedTaskIds.size,
            "completed" to report.taskSummary.completedTaskIds.size,
            "cancelled" to report.taskSummary.cancelledTaskIds.size,
        )
        val codeChangeCounts = linkedMapOf(
            "changed" to report.codeChangesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED },
            "added" to report.codeChangesSummary.count(SpecDeltaStatus.ADDED),
            "modified" to report.codeChangesSummary.count(SpecDeltaStatus.MODIFIED),
            "removed" to report.codeChangesSummary.count(SpecDeltaStatus.REMOVED),
            "workspaceCandidates" to report.codeChangesSummary.workspaceCandidateFiles.size,
        )
        val relatedFileCounts = linkedMapOf(
            "changed" to report.relatedFilesSummary.files.count { file -> file.status != SpecDeltaStatus.UNCHANGED },
            "workspaceCandidates" to report.relatedFilesSummary.workspaceCandidateFiles.size,
        )
        return linkedMapOf<String, Any?>(
            "schemaVersion" to REPORT_SCHEMA_VERSION,
            "reportType" to "spec-delta",
            "baselineKind" to context.baselineKind.name,
            "baselineWorkflowId" to context.baselineWorkflowId,
            "targetWorkflowId" to context.targetWorkflowId,
            "snapshotId" to context.snapshotId,
            "baselineId" to context.baselineId,
            "hasChanges" to report.hasChanges(),
            "artifacts" to report.artifactDeltas.map { artifact -> artifact.artifact.fileName },
            "artifactCounts" to artifactCounts,
            "taskCounts" to taskCounts,
            "codeChangeSource" to report.codeChangesSummary.source.name,
            "codeChangeCounts" to codeChangeCounts,
            "relatedFileCounts" to relatedFileCounts,
            "verificationChanged" to report.verificationSummary.hasChanges(),
        )
    }

    private fun formatArtifactCounts(report: SpecWorkflowDelta): String {
        return listOf(
            "ADDED=${report.count(SpecDeltaStatus.ADDED)}",
            "MODIFIED=${report.count(SpecDeltaStatus.MODIFIED)}",
            "REMOVED=${report.count(SpecDeltaStatus.REMOVED)}",
            "UNCHANGED=${report.count(SpecDeltaStatus.UNCHANGED)}",
        ).joinToString(", ")
    }

    private fun formatMarkdownComparisonContext(context: SpecDeltaComparisonContext): String {
        val suffix = when (context.baselineKind) {
            SpecDeltaBaselineKind.WORKFLOW -> context.baselineKind.name
            SpecDeltaBaselineKind.SNAPSHOT -> "${context.baselineKind.name} / snapshot=${context.snapshotId ?: "n/a"}"
            SpecDeltaBaselineKind.PINNED_BASELINE -> "${context.baselineKind.name} / baseline=${context.baselineId ?: "n/a"}"
        }
        return "`${context.baselineWorkflowId}` ($suffix)"
    }

    private fun formatHtmlComparisonContext(context: SpecDeltaComparisonContext): String {
        return formatMarkdownComparisonContext(context).replace("`", "")
    }

    private fun buildUnifiedDiffText(artifact: SpecArtifactDelta): String {
        val header = listOf(
            "--- baseline/${artifact.artifact.fileName}",
            "+++ target/${artifact.artifact.fileName}",
            "@@ added=${artifact.addedLineCount} removed=${artifact.removedLineCount} @@",
        )
        return (header + artifact.unifiedDiff.trimEnd().lines())
            .filter { line -> line.isNotBlank() }
            .joinToString("\n")
    }

    private fun formatTaskSnapshot(task: StructuredTask?): String {
        if (task == null) {
            return "missing"
        }
        return buildString {
            append("status=${task.status}")
            append(", priority=${task.priority}")
            append(", dependsOn=${formatPlainList(task.dependsOn)}")
            append(", relatedFiles=${formatPlainList(task.relatedFiles)}")
            append(", verification=${formatVerificationResult(task.verificationResult)}")
        }
    }

    private fun formatVerificationArtifactSummary(summary: SpecVerificationArtifactSummary): String {
        if (!summary.documentAvailable) {
            return "missing"
        }
        return listOfNotNull(
            "conclusion=${summary.conclusion?.name ?: "n/a"}",
            summary.runId?.let { runId -> "runId=$runId" },
            summary.executedAt?.let { executedAt -> "at=$executedAt" },
            summary.summary.takeIf { it.isNotBlank() }?.let { text -> "summary=$text" },
        ).joinToString(", ")
            .ifBlank { "document available" }
    }

    private fun formatVerificationResult(result: TaskVerificationResult?): String {
        if (result == null) {
            return "none"
        }
        return listOf(
            result.conclusion.name,
            result.runId,
            result.at,
            result.summary.ifBlank { "no summary" },
        ).joinToString(" | ")
    }

    private fun formatMarkdownList(items: List<String>): String {
        if (items.isEmpty()) {
            return "`none`"
        }
        return items.joinToString(", ") { item -> "`${escapeMarkdownText(item)}`" }
    }

    private fun formatPlainList(items: List<String>): String {
        return items.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"
    }

    private fun formatWorkspaceStatus(
        workspaceStatus: CodeChangeFileStatus?,
        presentInWorkspaceDiff: Boolean,
        presentInWorkspaceCandidates: Boolean,
    ): String {
        return when {
            workspaceStatus != null -> workspaceStatus.name
            presentInWorkspaceDiff -> "WORKSPACE_DIFF"
            presentInWorkspaceCandidates -> "WORKSPACE_CANDIDATE"
            else -> "none"
        }
    }

    private fun formatCodeChangeHints(file: SpecCodeFileDelta): String {
        val hints = buildList {
            if (file.symbolChanges.isNotEmpty()) {
                add("symbols: ${file.symbolChanges.joinToString("; ")}")
            }
            if (file.apiChanges.isNotEmpty()) {
                add("api: ${file.apiChanges.joinToString("; ")}")
            }
        }
        return hints.joinToString(" | ").ifBlank { "none" }
    }

    private fun escapeMarkdownCell(raw: String): String {
        return raw
            .replace("|", "\\|")
            .replace("\r\n", " ")
            .replace('\n', ' ')
    }

    private fun escapeMarkdownText(raw: String): String {
        return raw.replace('\r', ' ').replace('\n', ' ')
    }

    private fun escapeHtml(raw: String): String {
        return buildString(raw.length) {
            raw.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }
    }

    private fun statusBadge(status: SpecDeltaStatus): String {
        val className = when (status) {
            SpecDeltaStatus.ADDED -> "status-added"
            SpecDeltaStatus.MODIFIED -> "status-modified"
            SpecDeltaStatus.REMOVED -> "status-removed"
            SpecDeltaStatus.UNCHANGED -> "status-unchanged"
        }
        return "<span class=\"status $className\">${status.name}</span>"
    }
}

object SpecDeltaCalculator {
    fun compareWorkflows(
        baselineWorkflow: SpecWorkflow,
        targetWorkflow: SpecWorkflow,
        baselineVerificationContent: String? = null,
        targetVerificationContent: String? = null,
        workspaceCandidateFiles: List<String> = emptyList(),
        codeChangeSummary: CodeChangeSummary = CodeChangeSummary(),
    ): SpecWorkflowDelta {
        val artifactDeltas = buildList {
            add(
                compareArtifact(
                    artifact = SpecDeltaArtifact.REQUIREMENTS,
                    baselineDocument = baselineWorkflow.documents[SpecPhase.SPECIFY],
                    targetDocument = targetWorkflow.documents[SpecPhase.SPECIFY],
                ),
            )
            add(
                compareArtifact(
                    artifact = SpecDeltaArtifact.DESIGN,
                    baselineDocument = baselineWorkflow.documents[SpecPhase.DESIGN],
                    targetDocument = targetWorkflow.documents[SpecPhase.DESIGN],
                ),
            )
            add(
                compareArtifact(
                    artifact = SpecDeltaArtifact.TASKS,
                    baselineDocument = baselineWorkflow.documents[SpecPhase.IMPLEMENT],
                    targetDocument = targetWorkflow.documents[SpecPhase.IMPLEMENT],
                ),
            )

            if (
                baselineVerificationContent != null ||
                targetVerificationContent != null ||
                baselineWorkflow.verifyEnabled ||
                targetWorkflow.verifyEnabled
            ) {
                add(
                    compareArtifact(
                        artifact = SpecDeltaArtifact.VERIFICATION,
                        baselineContent = baselineVerificationContent,
                        targetContent = targetVerificationContent,
                    ),
                )
            }
        }

        val phaseDeltas = artifactDeltas
            .mapNotNull { artifactDelta ->
                artifactDelta.artifact.phase?.let { phase ->
                    SpecPhaseDelta(
                        phase = phase,
                        status = artifactDelta.status,
                        baselineDocument = artifactDelta.baselineDocument,
                        targetDocument = artifactDelta.targetDocument,
                        addedLineCount = artifactDelta.addedLineCount,
                        removedLineCount = artifactDelta.removedLineCount,
                        unifiedDiff = artifactDelta.unifiedDiff,
                    )
                }
            }

        val baselineTasks = parseTasks(baselineWorkflow.documents[SpecPhase.IMPLEMENT]?.content)
        val targetTasks = parseTasks(targetWorkflow.documents[SpecPhase.IMPLEMENT]?.content)

        return SpecWorkflowDelta(
            baselineWorkflowId = baselineWorkflow.id,
            targetWorkflowId = targetWorkflow.id,
            phaseDeltas = phaseDeltas,
            artifactDeltas = artifactDeltas,
            taskSummary = buildTaskSummary(baselineTasks, targetTasks),
            codeChangesSummary = buildCodeChangesSummary(
                baselineTasks = baselineTasks,
                targetTasks = targetTasks,
                workspaceCandidateFiles = workspaceCandidateFiles,
                codeChangeSummary = codeChangeSummary,
            ),
            relatedFilesSummary = buildRelatedFilesSummary(
                baselineTasks = baselineTasks,
                targetTasks = targetTasks,
                workspaceCandidateFiles = workspaceCandidateFiles,
            ),
            verificationSummary = buildVerificationSummary(
                baselineTasks = baselineTasks,
                targetTasks = targetTasks,
                baselineVerificationContent = baselineVerificationContent,
                targetVerificationContent = targetVerificationContent,
            ),
        )
    }

    private fun compareArtifact(
        artifact: SpecDeltaArtifact,
        baselineDocument: SpecDocument? = null,
        targetDocument: SpecDocument? = null,
        baselineContent: String? = baselineDocument?.content,
        targetContent: String? = targetDocument?.content,
    ): SpecArtifactDelta {
        val status = resolveStatus(baselineContent, targetContent)
        val diffStats = computeLineDiffStats(baselineContent, targetContent)
        return SpecArtifactDelta(
            artifact = artifact,
            status = status,
            baselineContent = baselineContent,
            targetContent = targetContent,
            baselineDocument = baselineDocument,
            targetDocument = targetDocument,
            addedLineCount = diffStats.addedLines,
            removedLineCount = diffStats.removedLines,
            unifiedDiff = if (status == SpecDeltaStatus.UNCHANGED) {
                ""
            } else {
                buildUnifiedDiff(baselineContent, targetContent)
            },
        )
    }

    private fun resolveStatus(
        baselineContent: String?,
        targetContent: String?,
    ): SpecDeltaStatus {
        return when {
            baselineContent == null && targetContent != null -> SpecDeltaStatus.ADDED
            baselineContent != null && targetContent == null -> SpecDeltaStatus.REMOVED
            baselineContent != null && targetContent != null -> {
                if (normalizeContent(baselineContent) == normalizeContent(targetContent)) {
                    SpecDeltaStatus.UNCHANGED
                } else {
                    SpecDeltaStatus.MODIFIED
                }
            }

            else -> SpecDeltaStatus.UNCHANGED
        }
    }

    private fun buildTaskSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
    ): SpecTaskDeltaSummary {
        val changes = mutableListOf<SpecTaskDelta>()
        val addedTaskIds = mutableListOf<String>()
        val removedTaskIds = mutableListOf<String>()
        val completedTaskIds = mutableListOf<String>()
        val cancelledTaskIds = mutableListOf<String>()
        val statusChangedTaskIds = mutableListOf<String>()
        val metadataChangedTaskIds = mutableListOf<String>()

        (baselineTasks.keys + targetTasks.keys)
            .toSortedSet()
            .forEach { taskId ->
                val baselineTask = baselineTasks[taskId]
                val targetTask = targetTasks[taskId]
                val changedFields = mutableListOf<String>()

                when {
                    baselineTask == null && targetTask != null -> {
                        addedTaskIds += taskId
                        changes += SpecTaskDelta(
                            taskId = taskId,
                            title = targetTask.title,
                            baselineTask = null,
                            targetTask = targetTask,
                            changedFields = emptyList(),
                        )
                    }

                    baselineTask != null && targetTask == null -> {
                        removedTaskIds += taskId
                        changes += SpecTaskDelta(
                            taskId = taskId,
                            title = baselineTask.title,
                            baselineTask = baselineTask,
                            targetTask = null,
                            changedFields = emptyList(),
                        )
                    }

                    baselineTask != null && targetTask != null -> {
                        if (baselineTask.title != targetTask.title) {
                            changedFields += "title"
                        }
                        if (baselineTask.status != targetTask.status) {
                            changedFields += "status"
                        }
                        if (baselineTask.priority != targetTask.priority) {
                            changedFields += "priority"
                        }
                        if (baselineTask.dependsOn != targetTask.dependsOn) {
                            changedFields += "dependsOn"
                        }
                        if (baselineTask.relatedFiles != targetTask.relatedFiles) {
                            changedFields += "relatedFiles"
                        }
                        if (baselineTask.verificationResult != targetTask.verificationResult) {
                            changedFields += "verificationResult"
                        }
                        if (changedFields.isNotEmpty()) {
                            changes += SpecTaskDelta(
                                taskId = taskId,
                                title = targetTask.title,
                                baselineTask = baselineTask,
                                targetTask = targetTask,
                                changedFields = changedFields.toList(),
                            )
                        }
                        if (baselineTask.status != targetTask.status) {
                            statusChangedTaskIds += taskId
                        }
                        if (changedFields.any { field -> field != "status" }) {
                            metadataChangedTaskIds += taskId
                        }
                    }
                }

                if (baselineTask?.status != TaskStatus.COMPLETED && targetTask?.status == TaskStatus.COMPLETED) {
                    completedTaskIds += taskId
                }
                if (baselineTask?.status != TaskStatus.CANCELLED && targetTask?.status == TaskStatus.CANCELLED) {
                    cancelledTaskIds += taskId
                }
            }

        return SpecTaskDeltaSummary(
            changes = changes.sortedBy(SpecTaskDelta::taskId),
            addedTaskIds = addedTaskIds.sorted(),
            removedTaskIds = removedTaskIds.sorted(),
            completedTaskIds = completedTaskIds.sorted(),
            cancelledTaskIds = cancelledTaskIds.sorted(),
            statusChangedTaskIds = statusChangedTaskIds.sorted(),
            metadataChangedTaskIds = metadataChangedTaskIds.sorted(),
        )
    }

    private fun buildCodeChangesSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
        workspaceCandidateFiles: List<String>,
        codeChangeSummary: CodeChangeSummary,
    ): SpecCodeChangesDeltaSummary {
        val baselineByFile = indexRelatedFiles(baselineTasks.values)
        val targetByFile = indexRelatedFiles(targetTasks.values)
        val normalizedWorkspaceFiles = workspaceCandidateFiles
            .mapNotNull(::normalizePath)
            .distinct()
            .sorted()
        val workspaceSet = normalizedWorkspaceFiles.toSet()
        val codeChangesByFile = linkedMapOf<String, CodeChangeFile>()

        codeChangeSummary.files.forEach { rawChange ->
            val normalizedPath = normalizePath(rawChange.path) ?: return@forEach
            val normalizedChange = rawChange.copy(path = normalizedPath)
            val existing = codeChangesByFile[normalizedPath]
            codeChangesByFile[normalizedPath] = if (existing == null) {
                normalizedChange
            } else {
                mergeCodeChangeFile(existing, normalizedChange)
            }
        }

        val files = (baselineByFile.keys + targetByFile.keys + workspaceSet + codeChangesByFile.keys)
            .toSortedSet()
            .map { path ->
                val change = codeChangesByFile[path]
                val baselineTaskIds = baselineByFile[path].orEmpty()
                val targetTaskIds = targetByFile[path].orEmpty()
                val presentInWorkspaceCandidates = workspaceSet.contains(path)
                SpecCodeFileDelta(
                    path = path,
                    status = resolveCodeFileStatus(
                        workspaceStatus = change?.status,
                        baselineTaskIds = baselineTaskIds,
                        targetTaskIds = targetTaskIds,
                        presentInWorkspaceCandidates = presentInWorkspaceCandidates,
                    ),
                    workspaceStatus = change?.status,
                    baselineTaskIds = baselineTaskIds,
                    targetTaskIds = targetTaskIds,
                    presentInWorkspaceDiff = change != null,
                    presentInWorkspaceCandidates = presentInWorkspaceCandidates,
                    addedLineCount = change?.addedLineCount ?: 0,
                    removedLineCount = change?.removedLineCount ?: 0,
                    symbolChanges = change?.symbolChanges.orEmpty(),
                    apiChanges = change?.apiChanges.orEmpty(),
                )
            }

        return SpecCodeChangesDeltaSummary(
            source = codeChangeSummary.source,
            summary = codeChangeSummary.summary.ifBlank {
                if (files.isEmpty()) {
                    "No code change summary was collected."
                } else {
                    "Aggregated ${files.size} file-level code change signal(s)."
                }
            },
            files = files,
            workspaceCandidateFiles = normalizedWorkspaceFiles,
            degradationReasons = if (codeChangeSummary.available) {
                emptyList()
            } else {
                listOf(codeChangeSummary.summary.ifBlank { "Code change signals were unavailable." })
            },
        )
    }

    private fun buildRelatedFilesSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
        workspaceCandidateFiles: List<String>,
    ): SpecRelatedFilesDeltaSummary {
        val baselineByFile = indexRelatedFiles(baselineTasks.values)
        val targetByFile = indexRelatedFiles(targetTasks.values)
        val normalizedWorkspaceFiles = workspaceCandidateFiles
            .mapNotNull(::normalizePath)
            .distinct()
            .sorted()
        val workspaceSet = normalizedWorkspaceFiles.toSet()

        val files = (baselineByFile.keys + targetByFile.keys + workspaceSet)
            .toSortedSet()
            .map { path ->
                val baselineTaskIds = baselineByFile[path].orEmpty()
                val targetTaskIds = targetByFile[path].orEmpty()
                val presentInWorkspace = workspaceSet.contains(path)
                val status = when {
                    baselineTaskIds.isEmpty() && targetTaskIds.isNotEmpty() -> SpecDeltaStatus.ADDED
                    baselineTaskIds.isNotEmpty() && targetTaskIds.isEmpty() -> SpecDeltaStatus.REMOVED
                    baselineTaskIds != targetTaskIds || presentInWorkspace -> SpecDeltaStatus.MODIFIED
                    else -> SpecDeltaStatus.UNCHANGED
                }
                SpecRelatedFileDelta(
                    path = path,
                    status = status,
                    baselineTaskIds = baselineTaskIds,
                    targetTaskIds = targetTaskIds,
                    presentInWorkspace = presentInWorkspace,
                )
            }

        return SpecRelatedFilesDeltaSummary(
            files = files,
            workspaceCandidateFiles = normalizedWorkspaceFiles,
        )
    }

    private fun mergeCodeChangeFile(
        current: CodeChangeFile,
        next: CodeChangeFile,
    ): CodeChangeFile {
        return CodeChangeFile(
            path = current.path,
            status = if (codeChangeStatusPriority(next.status) < codeChangeStatusPriority(current.status)) {
                next.status
            } else {
                current.status
            },
            addedLineCount = maxOf(current.addedLineCount, next.addedLineCount),
            removedLineCount = maxOf(current.removedLineCount, next.removedLineCount),
            symbolChanges = (current.symbolChanges + next.symbolChanges).distinct(),
            apiChanges = (current.apiChanges + next.apiChanges).distinct(),
        )
    }

    private fun resolveCodeFileStatus(
        workspaceStatus: CodeChangeFileStatus?,
        baselineTaskIds: List<String>,
        targetTaskIds: List<String>,
        presentInWorkspaceCandidates: Boolean,
    ): SpecDeltaStatus {
        return when (workspaceStatus) {
            CodeChangeFileStatus.ADDED,
            CodeChangeFileStatus.UNTRACKED,
            -> SpecDeltaStatus.ADDED

            CodeChangeFileStatus.REMOVED,
            CodeChangeFileStatus.MISSING,
            -> SpecDeltaStatus.REMOVED

            CodeChangeFileStatus.MODIFIED,
            CodeChangeFileStatus.CONFLICTED,
            CodeChangeFileStatus.UNKNOWN,
            -> SpecDeltaStatus.MODIFIED

            null -> when {
                baselineTaskIds.isEmpty() && targetTaskIds.isNotEmpty() -> SpecDeltaStatus.ADDED
                baselineTaskIds.isNotEmpty() && targetTaskIds.isEmpty() -> SpecDeltaStatus.REMOVED
                baselineTaskIds != targetTaskIds || presentInWorkspaceCandidates -> SpecDeltaStatus.MODIFIED
                else -> SpecDeltaStatus.UNCHANGED
            }
        }
    }

    private fun buildVerificationSummary(
        baselineTasks: Map<String, StructuredTask>,
        targetTasks: Map<String, StructuredTask>,
        baselineVerificationContent: String?,
        targetVerificationContent: String?,
    ): SpecVerificationDeltaSummary {
        val taskResultChanges = (baselineTasks.keys + targetTasks.keys)
            .toSortedSet()
            .map { taskId ->
                SpecTaskVerificationDelta(
                    taskId = taskId,
                    baselineResult = baselineTasks[taskId]?.verificationResult,
                    targetResult = targetTasks[taskId]?.verificationResult,
                )
            }
            .filter { change -> change.status != SpecDeltaStatus.UNCHANGED }

        return SpecVerificationDeltaSummary(
            baselineArtifact = parseVerificationArtifactSummary(baselineVerificationContent),
            targetArtifact = parseVerificationArtifactSummary(targetVerificationContent),
            taskResultChanges = taskResultChanges,
        )
    }

    private fun parseTasks(markdown: String?): Map<String, StructuredTask> {
        if (markdown.isNullOrBlank()) {
            return emptyMap()
        }
        return SpecTaskMarkdownParser.parse(markdown).tasks
            .mapNotNull { entry -> entry.toStructuredTaskOrNull() }
            .sortedBy(StructuredTask::id)
            .associateBy(StructuredTask::id)
    }

    private fun indexRelatedFiles(tasks: Collection<StructuredTask>): Map<String, List<String>> {
        val fileToTasks = linkedMapOf<String, MutableSet<String>>()
        tasks.forEach { task ->
            task.relatedFiles.forEach { rawPath ->
                val normalizedPath = normalizePath(rawPath) ?: return@forEach
                fileToTasks.getOrPut(normalizedPath) { linkedSetOf() }.add(task.id)
            }
        }
        return fileToTasks.mapValues { (_, taskIds) -> taskIds.toList().sorted() }
    }

    private fun parseVerificationArtifactSummary(content: String?): SpecVerificationArtifactSummary {
        if (content.isNullOrBlank()) {
            return SpecVerificationArtifactSummary(documentAvailable = false)
        }
        val normalized = normalizeContent(content)
        val lines = normalized.lines()
        val resultHeaderIndex = lines.indexOfFirst { line -> line.trim() == "## Result" }
        if (resultHeaderIndex < 0) {
            return SpecVerificationArtifactSummary(
                documentAvailable = true,
                summary = normalized.lineSequence().firstOrNull().orEmpty(),
            )
        }

        val yamlBlock = lines
            .drop(resultHeaderIndex + 1)
            .dropWhile(String::isBlank)
            .takeWhile { line ->
                val trimmed = line.trim()
                !(trimmed.startsWith("# ") || trimmed.startsWith("## "))
            }
            .joinToString("\n")
            .trim()

        if (yamlBlock.isBlank()) {
            return SpecVerificationArtifactSummary(documentAvailable = true)
        }

        val result = runCatching { SpecYamlCodec.decodeMap(yamlBlock) }.getOrDefault(emptyMap())
        return SpecVerificationArtifactSummary(
            documentAvailable = true,
            conclusion = parseVerificationConclusion(result["conclusion"]),
            runId = result["runId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            executedAt = result["at"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            summary = result["summary"]?.toString()?.trim().orEmpty(),
        )
    }

    private fun parseVerificationConclusion(raw: Any?): VerificationConclusion? {
        val normalized = raw?.toString()?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return VerificationConclusion.entries.firstOrNull { conclusion -> conclusion.name == normalized }
    }

    private data class LineDiffStats(
        val addedLines: Int,
        val removedLines: Int,
    )

    private fun computeLineDiffStats(
        baselineContent: String?,
        targetContent: String?,
    ): LineDiffStats {
        val baselineLines = normalizeLines(baselineContent)
        val targetLines = normalizeLines(targetContent)
        if (baselineLines.isEmpty() && targetLines.isEmpty()) {
            return LineDiffStats(0, 0)
        }
        val lcs = lcsLength(baselineLines, targetLines)
        return LineDiffStats(
            addedLines = (targetLines.size - lcs).coerceAtLeast(0),
            removedLines = (baselineLines.size - lcs).coerceAtLeast(0),
        )
    }

    private fun buildUnifiedDiff(
        baselineContent: String?,
        targetContent: String?,
    ): String {
        val baselineLines = normalizeLines(baselineContent)
        val targetLines = normalizeLines(targetContent)
        if (baselineLines.isEmpty() && targetLines.isEmpty()) {
            return ""
        }
        if (baselineLines.isEmpty()) {
            return targetLines.joinToString("\n") { line -> "+ $line" }
        }
        if (targetLines.isEmpty()) {
            return baselineLines.joinToString("\n") { line -> "- $line" }
        }

        val rows = baselineLines.size
        val cols = targetLines.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (row in 1..rows) {
            for (col in 1..cols) {
                dp[row][col] = if (baselineLines[row - 1] == targetLines[col - 1]) {
                    dp[row - 1][col - 1] + 1
                } else {
                    maxOf(dp[row - 1][col], dp[row][col - 1])
                }
            }
        }

        val operations = mutableListOf<String>()
        var row = rows
        var col = cols
        while (row > 0 || col > 0) {
            when {
                row > 0 && col > 0 && baselineLines[row - 1] == targetLines[col - 1] -> {
                    operations += "  ${baselineLines[row - 1]}"
                    row -= 1
                    col -= 1
                }

                col > 0 && (row == 0 || dp[row][col - 1] >= dp[row - 1][col]) -> {
                    operations += "+ ${targetLines[col - 1]}"
                    col -= 1
                }

                else -> {
                    operations += "- ${baselineLines[row - 1]}"
                    row -= 1
                }
            }
        }
        return operations
            .asReversed()
            .joinToString("\n")
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun normalizeLines(content: String?): List<String> {
        if (content.isNullOrBlank()) {
            return emptyList()
        }
        val normalized = normalizeContent(content)
        if (normalized.isBlank()) {
            return emptyList()
        }
        return normalized.split('\n')
    }

    private fun lcsLength(
        baselineLines: List<String>,
        targetLines: List<String>,
    ): Int {
        val rows = baselineLines.size
        val cols = targetLines.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (row in 1..rows) {
            for (col in 1..cols) {
                dp[row][col] = if (baselineLines[row - 1] == targetLines[col - 1]) {
                    dp[row - 1][col - 1] + 1
                } else {
                    maxOf(dp[row - 1][col], dp[row][col - 1])
                }
            }
        }
        return dp[rows][cols]
    }

    private fun codeChangeStatusPriority(status: CodeChangeFileStatus): Int {
        return when (status) {
            CodeChangeFileStatus.CONFLICTED -> 0
            CodeChangeFileStatus.REMOVED -> 1
            CodeChangeFileStatus.MISSING -> 2
            CodeChangeFileStatus.ADDED -> 3
            CodeChangeFileStatus.MODIFIED -> 4
            CodeChangeFileStatus.UNTRACKED -> 5
            CodeChangeFileStatus.UNKNOWN -> 6
        }
    }

    private fun normalizePath(rawPath: String): String? {
        val normalized = rawPath.trim()
            .replace('\\', '/')
            .trim('/')
        return normalized.takeIf { path ->
            path.isNotEmpty() &&
                EXCLUDED_DIFF_PATH_PREFIXES.none { prefix -> path.startsWith(prefix) }
        }
    }
}

private val EXCLUDED_DIFF_PATH_PREFIXES = listOf(
    ".spec-coding/",
    ".idea/",
    ".git/",
    ".gradle/",
    "build/",
    "out/",
)
