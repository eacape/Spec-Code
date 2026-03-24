package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP Hub 状态变更监听器
 */
interface McpHubListener {
    fun onServerStatusChanged(serverId: String, status: ServerStatus) {}
    fun onToolsDiscovered(serverId: String, tools: List<McpTool>) {}
    fun onServerRuntimeLogsChanged(serverId: String) {}

    companion object {
        val TOPIC: Topic<McpHubListener> = Topic.create(
            "McpHub.StatusChanged",
            McpHubListener::class.java
        )
    }
}

/**
 * MCP Hub
 * 管理多个 MCP Server 的生命周期
 */
@Service(Service.Level.PROJECT)
class McpHub internal constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val configStoreProvider: () -> McpConfigStore,
    private val clientFactory: (McpServer, CoroutineScope) -> McpClientAdapter,
    private val toolRegistry: McpToolRegistry
) : Disposable {
    constructor(project: Project) : this(
        project = project,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        configStoreProvider = { McpConfigStore.getInstance(project) },
        clientFactory = { server, clientScope -> RealMcpClientAdapter(McpClient(server, clientScope)) },
        toolRegistry = McpToolRegistry()
    )

    private val logger = thisLogger()

    // Server 实例映射
    private val servers = ConcurrentHashMap<String, McpServer>()

    // Client 实例映射
    private val clients = ConcurrentHashMap<String, McpClientAdapter>()

    // Server 运行日志（环形缓冲）
    private val serverRuntimeLogs = ConcurrentHashMap<String, MutableList<McpRuntimeLogEntry>>()
    private val serverRuntimeLogLocks = ConcurrentHashMap<String, Any>()
    private val serverLifecycleVersions = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 注册 Server 配置
     */
    fun registerServer(config: McpServerConfig): Result<Unit> {
        return runCatching {
            if (servers.containsKey(config.id)) {
                throw IllegalArgumentException("Server already registered: ${config.id}")
            }

            val server = McpServer(config)
            servers[config.id] = server

            logger.info("Registered MCP server: ${config.name} (${config.id})")

            // 如果配置了自动启动，则启动
            if (config.autoStart) {
                scope.launch {
                    startServer(config.id)
                }
            }
        }
    }

    /**
     * 注销 Server
     */
    fun unregisterServer(serverId: String): Result<Unit> {
        return runCatching {
            // 先停止 Server
            stopServer(serverId).getOrThrow()

            // 移除注册
            servers.remove(serverId)
            serverRuntimeLogs.remove(serverId)
            serverRuntimeLogLocks.remove(serverId)
            serverLifecycleVersions.remove(serverId)
            logger.info("Unregistered MCP server: $serverId")
        }
    }

    /**
     * 启动 Server
     */
    suspend fun startServer(serverId: String): Result<Unit> {
        return runCatching {
            val server = servers[serverId]
                ?: throw IllegalArgumentException("Server not found: $serverId")

            if (server.status == ServerStatus.RUNNING) {
                logger.warn("Server already running: $serverId")
                appendRuntimeLog(serverId, McpRuntimeLogLevel.WARN, "Server already running")
                return@runCatching
            }
            if (server.status == ServerStatus.STARTING) {
                logger.warn("Server already starting: $serverId")
                appendRuntimeLog(serverId, McpRuntimeLogLevel.WARN, "Server already starting")
                return@runCatching
            }
            val lifecycleVersion = advanceServerLifecycleVersion(serverId)

            appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Start requested")

            // 安全校验
            McpSecurityValidator.validateBeforeStart(server.config).getOrThrow()
            appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Security validation passed")

            // 启动中状态
            server.status = ServerStatus.STARTING
            server.error = null
            notifyStatusChanged(serverId, ServerStatus.STARTING)

            try {
                // 创建客户端
                val client = clientFactory(server, scope)
                client.setRuntimeLogListener { event ->
                    appendRuntimeLog(serverId, event.level, event.message)
                }
                client.setLifecycleListener { event ->
                    handleClientUnexpectedTermination(serverId, event)
                }
                clients[serverId] = client
                if (!isLifecycleVersionCurrent(serverId, lifecycleVersion)) {
                    appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Startup aborted by stop request")
                    clients.remove(serverId)?.stop()
                    resetServerToStoppedAfterStaleStartup(serverId)
                    return@runCatching
                }

                // 启动 Server
                client.start().getOrThrow()
                if (!isLifecycleVersionCurrent(serverId, lifecycleVersion)) {
                    appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Startup aborted by stop request")
                    clients.remove(serverId)?.stop()
                    resetServerToStoppedAfterStaleStartup(serverId)
                    return@runCatching
                }

                // 发现工具
                val toolCount = discoverTools(serverId)
                appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Startup completed, discovered $toolCount tool(s)")
                if (!isLifecycleVersionCurrent(serverId, lifecycleVersion)) {
                    appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Startup aborted by stop request")
                    clients.remove(serverId)?.stop()
                    resetServerToStoppedAfterStaleStartup(serverId)
                    return@runCatching
                }

                // 成功状态
                server.status = ServerStatus.RUNNING
                server.error = null
                notifyStatusChanged(serverId, ServerStatus.RUNNING)

                logger.info("Started MCP server: ${server.config.name}")
            } catch (e: Exception) {
                if (!isLifecycleVersionCurrent(serverId, lifecycleVersion)) {
                    logger.info("Ignoring MCP startup failure for outdated lifecycle: $serverId", e)
                    clients.remove(serverId)?.stop()
                    resetServerToStoppedAfterStaleStartup(serverId)
                    return@runCatching
                }
                clients.remove(serverId)?.stop()

                server.status = ServerStatus.ERROR
                server.error = e.message
                appendRuntimeLog(
                    serverId,
                    McpRuntimeLogLevel.ERROR,
                    "Startup failed: ${e.message ?: e::class.java.simpleName}",
                )
                notifyStatusChanged(serverId, ServerStatus.ERROR)

                throw e
            }
        }
    }

    /**
     * 停止 Server
     */
    fun stopServer(serverId: String): Result<Unit> {
        return runCatching {
            advanceServerLifecycleVersion(serverId)
            appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Stop requested")
            val client = clients.remove(serverId)
            if (client != null) {
                client.stop()
                logger.info("Stopped MCP server: $serverId")
            }

            // 更新状态
            servers[serverId]?.apply {
                status = ServerStatus.STOPPED
                error = null
                process = null
                capabilities = null
            }

            // 清除工具注册
            toolRegistry.clearServerTools(serverId)
            appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Server stopped")
            appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Stop completed (state updated immediately)")

            // 通知状态变更
            notifyStatusChanged(serverId, ServerStatus.STOPPED)
        }
    }

    /**
     * 重启 Server
     */
    suspend fun restartServer(serverId: String): Result<Unit> {
        return runCatching {
            stopServer(serverId).getOrThrow()
            delay(1000) // 等待 1 秒
            startServer(serverId).getOrThrow()
        }
    }

    /**
     * 发现工具
     */
    private suspend fun discoverTools(serverId: String): Int {
        val client = clients[serverId] ?: return 0
        val server = servers[serverId] ?: return 0

        try {
            val tools = client.listTools().getOrThrow()
            toolRegistry.registerTools(serverId, tools)
            logger.info("Discovered ${tools.size} tools from server: $serverId")
            appendRuntimeLog(serverId, McpRuntimeLogLevel.INFO, "Discovered ${tools.size} tool(s)")

            // 通知工具发现
            notifyToolsDiscovered(serverId, tools)
            return tools.size
        } catch (e: Exception) {
            server.status = ServerStatus.ERROR
            server.error = e.message
            appendRuntimeLog(
                serverId,
                McpRuntimeLogLevel.ERROR,
                "Tool discovery failed: ${e.message ?: e::class.java.simpleName}",
            )
            notifyStatusChanged(serverId, ServerStatus.ERROR)
            logger.warn("Failed to discover tools from server: $serverId", e)
            throw e
        }
    }

    /**
     * 调用工具
     */
    suspend fun callTool(request: ToolCallRequest): Result<ToolCallResult> {
        return runCatching {
            val client = clients[request.serverId]
                ?: throw IllegalArgumentException("Server not running: ${request.serverId}")

            client.callTool(request.toolName, request.arguments).getOrThrow()
        }
    }

    /**
     * 获取所有 Server
     */
    fun getAllServers(): List<McpServer> {
        return servers.values.toList()
    }

    /**
     * 获取运行中的 Server
     */
    fun getRunningServers(): List<McpServer> {
        return servers.values.filter { it.status == ServerStatus.RUNNING }
    }

    /**
     * 获取 Server
     */
    fun getServer(serverId: String): McpServer? {
        return servers[serverId]
    }

    /**
     * 获取 Server 运行日志（默认最近 200 条）
     */
    fun getServerRuntimeLogs(serverId: String, limit: Int = RUNTIME_LOG_MAX_LINES): List<McpRuntimeLogEntry> {
        val lock = serverRuntimeLogLocks.computeIfAbsent(serverId) { Any() }
        synchronized(lock) {
            val logs = serverRuntimeLogs[serverId] ?: return emptyList()
            if (logs.isEmpty()) return emptyList()
            val normalizedLimit = if (limit <= 0) RUNTIME_LOG_MAX_LINES else limit
            val fromIndex = (logs.size - normalizedLimit).coerceAtLeast(0)
            return logs.subList(fromIndex, logs.size).toList()
        }
    }

    /**
     * 清空指定 Server 运行日志
     */
    fun clearServerRuntimeLogs(serverId: String) {
        val lock = serverRuntimeLogLocks.computeIfAbsent(serverId) { Any() }
        synchronized(lock) {
            serverRuntimeLogs[serverId]?.clear()
        }
        notifyRuntimeLogsChanged(serverId)
    }

    /**
     * 获取所有工具
     */
    fun getAllTools(): List<Pair<String, McpTool>> {
        return toolRegistry.getAllTools()
    }

    /**
     * 获取指定 Server 的工具
     */
    fun getServerTools(serverId: String): List<McpTool> {
        return toolRegistry.getServerTools(serverId)
    }

    /**
     * 查找工具
     */
    fun findTool(serverId: String, toolName: String): McpTool? {
        return toolRegistry.findTool(serverId, toolName)
    }

    /**
     * 健康检查
     */
    suspend fun healthCheck(serverId: String): Result<Boolean> {
        return runCatching {
            val client = clients[serverId]
                ?: return@runCatching false

            client.isRunning()
        }
    }

    /**
     * 自动重连
     */
    fun enableAutoReconnect(serverId: String, enabled: Boolean = true) {
        if (!enabled) return

        scope.launch {
            while (true) {
                delay(10_000) // 每 10 秒检查一次

                val server = servers[serverId] ?: break
                if (server.status == ServerStatus.ERROR || server.status == ServerStatus.STOPPED) {
                    logger.info("Auto-reconnecting to server: $serverId")
                    try {
                        restartServer(serverId).getOrThrow()
                    } catch (e: Exception) {
                        logger.warn("Auto-reconnect failed: $serverId", e)
                    }
                }
            }
        }
    }

    /**
     * 从持久化存储加载配置
     */
    fun loadPersistedConfigs() {
        val configStore = configStoreProvider()
        val configs = configStore.getAll()
        for (config in configs) {
            if (!servers.containsKey(config.id)) {
                registerServer(config)
            }
        }
        logger.info("Loaded ${configs.size} persisted MCP server configs")
    }

    /**
     * 更新 Server 配置（同步到内存）
     */
    suspend fun updateServerConfig(config: McpServerConfig): Result<Unit> {
        return runCatching {
            val existing = servers[config.id]
            if (existing == null) {
                servers[config.id] = McpServer(config)
                return@runCatching
            }

            if (existing.status == ServerStatus.STARTING) {
                throw IllegalStateException("Cannot update MCP server while it is starting: ${config.id}")
            }

            val previousConfig = existing.config
            val runtimeSettingsChanged = previousConfig.command != config.command ||
                previousConfig.args != config.args ||
                previousConfig.env != config.env ||
                previousConfig.transport != config.transport

            existing.config = config

            if (runtimeSettingsChanged) {
                when (existing.status) {
                    ServerStatus.RUNNING -> {
                        restartServer(config.id).getOrThrow()
                    }

                    ServerStatus.ERROR -> {
                        existing.status = ServerStatus.STOPPED
                        existing.error = null
                        existing.process = null
                        existing.capabilities = null
                        toolRegistry.clearServerTools(config.id)
                        appendRuntimeLog(config.id, McpRuntimeLogLevel.INFO, "Server config updated; status reset to stopped")
                        notifyStatusChanged(config.id, ServerStatus.STOPPED)
                    }

                    ServerStatus.STOPPED -> {
                        existing.error = null
                        existing.process = null
                        existing.capabilities = null
                        toolRegistry.clearServerTools(config.id)
                    }

                    ServerStatus.STARTING -> Unit
                }
            } else {
                notifyStatusChanged(config.id, existing.status)
            }
        }
    }

    private fun appendRuntimeLog(serverId: String, level: McpRuntimeLogLevel, message: String) {
        val normalized = message.trim().replace('\n', ' ')
        if (normalized.isEmpty()) return
        val lock = serverRuntimeLogLocks.computeIfAbsent(serverId) { Any() }
        synchronized(lock) {
            val logs = serverRuntimeLogs.computeIfAbsent(serverId) { mutableListOf() }
            logs += McpRuntimeLogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                message = normalized.take(RUNTIME_LOG_MAX_CHARS),
            )
            if (logs.size > RUNTIME_LOG_MAX_LINES) {
                logs.removeFirst()
            }
        }
        notifyRuntimeLogsChanged(serverId)
    }

    private fun advanceServerLifecycleVersion(serverId: String): Long {
        return serverLifecycleVersions
            .computeIfAbsent(serverId) { AtomicLong(0) }
            .incrementAndGet()
    }

    private fun isLifecycleVersionCurrent(serverId: String, lifecycleVersion: Long): Boolean {
        return serverLifecycleVersions[serverId]?.get() == lifecycleVersion
    }

    private fun resetServerToStoppedAfterStaleStartup(serverId: String) {
        servers[serverId]?.apply {
            status = ServerStatus.STOPPED
            error = null
            process = null
            capabilities = null
        }
        toolRegistry.clearServerTools(serverId)
        notifyStatusChanged(serverId, ServerStatus.STOPPED)
    }

    private fun handleClientUnexpectedTermination(serverId: String, event: McpClientUnexpectedTermination) {
        val server = servers[serverId] ?: return
        clients.remove(serverId)
        server.status = ServerStatus.ERROR
        server.error = event.message
        server.process = null
        server.capabilities = null
        toolRegistry.clearServerTools(serverId)
        notifyStatusChanged(serverId, ServerStatus.ERROR)
    }

    /**
     * 通知状态变更
     */
    private fun notifyStatusChanged(serverId: String, status: ServerStatus) {
        try {
            project.messageBus.syncPublisher(McpHubListener.TOPIC)
                .onServerStatusChanged(serverId, status)
        } catch (e: Exception) {
            logger.debug("Failed to notify status change", e)
        }
    }

    /**
     * 通知工具发现
     */
    private fun notifyToolsDiscovered(serverId: String, tools: List<McpTool>) {
        try {
            project.messageBus.syncPublisher(McpHubListener.TOPIC)
                .onToolsDiscovered(serverId, tools)
        } catch (e: Exception) {
            logger.debug("Failed to notify tools discovered", e)
        }
    }

    private fun notifyRuntimeLogsChanged(serverId: String) {
        try {
            project.messageBus.syncPublisher(McpHubListener.TOPIC)
                .onServerRuntimeLogsChanged(serverId)
        } catch (e: Exception) {
            logger.debug("Failed to notify runtime logs changed", e)
        }
    }

    /**
     * 清理资源
     */
    override fun dispose() {
        // 停止所有 Server
        servers.keys.forEach { serverId ->
            stopServer(serverId)
        }

        // 取消协程作用域
        scope.cancel()

        logger.info("MCP Hub disposed")
    }

    companion object {
        private const val RUNTIME_LOG_MAX_LINES = 200
        private const val RUNTIME_LOG_MAX_CHARS = 700

        fun getInstance(project: Project): McpHub = project.service()
    }
}

