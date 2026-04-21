package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class ChatMessagePanelOutputFilterTest {
    @Test
    fun `output key filter should hide raw diff and code lines until all mode`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))
                [Output] diff --git a/src/main/kotlin/com/example/Demo.kt b/src/main/kotlin/com/example/Demo.kt
                [Output] index abc1234..def5678 100644
                [Output] --- a/src/main/kotlin/com/example/Demo.kt
                [Output] +++ b/src/main/kotlin/com/example/Demo.kt
                [Output] @@ -1,2 +1,2 @@
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertFalse(filteredText.contains("private val PASS_FG"))
        assertFalse(filteredText.contains("diff --git"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 6)
            )
        )

        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull {
                it.text == SpecCodingBundle.message(
                    "chat.timeline.output.filter.toggle",
                    SpecCodingBundle.message("chat.timeline.output.filter.key"),
                )
            }
        assertNotNull(filterButton, "Expected output filter button in key mode")

        runOnEdt { filterButton!!.doClick() }

        val allText = collectText(panel)
        assertTrue(allText.contains("private val PASS_FG"))
        assertTrue(allText.contains("diff --git"))
    }

    @Test
    fun `output key filter should keep narrative summary while hiding raw patch detail`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] Updated the output filter to keep narrative results visible in key mode.
                [Output] private val PASS_FG = JBColor(Color(39, 94, 57), Color(194, 232, 204))
                [Output] diff --git a/src/main/kotlin/com/example/Demo.kt b/src/main/kotlin/com/example/Demo.kt
                [Output] @@ -1,2 +1,2 @@
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertTrue(filteredText.contains("Updated the output filter to keep narrative results visible in key mode."))
        assertFalse(filteredText.contains("private val PASS_FG"))
        assertFalse(filteredText.contains("diff --git"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 3)
            )
        )
    }

    @Test
    fun `output key filter should stop after leading answer block when execution leak appears later`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] 这是一个针对当前 Spec 工作流的设计评审和演进建议文档。
                [Output] 建议先统一 change intent 与 stage 的职责边界。
                [Output] ) / previous()
                [Output] SpecChangeIntent.FULL | INCREMENTAL
                [Output] Updated enum mapping and workflow summary rendering.
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertTrue(filteredText.contains("这是一个针对当前 Spec 工作流的设计评审和演进建议文档。"))
        assertTrue(filteredText.contains("建议先统一 change intent 与 stage 的职责边界。"))
        assertFalse(filteredText.contains("previous()"))
        assertFalse(filteredText.contains("SpecChangeIntent.FULL | INCREMENTAL"))
        assertFalse(filteredText.contains("Updated enum mapping and workflow summary rendering."))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 3)
            )
        )
    }

    @Test
    fun `output key filter should hide screenshot style command diagnostics and keep explanation`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] I updated the filter to show only natural-language output in key mode.
                [Output] src/main/resources/messages/SpecCodingBundle_zh_CN.properties:682:changeset.timeline.status.error=异常
                [Output] currentAssistantPanel = null
                [Output] At line:2 char:1
                [Output] succeeded in 285ms:
                [Output] finishMessage()
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertTrue(filteredText.contains("I updated the filter to show only natural-language output in key mode."))
        assertFalse(filteredText.contains("SpecCodingBundle_zh_CN.properties:682"))
        assertFalse(filteredText.contains("currentAssistantPanel = null"))
        assertFalse(filteredText.contains("At line:2 char:1"))
        assertFalse(filteredText.contains("succeeded in 285ms:"))
        assertFalse(filteredText.contains("finishMessage()"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 5)
            )
        )
    }

    @Test
    fun `output key filter should hide line numbered source snippets and keep summary`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] I narrowed the output view to summary text only.
                [Output] 3252:     val wfId = selectedWorkflowId ?: return
                [Output] 3253:     val basePath = project.basePath ?: return
                [Output] 3262:     val workflow = currentWorkflow ?: return
                [Output] 3263:     onShowHistoryDiffForWorkflow(
                [Output] src/main/kotlin/com/eacape/speccodingplugin/spec/SpecDeltaModels.kt:147:data class SpecVerificationArtifactSummary(
                [Output] 527:     val totalChecks: Int,
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertTrue(filteredText.contains("I narrowed the output view to summary text only."))
        assertFalse(filteredText.contains("3252:"))
        assertFalse(filteredText.contains("selectedWorkflowId"))
        assertFalse(filteredText.contains("onShowHistoryDiffForWorkflow("))
        assertFalse(filteredText.contains("SpecVerificationArtifactSummary("))
        assertFalse(filteredText.contains("val totalChecks: Int"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 6)
            )
        )
    }

    @Test
    fun `output key filter should hide json fragments typed assignments and timing noise`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] I kept only the user-facing summary in the output panel.
                [Output] 11: "default_conference_values": [
                [Output] 16: "stale_threshold_minutes": 15,
                [Output] 17: "metrics": {
                [Output] 29: stale_threshold_minutes: int = 15
                [Output] 53: as_of_mode: str = "not_later_than"
                [Output] succeeded in 978ms:
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertTrue(filteredText.contains("I kept only the user-facing summary in the output panel."))
        assertFalse(filteredText.contains("default_conference_values"))
        assertFalse(filteredText.contains("stale_threshold_minutes"))
        assertFalse(filteredText.contains("metrics"))
        assertFalse(filteredText.contains("as_of_mode"))
        assertFalse(filteredText.contains("succeeded in 978ms"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 6)
            )
        )
    }

    @Test
    fun `output key filter should keep stage summary and hide tabular command output until all mode`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runOnEdt {
            panel.appendContent(
                """
                [Output] 我已经把输出面板收敛成只显示阶段性总结。
                [Output] 接下来我会恢复“全部”按钮，同时继续压缩噪音。
                [Output] Mode   Length LastWriteTime Name
                [Output] d-----        2026/3/2 1:39:24 .claude
                [Output] d-----        2026/3/2 1:39:24 .codex
                """.trimIndent()
            )
            panel.finishMessage()
        }

        val expandButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == SpecCodingBundle.message("chat.timeline.toggle.expand") }
        assertNotNull(expandButton, "Expected output expand button")
        runOnEdt { expandButton!!.doClick() }

        val filteredText = collectText(panel)
        assertTrue(filteredText.contains("我已经把输出面板收敛成只显示阶段性总结。"))
        assertTrue(filteredText.contains("接下来我会恢复“全部”按钮，同时继续压缩噪音。"))
        assertFalse(filteredText.contains("Mode   Length LastWriteTime Name"))
        assertFalse(filteredText.contains(".claude"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 3)
            )
        )

        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull {
                it.text == SpecCodingBundle.message(
                    "chat.timeline.output.filter.toggle",
                    SpecCodingBundle.message("chat.timeline.output.filter.key"),
                )
            }
        assertNotNull(filterButton, "Expected output filter button in key mode")

        runOnEdt { filterButton!!.doClick() }

        val allText = collectText(panel)
        assertTrue(allText.contains("Mode   Length LastWriteTime Name"))
        assertTrue(allText.contains(".claude"))
    }

    private fun collectText(panel: ChatMessagePanel): String {
        return collectDescendants(panel)
            .filterIsInstance<JTextPane>()
            .joinToString("\n") { it.text.orEmpty() }
    }

    private fun collectDescendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        val container = component as? Container ?: return@sequence
        container.components.forEach { child ->
            yieldAll(collectDescendants(child))
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
