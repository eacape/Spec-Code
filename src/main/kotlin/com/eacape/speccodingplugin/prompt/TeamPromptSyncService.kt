package com.eacape.speccodingplugin.prompt

import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.pathString

@Service(Service.Level.PROJECT)
class TeamPromptSyncService internal constructor(
    private val project: Project,
    private val settingsProvider: () -> SpecCodingSettingsState,
    private val promptManagerProvider: () -> PromptManager,
    private val gitExecutor: GitCommandExecutor,
) {
    private val logger = thisLogger()

    constructor(project: Project) : this(
        project = project,
        settingsProvider = { SpecCodingSettingsState.getInstance() },
        promptManagerProvider = { PromptManager.getInstance(project) },
        gitExecutor = CliGitCommandExecutor(),
    )

    fun pullFromTeamRepo(): Result<TeamPromptSyncResult> {
        return runCatching {
            val config = resolveConfig()
            val projectRoot = resolveProjectRoot()
            val mirrorRepo = resolveMirrorRepoPath(projectRoot)
            ensureMirrorReady(config, mirrorRepo)
            gitExecutor.runOrThrow(
                workingDir = mirrorRepo,
                args = listOf("pull", "--ff-only", "origin", config.branch),
            )

            val sourceDir = mirrorRepo.resolve(PROMPTS_DIR_RELATIVE_PATH)
            if (!Files.isDirectory(sourceDir)) {
                throw IllegalStateException("Team repo does not contain $PROMPTS_DIR_RELATIVE_PATH")
            }

            val localDir = projectRoot.resolve(PROMPTS_DIR_RELATIVE_PATH)
            val copiedFiles = replaceDirectory(sourceDir, localDir)
            promptManagerProvider().reloadFromDisk()

            TeamPromptSyncResult(
                remoteUrl = config.remoteUrl,
                branch = config.branch,
                syncedFiles = copiedFiles,
                noChanges = false,
                commitId = null,
            )
        }.onFailure { error ->
            logger.warn("Failed to pull prompts from team repo", error)
        }
    }

    fun pushToTeamRepo(
        commitMessage: String = "chore(prompt): sync prompts from ${project.name}",
    ): Result<TeamPromptSyncResult> {
        return runCatching {
            val config = resolveConfig()
            val projectRoot = resolveProjectRoot()
            val localDir = projectRoot.resolve(PROMPTS_DIR_RELATIVE_PATH)
            if (!Files.isDirectory(localDir)) {
                throw IllegalStateException("Local prompts directory does not exist: $PROMPTS_DIR_RELATIVE_PATH")
            }

            val mirrorRepo = resolveMirrorRepoPath(projectRoot)
            ensureMirrorReady(config, mirrorRepo)

            val mirrorDir = mirrorRepo.resolve(PROMPTS_DIR_RELATIVE_PATH)
            val syncedFiles = replaceDirectory(localDir, mirrorDir)

            gitExecutor.runOrThrow(
                workingDir = mirrorRepo,
                args = listOf("add", "--", PROMPTS_DIR_RELATIVE_PATH),
            )

            val statusOutput = gitExecutor.runOrThrow(
                workingDir = mirrorRepo,
                args = listOf("status", "--porcelain", "--", PROMPTS_DIR_RELATIVE_PATH),
            )
            if (statusOutput.isBlank()) {
                return@runCatching TeamPromptSyncResult(
                    remoteUrl = config.remoteUrl,
                    branch = config.branch,
                    syncedFiles = syncedFiles,
                    noChanges = true,
                    commitId = null,
                )
            }

            gitExecutor.runOrThrow(
                workingDir = mirrorRepo,
                args = listOf("commit", "-m", commitMessage),
            )
            gitExecutor.runOrThrow(
                workingDir = mirrorRepo,
                args = listOf("push", "origin", config.branch),
            )
            val commitId = gitExecutor.runOrThrow(
                workingDir = mirrorRepo,
                args = listOf("rev-parse", "HEAD"),
            ).lineSequence().firstOrNull().orEmpty()

            TeamPromptSyncResult(
                remoteUrl = config.remoteUrl,
                branch = config.branch,
                syncedFiles = syncedFiles,
                noChanges = false,
                commitId = commitId.ifBlank { null },
            )
        }.onFailure { error ->
            logger.warn("Failed to push prompts to team repo", error)
        }
    }

    private fun resolveConfig(): TeamPromptSyncConfig {
        val settings = settingsProvider()
        val remoteUrl = settings.teamPromptRepoUrl.trim()
        require(remoteUrl.isNotBlank()) { "Team prompt repository URL is empty" }

        val branch = normalizeBranch(settings.teamPromptRepoBranch)
        return TeamPromptSyncConfig(remoteUrl = remoteUrl, branch = branch)
    }

    private fun normalizeBranch(value: String): String {
        val normalized = value.trim().ifBlank { DEFAULT_BRANCH }
        require(BRANCH_PATTERN.matches(normalized)) { "Invalid git branch name: $normalized" }
        require(!normalized.contains("..")) { "Invalid git branch name: $normalized" }
        require(!normalized.startsWith("/") && !normalized.endsWith("/")) { "Invalid git branch name: $normalized" }
        require(!normalized.contains("//")) { "Invalid git branch name: $normalized" }
        return normalized
    }

    private fun resolveProjectRoot(): Path {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is not available")
        return Paths.get(basePath)
    }

    private fun resolveMirrorRepoPath(projectRoot: Path): Path {
        return projectRoot
            .resolve(".spec-coding")
            .resolve(".team-sync")
            .resolve("prompt-repo")
    }

    private fun ensureMirrorReady(config: TeamPromptSyncConfig, mirrorRepo: Path) {
        if (!isGitRepository(mirrorRepo)) {
            if (Files.exists(mirrorRepo)) {
                deleteRecursively(mirrorRepo)
            }
            Files.createDirectories(mirrorRepo.parent)
            gitExecutor.runOrThrow(
                workingDir = null,
                args = listOf("clone", config.remoteUrl, mirrorRepo.pathString),
            )
        }

        gitExecutor.runOrThrow(
            workingDir = mirrorRepo,
            args = listOf("remote", "set-url", "origin", config.remoteUrl),
        )
        gitExecutor.runOrThrow(
            workingDir = mirrorRepo,
            args = listOf("fetch", "origin"),
        )
        checkoutBranch(mirrorRepo, config.branch)
    }

    private fun checkoutBranch(mirrorRepo: Path, branch: String) {
        if (gitExecutor.run(mirrorRepo, listOf("checkout", branch)).isSuccess) {
            return
        }
        if (gitExecutor.run(mirrorRepo, listOf("checkout", "-b", branch, "--track", "origin/$branch")).isSuccess) {
            return
        }
        gitExecutor.runOrThrow(mirrorRepo, listOf("checkout", "-b", branch))
    }

    private fun isGitRepository(path: Path): Boolean {
        return Files.isDirectory(path.resolve(".git"))
    }

    private fun replaceDirectory(source: Path, target: Path): Int {
        deleteRecursively(target)
        Files.createDirectories(target)

        var fileCount = 0
        Files.walk(source).use { stream ->
            stream.forEach { current ->
                val relative = source.relativize(current)
                val destination = target.resolve(relative.toString())
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let(Files::createDirectories)
                    Files.copy(
                        current,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    fileCount += 1
                }
            }
        }
        return fileCount
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { current ->
                Files.deleteIfExists(current)
            }
        }
    }

    companion object {
        private const val DEFAULT_BRANCH = "main"
        private const val PROMPTS_DIR_RELATIVE_PATH = ".spec-coding/prompts"
        private val BRANCH_PATTERN = Regex("[A-Za-z0-9._/-]+")

        fun getInstance(project: Project): TeamPromptSyncService = project.service()
    }
}

data class TeamPromptSyncConfig(
    val remoteUrl: String,
    val branch: String,
)

data class TeamPromptSyncResult(
    val remoteUrl: String,
    val branch: String,
    val syncedFiles: Int,
    val noChanges: Boolean,
    val commitId: String?,
)

fun interface GitCommandExecutor {
    fun run(workingDir: Path?, args: List<String>): Result<String>
}

private class CliGitCommandExecutor : GitCommandExecutor {
    override fun run(workingDir: Path?, args: List<String>): Result<String> {
        return runCatching {
            val command = listOf("git") + args
            val process = ProcessBuilder(command)
                .apply {
                    if (workingDir != null) {
                        directory(workingDir.toFile())
                    }
                    redirectErrorStream(true)
                }
                .start()

            val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException("Git command failed (${command.joinToString(" ")}): $output")
            }
            output
        }
    }
}

private fun GitCommandExecutor.runOrThrow(workingDir: Path?, args: List<String>): String {
    return run(workingDir, args).getOrElse { error ->
        throw error
    }
}
