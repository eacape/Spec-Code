package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class SpecArtifactWriteResult(
    val stageId: StageId,
    val path: Path,
    val created: Boolean,
)

/**
 * Handles artifact path resolution, read/write, and template-driven skeleton bootstrap.
 */
class SpecArtifactService(
    project: Project,
    private val atomicFileIO: AtomicFileIO = AtomicFileIO(),
    private val workspaceInitializer: SpecWorkspaceInitializer = SpecWorkspaceInitializer(project),
    private val lockManager: SpecFileLockManager = SpecFileLockManager(workspaceInitializer),
) {

    fun listWorkflowMarkdownArtifacts(workflowId: String): List<Path> {
        validateWorkflowId(workflowId)
        val workflowDir = workflowDirectory(workflowId)
        if (!Files.isDirectory(workflowDir)) {
            return emptyList()
        }
        return Files.newDirectoryStream(workflowDir).use { entries ->
            entries
                .filter { entry ->
                    Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".md", ignoreCase = true)
                }
                .sortedBy { it.fileName.toString().lowercase() }
                .toList()
        }
    }

    fun locateArtifact(workflowId: String, stageId: StageId): Path {
        val fileName = stageId.artifactFileName
            ?: throw IllegalArgumentException("Stage $stageId has no artifact file.")
        return locateArtifact(workflowId, fileName)
    }

    fun locateArtifact(workflowId: String, fileName: String): Path {
        validateWorkflowId(workflowId)
        val normalizedFileName = validateArtifactFileName(fileName)
        return workflowDirectory(workflowId).resolve(normalizedFileName)
    }

    fun readArtifact(workflowId: String, stageId: StageId): String? {
        val path = locateArtifact(workflowId, stageId)
        if (!Files.exists(path)) {
            return null
        }
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    fun writeArtifact(workflowId: String, stageId: StageId, content: String): Path {
        val fileName = stageId.artifactFileName
            ?: throw IllegalArgumentException("Stage $stageId has no artifact file.")
        return writeArtifact(workflowId, fileName, content)
    }

    fun writeArtifact(workflowId: String, fileName: String, content: String): Path {
        validateWorkflowId(workflowId)
        val normalizedFileName = validateArtifactFileName(fileName)
        val normalizedContent = normalizeContent(content)
        return lockManager.withWorkflowLock(workflowId) {
            val workflowDir = workspaceInitializer.initializeWorkflowWorkspace(workflowId).workflowDir
            val path = workflowDir.resolve(normalizedFileName)
            atomicFileIO.writeString(path, normalizedContent, StandardCharsets.UTF_8)
            path
        }
    }

    fun writeArtifactIfMissing(workflowId: String, stageId: StageId, content: String): SpecArtifactWriteResult {
        val fileName = stageId.artifactFileName
            ?: throw IllegalArgumentException("Stage $stageId has no artifact file.")
        validateWorkflowId(workflowId)
        val normalizedFileName = validateArtifactFileName(fileName)
        val normalizedContent = normalizeContent(content)
        return lockManager.withWorkflowLock(workflowId) {
            val workflowDir = workspaceInitializer.initializeWorkflowWorkspace(workflowId).workflowDir
            val path = workflowDir.resolve(normalizedFileName)
            if (Files.exists(path)) {
                SpecArtifactWriteResult(
                    stageId = stageId,
                    path = path,
                    created = false,
                )
            } else {
                atomicFileIO.writeString(path, normalizedContent, StandardCharsets.UTF_8)
                SpecArtifactWriteResult(
                    stageId = stageId,
                    path = path,
                    created = true,
                )
            }
        }
    }

    fun ensureMissingArtifacts(
        workflowId: String,
        template: WorkflowTemplate,
        templatePolicy: SpecTemplatePolicy,
        verifyEnabled: Boolean? = null,
    ): List<SpecArtifactWriteResult> {
        validateWorkflowId(workflowId)
        val requiredArtifacts = requiredArtifacts(
            template = template,
            templatePolicy = templatePolicy,
            verifyEnabled = verifyEnabled,
        )
        return lockManager.withWorkflowLock(workflowId) {
            val workflowDir = workspaceInitializer.initializeWorkflowWorkspace(workflowId).workflowDir
            requiredArtifacts.map { (stageId, fileName) ->
                val path = workflowDir.resolve(fileName)
                if (Files.exists(path)) {
                    SpecArtifactWriteResult(
                        stageId = stageId,
                        path = path,
                        created = false,
                    )
                } else {
                    val content = defaultSkeletonFor(stageId)
                    atomicFileIO.writeString(path, content, StandardCharsets.UTF_8)
                    SpecArtifactWriteResult(
                        stageId = stageId,
                        path = path,
                        created = true,
                    )
                }
            }
        }
    }

    fun ensureMissingArtifacts(workflow: SpecWorkflow): List<SpecArtifactWriteResult> {
        validateWorkflowId(workflow.id)
        val requiredArtifacts = requiredArtifacts(workflow)
        return lockManager.withWorkflowLock(workflow.id) {
            val workflowDir = workspaceInitializer.initializeWorkflowWorkspace(workflow.id).workflowDir
            requiredArtifacts.map { (stageId, fileName) ->
                val path = workflowDir.resolve(fileName)
                if (Files.exists(path)) {
                    SpecArtifactWriteResult(
                        stageId = stageId,
                        path = path,
                        created = false,
                    )
                } else {
                    val content = defaultSkeletonFor(stageId)
                    atomicFileIO.writeString(path, content, StandardCharsets.UTF_8)
                    SpecArtifactWriteResult(
                        stageId = stageId,
                        path = path,
                        created = true,
                    )
                }
            }
        }
    }

    fun previewRequiredArtifacts(
        workflowId: String,
        template: WorkflowTemplate,
        templatePolicy: SpecTemplatePolicy,
    ): List<TemplateSwitchArtifactImpact> {
        validateWorkflowId(workflowId)
        val workflowDir = workflowDirectory(workflowId)
        return requiredArtifacts(template, templatePolicy).map { (stageId, fileName) ->
            val path = workflowDir.resolve(fileName)
            val exists = Files.exists(path)
            TemplateSwitchArtifactImpact(
                stageId = stageId,
                fileName = fileName,
                exists = exists,
                strategy = when {
                    exists -> TemplateSwitchArtifactStrategy.REUSE_EXISTING
                    canScaffold(stageId) -> TemplateSwitchArtifactStrategy.GENERATE_SKELETON
                    else -> TemplateSwitchArtifactStrategy.BLOCK_SWITCH
                },
            )
        }
    }

    private fun requiredArtifacts(
        template: WorkflowTemplate,
        templatePolicy: SpecTemplatePolicy,
        verifyEnabled: Boolean? = null,
    ): List<Pair<StageId, String>> {
        val ordered = LinkedHashMap<String, StageId>()
        val stagePlan = templatePolicy.stagePlan(verifyEnabled = verifyEnabled)

        // DIRECT_IMPLEMENT must still materialize a minimal tasks.md for task traceability.
        if (template == WorkflowTemplate.DIRECT_IMPLEMENT) {
            ordered[StageId.TASKS.artifactFileName!!] = StageId.TASKS
        }

        stagePlan.gateArtifactStages.forEach { stageId ->
            val fileName = stageId.artifactFileName ?: return@forEach
            ordered.putIfAbsent(fileName, stageId)
        }

        return ordered.entries.map { (fileName, stageId) -> stageId to fileName }
    }

    private fun requiredArtifacts(workflow: SpecWorkflow): List<Pair<StageId, String>> {
        val ordered = LinkedHashMap<String, StageId>()

        if (workflow.template == WorkflowTemplate.DIRECT_IMPLEMENT) {
            ordered[StageId.TASKS.artifactFileName!!] = StageId.TASKS
        }

        val activeArtifactStages = when {
            workflow.stageStates.isNotEmpty() -> StageId.entries.filter { stageId ->
                stageId.requiresArtifact() && workflow.stageStates[stageId]?.active == true
            }

            else -> WorkflowTemplates.definitionOf(workflow.template)
                .buildStagePlan(
                    StageActivationOptions.of(
                        verifyEnabled = workflow.verifyEnabled,
                        implementEnabled = null,
                    ),
                )
                .gateArtifactStages
        }

        activeArtifactStages.forEach { stageId ->
            val fileName = stageId.artifactFileName ?: return@forEach
            ordered.putIfAbsent(fileName, stageId)
        }

        return ordered.entries.map { (fileName, stageId) -> stageId to fileName }
    }

    private fun defaultSkeletonFor(stageId: StageId): String {
        return ArtifactDraftStateSupport.defaultSkeletonFor(stageId)
    }

    private fun canScaffold(stageId: StageId): Boolean {
        return when (stageId) {
            StageId.REQUIREMENTS,
            StageId.DESIGN,
            StageId.TASKS,
            StageId.VERIFY,
            -> true

            StageId.IMPLEMENT,
            StageId.ARCHIVE,
            -> false
        }
    }

    private fun workflowDirectory(workflowId: String): Path {
        return workspaceInitializer
            .specCodingDirectory()
            .resolve(SPECS_DIR_NAME)
            .resolve(workflowId)
    }

    private fun validateWorkflowId(workflowId: String) {
        require(workflowId.isNotBlank()) { "workflowId cannot be blank." }
    }

    private fun validateArtifactFileName(fileName: String): String {
        val normalized = fileName.trim()
        require(normalized.isNotEmpty()) { "artifact file name cannot be blank." }
        require(!normalized.contains('/') && !normalized.contains('\\')) {
            "artifact file name must not contain path separators: $normalized"
        }
        require(normalized == Path.of(normalized).fileName.toString()) {
            "artifact file name must be a single file name: $normalized"
        }
        return normalized
    }

    private fun normalizeContent(content: String): String {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        return if (normalized.endsWith("\n")) normalized else "$normalized\n"
    }

    companion object {
        private const val SPECS_DIR_NAME = "specs"
    }
}
