package com.eacape.speccodingplugin.context

/**
 * 上下文片段类型
 */
enum class ContextType {
    CURRENT_FILE,
    SELECTED_CODE,
    CONTAINING_SCOPE,
    REFERENCED_FILE,
    REFERENCED_SYMBOL,
    PROJECT_STRUCTURE,
    IMPORT_DEPENDENCY,
}

/**
 * 单个上下文片段
 */
data class ContextItem(
    val type: ContextType,
    val label: String,
    val content: String,
    val filePath: String? = null,
    val priority: Int = 50,
    val tokenEstimate: Int = content.length / 4,
)

/**
 * 聚合后的上下文快照
 */
data class ContextSnapshot(
    val items: List<ContextItem>,
    val totalTokenEstimate: Int = items.sumOf { it.tokenEstimate },
    val tokenBudget: Int,
    val wasTrimmed: Boolean = false,
)

/**
 * 上下文收集配置
 */
data class ContextConfig(
    val tokenBudget: Int = 8000,
    val includeCurrentFile: Boolean = true,
    val includeSelectedCode: Boolean = true,
    val includeContainingScope: Boolean = true,
    val includeImportDependencies: Boolean = false,
    val includeProjectStructure: Boolean = false,
    val preferGraphRelatedContext: Boolean = true,
)
