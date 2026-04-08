package com.eacape.speccodingplugin.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal enum class McpServerLaunchFailureKind(val label: String) {
    EXECUTABLE_NOT_FOUND("executable-not-found"),
    ACCESS_DENIED("access-denied"),
    STARTUP_FAILED("startup-failed"),
}

internal data class McpServerLaunchFailureDiagnostic(
    val kind: McpServerLaunchFailureKind,
    val serverId: String,
    val serverName: String,
    val configuredCommand: String,
    val launchCommand: List<String>,
    val startupErrorMessage: String? = null,
    val resolutionHint: String? = null,
) {
    fun renderMessage(): String {
        return "MCP server $serverName failed to start (${kind.label}): ${renderDetail()}"
    }

    fun renderDetail(): String {
        val attemptedCommand = renderAttemptedCommand()
        return when (kind) {
            McpServerLaunchFailureKind.EXECUTABLE_NOT_FOUND -> buildString {
                append("MCP command was not found: ")
                append(attemptedCommand)
                resolutionHint?.let {
                    append("; ")
                    append(it)
                }
            }

            McpServerLaunchFailureKind.ACCESS_DENIED ->
                "access denied while starting MCP command: $attemptedCommand"

            McpServerLaunchFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null } ?: "MCP server process could not be started"
        }
    }

    private fun renderAttemptedCommand(): String {
        return launchCommand.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: configuredCommand.trim().ifBlank { "<blank>" }
    }
}

internal class McpServerLaunchException(
    val diagnostic: McpServerLaunchFailureDiagnostic,
) : IllegalStateException(diagnostic.renderMessage())

internal data class McpServerProcessHandle(
    val process: Process,
    val launchCommand: List<String>,
)

internal class McpServerProcessRuntime(
    private val processStarter: (List<String>, Map<String, String>) -> Process = { launchCommand, env ->
        ProcessBuilder(launchCommand)
            .apply {
                if (env.isNotEmpty()) {
                    environment().putAll(env)
                }
                redirectErrorStream(false)
            }
            .start()
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val pathEnvironmentProvider: () -> String = { System.getenv("PATH").orEmpty() },
    private val envValueProvider: (String) -> String = { System.getenv(it).orEmpty() },
) {

    internal fun prepareLaunchCommand(config: McpServerConfig): List<String> {
        return resolveLaunchCommand(config.command, config.args)
    }

    fun start(
        config: McpServerConfig,
        launchCommand: List<String> = prepareLaunchCommand(config),
    ): McpServerProcessHandle {
        return runCatching {
            McpServerProcessHandle(
                process = processStarter(launchCommand, config.env),
                launchCommand = launchCommand,
            )
        }.getOrElse { error ->
            throw McpServerLaunchException(
                McpServerLaunchFailureDiagnostics.diagnoseStartup(
                    config = config,
                    launchCommand = launchCommand,
                    startupErrorMessage = error.message ?: error::class.java.simpleName,
                    isWindows = isWindows(),
                ),
            )
        }
    }

    private fun resolveLaunchCommand(command: String, args: List<String>): List<String> {
        val normalized = command.trim()
        if (!isWindows()) {
            return listOf(normalized) + args
        }
        val resolved = resolveWindowsCommand(normalized) ?: normalized
        return listOf(resolved) + args
    }

    private fun resolveWindowsCommand(command: String): String? {
        if (command.isBlank()) return null

        val path = runCatching { Paths.get(command) }.getOrNull()
        val hasPathSeparator = command.contains('\\') || command.contains('/')
        val hasExtension = path?.fileName?.toString()?.contains('.') == true
        if (hasPathSeparator) {
            if (path != null && Files.isRegularFile(path)) {
                return path.toString()
            }
            if (!hasExtension) {
                WINDOWS_EXEC_EXTENSIONS.forEach { extension ->
                    val candidate = Paths.get("$command$extension")
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString()
                    }
                }
            }
            return null
        }

        val pathEntries = pathEnvironmentProvider()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val commandVariants = if (hasExtension) {
            listOf(command)
        } else {
            WINDOWS_EXEC_EXTENSIONS.map { ext -> "$command$ext" }
        }
        pathEntries.forEach { directory ->
            commandVariants.forEach { variant ->
                val candidate = Paths.get(directory, variant)
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString()
                }
            }
        }

        if (command.equals("npx", ignoreCase = true) || command.equals("npm", ignoreCase = true)) {
            preferredNodeCommandCandidates(command).forEach { candidate ->
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString()
                }
            }
        }

        return null
    }

    private fun preferredNodeCommandCandidates(command: String): List<Path> {
        val commandCmd = "$command.cmd"
        val result = mutableListOf<Path>()
        val programFiles = envValueProvider("ProgramFiles").trim()
        val programFilesX86 = envValueProvider("ProgramFiles(x86)").trim()
        val appData = envValueProvider("APPDATA").trim()
        if (programFiles.isNotBlank()) {
            result.add(Paths.get(programFiles, "nodejs", commandCmd))
        }
        if (programFilesX86.isNotBlank()) {
            result.add(Paths.get(programFilesX86, "nodejs", commandCmd))
        }
        if (appData.isNotBlank()) {
            result.add(Paths.get(appData, "npm", commandCmd))
        }
        return result
    }

    private fun isWindows(): Boolean {
        return osNameProvider().startsWith("Windows", ignoreCase = true)
    }

    private companion object {
        private val WINDOWS_EXEC_EXTENSIONS = listOf(".cmd", ".bat", ".exe", ".com")
    }
}

internal object McpServerLaunchFailureDiagnostics {

    fun diagnoseStartup(
        config: McpServerConfig,
        launchCommand: List<String>,
        startupErrorMessage: String,
        isWindows: Boolean,
    ): McpServerLaunchFailureDiagnostic {
        val kind = classifyStartupFailureKind(startupErrorMessage)
        return McpServerLaunchFailureDiagnostic(
            kind = kind,
            serverId = config.id,
            serverName = config.name,
            configuredCommand = config.command,
            launchCommand = launchCommand,
            startupErrorMessage = startupErrorMessage,
            resolutionHint = buildResolutionHint(config.command, kind, isWindows),
        )
    }

    private fun classifyStartupFailureKind(
        startupErrorMessage: String,
    ): McpServerLaunchFailureKind {
        val normalized = startupErrorMessage.lowercase()
        return when {
            normalized.contains("createprocess error=5") ||
                normalized.contains("access is denied") ||
                normalized.contains("permission denied") ->
                McpServerLaunchFailureKind.ACCESS_DENIED

            normalized.contains("createprocess error=2") ||
                normalized.contains("no such file or directory") ||
                normalized.contains("cannot find the file specified") ||
                normalized.contains("error=2,") ||
                normalized.contains("missing executable") ||
                normalized.contains("executable not found") ->
                McpServerLaunchFailureKind.EXECUTABLE_NOT_FOUND

            else -> McpServerLaunchFailureKind.STARTUP_FAILED
        }
    }

    private fun buildResolutionHint(
        configuredCommand: String,
        kind: McpServerLaunchFailureKind,
        isWindows: Boolean,
    ): String? {
        if (!isWindows || kind != McpServerLaunchFailureKind.EXECUTABLE_NOT_FOUND) {
            return null
        }

        return when (configuredCommand.trim().lowercase()) {
            "npx", "npm" ->
                "on Windows this command is usually a .cmd wrapper; try using '${configuredCommand.trim()}.cmd' or install the global binary directly"

            else -> null
        }
    }
}
