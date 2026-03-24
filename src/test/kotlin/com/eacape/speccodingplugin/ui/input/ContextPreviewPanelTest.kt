package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ContextPreviewPanelTest {

    @Test
    fun `addItem should deduplicate exact context entries`() {
        val panel = ContextPreviewPanel(project = mockk<Project>(relaxed = true))
        val item = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "src/App.kt",
            content = "fun main() = Unit",
            filePath = "src/App.kt",
        )

        runOnEdt {
            panel.addItem(item)
            panel.addItem(item.copy())
        }

        assertEquals(1, panel.getItems().size)
        assertTrue(panel.isVisible)
    }

    @Test
    fun `context preview should render individual file chips with truncated labels`() {
        val removed = mutableListOf<ContextItem>()
        val panel = ContextPreviewPanel(
            project = mockk<Project>(relaxed = true),
            onRemove = { removed += it },
        )
        val longPath = "src/features/chat/super-long-file-name-for-context-preview-rendering-example.kt"

        runOnEdt {
            panel.addItem(ContextItem(ContextType.REFERENCED_FILE, "App.kt", "fun main() = Unit", "src/App.kt"))
            panel.addItem(ContextItem(ContextType.REFERENCED_FILE, "example.kt", "class Example", longPath))
        }

        val labels = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .toList()
        assertTrue(labels.contains("App.kt"))
        assertTrue(labels.any { it.endsWith("...") })

        val longNameLabel = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .first { it.text?.endsWith("...") == true }
        assertTrue(longNameLabel.toolTipText?.contains(longPath) == true)

        val removeButtons = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.toolTipText?.isNotBlank() == true }
            .toList()
        assertEquals(2, removeButtons.size)

        runOnEdt {
            removeButtons.first().doClick()
        }

        assertEquals(1, panel.getItems().size)
        assertEquals("example.kt", panel.getItems().single().label)
        assertEquals(1, removed.size)
        assertTrue(panel.isVisible)
    }

    @Test
    fun `removing the last chip should hide the panel`() {
        val panel = ContextPreviewPanel(project = mockk<Project>(relaxed = true))

        runOnEdt {
            panel.addItem(ContextItem(ContextType.REFERENCED_FILE, "App.kt", "fun main() = Unit", "src/App.kt"))
        }

        val removeButton = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .first { it.toolTipText?.isNotBlank() == true }

        runOnEdt {
            removeButton.doClick()
        }

        assertTrue(panel.getItems().isEmpty())
        assertFalse(panel.isVisible)
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
