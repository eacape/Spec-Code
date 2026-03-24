package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Paths

/**
 * MCP 安全校验器
 * 验证 MCP Server 配置的安全性，防止命令注入和未授权连接
 */
object McpSecurityValidator {
    private val logger = thisLogger()

    private val SAFE_COMMANDS = setOf(
        "npx", "npx.cmd", "npm", "npm.cmd",
        "node", "python", "python3", "uvx", "uv",
        "docker", "deno", "bun", "cargo",
        "chrome-devtools-mcp", "chrome-devtools-mcp.cmd"
    )

    private val UNSAFE_CONTROL_CHARACTERS = Regex("[\\u0000\\r\\n]")

    /**
     * 启动前完整校验
     */
    fun validateBeforeStart(config: McpServerConfig): Result<Unit> {
        return runCatching {
            if (!config.trusted) {
                throw SecurityException(
                    "Server '${config.name}' is not trusted. Mark as trusted before starting."
                )
            }
            if (!config.transport.isBuiltInRuntimeSupported()) {
                throw IllegalStateException(
                    "Transport '${config.transport.name}' is not supported by the built-in MCP runtime. " +
                        "Use STDIO for managed startup."
                )
            }
            validateCommandPath(config.command).getOrThrow()
            validateArgs(config.args).getOrThrow()
            logger.info("Security validation passed for server: ${config.name}")
        }
    }

    /**
     * 校验命令路径安全性
     */
    fun validateCommandPath(command: String): Result<Unit> {
        return runCatching {
            if (command.isBlank()) {
                throw SecurityException("Command cannot be empty")
            }
            if (UNSAFE_CONTROL_CHARACTERS.containsMatchIn(command)) {
                throw SecurityException("Command contains unsafe control characters")
            }
            val commandName = Paths.get(command).fileName?.toString() ?: command
            if (commandName in SAFE_COMMANDS) return@runCatching

            val path = Paths.get(command)
            if (!path.isAbsolute) {
                throw SecurityException(
                    "Command must be an absolute path or a known safe command: $command"
                )
            }
            if (!Files.exists(path)) {
                throw SecurityException("Command file does not exist: $command")
            }
        }
    }

    private fun validateArgs(args: List<String>): Result<Unit> {
        return runCatching {
            for (arg in args) {
                if (UNSAFE_CONTROL_CHARACTERS.containsMatchIn(arg)) {
                    throw SecurityException("Argument contains unsafe control characters")
                }
            }
        }
    }

    fun isTrusted(config: McpServerConfig): Boolean = config.trusted
}
