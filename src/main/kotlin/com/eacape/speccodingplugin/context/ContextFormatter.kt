package com.eacape.speccodingplugin.context

/**
 * 上下文格式化器
 * 将 ContextItem 列表格式化为 LLM 可读的 Markdown 文本
 */
object ContextFormatter {

    /**
     * 将上下文快照格式化为 LLM 系统消息文本
     */
    fun format(snapshot: ContextSnapshot): String {
        if (snapshot.items.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("## Project Context")
        sb.appendLine()

        for (item in snapshot.items) {
            sb.appendLine("### ${formatType(item.type)}: ${item.label}")
            if (item.filePath != null) {
                sb.appendLine("File: `${item.filePath}`")
            }
            sb.appendLine("```")
            sb.appendLine(item.content)
            sb.appendLine("```")
            sb.appendLine()
        }

        if (snapshot.wasTrimmed) {
            sb.appendLine("_Note: Some context was trimmed to fit token budget._")
        }

        return sb.toString().trimEnd()
    }

    private fun formatType(type: ContextType): String = when (type) {
        ContextType.CURRENT_FILE -> "Current File"
        ContextType.SELECTED_CODE -> "Selected Code"
        ContextType.CONTAINING_SCOPE -> "Containing Scope"
        ContextType.REFERENCED_FILE -> "Referenced File"
        ContextType.REFERENCED_SYMBOL -> "Referenced Symbol"
        ContextType.PROJECT_STRUCTURE -> "Project Structure"
        ContextType.IMPORT_DEPENDENCY -> "Import Dependency"
    }
}
