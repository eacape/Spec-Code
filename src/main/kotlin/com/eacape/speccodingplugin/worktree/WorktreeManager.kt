package com.eacape.speccodingplugin.worktree

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Service(Service.Level.PROJECT)
class WorktreeManager internal constructor(
    private val project: Project,
    private val store: WorktreeStateStore,
    private val gitExecutor: GitWorktreeExecutor,
    private val projectOpener: WorktreeProjectOpener,
) {
    private val logger = thisLogger()
    private val lock = Any()

    constructor(project: Project) : this(
        project = project,
        store = WorktreeStore.getInstance(project),
        gitExecutor = CliGitWorktreeExecutor(),
        projectOpener = IdeWorktreeProjectOpener(project),
    )

    fun createWorktree(specTaskId: String, shortName: String, baseBranch: String = "main"): Result<WorktreeBinding> {
        return runCatching {
            val normalizedSpecTaskId = specTaskId.trim()
            val normalizedShortName = normalizeSegment(shortName)
            require(normalizedSpecTaskId.isNotBlank()) { "Spec task id cannot be blank" }
            require(normalizedShortName.isNotBlank()) { "Short name cannot be blank" }

            synchronized(lock) {
                val current = store.load()
                if (current.bindings.any { it.specTaskId == normalizedSpecTaskId && it.status == WorktreeStatus.ACTIVE }) {
                    throw IllegalStateException("Active worktree already exists for spec task: $normalizedSpecTaskId")
                }

                val basePath = project.basePath ?: throw IllegalStateException("Project base path is not available")
                val branchName = "spec/${normalizeSegment(normalizedSpecTaskId)}-$normalizedShortName"
                val worktreePath = resolveWorktreePath(basePath, normalizedSpecTaskId, normalizedShortName)

                val resolvedBaseBranch = gitExecutor.resolveBaseBranch(basePath, baseBranch).getOrThrow()
                gitExecutor.addWorktree(basePath, worktreePath.toString(), branchName, resolvedBaseBranch).getOrThrow()

                val now = System.currentTimeMillis()
                val binding = WorktreeBinding(
                    id = UUID.randomUUID().toString(),
                    specTaskId = normalizedSpecTaskId,
                    branchName = branchName,
                    worktreePath = worktreePath.toString(),
                    baseBranch = resolvedBaseBranch,
                    status = WorktreeStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                )

                val nextState = WorktreeState(
                    activeWorktreeId = binding.id,
                    bindings = current.bindings + binding,
                )
                store.save(nextState)
                logger.info("Created worktree: ${binding.branchName} -> ${binding.worktreePath}")
                binding
            }
        }
    }

    fun createAndOpenWorktree(
        specTaskId: String,
        shortName: String,
        baseBranch: String = "main",
    ): Result<WorktreeBinding> {
        return runCatching {
            val created = createWorktree(specTaskId, shortName, baseBranch).getOrThrow()

            projectOpener.openProject(created.worktreePath).getOrThrow()

            synchronized(lock) {
                val current = store.load()
                val latest = current.bindings.firstOrNull { it.id == created.id } ?: created
                if (current.activeWorktreeId != latest.id) {
                    store.save(current.copy(activeWorktreeId = latest.id))
                }
                latest
            }
        }
    }

    fun suggestBaseBranch(defaultBranch: String = DEFAULT_BASE_BRANCH): String {
        val normalizedDefault = defaultBranch.trim().ifBlank { DEFAULT_BASE_BRANCH }
        val basePath = project.basePath?.trim()?.ifBlank { null } ?: return normalizedDefault
        val currentBranch = gitExecutor.resolveCurrentBranch(basePath)
            .getOrNull()
            ?.trim()
            ?.ifBlank { null }
        if (currentBranch != null) {
            return currentBranch
        }
        return gitExecutor.resolveBaseBranch(basePath, normalizedDefault)
            .getOrDefault(normalizedDefault)
    }

    fun switchWorktree(worktreeId: String): Result<WorktreeBinding> {
        return runCatching {
            val normalizedId = worktreeId.trim()
            require(normalizedId.isNotBlank()) { "Worktree id cannot be blank" }

            val target = synchronized(lock) {
                val current = store.load()
                val target = current.bindings.firstOrNull { it.id == normalizedId }
                    ?: throw IllegalArgumentException("Worktree not found: $normalizedId")

                if (target.status != WorktreeStatus.ACTIVE) {
                    throw IllegalStateException("Cannot switch to non-active worktree: ${target.status}")
                }

                target
            }

            projectOpener.openProject(target.worktreePath).getOrThrow()

            synchronized(lock) {
                val current = store.load()
                val latest = current.bindings.firstOrNull { it.id == normalizedId }
                    ?: throw IllegalArgumentException("Worktree not found: $normalizedId")

                if (latest.status != WorktreeStatus.ACTIVE) {
                    throw IllegalStateException("Cannot switch to non-active worktree: ${latest.status}")
                }

                store.save(current.copy(activeWorktreeId = latest.id))
                latest
            }
        }
    }

    fun mergeWorktree(worktreeId: String, targetBranch: String): Result<WorktreeMergeResult> {
        return runCatching {
            val normalizedId = worktreeId.trim()
            val normalizedTargetBranch = targetBranch.trim()
            require(normalizedId.isNotBlank()) { "Worktree id cannot be blank" }
            require(normalizedTargetBranch.isNotBlank()) { "Target branch cannot be blank" }

            synchronized(lock) {
                val current = store.load()
                val existing = current.bindings.firstOrNull { it.id == normalizedId }
                    ?: throw IllegalArgumentException("Worktree not found: $normalizedId")

                val basePath = project.basePath ?: throw IllegalStateException("Project base path is not available")
                val mergeOutcome = gitExecutor.mergeBranch(basePath, existing.branchName, normalizedTargetBranch).getOrThrow()

                val mergedStatus = if (mergeOutcome.hasConflicts) WorktreeStatus.ERROR else WorktreeStatus.MERGED
                val updated = existing.copy(
                    status = mergedStatus,
                    updatedAt = System.currentTimeMillis(),
                    lastError = if (mergeOutcome.hasConflicts) mergeOutcome.statusDescription else null,
                )

                store.save(current.replaceBinding(updated))

                WorktreeMergeResult(
                    worktreeId = existing.id,
                    sourceBranch = existing.branchName,
                    targetBranch = normalizedTargetBranch,
                    hasConflicts = mergeOutcome.hasConflicts,
                    statusDescription = mergeOutcome.statusDescription,
                )
            }
        }
    }

    fun cleanupWorktree(worktreeId: String, force: Boolean = true): Result<Unit> {
        return runCatching {
            val normalizedId = worktreeId.trim()
            require(normalizedId.isNotBlank()) { "Worktree id cannot be blank" }

            synchronized(lock) {
                val current = store.load()
                val existing = current.bindings.firstOrNull { it.id == normalizedId }
                    ?: throw IllegalArgumentException("Worktree not found: $normalizedId")

                val basePath = project.basePath ?: throw IllegalStateException("Project base path is not available")
                gitExecutor.removeWorktree(basePath, existing.worktreePath, force).getOrThrow()

                val updated = existing.copy(
                    status = WorktreeStatus.REMOVED,
                    updatedAt = System.currentTimeMillis(),
                    lastError = null,
                )

                val nextActive = if (current.activeWorktreeId == normalizedId) null else current.activeWorktreeId
                store.save(current.replaceBinding(updated).copy(activeWorktreeId = nextActive))
                logger.info("Removed worktree: ${existing.worktreePath}")
            }
        }
    }

    fun listBindings(includeInactive: Boolean = true): List<WorktreeBinding> {
        val all = store.load().bindings.sortedByDescending { it.updatedAt }
        return if (includeInactive) all else all.filter { it.status == WorktreeStatus.ACTIVE }
    }

    fun getBinding(worktreeId: String): WorktreeBinding? {
        val normalizedId = worktreeId.trim()
        if (normalizedId.isBlank()) return null
        return store.load().bindings.firstOrNull { it.id == normalizedId }
    }

    fun getActiveWorktree(): WorktreeBinding? {
        val state = store.load()
        val activeId = state.activeWorktreeId ?: return null
        return state.bindings.firstOrNull { it.id == activeId }
    }

    internal fun resolveWorktreePath(basePath: String, specTaskId: String, shortName: String): Path {
        val repoDir = Paths.get(basePath)
        val parent = repoDir.parent ?: repoDir
        val dirName = "spec-${normalizeSegment(specTaskId)}-$shortName"
        return parent.resolve(dirName)
    }

    internal fun normalizeSegment(value: String): String {
        return value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
    }

    private fun WorktreeState.replaceBinding(updated: WorktreeBinding): WorktreeState {
        val replaced = bindings.map { binding -> if (binding.id == updated.id) updated else binding }
        return copy(bindings = replaced)
    }

    companion object {
        private const val DEFAULT_BASE_BRANCH = "main"
        fun getInstance(project: Project): WorktreeManager = project.service()
    }
}
