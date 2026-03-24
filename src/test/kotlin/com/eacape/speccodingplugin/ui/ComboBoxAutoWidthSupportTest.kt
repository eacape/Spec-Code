package com.eacape.speccodingplugin.ui

import com.intellij.openapi.ui.ComboBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class ComboBoxAutoWidthSupportTest {

    @Test
    fun `selected item auto width should keep configured minimum width shrinkable`() {
        val comboBox = ComboBox(arrayOf("short", "a much longer value for auto sizing"))

        runOnEdt {
            comboBox.selectedItem = "a much longer value for auto sizing"
            ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
                comboBox = comboBox,
                minWidth = 80,
                maxWidth = 240,
                height = 28,
            )
        }

        assertEquals(80, comboBox.minimumSize.width)
        assertEquals(28, comboBox.minimumSize.height)
        assertTrue(comboBox.preferredSize.width >= comboBox.minimumSize.width)
        assertEquals(comboBox.preferredSize.width, comboBox.maximumSize.width)
        assertEquals(comboBox.preferredSize.height, comboBox.maximumSize.height)
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeAndWait(action)
        }
    }
}
