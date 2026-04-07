package com.eacape.speccodingplugin.ui.spec

import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.Font
import javax.swing.JButton

class SpecDetailActionBarChromePresenterTest {

    @Test
    fun `refreshChrome should restyle action bar buttons after icon presentation`() {
        val buttons = buttons()
        val chromePresenter = SpecDetailActionBarChromePresenter(buttons)
        buttons.generate.icon = SpecWorkflowIcons.Execute

        chromePresenter.refreshChrome()

        val generate = buttons.generate
        assertEquals(Font.BOLD, generate.font.style)
        assertEquals(JBUI.insets(0, 0, 0, 0), generate.margin)
        assertTrue(generate.isOpaque)
        assertEquals("roundRect", generate.getClientProperty("JButton.buttonType"))
        assertEquals(JBUI.scale(22), generate.preferredSize.width)
        assertEquals(JBUI.scale(22), generate.preferredSize.height)
        assertEquals(Cursor.HAND_CURSOR, generate.cursor.type)
        assertNotNull(generate.border)
    }

    @Test
    fun `refreshChrome should keep text button width floor for shared chrome styler`() {
        val button = JButton("Preview")

        SpecDetailButtonChromeStyler.apply(button)

        assertEquals(Font.BOLD, button.font.style)
        assertTrue(button.preferredSize.width >= JBUI.scale(40))
        assertEquals(JBUI.scale(26), button.preferredSize.height)
        assertEquals(Cursor.HAND_CURSOR, button.cursor.type)
        assertNotNull(button.border)
    }

    @Test
    fun `applySetupVisibility should hide setup only actions`() {
        val buttons = buttons()
        val chromePresenter = SpecDetailActionBarChromePresenter(buttons)

        chromePresenter.applySetupVisibility()

        assertFalse(buttons.save.isVisible)
        assertFalse(buttons.cancelEdit.isVisible)
        assertFalse(buttons.confirmGenerate.isVisible)
        assertFalse(buttons.regenerateClarification.isVisible)
        assertFalse(buttons.skipClarification.isVisible)
        assertFalse(buttons.cancelClarification.isVisible)
        assertTrue(buttons.generate.isVisible)
        assertTrue(buttons.edit.isVisible)
    }

    private fun buttons(): SpecDetailActionBarButtons {
        return SpecDetailActionBarButtons(
            generate = JButton(),
            nextPhase = JButton(),
            goBack = JButton(),
            complete = JButton(),
            pauseResume = JButton(),
            openEditor = JButton(),
            historyDiff = JButton(),
            edit = JButton(),
            save = JButton(),
            cancelEdit = JButton(),
            confirmGenerate = JButton(),
            regenerateClarification = JButton(),
            skipClarification = JButton(),
            cancelClarification = JButton(),
        )
    }
}
