package com.eacape.speccodingplugin.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 上下文收集编排服务（Project-level Service）
 * 编排所有上下文源，收集并裁剪上下文
 */
@Service(Service.Level.PROJECT)
class ContextCollector(private val project: Project) {
    private var codeGraphSnapshotProvider: () -> Result<CodeGraphSnapshot> = {
        runCatching {
            CodeGraphService.getInstance(project).buildFromActiveEditor().getOrThrow()
        }
    }

    internal constructor(
        project: Project,
        codeGraphSnapshotProvider: () -> Result<CodeGraphSnapshot>,
    ) : this(project) {
        this.codeGraphSnapshotProvider = codeGraphSnapshotProvider
    }

    /**
     * 自动收集编辑器上下文
     */
    fun collectContext(config: ContextConfig = ContextConfig()): ContextSnapshot {
        val items = mutableListOf<ContextItem>()

        if (config.includeSelectedCode) {
            EditorContextProvider.getSelectedCodeContext(project)?.let {
                items.add(it)
            }
        }

        if (config.includeContainingScope) {
            EditorContextProvider.getContainingScopeContext(project)?.let {
                items.add(it)
            }
        }

        if (config.includeCurrentFile) {
            EditorContextProvider.getCurrentFileContext(project)?.let {
                items.add(it)
            }
        }

        if (config.includeImportDependencies) {
            runCatching {
                RelatedFileDiscovery.getInstance(project).discoverRelatedFiles()
            }.getOrDefault(emptyList()).forEach { dependency ->
                items.add(dependency)
            }
        }

        if (config.includeProjectStructure) {
            runCatching {
                ProjectStructureScanner.getInstance(project).getProjectStructureContext()
            }.getOrNull()?.let { structure ->
                if (structure.content.isNotBlank()) {
                    items.add(structure)
                }
            }
        }

        return ContextTrimmer.trim(
            applyGraphAwarePrioritization(items, config),
            config.tokenBudget,
        )
    }

    /**
     * 合并显式引用项 + 自动收集的上下文
     */
    fun collectForItems(
        explicitItems: List<ContextItem>,
        config: ContextConfig = ContextConfig(),
    ): ContextSnapshot {
        val autoSnapshot = collectContext(config.copy(preferGraphRelatedContext = false))
        val allItems = explicitItems + autoSnapshot.items

        // 去重：相同 filePath + type 只保留显式引用
        val deduped = deduplicateItems(allItems)

        return ContextTrimmer.trim(
            applyGraphAwarePrioritization(deduped, config),
            config.tokenBudget,
        )
    }

    private fun deduplicateItems(items: List<ContextItem>): List<ContextItem> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            val key = "${item.type}:${item.filePath ?: item.label}"
            seen.add(key)
        }
    }

    private fun applyGraphAwarePrioritization(
        items: List<ContextItem>,
        config: ContextConfig,
    ): List<ContextItem> {
        if (!config.preferGraphRelatedContext || items.isEmpty()) {
            return items
        }
        val graph = codeGraphSnapshotProvider().getOrNull() ?: return items
        return ContextGraphPrioritizer.prioritize(items, graph)
    }

    companion object {
        fun getInstance(project: Project): ContextCollector {
            return project.getService(ContextCollector::class.java)
        }
    }
}
