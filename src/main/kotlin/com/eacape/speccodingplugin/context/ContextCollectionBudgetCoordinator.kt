package com.eacape.speccodingplugin.context

import java.nio.charset.StandardCharsets

internal enum class ContextCollectionDropReason(val wireName: String) {
    FILE_BUDGET("file-budget"),
    SYMBOL_BUDGET("symbol-budget"),
    BYTE_BUDGET("byte-budget"),
}

internal enum class ContextCollectionStage(val wireName: String) {
    IMPORT_DEPENDENCIES("import-dependencies"),
    PROJECT_STRUCTURE("project-structure"),
}

internal data class ContextCollectionBudgetStats(
    internal val filePaths: Set<String> = emptySet(),
    val symbolItemCount: Int = 0,
    val totalContentBytes: Int = 0,
) {
    val fileItemCount: Int
        get() = filePaths.size

    fun summary(): String {
        return "files=$fileItemCount, symbols=$symbolItemCount, bytes=$totalContentBytes"
    }

    companion object {
        val EMPTY = ContextCollectionBudgetStats()
    }
}

internal data class ContextCollectionBudgetResult(
    val items: List<ContextItem>,
    val stats: ContextCollectionBudgetStats,
    val dropCounts: Map<ContextCollectionDropReason, Int> = emptyMap(),
) {
    val wasTrimmed: Boolean
        get() = dropCounts.isNotEmpty()

    fun dropSummary(): String {
        if (dropCounts.isEmpty()) {
            return "none"
        }
        return dropCounts.entries.joinToString(separator = ",") { (reason, count) ->
            "${reason.wireName}=$count"
        }
    }
}

internal object ContextCollectionBudgetCoordinator {
    private const val PROJECT_STRUCTURE_MIN_REMAINING_BYTES = 4_096

    fun statsFor(items: List<ContextItem>): ContextCollectionBudgetStats {
        if (items.isEmpty()) {
            return ContextCollectionBudgetStats.EMPTY
        }

        val filePaths = linkedSetOf<String>()
        var symbolItemCount = 0
        var totalContentBytes = 0
        items.forEach { item ->
            countableFilePath(item)?.let(filePaths::add)
            if (item.type == ContextType.REFERENCED_SYMBOL) {
                symbolItemCount += 1
            }
            totalContentBytes += contentBytes(item)
        }

        return ContextCollectionBudgetStats(
            filePaths = filePaths,
            symbolItemCount = symbolItemCount,
            totalContentBytes = totalContentBytes,
        )
    }

    fun merge(
        left: ContextCollectionBudgetStats,
        right: ContextCollectionBudgetStats,
    ): ContextCollectionBudgetStats {
        if (left == ContextCollectionBudgetStats.EMPTY) {
            return right
        }
        if (right == ContextCollectionBudgetStats.EMPTY) {
            return left
        }
        return ContextCollectionBudgetStats(
            filePaths = left.filePaths + right.filePaths,
            symbolItemCount = left.symbolItemCount + right.symbolItemCount,
            totalContentBytes = left.totalContentBytes + right.totalContentBytes,
        )
    }

    fun optionalStageSkipReason(
        stage: ContextCollectionStage,
        stats: ContextCollectionBudgetStats,
        config: ContextConfig,
        elapsedMs: Long,
    ): String? {
        if (elapsedMs >= normalizedMaxCollectionTimeMs(config)) {
            return "time-budget"
        }
        if (stats.fileItemCount >= normalizedMaxFileItems(config)) {
            return "file-budget"
        }
        if (
            stage == ContextCollectionStage.PROJECT_STRUCTURE &&
            normalizedMaxContentBytes(config) - stats.totalContentBytes < PROJECT_STRUCTURE_MIN_REMAINING_BYTES
        ) {
            return "byte-headroom"
        }
        return null
    }

