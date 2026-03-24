package com.eacape.speccodingplugin.hook

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class HookManager internal constructor(
    private val project: Project,
    private val configStoreProvider: () -> HookConfigStore,
    private val executorProvider: () -> HookExecutor,
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = thisLogger()

    private val executionLogs = CopyOnWriteArrayList<HookExecutionLog>()
    private val filePatternCache = ConcurrentHashMap<String, java.nio.file.PathMatcher>()
    private val inFlightExecutionKeys = ConcurrentHashMap.newKeySet<String>()

    constructor(project: Project) : this(
        project = project,
        configStoreProvider = { HookConfigStore.getInstance(project) },
        executorProvider = { HookExecutor.getInstance(project) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    fun listHooks(): List<HookDefinition> = configStoreProvider().listHooks()

    fun saveHook(definition: HookDefinition) = configStoreProvider().saveHook(definition)

    fun deleteHook(id: String): Boolean = configStoreProvider().deleteHook(id)

    fun setHookEnabled(id: String, enabled: Boolean): Boolean =
        configStoreProvider().setHookEnabled(id, enabled)

    fun getExecutionLogs(limit: Int = 100): List<HookExecutionLog> {
        val normalizedLimit = limit.coerceAtLeast(1)
        return executionLogs.takeLast(normalizedLimit)
    }

    fun clearExecutionLogs() {
        executionLogs.clear()
    }

    fun trigger(event: HookEvent, triggerContext: HookTriggerContext) {
        val matchedHooks = matchHooks(event, triggerContext)
        if (matchedHooks.isEmpty()) {
            return
        }

        val executor = executorProvider()
        matchedHooks.forEach { hook ->
            val executionKey = buildExecutionKey(hook, event, triggerContext)
            if (!inFlightExecutionKeys.add(executionKey)) {
                logger.debug("Skipping duplicate in-flight hook execution: ${hook.id} ($executionKey)")
                return@forEach
            }
            scope.launch {
                try {
                    val log = executor.execute(hook, triggerContext)
                    appendLog(log)
                } finally {
                    inFlightExecutionKeys.remove(executionKey)
                }
            }
        }
    }

    fun matchHooks(event: HookEvent, triggerContext: HookTriggerContext): List<HookDefinition> {
        val hooks = configStoreProvider().listHooks()
        return hooks
            .asSequence()
            .filter { it.enabled }
            .filter { it.event == event }
            .filter { matchesConditions(it.conditions, triggerContext) }
            .toList()
    }

    private fun matchesConditions(conditions: HookConditions, triggerContext: HookTriggerContext): Boolean {
        if (!matchesSpecStage(conditions.specStage, triggerContext.specStage)) {
            return false
        }
        if (!matchesFilePattern(conditions.filePattern, triggerContext.filePath)) {
            return false
        }
        return true
    }

    private fun matchesSpecStage(expected: String?, actual: String?): Boolean {
        val normalizedExpected = expected?.trim()?.ifBlank { null } ?: return true
        val normalizedActual = actual?.trim()?.ifBlank { null } ?: return false
        return normalizedActual.equals(normalizedExpected, ignoreCase = true)
    }

    private fun matchesFilePattern(pattern: String?, filePath: String?): Boolean {
        val normalizedPattern = pattern?.trim()?.ifBlank { null } ?: return true
        val normalizedFilePath = filePath?.trim()?.ifBlank { null } ?: return false

        return try {
            val matcher = filePatternCache.computeIfAbsent(normalizedPattern) {
                FileSystems.getDefault().getPathMatcher("glob:$it")
            }
            val path = Paths.get(normalizedFilePath)
            matcher.matches(path) || matcher.matches(path.fileName)
        } catch (e: Exception) {
            logger.warn("Invalid hook file pattern: $normalizedPattern", e)
            false
        }
    }

    private fun appendLog(log: HookExecutionLog) {
        executionLogs.add(log)
        while (executionLogs.size > MAX_LOG_ENTRIES) {
            executionLogs.removeAt(0)
        }
        if (log.success) {
            logger.info("Hook executed successfully: ${log.hookId} - ${log.message}")
        } else {
            logger.warn("Hook execution failed: ${log.hookId} - ${log.message}")
        }
    }

    private fun buildExecutionKey(
        hook: HookDefinition,
        event: HookEvent,
        triggerContext: HookTriggerContext,
    ): String {
        return listOf(
            hook.id,
            event.name,
            triggerContext.filePath.orEmpty(),
            triggerContext.specStage.orEmpty(),
        ).joinToString("|")
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 200

        fun getInstance(project: Project): HookManager = project.service()
    }
}
