package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.engine.CliDiscoveryService
import com.eacape.speccodingplugin.engine.CliToolInfo
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.project.Project
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Files
import java.nio.file.Path

internal enum class LocalEnvironmentCheckSeverity {
    READY,
    WARNING,
    BLOCKER,
}

internal data class LocalEnvironmentCheck(
    val label: String,
    val severity: LocalEnvironmentCheckSeverity,
    val detail: String,
)

internal data class LocalEnvironmentReadinessSnapshot(
    val summary: String,
    val quickTaskCheck: LocalEnvironmentCheck,
    val fullSpecCheck: LocalEnvironmentCheck,
    val detailChecks: List<LocalEnvironmentCheck>,
    val note: String,
) {
    val quickTaskReady: Boolean
        get() = quickTaskCheck.severity == LocalEnvironmentCheckSeverity.READY

    val fullSpecReady: Boolean
        get() = fullSpecCheck.severity == LocalEnvironmentCheckSeverity.READY

    val summarySeverity: LocalEnvironmentCheckSeverity
        get() = when {
            fullSpecReady -> LocalEnvironmentCheckSeverity.READY
            quickTaskReady -> LocalEnvironmentCheckSeverity.WARNING
            else -> LocalEnvironmentCheckSeverity.BLOCKER
        }
}

internal data class LocalEnvironmentReadinessInput(
    val projectPath: Path?,
    val projectWritable: Boolean,
    val gitRepositoryDetected: Boolean,
    val configuredClaudePath: String,
    val configuredCodexPath: String,
    val claudeInfo: CliToolInfo,
    val codexInfo: CliToolInfo,
)

internal object LocalEnvironmentReadiness {
    fun inspect(
        project: Project,
        configuredClaudePath: String? = null,
        configuredCodexPath: String? = null,
        settings: SpecCodingSettingsState = SpecCodingSettingsState.getInstance(),
        discoveryService: CliDiscoveryService = CliDiscoveryService.getInstance(),
    ): LocalEnvironmentReadinessSnapshot {
        val projectPath = project.basePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
        return evaluate(
            LocalEnvironmentReadinessInput(
                projectPath = projectPath,
                projectWritable = projectPath?.let(::isWorkspaceWritable) == true,
                gitRepositoryDetected = projectPath?.let(::isGitRepository) == true,
                configuredClaudePath = configuredClaudePath ?: settings.claudeCodeCliPath,
                configuredCodexPath = configuredCodexPath ?: settings.codexCliPath,
                claudeInfo = discoveryService.claudeInfo,
                codexInfo = discoveryService.codexInfo,
            ),
        )
    }

    internal fun evaluate(input: LocalEnvironmentReadinessInput): LocalEnvironmentReadinessSnapshot {
        val gitCheck = buildGitCheck(input)
        val cliCheck = buildCliCheck(input)
        val workspaceCheck = buildWorkspaceCheck(input)
        val cliPathCheck = buildCliPathCheck(input)

        val quickTaskMissing = buildList {
            if (workspaceCheck.severity == LocalEnvironmentCheckSeverity.BLOCKER) {
                add(SpecCodingBundle.message("local.setup.requirement.workspace"))
            }
            if (cliCheck.severity == LocalEnvironmentCheckSeverity.BLOCKER) {
                add(SpecCodingBundle.message("local.setup.requirement.cli"))
            }
        }
        val fullSpecMissing = buildList {
            if (gitCheck.severity == LocalEnvironmentCheckSeverity.BLOCKER) {
                add(SpecCodingBundle.message("local.setup.requirement.git"))
            }
            addAll(quickTaskMissing)
        }

        val quickTaskCheck = LocalEnvironmentCheck(
            label = SpecCodingBundle.message("local.setup.entry.quick"),
            severity = if (quickTaskMissing.isEmpty()) {
                LocalEnvironmentCheckSeverity.READY
            } else {
                LocalEnvironmentCheckSeverity.BLOCKER
            },
            detail = if (quickTaskMissing.isEmpty()) {
                SpecCodingBundle.message("local.setup.entry.quick.ready")
            } else {
                SpecCodingBundle.message(
                    "local.setup.entry.quick.blocked",
                    localizedRequirementList(quickTaskMissing),
                )
            },
        )
        val fullSpecCheck = LocalEnvironmentCheck(
            label = SpecCodingBundle.message("local.setup.entry.full"),
            severity = if (fullSpecMissing.isEmpty()) {
                LocalEnvironmentCheckSeverity.READY
            } else {
                LocalEnvironmentCheckSeverity.BLOCKER
            },
            detail = if (fullSpecMissing.isEmpty()) {
                SpecCodingBundle.message("local.setup.entry.full.ready")
            } else {
                SpecCodingBundle.message(
                    "local.setup.entry.full.blocked",
                    localizedRequirementList(fullSpecMissing),
                )
            },
        )

        val summary = when {
            fullSpecCheck.severity == LocalEnvironmentCheckSeverity.READY -> {
                SpecCodingBundle.message("local.setup.summary.ready")
            }

            quickTaskCheck.severity == LocalEnvironmentCheckSeverity.READY -> {
                SpecCodingBundle.message("local.setup.summary.quickOnly")
            }

            else -> SpecCodingBundle.message("local.setup.summary.blocked")
        }

        return LocalEnvironmentReadinessSnapshot(
            summary = summary,
            quickTaskCheck = quickTaskCheck,
            fullSpecCheck = fullSpecCheck,
            detailChecks = listOf(gitCheck, cliCheck, workspaceCheck, cliPathCheck),
            note = SpecCodingBundle.message("local.setup.note.advanced"),
        )
    }

