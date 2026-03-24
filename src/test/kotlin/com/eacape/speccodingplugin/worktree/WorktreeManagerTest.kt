package com.eacape.speccodingplugin.worktree

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorktreeManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project
    private lateinit var store: FakeWorktreeStore
    private lateinit var gitExecutor: FakeGitWorktreeExecutor
    private lateinit var projectOpener: FakeWorktreeProjectOpener
    private lateinit var manager: WorktreeManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.resolve("repo").toString()

        store = FakeWorktreeStore()
        gitExecutor = FakeGitWorktreeExecutor()
        projectOpener = FakeWorktreeProjectOpener()
        manager = WorktreeManager(project, store, gitExecutor, projectOpener)
    }

    @Test
    fun `createWorktree should create binding and mark it active`() {
        val created = manager.createWorktree(
            specTaskId = "SPEC-123",
            shortName = "Auth Flow",
            baseBranch = "main",
        ).getOrThrow()

        assertEquals("spec/spec-123-auth-flow", created.branchName)
        assertTrue(created.worktreePath.contains("spec-spec-123-auth-flow"))
        assertEquals(WorktreeStatus.ACTIVE, created.status)
        assertEquals(created.id, manager.getActiveWorktree()?.id)
        assertEquals(1, manager.listBindings().size)

        assertEquals(1, gitExecutor.addCalls.size)
        val call = gitExecutor.addCalls.first()
        assertEquals("spec/spec-123-auth-flow", call.branchName)
        assertEquals("main", call.baseBranch)
    }

    @Test
    fun `createWorktree should fail when active binding exists for same spec task`() {
        manager.createWorktree("SPEC-123", "one", "main").getOrThrow()

        val result = manager.createWorktree("SPEC-123", "two", "main")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Active worktree already exists") == true)
    }

    @Test
    fun `createWorktree should persist resolved base branch from executor`() {
        gitExecutor.resolveOutcome = Result.success("master")

        val created = manager.createWorktree("SPEC-124", "fallback-base", "main").getOrThrow()

        assertEquals("master", created.baseBranch)
        assertEquals(1, gitExecutor.resolveCalls.size)
        assertEquals("main", gitExecutor.resolveCalls.first().requestedBaseBranch)
        assertEquals("master", gitExecutor.addCalls.first().baseBranch)
    }

    @Test
    fun `switchWorktree should set active binding`() {
        val first = manager.createWorktree("SPEC-1", "first", "main").getOrThrow()
        val second = manager.createWorktree("SPEC-2", "second", "main").getOrThrow()

        manager.switchWorktree(first.id).getOrThrow()
        assertEquals(first.id, manager.getActiveWorktree()?.id)

        manager.switchWorktree(second.id).getOrThrow()
        assertEquals(second.id, manager.getActiveWorktree()?.id)

        assertEquals(2, projectOpener.openCalls.size)
        assertEquals(first.worktreePath, projectOpener.openCalls[0])
        assertEquals(second.worktreePath, projectOpener.openCalls[1])
    }

    @Test
    fun `createAndOpenWorktree should open newly created worktree`() {
        val created = manager.createAndOpenWorktree("SPEC-3", "open-now", "main").getOrThrow()

        assertEquals(1, projectOpener.openCalls.size)
        assertEquals(created.worktreePath, projectOpener.openCalls.first())
        assertEquals(created.id, manager.getActiveWorktree()?.id)
    }

    @Test
    fun `createAndOpenWorktree should fail when opener fails`() {
        projectOpener.openOutcome = Result.failure(IllegalStateException("open failed"))

        val result = manager.createAndOpenWorktree("SPEC-4", "open-fail", "main")

        assertTrue(result.isFailure)
        assertEquals(1, projectOpener.openCalls.size)

        val bindings = manager.listBindings()
        assertEquals(1, bindings.size)
        assertEquals(bindings.first().id, manager.getActiveWorktree()?.id)
    }

    @Test
    fun `switchWorktree should fail and keep active when opener fails`() {
        val first = manager.createWorktree("SPEC-1", "first", "main").getOrThrow()
        val second = manager.createWorktree("SPEC-2", "second", "main").getOrThrow()
        assertEquals(second.id, manager.getActiveWorktree()?.id)

        projectOpener.openOutcome = Result.failure(IllegalStateException("open failed"))

        val result = manager.switchWorktree(first.id)

        assertTrue(result.isFailure)
        assertEquals(second.id, manager.getActiveWorktree()?.id)
    }

    @Test
    fun `mergeWorktree should mark merged on clean merge`() {
        val created = manager.createWorktree("SPEC-1", "clean-merge", "main").getOrThrow()
        gitExecutor.mergeOutcome = Result.success(GitMergeOutcome(hasConflicts = false, statusDescription = "MERGED"))

        val merged = manager.mergeWorktree(created.id, "main").getOrThrow()

        assertFalse(merged.hasConflicts)
        assertEquals("MERGED", merged.statusDescription)
        assertEquals(WorktreeStatus.MERGED, manager.getBinding(created.id)?.status)
    }

    @Test
    fun `mergeWorktree should mark error on conflicts`() {
        val created = manager.createWorktree("SPEC-1", "conflict-merge", "main").getOrThrow()
        gitExecutor.mergeOutcome = Result.success(
            GitMergeOutcome(hasConflicts = true, statusDescription = "CONFLICT (content): Merge conflict in A.kt")
        )

        val merged = manager.mergeWorktree(created.id, "main").getOrThrow()
        val updated = manager.getBinding(created.id)

        assertTrue(merged.hasConflicts)
        assertNotNull(updated)
        assertEquals(WorktreeStatus.ERROR, updated?.status)
        assertTrue(updated?.lastError?.contains("CONFLICT") == true)
    }

    @Test
    fun `cleanupWorktree should remove active marker and set removed status`() {
        val created = manager.createWorktree("SPEC-1", "cleanup", "main").getOrThrow()
        assertEquals(created.id, manager.getActiveWorktree()?.id)

        manager.cleanupWorktree(created.id, force = true).getOrThrow()

        assertEquals(WorktreeStatus.REMOVED, manager.getBinding(created.id)?.status)
        assertEquals(null, manager.getActiveWorktree())
        assertEquals(1, gitExecutor.removeCalls.size)
        assertTrue(gitExecutor.removeCalls.first().force)
    }

    @Test
    fun `worktree lifecycle should support create switch merge and cleanup flow`() {
        val first = manager.createWorktree("SPEC-100", "flow-a", "main").getOrThrow()
        val second = manager.createWorktree("SPEC-101", "flow-b", "main").getOrThrow()
        assertEquals(second.id, manager.getActiveWorktree()?.id)

        manager.switchWorktree(first.id).getOrThrow()
        assertEquals(first.id, manager.getActiveWorktree()?.id)

        gitExecutor.mergeOutcome = Result.success(
            GitMergeOutcome(hasConflicts = false, statusDescription = "MERGED")
        )
        val merged = manager.mergeWorktree(first.id, "main").getOrThrow()
        assertFalse(merged.hasConflicts)
        assertEquals(WorktreeStatus.MERGED, manager.getBinding(first.id)?.status)

        manager.cleanupWorktree(first.id, force = true).getOrThrow()
        assertEquals(WorktreeStatus.REMOVED, manager.getBinding(first.id)?.status)
        assertEquals(null, manager.getActiveWorktree())

        manager.switchWorktree(second.id).getOrThrow()
        assertEquals(second.id, manager.getActiveWorktree()?.id)

        assertEquals(2, projectOpener.openCalls.size)
        assertEquals(first.worktreePath, projectOpener.openCalls[0])
        assertEquals(second.worktreePath, projectOpener.openCalls[1])

        val activeOnly = manager.listBindings(includeInactive = false)
        assertEquals(1, activeOnly.size)
        assertEquals(second.id, activeOnly.first().id)
    }

    @Test
    fun `normalizeSegment should normalize unsupported characters`() {
        assertEquals("spec-123-abc", manager.normalizeSegment("  SPEC@123 ABC  "))
        assertEquals("a-b-c", manager.normalizeSegment("A@@B@@C"))
    }

    @Test
    fun `suggestBaseBranch should prefer current branch`() {
        gitExecutor.currentBranchOutcome = Result.success("feature/xyz")
        gitExecutor.resolveOutcome = Result.success("main")

        val suggested = manager.suggestBaseBranch()

        assertEquals("feature/xyz", suggested)
        assertEquals(1, gitExecutor.currentBranchCalls.size)
        assertEquals(0, gitExecutor.resolveCalls.size)
    }

    @Test
    fun `suggestBaseBranch should fallback to resolved base branch when current branch unavailable`() {
        gitExecutor.currentBranchOutcome = Result.failure(IllegalStateException("detached head"))
        gitExecutor.resolveOutcome = Result.success("develop")

        val suggested = manager.suggestBaseBranch()

        assertEquals("develop", suggested)
        assertEquals(1, gitExecutor.currentBranchCalls.size)
        assertEquals(1, gitExecutor.resolveCalls.size)
    }

    private class FakeWorktreeStore : WorktreeStateStore {
        private var state = WorktreeState()

        override fun load(): WorktreeState = state

        override fun save(newState: WorktreeState) {
            state = newState
        }
    }

    private class FakeGitWorktreeExecutor : GitWorktreeExecutor {
        data class CurrentBranchCall(
            val repoPath: String,
        )

        data class ResolveCall(
            val repoPath: String,
            val requestedBaseBranch: String,
        )

        data class AddCall(
            val repoPath: String,
            val worktreePath: String,
            val branchName: String,
            val baseBranch: String,
        )

        data class RemoveCall(
            val repoPath: String,
            val worktreePath: String,
            val force: Boolean,
        )

        val currentBranchCalls = mutableListOf<CurrentBranchCall>()
        val resolveCalls = mutableListOf<ResolveCall>()
        val addCalls = mutableListOf<AddCall>()
        val removeCalls = mutableListOf<RemoveCall>()

        var currentBranchOutcome: Result<String> = Result.failure(IllegalStateException("not configured"))
        var resolveOutcome: Result<String> = Result.success("main")
        var addOutcome: Result<Unit> = Result.success(Unit)
        var removeOutcome: Result<Unit> = Result.success(Unit)
        var mergeOutcome: Result<GitMergeOutcome> = Result.success(
            GitMergeOutcome(hasConflicts = false, statusDescription = "MERGED")
        )

        override fun resolveCurrentBranch(repoPath: String): Result<String> {
            currentBranchCalls += CurrentBranchCall(repoPath = repoPath)
            return currentBranchOutcome
        }

        override fun resolveBaseBranch(repoPath: String, requestedBaseBranch: String): Result<String> {
            resolveCalls += ResolveCall(repoPath = repoPath, requestedBaseBranch = requestedBaseBranch)
            return resolveOutcome
        }

        override fun addWorktree(
            repoPath: String,
            worktreePath: String,
            branchName: String,
            baseBranch: String,
        ): Result<Unit> {
            addCalls += AddCall(repoPath, worktreePath, branchName, baseBranch)
            return addOutcome
        }

        override fun removeWorktree(repoPath: String, worktreePath: String, force: Boolean): Result<Unit> {
            removeCalls += RemoveCall(repoPath, worktreePath, force)
            return removeOutcome
        }

        override fun mergeBranch(repoPath: String, sourceBranch: String, targetBranch: String): Result<GitMergeOutcome> {
            return mergeOutcome
        }
    }

    private class FakeWorktreeProjectOpener : WorktreeProjectOpener {
        val openCalls = mutableListOf<String>()
        var openOutcome: Result<Unit> = Result.success(Unit)

        override fun openProject(worktreePath: String): Result<Unit> {
            openCalls += worktreePath
            return openOutcome
        }
    }
}
