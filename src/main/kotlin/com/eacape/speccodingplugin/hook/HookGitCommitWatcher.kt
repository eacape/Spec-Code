package com.eacape.speccodingplugin.hook

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class HookGitCommitWatcher(
    private val project: Project,
) : Disposable {
    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @Volatile
    private var lastObservedHead: String? = null

    @Volatile
    private var initialized = false

    fun start() {
        if (started || project.isDisposed) {
            return
        }
        started = true
        scope.launch {
            while (!project.isDisposed) {
                runCatching { pollHeadChange() }
                    .onFailure { error ->
                        logger.debug("HookGitCommitWatcher poll failed", error)
                    }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun pollHeadChange() {
        val basePath = project.basePath?.trim().orEmpty()
        if (basePath.isBlank()) {
            return
        }
        if (!File(basePath, ".git").exists()) {
            return
        }
        val currentHead = runGit(basePath, "rev-parse", "HEAD") ?: return
        if (!initialized) {
            lastObservedHead = currentHead
            initialized = true
            return
        }

        val previousHead = lastObservedHead
        if (!previousHead.isNullOrBlank() && previousHead != currentHead) {
            val reflogSummary = runGit(basePath, "reflog", "-1", "--pretty=%gs")
            val isCommitLike = reflogSummary?.contains("commit", ignoreCase = true) == true
            if (!isCommitLike) {
                lastObservedHead = currentHead
                return
            }
            val metadata = linkedMapOf(
                "projectName" to project.name,
                "previousCommit" to previousHead,
                "currentCommit" to currentHead,
            )
            val branch = runGit(basePath, "rev-parse", "--abbrev-ref", "HEAD")
            if (!branch.isNullOrBlank()) {
                metadata["branch"] = branch
            }
            if (!reflogSummary.isNullOrBlank()) {
                metadata["reflog"] = reflogSummary
            }
            HookManager.getInstance(project).trigger(
                event = HookEvent.GIT_COMMIT,
                triggerContext = HookTriggerContext(metadata = metadata),
            )
        }
        lastObservedHead = currentHead
    }

    private fun runGit(basePath: String, vararg args: String): String? {
        return runCatching {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val finished = process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return null
            }
            if (process.exitValue() != 0) {
                return null
            }
            output.ifBlank { null }
        }.getOrNull()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
        private const val GIT_COMMAND_TIMEOUT_SECONDS = 2L

        fun getInstance(project: Project): HookGitCommitWatcher = project.service()
    }
}
