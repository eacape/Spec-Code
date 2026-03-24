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

        val filterButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .firstOrNull {
                it.text == SpecCodingBundle.message(
                    "chat.timeline.output.filter.toggle",
                    SpecCodingBundle.message("chat.timeline.output.filter.key"),
                )
            }
        assertNotNull(filterButton, "Expected output filter button in key mode")

        val filteredText = collectText(panel)
        assertFalse(filteredText.contains("private val PASS_FG"))
        assertFalse(filteredText.contains("diff --git"))
        assertTrue(
            filteredText.contains(
                SpecCodingBundle.message("chat.timeline.output.filtered.more", 6)
            )
        )

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
