package com.eacape.speccodingplugin.ui.spec

import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JTextPane
import javax.swing.SwingUtilities

internal object SpecDetailPreviewChecklistInteractionBinder {

    fun bind(
        pane: JTextPane,
        onToggleRequested: (MouseEvent) -> Unit,
        onCursorRefreshRequested: (MouseEvent?) -> Unit,
    ) {
        pane.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (shouldToggle(event)) {
                        onToggleRequested(event)
                    }
                }

                override fun mouseExited(event: MouseEvent?) {
                    onCursorRefreshRequested(null)
                }
            },
        )
        pane.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    onCursorRefreshRequested(event)
                }
            },
        )
    }

    fun shouldToggle(event: MouseEvent): Boolean {
        return SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1
    }
}
