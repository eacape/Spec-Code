package com.eacape.speccodingplugin.ui.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JLabel
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

class ImageAttachmentPreviewPanelTest {

    @Test
    fun `addImagePaths should deduplicate case insensitively`() {
        val panel = ImageAttachmentPreviewPanel()

        runOnEdt {
            panel.addImagePaths(
                listOf(
                    "C:/tmp/Design.png",
                    "c:/tmp/design.png",
                    "D:/tmp/Flow.jpg",
                )
            )
        }

        assertEquals(
            listOf("C:/tmp/Design.png", "D:/tmp/Flow.jpg"),
            panel.getImagePaths(),
        )
    }

    @Test
    fun `clear should empty attachments and hide panel`() {
        val panel = ImageAttachmentPreviewPanel()

        runOnEdt {
            panel.addImagePaths(listOf("C:/tmp/a.png"))
        }
        assertTrue(panel.isVisible)

        runOnEdt {
            panel.clear()
        }

        assertTrue(panel.getImagePaths().isEmpty())
        assertFalse(panel.isVisible)
    }

    @Test
    fun `preview chips should use image aliases and keep horizontal chip list`() {
        val panel = ImageAttachmentPreviewPanel()

        runOnEdt {
            panel.addImagePaths(
                listOf(
                    "C:/tmp/first.png",
                    "D:/tmp/second.jpg",
                    "E:/tmp/third.webp",
                )
            )
        }

        val aliases = collectDescendants(panel)
            .filterIsInstance<JLabel>()
            .mapNotNull { it.text }
            .filter { it.startsWith("image#") }
            .toList()

        assertEquals(listOf("image#1", "image#2", "image#3"), aliases)

        val removeButtons = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.toolTipText?.isNotBlank() == true }
            .toList()
        assertEquals(3, removeButtons.size)
    }

    @Test
    fun `remove button should delete only the targeted image`() {
        val removedPaths = mutableListOf<String>()
        val panel = ImageAttachmentPreviewPanel(onRemove = removedPaths::add)

        runOnEdt {
            panel.addImagePaths(
                listOf(
                    "C:/tmp/first.png",
                    "D:/tmp/second.jpg",
                )
            )
        }

        val removeButtons = collectDescendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.toolTipText?.isNotBlank() == true }
            .toList()

        runOnEdt {
            removeButtons.first().doClick()
        }

        assertEquals(listOf("D:/tmp/second.jpg"), panel.getImagePaths())
        assertEquals(listOf("C:/tmp/first.png"), removedPaths)
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