    fun formatDetails(snapshot: LocalEnvironmentReadinessSnapshot): String {
        return buildString {
            appendLine(formatLine(snapshot.quickTaskCheck))
            appendLine(formatLine(snapshot.fullSpecCheck))
            snapshot.detailChecks.forEach { check ->
                appendLine(formatLine(check))
            }
            append(SpecCodingBundle.message("local.setup.note.label"))
            append(": ")
            append(snapshot.note)
        }
    }

    private fun buildGitCheck(input: LocalEnvironmentReadinessInput): LocalEnvironmentCheck {
        val projectLabel = displayProjectPath(input.projectPath)
        return LocalEnvironmentCheck(
            label = SpecCodingBundle.message("local.setup.check.git"),
            severity = if (input.gitRepositoryDetected) {
                LocalEnvironmentCheckSeverity.READY
            } else {
                LocalEnvironmentCheckSeverity.BLOCKER
            },
            detail = if (input.gitRepositoryDetected) {
                SpecCodingBundle.message("local.setup.check.git.ready", projectLabel)
            } else {
                SpecCodingBundle.message("local.setup.check.git.blocked", projectLabel)
            },
        )
    }

    private fun buildCliCheck(input: LocalEnvironmentReadinessInput): LocalEnvironmentCheck {
        val availableProviders = buildList {
            cliDescription(
                SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli"),
                input.claudeInfo,
            )?.let(::add)
            cliDescription(
                SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli"),
                input.codexInfo,
            )?.let(::add)
        }
        val unavailableProviders = buildList {
            cliUnavailableDescription(
                SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli"),
                input.claudeInfo,
            )?.let(::add)
            cliUnavailableDescription(
                SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli"),
                input.codexInfo,
            )?.let(::add)
        }
        return LocalEnvironmentCheck(
            label = SpecCodingBundle.message("local.setup.check.cli"),
            severity = if (availableProviders.isNotEmpty()) {
                LocalEnvironmentCheckSeverity.READY
            } else {
                LocalEnvironmentCheckSeverity.BLOCKER
            },
            detail = if (availableProviders.isNotEmpty()) {
                SpecCodingBundle.message(
                    "local.setup.check.cli.ready",
                    localizedRequirementList(availableProviders),
                )
            } else if (unavailableProviders.isNotEmpty()) {
                SpecCodingBundle.message("local.setup.check.cli.blocked") +
                    " " + unavailableProviders.joinToString("; ")
            } else {
                SpecCodingBundle.message("local.setup.check.cli.blocked")
            },
        )
    }

    private fun buildWorkspaceCheck(input: LocalEnvironmentReadinessInput): LocalEnvironmentCheck {
        val projectLabel = displayProjectPath(input.projectPath)
        return LocalEnvironmentCheck(
            label = SpecCodingBundle.message("local.setup.check.workspace"),
            severity = if (input.projectWritable) {
                LocalEnvironmentCheckSeverity.READY
            } else {
                LocalEnvironmentCheckSeverity.BLOCKER
            },
            detail = if (input.projectWritable) {
                SpecCodingBundle.message("local.setup.check.workspace.ready", projectLabel)
            } else {
                SpecCodingBundle.message("local.setup.check.workspace.blocked", projectLabel)
            },
        )
    }

