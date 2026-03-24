package com.eacape.speccodingplugin.prompt

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 全局级提示词管理器（Application-level Service）
 * 管理存储在用户主目录 ~/.spec-coding/prompts/ 下的全局提示词
 */
@Service(Service.Level.APP)
class GlobalPromptManager {
    private val logger = thisLogger()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private var catalog = PromptCatalog()

    fun listPromptTemplates(): List<PromptTemplate> {
        ensureLoaded()
        return synchronized(lock) { catalog.templates }
    }

    fun getTemplate(promptId: String): PromptTemplate? {
        ensureLoaded()
        return synchronized(lock) {
            catalog.templates.firstOrNull { it.id == promptId }
        }
    }

    fun upsertTemplate(template: PromptTemplate) {
        ensureLoaded()
        val normalized = template.copy(
            id = template.id.trim(),
            name = template.name.trim(),
            content = template.content.trim(),
            scope = PromptScope.GLOBAL, // 强制设置为全局作用域
            tags = template.tags.map { it.trim() }.filter { it.isNotBlank() },
        )
        if (normalized.id.isBlank() || normalized.name.isBlank() || normalized.content.isBlank()) {
            return
        }

        synchronized(lock) {
            val remaining = catalog.templates.filterNot { it.id == normalized.id }
            val updated = (remaining + normalized).sortedBy { it.name.lowercase() }
            catalog = catalog.copy(templates = updated)
            saveCatalogSafely(catalog)
        }
    }

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
            catalog = catalog.copy(templates = remaining)
            saveCatalogSafely(catalog)
            true
        }
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

    private fun loadCatalogSafely(): PromptCatalog? {
        val path = storagePath() ?: return null
        if (!Files.exists(path)) {
            return null
        }

        return runCatching {
            val raw = Files.readString(path, StandardCharsets.UTF_8)
            if (raw.isBlank()) {
                return@runCatching null
            }

            PromptCatalogYaml.deserialize(raw)
        }.onFailure {
            logger.warn("Failed to load global prompts from $path", it)
        }.getOrNull()
    }

    private fun saveCatalogSafely(value: PromptCatalog) {
        val path = storagePath() ?: return
        runCatching {
            Files.createDirectories(path.parent)
            val payload = PromptCatalogYaml.serialize(value)
            Files.writeString(path, payload, StandardCharsets.UTF_8)
        }.onFailure {
            logger.warn("Failed to save global prompts to $path", it)
        }
    }

    private fun storagePath(): Path? {
        val userHome = System.getProperty("user.home") ?: return null
        return Paths.get(userHome)
            .resolve(".spec-coding")
            .resolve("prompts")
            .resolve("global-catalog.yaml")
    }

    private fun normalizeCatalog(value: PromptCatalog?): PromptCatalog {
        if (value == null) {
            return createDefaultCatalog()
        }
        val templates = value.templates
            .filterNot(::isLegacyDefaultTemplate)
            .sortedBy { it.name.lowercase() }
        return PromptCatalog(templates = templates, activePromptId = null)
    }

    private fun createDefaultCatalog(): PromptCatalog {
        return PromptCatalog()
    }

    private fun isLegacyDefaultTemplate(template: PromptTemplate): Boolean {
        if (template.id.trim().lowercase() !in LEGACY_GLOBAL_DEFAULT_IDS) {
            return false
        }
        val normalizedTags = template.tags.map { it.trim().lowercase() }
        return normalizedTags.contains("built-in") || template.name.trim().equals("Global Default Assistant", ignoreCase = true)
    }

    companion object {
        private val LEGACY_GLOBAL_DEFAULT_IDS = setOf("global-default")

        fun getInstance(): GlobalPromptManager {
            return com.intellij.openapi.components.service()
        }
    }
}

/**
 * YAML 序列化/反序列化工具
 */
internal object PromptCatalogYaml {
    private val loadYaml = Yaml(SafeConstructor(LoaderOptions()))
    private val dumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        indent = 2
        defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
    }

    private val dumpYaml = Yaml(dumperOptions)

    fun serialize(catalog: PromptCatalog): String {
        val data = mapOf(
            "activePromptId" to catalog.activePromptId,
            "templates" to catalog.templates.map { template ->
                mapOf(
                    "id" to template.id,
                    "name" to template.name,
                    "content" to template.content,
                    "scope" to template.scope.name,
                    "variables" to template.variables,
                    "tags" to template.tags,
                )
            },
        )
        val writer = StringWriter()
        dumpYaml.dump(data, writer)
        return writer.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun deserialize(raw: String): PromptCatalog {
        val loaded = loadYaml.load(raw) as? Map<String, Any?> ?: return PromptCatalog()
        val templates = (loaded["templates"] as? List<*>)
            .orEmpty()
            .mapNotNull { parseTemplate(it as? Map<String, Any?> ?: return@mapNotNull null) }
        val activePromptId = loaded["activePromptId"]?.toString()?.takeIf { it.isNotBlank() }
        return if (templates.isEmpty()) {
            PromptCatalog()
        } else {
            PromptCatalog(templates = templates, activePromptId = activePromptId)
        }
    }

    private fun parseTemplate(raw: Map<String, Any?>): PromptTemplate? {
        val id = raw["id"]?.toString()?.trim().orEmpty()
        val name = raw["name"]?.toString()?.trim().orEmpty()
        val content = raw["content"]?.toString()?.trim().orEmpty()
        if (id.isBlank() || name.isBlank() || content.isBlank()) {
            return null
        }

        val scope = raw["scope"]
            ?.toString()
            ?.uppercase()
            ?.let { value -> PromptScope.entries.firstOrNull { it.name == value } }
            ?: PromptScope.PROJECT
        val variables = (raw["variables"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { entry ->
                val normalizedKey = entry.key?.toString()?.trim().orEmpty()
                val normalizedValue = entry.value?.toString()?.trim().orEmpty()
                if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                    null
                } else {
                    normalizedKey to normalizedValue
                }
            }
            ?.toMap()
            .orEmpty()
        val tags = (raw["tags"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.takeIf { tag -> tag.isNotBlank() } }

        return PromptTemplate(
            id = id,
            name = name,
            content = content,
            variables = variables,
            scope = scope,
            tags = tags,
        )
    }
}
