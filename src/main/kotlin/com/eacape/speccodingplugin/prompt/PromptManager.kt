package com.eacape.speccodingplugin.prompt

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 项目级提示词管理器（Project-level Service）
 * 支持三层继承：全局 -> 项目 -> 会话级
 */
@Service(Service.Level.PROJECT)
class PromptManager(private val project: Project) {
    private val logger = thisLogger()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private var catalog = defaultCatalog()

    // 会话级提示词覆盖（临时，不持久化）
    private var sessionOverride: PromptTemplate? = null

    /**
     * 列出所有可用的提示词模板（全局 + 项目级）
     */
    fun listPromptTemplates(): List<PromptTemplate> {
        ensureLoaded()
        val globalTemplates = GlobalPromptManager.getInstance().listPromptTemplates()
        val projectTemplates = synchronized(lock) { catalog.templates }

        // 项目级模板优先，去重
        val allTemplates = (projectTemplates + globalTemplates)
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }

        return allTemplates
    }

    /**
     * 获取当前活跃的提示词 ID
     */
    fun getActivePromptId(): String {
        ensureLoaded()
        return synchronized(lock) { catalog.activePromptId.orEmpty() }
    }

    /**
     * 设置活跃的提示词
     */
    fun setActivePrompt(promptId: String): Boolean {
        ensureLoaded()
        val normalizedPromptId = promptId.trim()
        if (normalizedPromptId.isBlank()) {
            return false
        }

        return synchronized(lock) {
            // 检查提示词是否存在（项目级或全局级）
            val allTemplates = listPromptTemplates()
            if (allTemplates.none { it.id == normalizedPromptId }) {
                return@synchronized false
            }

            if (catalog.activePromptId == normalizedPromptId) {
                return@synchronized true
            }

            catalog = catalog.copy(activePromptId = normalizedPromptId)
            saveCatalogSafely(catalog)
            true
        }
    }

    /**
     * 创建或更新提示词模板（项目级）
     */
    fun upsertTemplate(template: PromptTemplate) {
        ensureLoaded()
        val normalized = template.copy(
            id = template.id.trim(),
            name = template.name.trim(),
            content = template.content.trim(),
            scope = PromptScope.PROJECT, // 强制设置为项目作用域
            tags = template.tags.map { it.trim() }.filter { it.isNotBlank() },
        )
        if (normalized.id.isBlank() || normalized.name.isBlank() || normalized.content.isBlank()) {
            return
        }

        synchronized(lock) {
            val remaining = catalog.templates.filterNot { it.id == normalized.id }
            val updated = (remaining + normalized).sortedBy { it.name.lowercase() }
            val nextActiveId = catalog.activePromptId
                ?.takeIf { activeId ->
                    updated.any { it.id == activeId } ||
                    GlobalPromptManager.getInstance().getTemplate(activeId) != null
                }
            catalog = catalog.copy(templates = updated, activePromptId = nextActiveId)
            saveCatalogSafely(catalog)
        }
    }

    /**
     * 删除提示词模板（仅项目级）
     */
    fun deleteTemplate(promptId: String): Boolean {
        ensureLoaded()
        val normalizedPromptId = promptId.trim()
        if (normalizedPromptId.isBlank()) {
            return false
        }

        return synchronized(lock) {
            if (catalog.templates.none { it.id == normalizedPromptId }) {
                return@synchronized false
            }

            val remaining = catalog.templates.filterNot { it.id == normalizedPromptId }
            val nextActiveId = catalog.activePromptId
                ?.takeIf { activeId ->
                    activeId != normalizedPromptId &&
                    (
                        remaining.any { it.id == activeId } ||
                        GlobalPromptManager.getInstance().getTemplate(activeId) != null
                    )
                }
            catalog = catalog.copy(templates = remaining, activePromptId = nextActiveId)
            saveCatalogSafely(catalog)
            true
        }
    }

    /**
     * 设置会话级提示词覆盖（临时，不持久化）
     */
    fun setSessionOverride(template: PromptTemplate?) {
        synchronized(lock) {
            sessionOverride = template?.copy(scope = PromptScope.SESSION)
        }
    }

    /**
     * 获取会话级提示词覆盖
     */
    fun getSessionOverride(): PromptTemplate? {
        return synchronized(lock) { sessionOverride }
    }

    /**
     * 清除会话级提示词覆盖
     */
    fun clearSessionOverride() {
        synchronized(lock) {
            sessionOverride = null
        }
    }

    /**
     * 强制从磁盘重新加载项目级提示词目录。
     */
    fun reloadFromDisk() {
        synchronized(lock) {
            val fromDisk = loadCatalogSafely()
            catalog = normalizeCatalog(fromDisk)
            loaded = true
        }
    }

    /**
     * 渲染当前活跃的提示词（应用三层继承和变量插值）
     */
    fun renderActivePrompt(runtimeVariables: Map<String, String> = emptyMap()): String {
        ensureLoaded()

        // 三层继承：会话级 > 项目级 > 全局级
        val active = synchronized(lock) {
            sessionOverride ?: resolveActivePrompt(catalog)
        }
        if (active == null) {
            return ""
        }

        // 合并变量：全局变量 < 项目变量 < 模板变量 < 运行时变量
        val globalVariables = getGlobalVariables()
        val projectVariables = getProjectVariables()
        val mergedVariables = globalVariables + projectVariables + active.variables + runtimeVariables

        return PromptInterpolator.render(active.content, mergedVariables)
    }

    /**
     * 获取全局级变量
     */
    private fun getGlobalVariables(): Map<String, String> {
        return mapOf(
            "user_home" to (System.getProperty("user.home") ?: ""),
            "os_name" to (System.getProperty("os.name") ?: ""),
        )
    }

    /**
     * 获取项目级变量
     */
    private fun getProjectVariables(): Map<String, String> {
        return mapOf(
            "project_name" to (project.name),
            "project_path" to (project.basePath ?: ""),
        )
    }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }

        synchronized(lock) {
            if (loaded) {
                return
            }

            val fromDisk = loadCatalogSafely()
            catalog = normalizeCatalog(fromDisk)
            loaded = true
        }
    }

    private fun normalizeCatalog(value: PromptCatalog?): PromptCatalog {
        if (value == null) {
            return defaultCatalog()
        }

        val templates = value.templates
            .filterNot(::isLegacyDefaultTemplate)
            .sortedBy { it.name.lowercase() }
        val active = value.activePromptId
            ?.takeIf { activeId ->
                // 检查活跃 ID 是否存在于项目级或全局级
                templates.any { it.id == activeId } ||
                GlobalPromptManager.getInstance().getTemplate(activeId) != null
            }
        return PromptCatalog(templates = templates, activePromptId = active)
    }

    private fun resolveActivePrompt(value: PromptCatalog): PromptTemplate? {
        val activeId = value.activePromptId
            ?.trim()
            ?.ifBlank { null }
            ?: return null

        // 优先从项目级查找
        val projectTemplate = value.templates.firstOrNull { it.id == activeId }
        if (projectTemplate != null) {
            return projectTemplate
        }

        // 其次从全局级查找
        val globalTemplate = GlobalPromptManager.getInstance().getTemplate(activeId)
        if (globalTemplate != null) {
            return globalTemplate
        }

        return null
    }

    private fun loadCatalogSafely(): PromptCatalog? {
        val path = storagePath() ?: return null
        if (!Files.exists(path)) {
            return null
        }

        return runCatching {
            val raw = Files.readString(path, StandardCharsets.UTF_8)
            if (raw.isBlank()) {
                return@runCatching defaultCatalog()
            }

            PromptCatalogYaml.deserialize(raw)
        }.onFailure {
            logger.warn("Failed to load prompts from $path", it)
        }.getOrNull()
    }

    private fun saveCatalogSafely(value: PromptCatalog) {
        val path = storagePath() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            val payload = PromptCatalogYaml.serialize(value)
            Files.writeString(path, payload, StandardCharsets.UTF_8)
        }.onFailure {
            logger.warn("Failed to save prompts to $path", it)
        }
    }

    private fun storagePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("prompts")
            .resolve("catalog.yaml")
    }

    private fun defaultCatalog(): PromptCatalog {
        return PromptCatalog()
    }

    private fun isLegacyDefaultTemplate(template: PromptTemplate): Boolean {
        if (template.id.trim().lowercase() !in LEGACY_DEFAULT_PROMPT_IDS) {
            return false
        }
        val normalizedTags = template.tags.map { it.trim().lowercase() }
        return normalizedTags.contains("built-in") || template.name.trim().equals("Default Assistant", ignoreCase = true)
    }

    companion object {
        private val LEGACY_DEFAULT_PROMPT_IDS = setOf("default")

        fun getInstance(project: Project): PromptManager {
            return project.getService(PromptManager::class.java)
        }
    }
}
