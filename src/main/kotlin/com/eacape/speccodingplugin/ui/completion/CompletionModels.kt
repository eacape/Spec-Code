package com.eacape.speccodingplugin.ui.completion

import com.eacape.speccodingplugin.context.ContextItem
import javax.swing.Icon

/**
 * 触发字符类型
 */
enum class TriggerType(val char: Char) {
    SLASH('/'),
    AT('@'),
    HASH('#'),
    ANGLE('>'),
}

/**
 * 触发解析结果
 */
data class TriggerParseResult(
    val triggerType: TriggerType,
    val triggerOffset: Int,
    val query: String,
)

/**
 * 补全项
 */
data class CompletionItem(
    val displayText: String,
    val insertText: String,
    val description: String = "",
    val icon: Icon? = null,
    val contextItem: ContextItem? = null,
)
