package com.eacape.speccodingplugin.ui.completion

/**
 * 触发字符解析器
 * 检测输入文本中的触发字符并提取查询文本
 */
object TriggerParser {

    private val TRIGGER_CHARS = TriggerType.entries.map { it.char }.toSet()

    /**
     * 解析光标位置前的触发字符
     * @param text 完整输入文本
     * @param caretPosition 光标位置
     * @return 解析结果，无触发时返回 null
     */
    fun parse(text: String, caretPosition: Int): TriggerParseResult? {
        if (text.isEmpty() || caretPosition <= 0 || caretPosition > text.length) {
            return null
        }

        // 从光标位置向前搜索最近的触发字符
        val beforeCaret = text.substring(0, caretPosition)

        for (i in beforeCaret.length - 1 downTo 0) {
            val ch = beforeCaret[i]

            // 遇到空白字符或换行则停止搜索
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                break
            }

            if (ch in TRIGGER_CHARS) {
                // 触发字符必须在行首或前面是空白
                val isAtStart = i == 0
                val prevIsWhitespace = i > 0 && beforeCaret[i - 1].isWhitespace()

                if (isAtStart || prevIsWhitespace) {
                    val triggerType = TriggerType.entries.first { it.char == ch }
                    val query = beforeCaret.substring(i + 1)
                    return TriggerParseResult(
                        triggerType = triggerType,
                        triggerOffset = i,
                        query = query,
                    )
                }

                // 触发字符不在合法位置，停止搜索
                break
            }
        }

        return null
    }
}
