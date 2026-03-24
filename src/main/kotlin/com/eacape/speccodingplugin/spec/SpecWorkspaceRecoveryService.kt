package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class SpecWorkspaceRecoveryReport(
    val cleanedTempFiles: List<Path>,
    val staleLocks: List<SpecLockInspection>,
    val snapshotIssues: List<SpecSnapshotConsistencyIssue>,
) {
    val hasFindings: Boolean
        get() = cleanedTempFiles.isNotEmpty() || staleLocks.isNotEmpty() || snapshotIssues.isNotEmpty()

    val requiresAttention: Boolean
        get() = staleLocks.isNotEmpty() || snapshotIssues.isNotEmpty()
}

class SpecWorkspaceRecoveryService(private val project: Project) {
    private var _storageOverride: SpecStorage? = null
    private var _workspaceInitializerOverride: SpecWorkspaceInitializer? = null
    private var _lockManagerOverride: SpecFileLockManager? = null
    private var _nowProviderOverride: (() -> Long)? = null
    private var _orphanTempAgeMsOverride: Long? = null

    private val storage: SpecStorage
        get() = _storageOverride ?: SpecStorage.getInstance(project)

    private val workspaceInitializer: SpecWorkspaceInitializer
        get() = _workspaceInitializerOverride ?: SpecWorkspaceInitializer(project)

    private val lockManager: SpecFileLockManager
        get() = _lockManagerOverride ?: SpecFileLockManager(workspaceInitializer)

    private val nowProvider: () -> Long
        get() = _nowProviderOverride ?: System::currentTimeMillis

    private val orphanTempAgeMs: Long
        get() = _orphanTempAgeMsOverride ?: DEFAULT_ORPHAN_TEMP_AGE_MS

    internal constructor(
        project: Project,
        storage: SpecStorage,
        workspaceInitializer: SpecWorkspaceInitializer,
        lockManager: SpecFileLockManager,
        nowProvider: () -> Long = System::currentTimeMillis,
        orphanTempAgeMs: Long = DEFAULT_ORPHAN_TEMP_AGE_MS,
    ) : this(project) {
        _storageOverride = storage
        _workspaceInitializerOverride = workspaceInitializer
        _lockManagerOverride = lockManager
        _nowProviderOverride = nowProvider
        _orphanTempAgeMsOverride = orphanTempAgeMs
    }

    fun runStartupRecovery(): SpecWorkspaceRecoveryReport {
        val workspaceRoot = workspaceInitializer.specCodingDirectory()
        if (!Files.exists(workspaceRoot)) {
            return SpecWorkspaceRecoveryReport(
                cleanedTempFiles = emptyList(),
                staleLocks = emptyList(),
                snapshotIssues = emptyList(),
            )
        }

        return SpecWorkspaceRecoveryReport(
            cleanedTempFiles = cleanupOrphanTempFiles(workspaceRoot),
            staleLocks = lockManager.inspectLocks().filter { it.stale },
            snapshotIssues = storage.listWorkflows()
                .sorted()
                .flatMap(storage::checkWorkflowSnapshotConsistency),
        )
    }

    fun recoverStaleLocks(): List<Path> = lockManager.recoverStaleLocks()

    private fun cleanupOrphanTempFiles(workspaceRoot: Path): List<Path> {
        val cutoff = nowProvider() - orphanTempAgeMs
        return Files.walk(workspaceRoot).use { stream ->
            val deleted = mutableListOf<Path>()
            stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                        AtomicFileIO.isManagedTempFile(path)
                }
                .sorted(Comparator.comparing<Path, String> { it.toString() })
                .forEach { path ->
                    val lastModified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()
                        ?: return@forEach
                    if (lastModified > cutoff) {
                        return@forEach
                    }
                    if (Files.deleteIfExists(path)) {
                        deleted.add(path)
                    }
                }
            deleted
        }
    }

    companion object {
        private const val DEFAULT_ORPHAN_TEMP_AGE_MS = 30_000L
    }
}

