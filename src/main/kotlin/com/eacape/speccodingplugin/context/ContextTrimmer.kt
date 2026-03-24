package com.eacape.speccodingplugin.context

/**
 * 上下文裁剪器
 * 按优先级排序，在 token 预算内裁剪上下文项
 */
object ContextTrimmer {

    /**
     * 估算文本的 token 数（启发式：字符数 / 4）
     */
    fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    /**
     * 对上下文项列表进行裁剪，使总 token 不超过预算
     */
    fun trim(items: List<ContextItem>, tokenBudget: Int): ContextSnapshot {
        if (items.isEmpty()) {
            return ContextSnapshot(
                items = emptyList(),
                tokenBudget = tokenBudget,
                wasTrimmed = false,
            )
        }

        // 按优先级降序排列（高优先级在前）
        val sorted = items.sortedByDescending { it.priority }

        val accepted = mutableListOf<ContextItem>()
        var usedTokens = 0
        var trimmed = false

        for (item in sorted) {
            val estimate = item.tokenEstimate
            if (usedTokens + estimate <= tokenBudget) {
                accepted.add(item)
                usedTokens += estimate
            } else {
                trimmed = true
            }
        }

        return ContextSnapshot(
            items = accepted,
            totalTokenEstimate = usedTokens,
            tokenBudget = tokenBudget,
            wasTrimmed = trimmed,
        )
    }
}