    fun shouldRunGraphPrioritization(
        items: List<ContextItem>,
        config: ContextConfig,
        elapsedMs: Long,
    ): Boolean {
        if (!config.preferGraphRelatedContext || items.isEmpty()) {
            return false
        }
        if (elapsedMs >= normalizedMaxCollectionTimeMs(config)) {
            return false
        }
        return items.any { item ->
            when (item.type) {
                ContextType.REFERENCED_FILE,
                ContextType.REFERENCED_SYMBOL,
                ContextType.IMPORT_DEPENDENCY,
                ContextType.PROJECT_STRUCTURE,
                -> true

                ContextType.CURRENT_FILE,
                ContextType.SELECTED_CODE,
                ContextType.CONTAINING_SCOPE,
                -> false
            }
        }
    }

    fun enforce(
        items: List<ContextItem>,
        config: ContextConfig,
    ): ContextCollectionBudgetResult {
        if (items.isEmpty()) {
            return ContextCollectionBudgetResult(
                items = emptyList(),
                stats = ContextCollectionBudgetStats.EMPTY,
            )
        }

        val accepted = mutableListOf<ContextItem>()
        val filePaths = linkedSetOf<String>()
        val dropCounts = linkedMapOf<ContextCollectionDropReason, Int>()
        var symbolItemCount = 0
        var totalContentBytes = 0
        val maxFileItems = normalizedMaxFileItems(config)
        val maxSymbolItems = normalizedMaxSymbolItems(config)
        val maxContentBytes = normalizedMaxContentBytes(config)

        for (item in items.sortedByDescending { it.priority }) {
            val filePath = countableFilePath(item)
            val itemConsumesNewFileSlot = filePath != null && filePath !in filePaths
            if (itemConsumesNewFileSlot && filePaths.size >= maxFileItems) {
                incrementDrop(dropCounts, ContextCollectionDropReason.FILE_BUDGET)
                continue
            }
            if (item.type == ContextType.REFERENCED_SYMBOL && symbolItemCount >= maxSymbolItems) {
                incrementDrop(dropCounts, ContextCollectionDropReason.SYMBOL_BUDGET)
                continue
            }

            val itemBytes = contentBytes(item)
            if (totalContentBytes + itemBytes > maxContentBytes) {
                incrementDrop(dropCounts, ContextCollectionDropReason.BYTE_BUDGET)
                continue
            }

            accepted += item
            filePath?.let(filePaths::add)
            if (item.type == ContextType.REFERENCED_SYMBOL) {
                symbolItemCount += 1
            }
            totalContentBytes += itemBytes
        }

        return ContextCollectionBudgetResult(
            items = accepted,
            stats = ContextCollectionBudgetStats(
                filePaths = filePaths,
                symbolItemCount = symbolItemCount,
                totalContentBytes = totalContentBytes,
            ),
            dropCounts = dropCounts,
        )
    }

    private fun incrementDrop(
        dropCounts: MutableMap<ContextCollectionDropReason, Int>,
        reason: ContextCollectionDropReason,
    ) {
        dropCounts[reason] = (dropCounts[reason] ?: 0) + 1
    }

    private fun countableFilePath(item: ContextItem): String? {
        if (item.type == ContextType.REFERENCED_SYMBOL) {
            return null
        }
        return item.filePath?.trim()?.takeIf(String::isNotBlank)
    }

    private fun contentBytes(item: ContextItem): Int {
        return item.content.toByteArray(StandardCharsets.UTF_8).size
    }

    private fun normalizedMaxFileItems(config: ContextConfig): Int {
        return config.maxFileItems.coerceAtLeast(0)
    }

    private fun normalizedMaxSymbolItems(config: ContextConfig): Int {
        return config.maxSymbolItems.coerceAtLeast(0)
    }

    private fun normalizedMaxContentBytes(config: ContextConfig): Int {
        return config.maxContentBytes.coerceAtLeast(0)
    }

    private fun normalizedMaxCollectionTimeMs(config: ContextConfig): Long {
        return config.maxCollectionTimeMs.coerceAtLeast(0L)
    }
}
