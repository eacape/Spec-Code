package com.eacape.speccodingplugin.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal enum class McpProcessLaunchFailureKind(val label: String) {
    EXECUTABLE_NOT_FOUND("executable-not-found"),
    ACCESS_DENIED("access-denied"),
    STARTUP_FAILED("startup-failed"),
}

internal data class McpProcessLaunchFailureDiagnostic(
    val kind: McpProcessLaunchFailureKind,
    val serverId: String,
    val serverName: String,
    val configuredCommand: String,
    val launchCommand: List<String>,
    val startupErrorMessage: String? = null,
) {
    fun renderMessage(): String {
        return "Failed to launch MCP server '$serverName' (${kind.label}): ${renderDetail()}"
    }

    fun renderDetail(): String {
        return when (kind) {
            McpProcessLaunchFailureKind.EXECUTABLE_NOT_FOUND ->
                buildString {
                    append("mcp executable was not found: ")
                    append(renderExecutable())
                    renderWindowsShellWrapperHint()?.let { hint ->
                        append("; ")
                        append(hint)
                    }
                }

            McpProcessLaunchFailureKind.ACCESS_DENIED ->
                "access denied while starting MCP server '$serverName'"

            McpProcessLaunchFailureKind.STARTUP_FAILED ->
                startupErrorMessage?.ifBlank { null }
                    ?: "mcp process could not be started"
        }
    }

    private fun renderExecutable(): String {
        return configuredCommand.substringAfterLast('/').substringAfterLast('\\').ifBlank {
            launchCommand.firstOrNull().orEmpty()
        }
    }

    private fun renderWindowsShellWrapperHint(): String? {
        val normalized = configuredCommand.trim().lowercase()
        if (normalized != "npx" && normalized != "npm") {
            return null
        }
        return "on Windows this command is usually a .cmd wrapper. Try '$normalized.cmd' or use an absolute path"
    }
}

internal class McpProcessLaunchException(
    val diagnostic: McpProcessLaunchFailureDiagnostic,
    cause: Throwable? = null,
) : IllegalStateException(diagnostic.renderMessage(), cause)

internal data class McpServerProcessLaunchRequest(
    val config: McpServerConfig,
    val launchCommand: List<String>,
)

internal class McpServerProcessRuntime(
    private val processStarter: (McpServerProcessLaunchRequest) -> Process = { request ->
        ProcessBuilder(request.launchCommand)
            .apply {
                if (request.config.env.isNotEmpty()) {
                    environment().putAll(request.config.env)
                }
                redirectErrorStream(false)
            }
            .start()
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val envProvider: (String) -> String? = System::getenv,
    private val regularFileChecker: (Path) -> Boolean = { path -> Files.isRegularFile(path) },
) {

    fun prepareLaunchCommand(config: McpServerConfig): List<String> {
        val normalized = config.command.trim()
        if (!isWindows()) {
            return listOf(normalized) + config.args
        }
        val resolved = resolveWindowsCommand(normalized) ?: normalized
        return listOf(resolved) + config.args
    }

    fun start(
        config: McpServerConfig,
        launchCommand: List<String> = prepareLaunchCommand(config),
    ): Process {
        val request = McpServerProcessLaunchRequest(
            config = config,
            launchCommand = launchCommand,
        )
        return runCatching {
            processStarter(request)
        }.getOrElse { error ->
            throw McpProcessLaunchException(
                diagnostic = McpProcessLaunchFailureDiagnostics.diagnoseLaunch(
                    config = config,
                    launchCommand = launchCommand,
                    startupErrorMessage = error.message ?: error::class.java.simpleName,
                ),
                cause = error,
            )
        }
    }

    private fun resolveWindowsCommand(command: String): String? {
        if (command.isBlank()) return null

        val path = runCatching { Paths.get(command) }.getOrNull()
        val hasPathSeparator = command.contains('\\') || command.contains('/')
        val hasExtension = path?.fileName?.toString()?.contains('.') == true
        if (hasPathSeparator) {
            if (path != null && regularFileChecker(path)) {
                return path.toString()
            }
            if (!hasExtension) {
                WINDOWS_EXEC_EXTENSIONS.forEach { extension ->
                    val candidate = Paths.get("$command$extension")
                    if (regularFileChecker(candidate)) {
                        return candidate.toString()
                    }
                }
            }
            return null
        }

        val pathEntries = envProvider("PATH").orEmpty()
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
                if (regularFileChecker(candidate)) {
                    return candidate.toString()
                }
            }
        }

        if (command.equals("npx", ignoreCase = true) || command.equals("npm", ignoreCase = true)) {
            preferredNodeCommandCandidates(command).forEach { candidate ->
                if (regularFileChecker(candidate)) {
                    return candidate.toString()
                }
            }
        }

        return null
    }

    private fun preferredNodeCommandCandidates(command: String): List<Path> {
        val commandCmd = "$command.cmd"
        val result = mutableListOf<Path>()
        val programFiles = envProvider("ProgramFiles")?.trim().orEmpty()
        val programFilesX86 = envProvider("ProgramFiles(x86)")?.trim().orEmpty()
        val appData = envProvider("APPDATA")?.trim().orEmpty()
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

    private fun isWindows(): Boolean = osNameProvider().startsWith("Windows", ignoreCase = true)

    private companion object {
        private val WINDOWS_EXEC_EXTENSIONS = listOf(".cmd", ".bat", ".exe", ".com")
    }
}

internal object McpProcessLaunchFailureDiagnostics {

    fun diagnoseLaunch(
        config: McpServerConfig,
        launchCommand: List<String>,
        startupErrorMessage: String,
    ): McpProcessLaunchFailureDiagnostic {
        return McpProcessLaunchFailureDiagnostic(
            kind = classifyFailureKind(startupErrorMessage),
            serverId = config.id,
            serverName = config.name,
            configuredCommand = config.command,
            launchCommand = launchCommand,
            startupErrorMessage = startupErrorMessage,
        )
    }

    private fun classifyFailureKind(startupErrorMessage: String): McpProcessLaunchFailureKind {
        val normalized = startupErrorMessage.lowercase()
        return when {
            normalized.contains("createprocess error=5") ||
                normalized.contains("access is denied") ||
                normalized.contains("permission denied") ->
                McpProcessLaunchFailureKind.ACCESS_DENIED

            normalized.contains("createprocess error=2") ||
                normalized.contains("no such file or directory") ||
                normalized.contains("cannot find the file specified") ||
                normalized.contains("error=2,") ||
                normalized.contains("missing executable") ||
                normalized.contains("executable not found") ||
                normalized.contains("command not found") ->
                McpProcessLaunchFailureKind.EXECUTABLE_NOT_FOUND

            else -> McpProcessLaunchFailureKind.STARTUP_FAILED
        }
    }
}
