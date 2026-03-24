package com.eacape.speccodingplugin.worktree

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CliGitWorktreeExecutor(
    private val processRunner: (repoPath: String, command: List<String>) -> ProcessExecutionResult = ::defaultProcessRunner,
) : GitWorktreeExecutor {
    private val logger = thisLogger()

    override fun resolveCurrentBranch(repoPath: String): Result<String> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    ensureRepositoryAccessible(repoPath)
                    queryGitOutput(repoPath, listOf("branch", "--show-current"))
                        ?: queryGitOutput(repoPath, listOf("symbolic-ref", "--short", "HEAD"))?.removePrefix("refs/heads/")
                        ?: throw IllegalStateException("Unable to resolve current git branch")
                }.onFailure { error ->
                    logger.warn("Failed to resolve current git branch", error)
                }
            }
        }
    }

    override fun resolveBaseBranch(repoPath: String, requestedBaseBranch: String): Result<String> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val preferred = requestedBaseBranch.trim().ifBlank { DEFAULT_REQUESTED_BASE_BRANCH }
                    ensureRepositoryAccessible(repoPath)

                    val checkedRefs = mutableListOf<String>()
                    val preferredResolved = resolveFirstReference(repoPath, listOf(preferred), checkedRefs)
                    if (preferredResolved != null) {
                        return@runCatching preferredResolved
                    }
                    if (!shouldAutoFallback(preferred)) {
                        throw IllegalStateException(
                            "Base branch '$preferred' not found. Please set an existing base branch."
                        )
                    }

                    val candidateRefs = buildFallbackCandidates(repoPath, preferred)
                    val resolved = resolveFirstReference(repoPath, candidateRefs, checkedRefs)
                    if (resolved != null) {
                        logger.info("Resolved worktree base branch fallback: requested=$preferred, resolved=$resolved")
                        return@runCatching resolved
                    }

                    if (!hasAnyCommit(repoPath)) {
                        throw IllegalStateException(
                            "Base branch '$preferred' not found because repository has no commits. " +
                                "Create an initial commit first."
                        )
                    }

                    val compactCheckedRefs = checkedRefs.distinct().take(MAX_ERROR_REFS).joinToString(", ")
                    throw IllegalStateException(
                        "Base branch '$preferred' not found. Checked refs: $compactCheckedRefs"
                    )
                }.onFailure { error ->
                    logger.warn("Failed to resolve worktree base branch: requested=$requestedBaseBranch", error)
                }
            }
        }
    }

    override fun addWorktree(
        repoPath: String,
        worktreePath: String,
        branchName: String,
        baseBranch: String,
    ): Result<Unit> {
        return runCommand(
            repoPath = repoPath,
            args = listOf("worktree", "add", worktreePath, "-b", branchName, baseBranch)
        ).map { Unit }
    }

    override fun removeWorktree(repoPath: String, worktreePath: String, force: Boolean): Result<Unit> {
        val args = mutableListOf("worktree", "remove")
        if (force) args += "--force"
        args += worktreePath
        return runCommand(repoPath = repoPath, args = args).map { Unit }
    }

    override fun mergeBranch(repoPath: String, sourceBranch: String, targetBranch: String): Result<GitMergeOutcome> {
        return runCatching {
            val mergeRepoPath = resolveMergeExecutionPath(repoPath, targetBranch)
            val currentBranch = queryGitOutput(mergeRepoPath, listOf("branch", "--show-current"))
            if (!branchNamesMatch(currentBranch, targetBranch)) {
                runCommand(mergeRepoPath, listOf("checkout", targetBranch)).getOrThrow()
            }
            val mergeOutput = runCommand(mergeRepoPath, listOf("merge", "--no-ff", sourceBranch)).getOrThrow()
            val hasConflicts = mergeOutput.contains("CONFLICT", ignoreCase = true)
            GitMergeOutcome(
                hasConflicts = hasConflicts,
                statusDescription = mergeOutput.ifBlank { "MERGED" },
            )
        }
    }

    private fun resolveMergeExecutionPath(repoPath: String, targetBranch: String): String {
        val worktrees = queryGitWorktrees(repoPath)
        val matched = worktrees.firstOrNull { info -> branchNamesMatch(info.branch, targetBranch) }
        return matched?.path ?: repoPath
    }

    private fun queryGitWorktrees(repoPath: String): List<GitWorktreeInfo> {
        val output = queryGitOutput(repoPath, listOf("worktree", "list", "--porcelain")) ?: return emptyList()
        if (output.isBlank()) return emptyList()

        val result = mutableListOf<GitWorktreeInfo>()
        var currentPath: String? = null
        var currentBranch: String? = null

        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                if (!currentPath.isNullOrBlank()) {
                    result += GitWorktreeInfo(path = currentPath.orEmpty(), branch = currentBranch)
                }
                currentPath = null
                currentBranch = null
                return@forEach
            }
            when {
                trimmed.startsWith("worktree ") -> {
                    currentPath = trimmed.removePrefix("worktree ").trim()
                }

                trimmed.startsWith("branch ") -> {
                    currentBranch = trimmed
                        .removePrefix("branch ")
                        .trim()
                        .removePrefix("refs/heads/")
                }
            }
        }

        if (!currentPath.isNullOrBlank()) {
            result += GitWorktreeInfo(path = currentPath.orEmpty(), branch = currentBranch)
        }
        return result
    }

    private fun branchNamesMatch(left: String?, right: String): Boolean {
        val normalizedLeft = left?.trim()?.ifBlank { null } ?: return false
        val normalizedRight = right.trim()
        if (normalizedRight.isBlank()) return false
        val leftVariants = referenceVariants(normalizedLeft).toSet()
        val rightVariants = referenceVariants(normalizedRight).toSet()
        return leftVariants.any { variant -> variant in rightVariants }
    }

    private fun runCommand(repoPath: String, args: List<String>): Result<String> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val command = listOf("git") + args
                    val result = processRunner(repoPath, command)
                    if (result.exitCode != 0) {
                        throw IllegalStateException(
                            "Git command failed (${command.joinToString(" ")}): ${result.output.trim()}"
                        )
                    }

                    result.output
                }.onFailure { e ->
                    logger.warn("Git command execution failed: ${args.joinToString(" ")}", e)
                }
            }
        }
    }

    private fun buildFallbackCandidates(repoPath: String, preferred: String): List<String> {
        val candidates = linkedSetOf<String>()
        addCandidateRefs(
            candidates,
            queryGitOutput(repoPath, listOf("branch", "--show-current")),
        )
        addCandidateRefs(
            candidates,
            queryGitOutput(repoPath, listOf("symbolic-ref", "--short", "HEAD"))?.removePrefix("refs/heads/"),
        )
        addCandidateRefs(
            candidates,
            queryGitOutput(repoPath, listOf("symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"))
        )
        addCandidateRefs(
            candidates,
            queryGitOutput(repoPath, listOf("config", "--get", "init.defaultBranch")),
        )

        FALLBACK_BASE_REFS.forEach { addCandidateRefs(candidates, it) }
        discoverLocalBranches(repoPath).forEach { addCandidateRefs(candidates, it) }
        discoverRemoteBranches(repoPath).forEach { addCandidateRefs(candidates, it) }
        addCandidateRefs(
            candidates,
            queryGitOutput(repoPath, listOf("rev-list", "--max-count=1", "--all")),
        )
        candidates += "HEAD"
        candidates.remove(preferred)
        return candidates.toList()
    }

    private fun shouldAutoFallback(preferred: String): Boolean {
        return preferred.lowercase(Locale.ROOT) in AUTO_FALLBACK_REQUESTED_BASE_REFS
    }

    private fun addCandidateRefs(candidates: MutableSet<String>, rawRef: String?) {
        val normalized = rawRef?.trim()?.ifBlank { null } ?: return
        referenceVariants(normalized)
            .filterNot { it.equals("HEAD", ignoreCase = true) && !normalized.equals("HEAD", ignoreCase = true) }
            .forEach { candidates += it }
    }

    private fun discoverLocalBranches(repoPath: String): List<String> {
        return queryGitLines(repoPath, listOf("for-each-ref", "--format=%(refname:short)", "refs/heads"))
            .filter { it.isNotBlank() && !it.equals("HEAD", ignoreCase = true) }
            .distinct()
    }

    private fun discoverRemoteBranches(repoPath: String): List<String> {
        return queryGitLines(repoPath, listOf("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin"))
            .filter { it.isNotBlank() && !it.endsWith("/HEAD", ignoreCase = true) && !it.equals("HEAD", ignoreCase = true) }
            .distinct()
    }

    private fun isResolvableReference(repoPath: String, ref: String): Boolean {
        if (ref.isBlank()) return false
        val command = listOf("git", "rev-parse", "--verify", "--quiet", ref)
        val result = runCatching { processRunner(repoPath, command) }.getOrElse { return false }
        return result.exitCode == 0
    }

    private fun resolveFirstReference(
        repoPath: String,
        candidates: List<String>,
        checkedRefs: MutableList<String>,
    ): String? {
        val visited = linkedSetOf<String>()
        for (candidate in candidates) {
            for (ref in referenceVariants(candidate)) {
                if (!visited.add(ref)) continue
                checkedRefs += ref
                val resolved = isResolvableReference(repoPath, ref)
                logger.debug("Worktree base ref probe: candidate=$candidate, ref=$ref, resolved=$resolved")
                if (resolved) {
                    return ref
                }
            }
        }
        return null
    }

    private fun referenceVariants(ref: String): List<String> {
        val normalized = ref.trim()
        if (normalized.isBlank()) return emptyList()

        val variants = linkedSetOf<String>()
        variants += normalized
        if (normalized.startsWith("refs/heads/")) {
            variants += normalized.removePrefix("refs/heads/")
        }
        if (normalized.startsWith("refs/remotes/")) {
            val remoteShort = normalized.removePrefix("refs/remotes/")
            variants += remoteShort
            if (remoteShort.startsWith("origin/")) {
                variants += remoteShort.removePrefix("origin/")
            }
        }
        if (normalized.startsWith("origin/")) {
            variants += normalized.removePrefix("origin/")
            variants += "refs/remotes/$normalized"
        }

        if (!normalized.startsWith("refs/") &&
            !normalized.equals("HEAD", ignoreCase = true) &&
            !normalized.matches(COMMIT_HASH_REGEX)
        ) {
            variants += "refs/heads/$normalized"
            if (!normalized.startsWith("origin/")) {
                variants += "refs/remotes/origin/$normalized"
            }
        }

        return variants.toList()
    }

    private fun ensureRepositoryAccessible(repoPath: String) {
        val command = listOf("git", "rev-parse", "--is-inside-work-tree")
        val result = runCatching { processRunner(repoPath, command) }.getOrElse { error ->
            throw IllegalStateException("Unable to run git command: ${error.message ?: "unknown error"}", error)
        }
        if (result.exitCode == 0) {
            return
        }
        val output = compactOutput(result.output)
        when {
            output.contains("dubious ownership", ignoreCase = true) -> {
                throw IllegalStateException(
                    "Git repository access denied by safe.directory. " +
                        "Run: git config --global --add safe.directory \"$repoPath\""
                )
            }

            output.contains("not a git repository", ignoreCase = true) -> {
                throw IllegalStateException("Project path is not a Git repository: $repoPath")
            }

            else -> {
                throw IllegalStateException("Unable to access Git repository: ${output.ifBlank { "unknown error" }}")
            }
        }
    }

    private fun hasAnyCommit(repoPath: String): Boolean {
        val revision = queryGitOutput(repoPath, listOf("rev-list", "--max-count=1", "--all"))
        return !revision.isNullOrBlank()
    }

    private fun queryGitOutput(repoPath: String, args: List<String>): String? {
        val command = listOf("git") + args
        val result = runCatching { processRunner(repoPath, command) }.getOrNull() ?: return null
        if (result.exitCode != 0) {
            logger.debug("Git probe failed: ${args.joinToString(" ")} :: ${compactOutput(result.output)}")
            return null
        }
        return result.output.trim().ifBlank { null }
    }

    private fun queryGitLines(repoPath: String, args: List<String>): List<String> {
        return queryGitOutput(repoPath, args)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
    }

    data class ProcessExecutionResult(
        val exitCode: Int,
        val output: String,
    )

    private data class GitWorktreeInfo(
        val path: String,
        val branch: String?,
    )

    companion object {
        private const val DEFAULT_REQUESTED_BASE_BRANCH = "main"
        private const val MAX_ERROR_REFS = 12
        private val COMMIT_HASH_REGEX = Regex("^[0-9a-fA-F]{7,40}$")
        private val AUTO_FALLBACK_REQUESTED_BASE_REFS = setOf("main", "master")
        private val FALLBACK_BASE_REFS = listOf("main", "master", "develop", "dev", "trunk")

        private fun compactOutput(output: String, maxLength: Int = 200): String {
            val compact = output
                .replace('\n', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            if (compact.length <= maxLength) return compact
            return compact.take(maxLength - 3).trimEnd() + "..."
        }

        private fun defaultProcessRunner(repoPath: String, command: List<String>): ProcessExecutionResult {
            val process = ProcessBuilder(command)
                .directory(File(repoPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            return ProcessExecutionResult(exitCode = exitCode, output = output)
        }
    }
}
