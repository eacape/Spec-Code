package com.eacape.speccodingplugin.engine

import com.eacape.speccodingplugin.core.ExternalProcessLaunchSpec
import com.eacape.speccodingplugin.core.ExternalProcessLauncher
import com.eacape.speccodingplugin.core.ExternalProcessStartupFailureClassifier
import com.eacape.speccodingplugin.core.ExternalProcessStartupFailureKind
import com.eacape.speccodingplugin.core.ManagedMergedOutputProcess
import java.io.File
import java.util.concurrent.TimeUnit

internal enum class CliCommandFailureKind(val label: String) {
    EXECUTABLE_NOT_FOUND("executable-not-found"),
    WORKING_DIRECTORY_UNAVAILABLE("working-directory-unavailable"),
    ACCESS_DENIED("access-denied"),
    STARTUP_FAILED("startup-failed"),
}

internal data class CliCommandStartupDiagnostic(
    val kind: CliCommandFailureKind,
    val executable: String,
    val workingDirectory: File?,
    val launchCommand: List<String>,
    val startupErrorMessage: String? = null,
) {
    fun renderMessage(): String {
        return "CLI command failed to start (${kind.label}): ${renderDetail()}"
    }

    fun renderDetail(): String {
        return when (kind) {
            CliCommandFailureKind.EXECUTABLE_NOT_FOUND ->
                "cli executable was not found: ${renderExecutable()}"

            CliCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                "working directory is unavailable: ${workingDirectory?.absolutePath.orEmpty()}"

            CliCommandFailureKind.ACCESS_DENIED ->
                "access denied while starting CLI command in ${workingDirectory?.absolutePath.orEmpty()}"

            CliCommandFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null }
                    ?: "cli process could not be started"
        }
    }

    private fun renderExecutable(): String {
        return executable.substringAfterLast('/').substringAfterLast('\\').ifBlank { executable }
    }
}

internal class CliCommandStartupException(
    val diagnostic: CliCommandStartupDiagnostic,
    cause: Throwable? = null,
) : IllegalStateException(diagnostic.renderMessage(), cause)

internal data class CliCommandRequest(
    val executable: String,
    val args: List<String>,
    val workingDirectory: File? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val redirectErrorStream: Boolean = true,
    val allowWindowsCmdFallback: Boolean = true,
    val normalizeWindowsArgs: Boolean = true,
    val outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
) {
    fun renderCommand(): String = (listOf(executable) + args).joinToString(" ")

    companion object {
        private const val DEFAULT_OUTPUT_LIMIT_CHARS = 131_072
    }
}

internal data class CliCommandExecutionResult(
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val startupDiagnostic: CliCommandStartupDiagnostic? = null,
)

internal data class CliCommandLaunchPlan(
    val executable: String,
    val command: List<String>,
    val workingDirectory: File?,
    val environmentOverrides: Map<String, String>,
    val redirectErrorStream: Boolean,
)

