package com.eacape.speccodingplugin.engine

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * 引擎管理器（Project-level Service）
 * 负责引擎注册、发现、健康检查和热切换
 */
@Service(Service.Level.PROJECT)
class EngineManager(private val project: Project) {
    private val logger = thisLogger()
    private val engines = mutableMapOf<String, CodeGenerationEngine>()
    private val lock = Any()

    @Volatile
    private var activeEngineId: String? = null

    /**
     * 注册引擎
     */
    fun register(engine: CodeGenerationEngine) {
        synchronized(lock) {
            engines[engine.id] = engine
            logger.info("Registered engine: ${engine.id} (${engine.displayName})")

            if (activeEngineId == null) {
                activeEngineId = engine.id
            }
        }
    }

    /**
     * 获取当前活跃引擎
     */
    fun getActiveEngine(): CodeGenerationEngine? {
        return synchronized(lock) {
            activeEngineId?.let { engines[it] }
        }
    }

    /**
     * 切换活跃引擎
     */
    fun switchEngine(engineId: String): Boolean {
        synchronized(lock) {
            if (!engines.containsKey(engineId)) {
                logger.warn("Engine not found: $engineId")
                return false
            }
            activeEngineId = engineId
            logger.info("Switched active engine to: $engineId")
            return true
        }
    }

    /**
     * 列出所有已注册引擎
     */
    fun listEngines(): List<CodeGenerationEngine> {
        return synchronized(lock) {
            engines.values.toList()
        }
    }

    /**
     * 根据能力查找引擎
     */
    fun findByCapability(
        capability: EngineCapability
    ): List<CodeGenerationEngine> {
        return synchronized(lock) {
            engines.values.filter {
                capability in it.capabilities
            }
        }
    }

    /**
     * 根据能力选择最优引擎
     */
    fun selectBestEngine(
        capability: EngineCapability
    ): CodeGenerationEngine? {
        val candidates = findByCapability(capability)
        if (candidates.isEmpty()) return null

        // 优先返回活跃引擎（如果支持该能力）
        val active = getActiveEngine()
        if (active != null && capability in active.capabilities) {
            return active
        }

        // 否则返回第一个支持的引擎
        return candidates.first()
    }

    /**
     * 对所有引擎执行健康检查
     */
    suspend fun healthCheckAll(): Map<String, EngineHealthResult> {
        val results = mutableMapOf<String, EngineHealthResult>()
        val engineList = synchronized(lock) {
            engines.values.toList()
        }

        for (engine in engineList) {
            try {
                results[engine.id] = engine.healthCheck()
            } catch (e: Exception) {
                results[engine.id] = EngineHealthResult(
                    healthy = false,
                    status = EngineStatus.ERROR,
                    message = e.message ?: "Health check failed"
                )
            }
        }

        return results
    }

    companion object {
        fun getInstance(project: Project): EngineManager {
            return project.getService(EngineManager::class.java)
        }
    }
}
