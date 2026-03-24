package com.eacape.speccodingplugin.ui.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class SmartInputFieldTest {

    @Test
    fun `enter should invoke send callback when popup is hidden`() {
        val sent = mutableListOf<String>()
        val field = createField(sent)

        runOnEdt {
            field.text = "hello world"
            field.caretPosition = field.text.length
            invokeKeyAction(field, KeyEvent.VK_ENTER, 0)
        }

        assertEquals(listOf("hello world"), sent)
    }

    @Test
    fun `shift enter should insert newline and not send`() {
        val sent = mutableListOf<String>()
        val field = createField(sent)

        runOnEdt {
            field.text = "hello"
            field.caretPosition = field.text.length
            invokeKeyAction(field, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        }

        assertEquals("hello\n", field.text)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `enter should not send when input is blank`() {
        val sent = mutableListOf<String>()
        val field = createField(sent)

        runOnEdt {
            field.text = "   "
            field.caretPosition = field.text.length
            invokeKeyAction(field, KeyEvent.VK_ENTER, 0)
        }

        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `up and down should move caret between lines when popup is hidden`() {
        val field = createField(mutableListOf())

        runOnEdt {
            field.text = "111\n222"
            field.caretPosition = field.text.length

            invokeKeyAction(field, KeyEvent.VK_UP, 0)
            assertEquals(3, field.caretPosition)

            invokeKeyAction(field, KeyEvent.VK_DOWN, 0)
            assertTrue(field.caretPosition > 3)
            assertEquals(field.text.length, field.caretPosition)
        }
    }

    @Test
    fun `ctrl v should invoke paste intercept`() {
        var intercepted = 0
        val field = SmartInputField(onPasteIntercept = {
            intercepted += 1
            true
        })

        runOnEdt {
            invokeKeyAction(field, KeyEvent.VK_V, shortcutMask())
        }

        assertEquals(1, intercepted)
    }

    @Test
    fun `shift insert should invoke paste intercept`() {
        var intercepted = 0
        val field = SmartInputField(onPasteIntercept = {
            intercepted += 1
            true
        })

        runOnEdt {
            invokeKeyAction(field, KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK)
        }

        assertEquals(1, intercepted)
    }

    private fun createField(sent: MutableList<String>): SmartInputField {
        return SmartInputField(onSend = { sent += it })
    }

    private fun invokeKeyAction(field: SmartInputField, keyCode: Int, modifiers: Int) {
        val keyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)
        val actionKey = field.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke)
        assertNotNull(actionKey, "No action key bound for keyStroke=$keyStroke")
        val action = field.actionMap.get(actionKey)
        assertNotNull(action, "No action found for keyStroke=$keyStroke")
        action.actionPerformed(ActionEvent(field, ActionEvent.ACTION_PERFORMED, "test"))
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private fun shortcutMask(): Int {
        return runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
            .getOrDefault(InputEvent.CTRL_DOWN_MASK)
    }
}
