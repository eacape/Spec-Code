package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

data class SpecWorkspacePaths(
    val rootDir: Path,
    val specsDir: Path,
    val locksDir: Path,
    val backupDir: Path,
)

data class WorkflowWorkspacePaths(
    val workflowDir: Path,
    val sourcesDir: Path,
    val sourcesMetadataPath: Path,
    val historyDir: Path,
    val snapshotsDir: Path,
    val baselinesDir: Path,
    val configSnapshotsDir: Path,
    val auditLogPath: Path,
)

/**
 * Initializes and guides the project-level spec workspace layout.
 */
class SpecWorkspaceInitializer(
    private val project: Project,
) {

    fun initializeProjectWorkspace(): SpecWorkspacePaths {
        val rootDir = specCodingDirectory()
        val specsDir = rootDir.resolve(SPECS_DIR_NAME)
        val locksDir = rootDir.resolve(LOCKS_DIR_NAME)
        val backupDir = rootDir.resolve(BACKUP_DIR_NAME)

        listOf(rootDir, specsDir, locksDir, backupDir).forEach { directory ->
            Files.createDirectories(directory)
        }
        ensureWorkspaceGuide(rootDir)

        return SpecWorkspacePaths(
            rootDir = rootDir,
            specsDir = specsDir,
            locksDir = locksDir,
            backupDir = backupDir,
        )
    }

    fun initializeWorkflowWorkspace(workflowId: String): WorkflowWorkspacePaths {
        require(workflowId.isNotBlank()) { "workflowId cannot be blank" }

        val projectWorkspace = initializeProjectWorkspace()
        val workflowDir = projectWorkspace.specsDir.resolve(workflowId)
        val sourcesDir = workflowDir.resolve(WORKFLOW_SOURCES_DIR_NAME)
        val sourcesMetadataPath = workflowDir.resolve(WORKFLOW_SOURCES_METADATA_FILE_NAME)
        val historyDir = workflowDir.resolve(WORKFLOW_HISTORY_DIR_NAME)
        val snapshotsDir = historyDir.resolve(HISTORY_SNAPSHOTS_DIR_NAME)
        val baselinesDir = historyDir.resolve(HISTORY_BASELINES_DIR_NAME)
        val configSnapshotsDir = historyDir.resolve(HISTORY_CONFIG_DIR_NAME)
        val auditLogPath = historyDir.resolve(HISTORY_AUDIT_FILE_NAME)

        listOf(
            workflowDir,
            sourcesDir,
            historyDir,
            snapshotsDir,
            baselinesDir,
            configSnapshotsDir,
        ).forEach { directory ->
            Files.createDirectories(directory)
        }
        if (!Files.exists(auditLogPath)) {
            try {
                Files.writeString(
                    auditLogPath,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
            } catch (_: FileAlreadyExistsException) {
                // Ignore races: another thread/process initialized the same workflow audit file.
            }
        }

        return WorkflowWorkspacePaths(
            workflowDir = workflowDir,
            sourcesDir = sourcesDir,
            sourcesMetadataPath = sourcesMetadataPath,
            historyDir = historyDir,
            snapshotsDir = snapshotsDir,
            baselinesDir = baselinesDir,
            configSnapshotsDir = configSnapshotsDir,
            auditLogPath = auditLogPath,
        )
    }

    fun specCodingDirectory(): Path {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
        return Paths.get(basePath).resolve(SPEC_CODING_DIR_NAME)
    }

    private fun ensureWorkspaceGuide(rootDir: Path) {
        val guidePath = rootDir.resolve(WORKSPACE_GUIDE_FILE_NAME)
        if (Files.exists(guidePath)) {
            return
        }
        try {
            Files.writeString(
                guidePath,
                WORKSPACE_GUIDE_CONTENT,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        } catch (_: FileAlreadyExistsException) {
            // Ignore races: another thread/process created the same guide concurrently.
        }
    }

    companion object {
        private const val SPEC_CODING_DIR_NAME = ".spec-coding"
        private const val SPECS_DIR_NAME = "specs"
        private const val LOCKS_DIR_NAME = ".locks"
        private const val BACKUP_DIR_NAME = ".backup"
        private const val WORKFLOW_SOURCES_DIR_NAME = "sources"
        private const val WORKFLOW_SOURCES_METADATA_FILE_NAME = "sources.yaml"
        private const val WORKFLOW_HISTORY_DIR_NAME = ".history"
        private const val HISTORY_SNAPSHOTS_DIR_NAME = "snapshots"
        private const val HISTORY_BASELINES_DIR_NAME = "baselines"
        private const val HISTORY_CONFIG_DIR_NAME = "config"
        private const val HISTORY_AUDIT_FILE_NAME = "audit.yaml"
        private const val WORKSPACE_GUIDE_FILE_NAME = "WORKSPACE.md"

        private val WORKSPACE_GUIDE_CONTENT = """
            # Spec Workspace Layout

            This folder is managed by the Spec workflow engine.

            - `specs/`: workflow folders (`<workflowId>/`) and workflow artifacts.
            - `.locks/`: lock files for future concurrent write protection.
            - `.backup/`: backups used for migration and high-risk operations.

            Each workflow directory is initialized with:

            - `sources/`: workflow-scoped imported reference files.
            - `sources.yaml`: metadata for persisted workflow sources.
            - `.history/snapshots/`: artifact snapshots.
            - `.history/baselines/`: delta baselines.
            - `.history/config/`: pinned config snapshots.
            - `.history/audit.yaml`: append-only YAML audit stream.
            """.trimIndent() + "\n"
    }
}
