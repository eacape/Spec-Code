package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ChatMessagePanelFailureStateTest {

    @Test
    fun `failed trace should surface failed badges in collapsed cards`() {
        val panel = runOnEdtResult {
            ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT).apply {
                appendContent(
                    """
                    [Task] prepare workflow context done
                    [Verify] spec verification failed
                    [Output] CLI exited with code: 1
                    """.trimIndent()
                )
                finishMessage()
            }
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.kind.output")))
        assertTrue(
            labels.any { it == SpecCodingBundle.message("chat.timeline.status.failed") },
            labels.joinToString(" | "),
        )
    }

    @Test
    fun `final assistant answer should suppress overall failed badge even when a step failed`() {
        val panel = runOnEdtResult {
            ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT).apply {
                appendContent(
                    """
                    [Task] prepare workflow context done
                    [Verify] spec verification failed

                    已完成分析并给出最终处理建议。
                    """.trimIndent()
                )
                finishMessage()
            }
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(
            labels.none { it == SpecCodingBundle.message("chat.timeline.status.failed") },
            labels.joinToString(" | "),
        )
    }

    @Test
    fun `fallback output summary should suppress overall failed badge when final conclusion exists`() {
        val panel = runOnEdtResult {
            ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT).apply {
                appendContent(
                    """
                    [Verify] business assertions failed
                    [Output] 结果: BUILD SUCCESSFUL.
                    [Output] 结果: harness 已恢复，完整 smoke 真正跑起来了，但还有 2 条业务断言失败，不再是平台容器起不来。
                    """.trimIndent()
                )
                finishMessage()
            }
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()

        assertTrue(labels.contains(SpecCodingBundle.message("chat.timeline.summary.label")))
        assertTrue(
            labels.none { it == SpecCodingBundle.message("chat.timeline.status.failed") },
            labels.joinToString(" | "),
        )
    }

    private fun <T> runOnEdtResult(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return action()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait {
            result = action()
        }
        return result ?: error("Expected EDT result")
    }

    private fun collectDescendants(component: Component): List<Component> {
        val result = mutableListOf<Component>()
        result += component
        val container = component as? Container ?: return result
        container.components.forEach { child ->
            result += collectDescendants(child)
        }
        return result
    }
}