    private fun buildCliPathCheck(input: LocalEnvironmentReadinessInput): LocalEnvironmentCheck {
        val configuredPaths = buildList {
            normalizedPathLabel("claude", input.configuredClaudePath)?.let(::add)
            normalizedPathLabel("codex", input.configuredCodexPath)?.let(::add)
        }
        val availablePaths = buildList {
            availablePathLabel("claude", input.claudeInfo)?.let(::add)
            availablePathLabel("codex", input.codexInfo)?.let(::add)
        }
        val configuredMissing = buildList {
            if (input.configuredClaudePath.isNotBlank() && !input.claudeInfo.available) {
                normalizedPathLabel("claude", input.configuredClaudePath)?.let(::add)
            }
            if (input.configuredCodexPath.isNotBlank() && !input.codexInfo.available) {
                normalizedPathLabel("codex", input.configuredCodexPath)?.let(::add)
            }
        }
        val configuredIssueDetails = buildList {
            if (input.configuredClaudePath.isNotBlank()) {
                cliUnavailableDescription("claude", input.claudeInfo)?.let(::add)
            }
            if (input.configuredCodexPath.isNotBlank()) {
                cliUnavailableDescription("codex", input.codexInfo)?.let(::add)
            }
        }

        val severity: LocalEnvironmentCheckSeverity
        val detail: String
        when {
            configuredPaths.isNotEmpty() && configuredMissing.isEmpty() -> {
                severity = LocalEnvironmentCheckSeverity.READY
                detail = SpecCodingBundle.message(
                    "local.setup.check.cliPath.ready.configured",
                    localizedRequirementList(configuredPaths),
                )
            }

            configuredMissing.isNotEmpty() && availablePaths.isNotEmpty() -> {
                severity = LocalEnvironmentCheckSeverity.WARNING
                val baseDetail = SpecCodingBundle.message(
                    "local.setup.check.cliPath.warning",
                    localizedRequirementList(configuredMissing),
                    localizedRequirementList(availablePaths),
                )
                detail = appendDiagnosticDetail(baseDetail, configuredIssueDetails)
            }

            configuredMissing.isNotEmpty() -> {
                severity = LocalEnvironmentCheckSeverity.BLOCKER
                val baseDetail = SpecCodingBundle.message(
                    "local.setup.check.cliPath.blocked",
                    localizedRequirementList(configuredMissing),
                )
                detail = appendDiagnosticDetail(baseDetail, configuredIssueDetails)
            }

            availablePaths.isNotEmpty() -> {
                severity = LocalEnvironmentCheckSeverity.READY
                detail = SpecCodingBundle.message(
                    "local.setup.check.cliPath.ready.auto",
                    localizedRequirementList(availablePaths),
                )
            }

            else -> {
                severity = LocalEnvironmentCheckSeverity.BLOCKER
                detail = SpecCodingBundle.message("local.setup.check.cliPath.missing")
            }
        }
        return LocalEnvironmentCheck(
            label = SpecCodingBundle.message("local.setup.check.cliPath"),
            severity = severity,
            detail = detail,
        )
    }

    private fun formatLine(check: LocalEnvironmentCheck): String {
        return buildString {
            append(check.label)
            append(" [")
            append(
                when (check.severity) {
                    LocalEnvironmentCheckSeverity.READY -> SpecCodingBundle.message("local.setup.state.ready")
                    LocalEnvironmentCheckSeverity.WARNING -> SpecCodingBundle.message("local.setup.state.warning")
                    LocalEnvironmentCheckSeverity.BLOCKER -> SpecCodingBundle.message("local.setup.state.blocker")
                },
            )
            append("]: ")
            append(check.detail)
        }
    }

    private fun cliDescription(label: String, info: CliToolInfo): String? {
        if (!info.available) return null
        val version = info.version?.trim().takeUnless { it.isNullOrEmpty() }
        return if (version != null) {
            "$label $version"
        } else {
            label
        }
    }

    private fun normalizedPathLabel(prefix: String, rawPath: String): String? {
        val normalized = rawPath.trim()
        if (normalized.isEmpty()) return null
        return "$prefix=$normalized"
    }

    private fun availablePathLabel(prefix: String, info: CliToolInfo): String? {
        if (!info.available) return null
        val normalized = info.path.trim()
        if (normalized.isEmpty()) return null
        return "$prefix=$normalized"
    }

    private fun cliUnavailableDescription(label: String, info: CliToolInfo): String? {
        if (info.available) return null
        val issue = info.availabilityIssue ?: return null
        return "$label: ${issue.renderSummary()}"
    }

    private fun appendDiagnosticDetail(baseDetail: String, diagnostics: List<String>): String {
        if (diagnostics.isEmpty()) return baseDetail
        return baseDetail + " " + diagnostics.joinToString("; ")
    }

    private fun displayProjectPath(projectPath: Path?): String {
        return projectPath?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: SpecCodingBundle.message("local.setup.path.unavailable")
    }

    private fun localizedRequirementList(values: List<String>): String {
        return values.joinToString(SpecCodingBundle.message("local.setup.list.delimiter"))
    }

    private fun isGitRepository(projectPath: Path): Boolean {
        return runCatching {
            FileRepositoryBuilder()
                .findGitDir(projectPath.toFile())
                .gitDir != null
        }.getOrDefault(false)
    }

    private fun isWorkspaceWritable(projectPath: Path): Boolean {
        return runCatching {
            Files.exists(projectPath) && Files.isWritable(projectPath)
        }.getOrDefault(false)
    }
}