internal interface McpClientAdapter {
    suspend fun start(): Result<Unit>
    suspend fun listTools(): Result<List<McpTool>>
    suspend fun callTool(toolName: String, arguments: Map<String, Any>): Result<ToolCallResult>
    fun stop()
    fun isRunning(): Boolean
    fun setRuntimeLogListener(listener: (McpRuntimeLogEvent) -> Unit) {}
    fun setLifecycleListener(listener: (McpClientUnexpectedTermination) -> Unit) {}
}

private class RealMcpClientAdapter(
    private val delegate: McpClient
) : McpClientAdapter {
    override suspend fun start(): Result<Unit> = delegate.start()

    override suspend fun listTools(): Result<List<McpTool>> = delegate.listTools()

    override suspend fun callTool(toolName: String, arguments: Map<String, Any>): Result<ToolCallResult> {
        return delegate.callTool(toolName, arguments)
    }

    override fun stop() {
        delegate.stop()
    }

    override fun isRunning(): Boolean = delegate.isRunning()

    override fun setRuntimeLogListener(listener: (McpRuntimeLogEvent) -> Unit) {
        delegate.setRuntimeLogListener(listener)
    }

    override fun setLifecycleListener(listener: (McpClientUnexpectedTermination) -> Unit) {
        delegate.setLifecycleListener(listener)
    }
}