@Suppress("OVERRIDE_DEPRECATION")
class SpecWorkspaceRecoveryProjectManagerListener(
    private val recoveryServiceFactory: (Project) -> SpecWorkspaceRecoveryService = ::SpecWorkspaceRecoveryService,
) : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        if (project.isDisposed || project.basePath.isNullOrBlank()) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) {
                return@executeOnPooledThread
            }
            val recoveryService = recoveryServiceFactory(project)
            val report = recoveryService.runStartupRecovery()
            if (!report.hasFindings) {
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                notifyRecoveryReport(project, recoveryService, report)
            }
        }
    }

    private fun notifyRecoveryReport(
        project: Project,
        recoveryService: SpecWorkspaceRecoveryService,
        report: SpecWorkspaceRecoveryReport,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                SpecCodingBundle.message("spec.recovery.notification.title"),
                buildNotificationContent(report),
                if (report.requiresAttention) NotificationType.WARNING else NotificationType.INFORMATION,
            )

        if (report.staleLocks.isNotEmpty()) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    SpecCodingBundle.message("spec.recovery.notification.action.recoverLocks"),
                ) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val recovered = recoveryService.recoverStaleLocks()
                        if (project.isDisposed) {
                            return@executeOnPooledThread
                        }
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) {
                                return@invokeLater
                            }
                            val messageKey = if (recovered.isEmpty()) {
                                "spec.recovery.notification.recoverLocks.none"
                            } else {
                                "spec.recovery.notification.recoverLocks.done"
                            }
                            val message = if (recovered.isEmpty()) {
                                SpecCodingBundle.message(messageKey)
                            } else {
                                SpecCodingBundle.message(messageKey, recovered.size)
                            }
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                                .createNotification(
                                    SpecCodingBundle.message("spec.recovery.notification.title"),
                                    message,
                                    NotificationType.INFORMATION,
                                )
                                .notify(project)
                        }
                    }
                },
            )
        }

        if (report.snapshotIssues.isNotEmpty()) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    SpecCodingBundle.message("spec.recovery.notification.action.showDetails"),
                ) {
                    Messages.showWarningDialog(
                        project,
                        buildSnapshotIssueDetails(report.snapshotIssues),
                        SpecCodingBundle.message("spec.recovery.notification.details.title"),
                    )
                },
            )
        }

        notification.notify(project)
    }

    private fun buildNotificationContent(report: SpecWorkspaceRecoveryReport): String {
        val lines = mutableListOf<String>()
        if (report.cleanedTempFiles.isNotEmpty()) {
            lines += SpecCodingBundle.message(
                "spec.recovery.notification.cleanedTemps",
                report.cleanedTempFiles.size,
            )
        }
        if (report.staleLocks.isNotEmpty()) {
            lines += SpecCodingBundle.message(
                "spec.recovery.notification.staleLocks",
                report.staleLocks.size,
            )
        }
        if (report.snapshotIssues.isNotEmpty()) {
            lines += SpecCodingBundle.message(
                "spec.recovery.notification.snapshotIssues",
                report.snapshotIssues.size,
                report.snapshotIssues.map(SpecSnapshotConsistencyIssue::workflowId).toSet().size,
            )
        }
        return lines.joinToString("<br/>")
    }

    private fun buildSnapshotIssueDetails(issues: List<SpecSnapshotConsistencyIssue>): String {
        return buildString {
            issues.forEachIndexed { index, issue ->
                if (index > 0) {
                    appendLine()
                }
                append(issue.workflowId)
                append(" / ")
                append(issue.snapshotId)
                append(": ")
                append(describeIssue(issue))
            }
        }
    }

    private fun describeIssue(issue: SpecSnapshotConsistencyIssue): String {
        return when (issue.kind) {
            SpecSnapshotConsistencyIssueKind.MISSING_METADATA ->
                SpecCodingBundle.message("spec.recovery.snapshotIssue.missingMetadata")
            SpecSnapshotConsistencyIssueKind.INVALID_METADATA ->
                SpecCodingBundle.message(
                    "spec.recovery.snapshotIssue.invalidMetadata",
                    issue.detail?.takeIf { it.isNotBlank() } ?: "parse error",
                )
            SpecSnapshotConsistencyIssueKind.MISSING_ARTIFACT ->
                SpecCodingBundle.message(
                    "spec.recovery.snapshotIssue.missingArtifact",
                    issue.artifactFileName ?: "unknown",
                )
        }
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "SpecCoding.Notifications"
    }
}
