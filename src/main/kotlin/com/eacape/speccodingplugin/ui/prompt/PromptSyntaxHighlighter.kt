package com.eacape.speccodingplugin.ui.prompt

import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Prompt 变量语法高亮渲染器
 * 对 {{variable}} 格式的变量进行高亮显示
 */
object PromptSyntaxHighlighter {

    private val VARIABLE_PATTERN = Regex("\\{\\{\\s*(\\w+)\\s*}}")

    private val VAR_FG_LIGHT = Color(156, 39, 176)
    private val VAR_FG_DARK = Color(206, 147, 216)
    private val VAR_BG_LIGHT = Color(243, 229, 245)
    private val VAR_BG_DARK = Color(50, 30, 55)

    /**
     * 对 JTextPane 中的内容应用语法高亮
     */
    fun highlight(textPane: JTextPane) {
        val doc = textPane.styledDocument
        val text = doc.getText(0, doc.length)

        // 先重置所有样式为默认
        val defaultAttrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(defaultAttrs, "JetBrains Mono")
        StyleConstants.setFontSize(defaultAttrs, 13)
        doc.setCharacterAttributes(
            0, doc.length, defaultAttrs, true
        )

        // 高亮变量
        highlightVariables(doc, text)
    }

    private fun highlightVariables(
        doc: StyledDocument, text: String
    ) {
        val varAttrs = SimpleAttributeSet()
        StyleConstants.setForeground(
            varAttrs, JBColor(VAR_FG_LIGHT, VAR_FG_DARK)
        )
        StyleConstants.setBackground(
            varAttrs, JBColor(VAR_BG_LIGHT, VAR_BG_DARK)
        )
        StyleConstants.setBold(varAttrs, true)
        StyleConstants.setFontFamily(varAttrs, "JetBrains Mono")
        StyleConstants.setFontSize(varAttrs, 13)

        VARIABLE_PATTERN.findAll(text).forEach { match ->
            doc.setCharacterAttributes(
                match.range.first,
                match.value.length,
                varAttrs,
                true
            )
        }
    }

    /**
     * 提取文本中的所有变量名
     */
    fun extractVariables(text: String): List<String> {
        return VARIABLE_PATTERN.findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }
}
