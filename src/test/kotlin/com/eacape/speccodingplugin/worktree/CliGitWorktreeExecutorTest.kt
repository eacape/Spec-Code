package com.eacape.speccodingplugin.worktree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliGitWorktreeExecutorTest {

    @Test
    fun `addWorktree should build git worktree add command`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = CliGitWorktreeExecutor { repoPath, command ->
            calls += repoPath to command
            CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "ok")
        }

        val result = executor.addWorktree(
            repoPath = "D:/repo",
            worktreePath = "D:/repo-worktrees/spec-1",
            branchName = "spec/spec-1-demo",
            baseBranch = "main",
        )

        assertTrue(result.isSuccess)
        assertEquals(1, calls.size)
        assertEquals("D:/repo", calls.first().first)
        assertEquals(
            listOf("git", "worktree", "add", "D:/repo-worktrees/spec-1", "-b", "spec/spec-1-demo", "main"),
            calls.first().second,
        )
    }

    @Test
    fun `removeWorktree should include force flag when enabled`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = CliGitWorktreeExecutor { repoPath, command ->
            calls += repoPath to command
            CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "removed")
        }

        val result = executor.removeWorktree(
            repoPath = "D:/repo",
            worktreePath = "D:/repo-worktrees/spec-2",
            force = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("git", "worktree", "remove", "--force", "D:/repo-worktrees/spec-2"),
            calls.first().second,
        )
    }

    @Test
    fun `mergeBranch should run checkout then merge and detect conflicts`() {
        val commands = mutableListOf<List<String>>()
        val executor = CliGitWorktreeExecutor { _, command ->
            commands += command
            when (command) {
                listOf("git", "worktree", "list", "--porcelain") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
                }

                listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "spec/spec-3\n")
                }

                listOf("git", "checkout", "main") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "Switched to branch 'main'")
                }

                listOf("git", "merge", "--no-ff", "spec/spec-3") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "CONFLICT (content): Merge conflict")
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.mergeBranch(
            repoPath = "D:/repo",
            sourceBranch = "spec/spec-3",
            targetBranch = "main",
        )

        assertTrue(result.isSuccess)
        val mergeOutcome = result.getOrThrow()
        assertTrue(mergeOutcome.hasConflicts)
        assertTrue(mergeOutcome.statusDescription.contains("CONFLICT"))
        assertTrue(commands.contains(listOf("git", "checkout", "main")))
        assertTrue(commands.contains(listOf("git", "merge", "--no-ff", "spec/spec-3")))
    }

    @Test
    fun `mergeBranch should use MERGED description when merge output blank`() {
        val commands = mutableListOf<List<String>>()
        val executor = CliGitWorktreeExecutor { _, command ->
            commands += command
            when (command) {
                listOf("git", "worktree", "list", "--porcelain") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
                }

                listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "main\n")
                }

                listOf("git", "merge", "--no-ff", "spec/spec-4") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "   ")
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.mergeBranch(
            repoPath = "D:/repo",
            sourceBranch = "spec/spec-4",
            targetBranch = "main",
        )

        assertTrue(result.isSuccess)
        val mergeOutcome = result.getOrThrow()
        assertFalse(mergeOutcome.hasConflicts)
        assertEquals("MERGED", mergeOutcome.statusDescription)
        assertFalse(commands.contains(listOf("git", "checkout", "main")))
    }

    @Test
    fun `mergeBranch should prefer target branch worktree when available`() {
        val calls = mutableListOf<Pair<String, List<String>>>()
        val executor = CliGitWorktreeExecutor { repoPath, command ->
            calls += repoPath to command
            when {
                repoPath == "D:/repo-worktrees/spec-3" &&
                    command == listOf("git", "worktree", "list", "--porcelain") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(
                        exitCode = 0,
                        output = """
                            worktree D:/repo-main
                            HEAD abcdef1
                            branch refs/heads/main

                            worktree D:/repo-worktrees/spec-3
                            HEAD abcdef2
                            branch refs/heads/spec/spec-3
                        """.trimIndent(),
                    )
                }

                repoPath == "D:/repo-main" &&
                    command == listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "main\n")
                }

                repoPath == "D:/repo-main" &&
                    command == listOf("git", "merge", "--no-ff", "spec/spec-3") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "MERGED")
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.mergeBranch(
            repoPath = "D:/repo-worktrees/spec-3",
            sourceBranch = "spec/spec-3",
            targetBranch = "main",
        )

        assertTrue(result.isSuccess)
        assertTrue(
            calls.any { (path, command) ->
                path == "D:/repo-main" && command == listOf("git", "merge", "--no-ff", "spec/spec-3")
            }
        )
    }

    @Test
    fun `addWorktree should expose git stderr output on failure`() {
        val executor = CliGitWorktreeExecutor { _, _ ->
            CliGitWorktreeExecutor.ProcessExecutionResult(
                exitCode = 128,
                output = "fatal: invalid reference: missing-base",
            )
        }

        val result = executor.addWorktree(
            repoPath = "D:/repo",
            worktreePath = "D:/repo-worktrees/spec-5",
            branchName = "spec/spec-5",
            baseBranch = "missing-base",
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Git command failed"))
        assertTrue(message.contains("invalid reference"))
    }

    @Test
    fun `resolveBaseBranch should fallback from main to current branch`() {
        val commands = mutableListOf<List<String>>()
        val executor = CliGitWorktreeExecutor { _, command ->
            commands += command
            when (command) {
                listOf("git", "rev-parse", "--is-inside-work-tree") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "true\n")
                }

                listOf("git", "rev-parse", "--verify", "--quiet", "main") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "release\n")
                }

                listOf("git", "symbolic-ref", "--short", "HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "refs/heads/release\n")
                }

                listOf("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "origin/main\n")
                }

                listOf("git", "rev-parse", "--verify", "--quiet", "release") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "abc123")
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.resolveBaseBranch(
            repoPath = "D:/repo",
            requestedBaseBranch = "main",
        )

        assertTrue(result.isSuccess)
        assertEquals("release", result.getOrThrow())
        assertTrue(commands.any { it == listOf("git", "rev-parse", "--verify", "--quiet", "main") })
        assertTrue(commands.any { it == listOf("git", "rev-parse", "--verify", "--quiet", "release") })
    }

    @Test
    fun `resolveBaseBranch should fail for non-default missing branch`() {
        val executor = CliGitWorktreeExecutor { _, command ->
            if (command == listOf("git", "rev-parse", "--is-inside-work-tree")) {
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "true\n")
            } else if (command.take(4) == listOf("git", "rev-parse", "--verify", "--quiet")) {
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
            } else {
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
            }
        }

        val result = executor.resolveBaseBranch(
            repoPath = "D:/repo",
            requestedBaseBranch = "feature/missing",
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Base branch"))
        assertTrue(message.contains("feature/missing"))
    }

    @Test
    fun `resolveBaseBranch should fallback to discovered local branch when current branch unavailable`() {
        val commands = mutableListOf<List<String>>()
        val executor = CliGitWorktreeExecutor { _, command ->
            commands += command
            when (command) {
                listOf("git", "rev-parse", "--is-inside-work-tree") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "true\n")
                }

                listOf("git", "rev-parse", "--verify", "--quiet", "main") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                listOf("git", "symbolic-ref", "--short", "HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                listOf("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                listOf("git", "config", "--get", "init.defaultBranch") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/heads") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "specify\nfeature/demo\n")
                }

                listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "origin/HEAD\norigin/specify\n")
                }

                listOf("git", "rev-list", "--max-count=1", "--all") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "abc123def")
                }

                listOf("git", "rev-parse", "--verify", "--quiet", "specify") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "abc123def")
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.resolveBaseBranch(
            repoPath = "D:/repo",
            requestedBaseBranch = "main",
        )

        assertTrue(result.isSuccess)
        assertEquals("specify", result.getOrThrow())
        assertTrue(commands.any { it == listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/heads") })
        assertTrue(commands.any { it == listOf("git", "rev-parse", "--verify", "--quiet", "specify") })
    }

    @Test
    fun `resolveBaseBranch should fallback to origin main when local main is missing`() {
        val commands = mutableListOf<List<String>>()
        val executor = CliGitWorktreeExecutor { _, command ->
            commands += command
            when {
                command == listOf("git", "rev-parse", "--is-inside-work-tree") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "true\n")
                }

                command == listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "symbolic-ref", "--short", "HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "origin/main\n")
                }

                command == listOf("git", "config", "--get", "init.defaultBranch") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/heads") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
                }

                command == listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "origin/HEAD\norigin/main\n")
                }

                command == listOf("git", "rev-list", "--max-count=1", "--all") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "abc123")
                }

                command.take(4) == listOf("git", "rev-parse", "--verify", "--quiet") -> {
                    val ref = command.last()
                    val isRemoteMain = ref == "origin/main"
                    CliGitWorktreeExecutor.ProcessExecutionResult(
                        exitCode = if (isRemoteMain) 0 else 1,
                        output = if (isRemoteMain) "abc123" else "",
                    )
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.resolveBaseBranch(
            repoPath = "D:/repo",
            requestedBaseBranch = "main",
        )

        assertTrue(result.isSuccess)
        assertEquals("origin/main", result.getOrThrow())
        assertTrue(commands.any { it == listOf("git", "rev-parse", "--verify", "--quiet", "origin/main") })
    }

    @Test
    fun `resolveBaseBranch should fail with clear message when repository has no commits`() {
        val executor = CliGitWorktreeExecutor { _, command ->
            when {
                command == listOf("git", "rev-parse", "--is-inside-work-tree") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "true\n")
                }

                command == listOf("git", "branch", "--show-current") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "symbolic-ref", "--short", "HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "config", "--get", "init.defaultBranch") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                command == listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/heads") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
                }

                command == listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
                }

                command == listOf("git", "rev-list", "--max-count=1", "--all") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 0, output = "")
                }

                command.take(4) == listOf("git", "rev-parse", "--verify", "--quiet") -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }

                else -> {
                    CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
                }
            }
        }

        val result = executor.resolveBaseBranch(
            repoPath = "D:/repo",
            requestedBaseBranch = "main",
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("no commits"))
    }

    @Test
    fun `resolveBaseBranch should surface safe directory hint on dubious ownership`() {
        val executor = CliGitWorktreeExecutor { _, command ->
            if (command == listOf("git", "rev-parse", "--is-inside-work-tree")) {
                CliGitWorktreeExecutor.ProcessExecutionResult(
                    exitCode = 128,
                    output = "fatal: detected dubious ownership in repository at 'D:/repo'",
                )
            } else {
                CliGitWorktreeExecutor.ProcessExecutionResult(exitCode = 1, output = "")
            }
        }

        val result = executor.resolveBaseBranch(
            repoPath = "D:/repo",
            requestedBaseBranch = "main",
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("safe.directory"))
    }
}
