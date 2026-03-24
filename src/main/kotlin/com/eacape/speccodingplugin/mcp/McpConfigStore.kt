package com.eacape.speccodingplugin.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * MCP Server 配置持久化存储（Project-level Service）
 * 将 MCP Server 配置保存到 .spec-coding/mcp-servers.json
 */
@Service(Service.Level.PROJECT)
class McpConfigStore(private val project: Project) {
    private val logger = thisLogger()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val configs = mutableListOf<McpServerConfig>()
    private val lock = Any()

    @Volatile
    private var loaded = false

    /**
     * 保存或更新配置
     */
    fun save(config: McpServerConfig): Result<Unit> {
        return runCatching {
            synchronized(lock) {
                ensureLoaded()

                val index = configs.indexOfFirst { it.id == config.id }
                if (index >= 0) {
                    configs[index] = config
                    logger.info("Updated MCP server config: ${config.name} (${config.id})")
                } else {
                    configs.add(config)
                    logger.info("Saved MCP server config: ${config.name} (${config.id})")
                }

                persistToDisk()
            }
        }
    }

    /**
     * 删除配置
     */
    fun delete(configId: String): Result<Boolean> {
        return runCatching {
            synchronized(lock) {
                ensureLoaded()

                val removed = configs.removeIf { it.id == configId }
                if (removed) {
                    logger.info("Deleted MCP server config: $configId")
                    persistToDisk()
                }
                removed
            }
        }
    }

    /**
     * 获取所有配置
     */
    fun getAll(): List<McpServerConfig> {
        ensureLoaded()
        return synchronized(lock) {
            configs.toList()
        }
    }

    /**
     * 根据 ID 获取配置
     */
    fun getById(id: String): McpServerConfig? {
        ensureLoaded()
        return synchronized(lock) {
            configs.find { it.id == id }
        }
    }

    /**
     * 获取配置数量
     */
    fun count(): Int {
        ensureLoaded()
        return synchronized(lock) {
            configs.size
        }
    }

    private fun ensureLoaded() {
        if (loaded) return

        synchronized(lock) {
            if (loaded) return
            loadFromDisk()
            loaded = true
        }
    }

    private fun loadFromDisk() {
        val storePath = getStorePath() ?: return

        if (!storePath.exists()) {
            logger.info("No MCP server config found, starting fresh")
            return
        }

        try {
            val content = storePath.readText()
            val stored = json.decodeFromString<StoredMcpConfigs>(content)

            configs.clear()
            configs.addAll(stored.servers.map { it.toConfig() })

            logger.info("Loaded ${configs.size} MCP server configs from disk")
        } catch (e: Exception) {
            logger.error("Failed to load MCP server configs from disk", e)
        }
    }

    private fun persistToDisk() {
        val storePath = getStorePath() ?: return
        storePath.parent?.let { Files.createDirectories(it) }

        val stored = StoredMcpConfigs(
            version = 1,
            servers = configs.map { StoredMcpServerConfig.fromConfig(it) }
        )

        val content = json.encodeToString(stored)
        Files.writeString(
            storePath,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )

        logger.debug("Persisted ${configs.size} MCP server configs to disk")
    }

    private fun getStorePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("mcp-servers.json")
    }

    companion object {
        fun getInstance(project: Project): McpConfigStore = project.service()
    }
}

/**
 * 序列化用的数据结构
 */
@Serializable
private data class StoredMcpConfigs(
    val version: Int,
    val servers: List<StoredMcpServerConfig>
)

@Serializable
private data class StoredMcpServerConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val transport: String = "STDIO",
    val autoStart: Boolean = false,
    val trusted: Boolean = false
) {
    fun toConfig(): McpServerConfig {
        return McpServerConfig(
            id = id,
            name = name,
            command = command,
            args = args,
            env = env,
            transport = try { TransportType.valueOf(transport) } catch (_: Exception) { TransportType.STDIO },
            autoStart = autoStart,
            trusted = trusted
        )
    }

    companion object {
        fun fromConfig(config: McpServerConfig): StoredMcpServerConfig {
            return StoredMcpServerConfig(
                id = config.id,
                name = config.name,
                command = config.command,
                args = config.args,
                env = config.env,
                transport = config.transport.name,
                autoStart = config.autoStart,
                trusted = config.trusted
            )
        }
    }
}