internal class CliCommandRuntime(
    private val processStarter: (CliCommandLaunchPlan) -> Process = { plan ->
        ExternalProcessLauncher.start(
            ExternalProcessLaunchSpec(
                command = plan.command,
                workingDirectory = plan.workingDirectory,
                environmentOverrides = plan.environmentOverrides,
                redirectErrorStream = plan.redirectErrorStream,
            ),
        )
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val environmentProvider: () -> Map<String, String> = System::getenv,
    private val gitBashPathProvider: () -> String? = ::findGitBashPath,
    private val outputJoinTimeoutMillis: Long = DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS,
    private val timeoutDestroyWaitMillis: Long = DEFAULT_TIMEOUT_DESTROY_WAIT_MILLIS,
) {

    fun start(request: CliCommandRequest): Process {
        val launchPlans = buildLaunchPlans(request)
        var lastError: Throwable? = null
        var failedPlan: CliCommandLaunchPlan = launchPlans.first()
        launchPlans.forEachIndexed { index, plan ->
            try {
                return processStarter(plan)
            } catch (error: Throwable) {
                lastError = error
                failedPlan = plan
                if (index < launchPlans.lastIndex) {
                    return@forEachIndexed
                }
            }
        }
        throw CliCommandStartupException(
            diagnostic = CliCommandFailureDiagnostics.diagnoseStartup(
                request = request,
                failedPlan = failedPlan,
                startupErrorMessage = lastError?.message ?: lastError?.javaClass?.simpleName.orEmpty(),
            ),
            cause = lastError,
        )
    }

    fun execute(
        request: CliCommandRequest,
        timeoutMs: Long,
    ): CliCommandExecutionResult {
        val normalizedTimeoutMs = timeoutMs.coerceAtLeast(1L)
        return runCatching {
            val process = start(request)
            val runtime = ManagedMergedOutputProcess.start(
                process = process,
                outputLimitChars = request.outputLimitChars,
                threadName = "cli-command-${request.renderCommand().hashCode()}",
            )
            val completion = runtime.awaitCompletion(
                timeout = normalizedTimeoutMs,
                timeoutUnit = TimeUnit.MILLISECONDS,
                joinTimeoutMillis = outputJoinTimeoutMillis,
                timeoutDestroyWait = timeoutDestroyWaitMillis,
                timeoutDestroyWaitUnit = TimeUnit.MILLISECONDS,
            )
            CliCommandExecutionResult(
                output = completion.output,
                exitCode = completion.exitCode,
                timedOut = completion.timedOut,
                outputTruncated = completion.outputTruncated,
            )
        }.getOrElse { error ->
            val startupException = error as? CliCommandStartupException
            CliCommandExecutionResult(
                output = "",
                exitCode = null,
                timedOut = false,
                outputTruncated = false,
                startupDiagnostic = startupException?.diagnostic
                    ?: CliCommandFailureDiagnostics.diagnoseStartup(
                        request = request,
                        failedPlan = buildLaunchPlans(request).last(),
                        startupErrorMessage = error.message ?: error::class.java.simpleName,
                    ),
            )
        }
    }

    private fun buildLaunchPlans(request: CliCommandRequest): List<CliCommandLaunchPlan> {
        val baseEnvironment = environmentProvider()
        val normalizedArgs = normalizeArgs(request.args, request.normalizeWindowsArgs)
        val directPlan = CliCommandLaunchPlan(
            executable = request.executable,
            command = listOf(request.executable) + normalizedArgs,
            workingDirectory = request.workingDirectory,
            environmentOverrides = prepareEnvironmentOverrides(
                executable = request.executable,
                requestedOverrides = request.environmentOverrides,
                baseEnvironment = baseEnvironment,
            ),
            redirectErrorStream = request.redirectErrorStream,
        )
        if (!isWindows() || !request.allowWindowsCmdFallback) {
            return listOf(directPlan)
        }
        val fallbackPlan = directPlan.copy(
            command = listOf("cmd", "/c", request.executable) + normalizedArgs,
        )
        return listOf(directPlan, fallbackPlan)
    }

    private fun prepareEnvironmentOverrides(
        executable: String,
        requestedOverrides: Map<String, String>,
        baseEnvironment: Map<String, String>,
    ): Map<String, String> {
        val overrides = linkedMapOf<String, String>()
        requestedOverrides.forEach { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isNotEmpty() && normalizedValue.isNotEmpty()) {
                overrides[normalizedKey] = normalizedValue
            }
        }

        val parentDirectory = File(executable.trim().trim('"', '\'')).parentFile
            ?.takeIf(File::isDirectory)
        if (parentDirectory != null) {
            val pathKey = resolveEnvironmentKey(baseEnvironment, "PATH")
            val currentPath = overrides[pathKey] ?: envValue(baseEnvironment, "PATH").orEmpty()
            val normalizedParent = normalizePathForComparison(parentDirectory.path)
            val hasParentInPath = currentPath.split(File.pathSeparator)
                .asSequence()
                .map { normalizePathForComparison(it.trim().trim('"', '\'')) }
                .any { it == normalizedParent }
            if (!hasParentInPath) {
                overrides[pathKey] = if (currentPath.isBlank()) {
                    parentDirectory.path
                } else {
                    parentDirectory.path + File.pathSeparator + currentPath
                }
            }
        }

        if (isWindows() && executable.contains("claude", ignoreCase = true)) {
            val bashKey = resolveEnvironmentKey(baseEnvironment, CLAUDE_GIT_BASH_ENV)
            val configuredBash = overrides[bashKey]
                ?: envValue(baseEnvironment, CLAUDE_GIT_BASH_ENV)
            if (configuredBash.isNullOrBlank() || !File(configuredBash).isFile) {
                gitBashPathProvider()?.let { overrides[bashKey] = it }
            }
        }

        return overrides
    }

    private fun normalizeArgs(args: List<String>, normalizeWindowsArgs: Boolean): List<String> {
        if (!isWindows() || !normalizeWindowsArgs) return args
        return args.map { arg ->
            arg
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\n")
        }
    }

    private fun resolveEnvironmentKey(environment: Map<String, String>, key: String): String {
        return environment.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: key
    }

    private fun envValue(environment: Map<String, String>, key: String): String? {
        return environment[key]
            ?: environment.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    private fun normalizePathForComparison(path: String): String {
        return if (isWindows()) {
            path.replace('\\', '/').trimEnd('/').lowercase()
        } else {
            path.replace('\\', '/').trimEnd('/')
        }
    }

    private fun isWindows(): Boolean = osNameProvider().startsWith("Windows", ignoreCase = true)

    private companion object {
        private const val CLAUDE_GIT_BASH_ENV = "CLAUDE_CODE_GIT_BASH_PATH"
        private const val DEFAULT_OUTPUT_JOIN_TIMEOUT_MILLIS = 1_000L
        private const val DEFAULT_TIMEOUT_DESTROY_WAIT_MILLIS = 1_000L

        private fun findGitBashPath(): String? {
            val candidates = listOf(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe",
            )
            return candidates.firstOrNull { File(it).isFile }
        }
    }
}

internal object CliCommandFailureDiagnostics {

    fun diagnoseStartup(
        request: CliCommandRequest,
        failedPlan: CliCommandLaunchPlan,
        startupErrorMessage: String,
    ): CliCommandStartupDiagnostic {
        return CliCommandStartupDiagnostic(
            kind = classifyStartupFailureKind(
                workingDirectory = request.workingDirectory,
                startupErrorMessage = startupErrorMessage,
            ),
            executable = request.executable,
            workingDirectory = request.workingDirectory,
            launchCommand = failedPlan.command,
            startupErrorMessage = startupErrorMessage,
        )
    }

    private fun classifyStartupFailureKind(
        workingDirectory: File?,
        startupErrorMessage: String,
    ): CliCommandFailureKind {
        return when (
            ExternalProcessStartupFailureClassifier.classify(
                startupErrorMessage = startupErrorMessage,
                workingDirectory = workingDirectory,
            )
        ) {
            ExternalProcessStartupFailureKind.EXECUTABLE_NOT_FOUND ->
                CliCommandFailureKind.EXECUTABLE_NOT_FOUND

            ExternalProcessStartupFailureKind.WORKING_DIRECTORY_UNAVAILABLE ->
                CliCommandFailureKind.WORKING_DIRECTORY_UNAVAILABLE

            ExternalProcessStartupFailureKind.ACCESS_DENIED ->
                CliCommandFailureKind.ACCESS_DENIED

            ExternalProcessStartupFailureKind.STARTUP_FAILED ->
                CliCommandFailureKind.STARTUP_FAILED
        }
    }
}