/**
 * MCP 工具注册表
 */
class McpToolRegistry {
    private val logger = thisLogger()

    // Server ID -> Tools 映射
    private val serverTools = ConcurrentHashMap<String, List<McpTool>>()

    /**
     * 注册工具
     */
    fun registerTools(serverId: String, tools: List<McpTool>) {
        serverTools[serverId] = tools
        logger.info("Registered ${tools.size} tools for server: $serverId")
    }

    /**
     * 清除 Server 的工具
     */
    fun clearServerTools(serverId: String) {
        serverTools.remove(serverId)
        logger.info("Cleared tools for server: $serverId")
    }

    /**
     * 获取所有工具
     */
    fun getAllTools(): List<Pair<String, McpTool>> {
        return serverTools.flatMap { (serverId, tools) ->
            tools.map { serverId to it }
        }
    }

    /**
     * 获取指定 Server 的工具
     */
    fun getServerTools(serverId: String): List<McpTool> {
        return serverTools[serverId] ?: emptyList()
    }

    /**
     * 查找工具
     */
    fun findTool(serverId: String, toolName: String): McpTool? {
        return serverTools[serverId]?.firstOrNull { it.name == toolName }
    }

    /**
     * 搜索工具
     */
    fun searchTools(query: String): List<Pair<String, McpTool>> {
        return getAllTools().filter { (_, tool) ->
            tool.name.contains(query, ignoreCase = true) ||
                    tool.description?.contains(query, ignoreCase = true) == true
        }
    }
}
