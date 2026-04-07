package com.eacape.speccodingplugin.ui.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.JTextPane

class SpecDetailPreviewChecklistInteractionBinderTest {

    @Test
    fun `bind should forward single left click to toggle callback`() {
        val pane = JTextPane()
        val toggleEvents = mutableListOf<MouseEvent>()
        val cursorRefreshEvents = mutableListOf<MouseEvent?>()

        SpecDetailPreviewChecklistInteractionBinder.bind(
            pane = pane,
            onToggleRequested = { toggleEvents += it },
            onCursorRefreshRequested = { cursorRefreshEvents += it },
        )

        pane.dispatchEvent(mouseEvent(pane, MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON1, clickCount = 1))

        assertEquals(1, toggleEvents.size)
        assertEquals(MouseEvent.MOUSE_CLICKED, toggleEvents.single().id)
        assertEquals(0, cursorRefreshEvents.size)
    }

    @Test
    fun `bind should ignore non single left clicks`() {
        val pane = JTextPane()
        var toggleCount = 0

        SpecDetailPreviewChecklistInteractionBinder.bind(
            pane = pane,
            onToggleRequested = { toggleCount += 1 },
            onCursorRefreshRequested = {},
        )

        pane.dispatchEvent(mouseEvent(pane, MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON3, clickCount = 1))
        pane.dispatchEvent(mouseEvent(pane, MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON1, clickCount = 2))

        assertEquals(0, toggleCount)
    }

    @Test
    fun `bind should forward mouse move and exit to cursor refresh callback`() {
        val pane = JTextPane()
        val cursorRefreshEvents = mutableListOf<MouseEvent?>()

        SpecDetailPreviewChecklistInteractionBinder.bind(
            pane = pane,
            onToggleRequested = {},
            onCursorRefreshRequested = { cursorRefreshEvents += it },
        )

        pane.dispatchEvent(mouseEvent(pane, MouseEvent.MOUSE_MOVED))
        pane.dispatchEvent(mouseEvent(pane, MouseEvent.MOUSE_EXITED))

        assertEquals(MouseEvent.MOUSE_MOVED, cursorRefreshEvents.first()?.id)
        assertNull(cursorRefreshEvents.last())
    }

    @Test
    fun `shouldToggle should only accept single left click`() {
        val pane = JTextPane()

        assertEquals(
            true,
            SpecDetailPreviewChecklistInteractionBinder.shouldToggle(
                mouseEvent(pane, MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON1, clickCount = 1),
            ),
        )
        assertEquals(
            false,
            SpecDetailPreviewChecklistInteractionBinder.shouldToggle(
                mouseEvent(pane, MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON1, clickCount = 2),
            ),
        )
        assertEquals(
            false,
            SpecDetailPreviewChecklistInteractionBinder.shouldToggle(
                mouseEvent(pane, MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON3, clickCount = 1),
            ),
        )
    }

    private fun mouseEvent(
        source: JTextPane,
        id: Int,
        button: Int = MouseEvent.NOBUTTON,
        clickCount: Int = 0,
    ): MouseEvent {
        return MouseEvent(
            source,
            id,
            System.currentTimeMillis(),
            0,
            8,
            8,
            clickCount,
            false,
            button,
        )
    }
}
