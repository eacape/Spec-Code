package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.ui.components.JBLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JPanel

class SpecDetailCollapsibleSectionViewTest {

    @Test
    fun `toggle button should keep english labels fully visible`() {
        val section = createSection(expandedInitially = false)

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.expand"), section.toggleTextForTest())
        assertTrue(section.toggleHasEnoughWidthForTest())
        assertTrue(section.toggleCanFitTextForTest("Expand"))

        section.applyToggleState(expanded = true, enabled = true)

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.collapse"), section.toggleTextForTest())
        assertTrue(section.toggleHasEnoughWidthForTest())
        assertTrue(section.toggleCanFitTextForTest("Collapse"))
    }

    @Test
    fun `disabled expanded section should use expand label and default cursor`() {
        val section = createSection(expandedInitially = true)

        section.applyToggleState(expanded = true, enabled = false)

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.expand"), section.toggleTextForTest())
        assertFalse(section.isToggleEnabledForTest())
        assertFalse(section.isToggleHandCursorForTest())
    }

    @Test
    fun `clicking toggle should update body visibility and notify callback`() {
        var expanded: Boolean? = null
        val section = createSection(
            expandedInitially = false,
            onToggle = { nextExpanded -> expanded = nextExpanded },
        )

        section.clickToggleForTest()

        assertTrue(section.bodyContainer.isVisible)
        assertEquals(true, expanded)
    }

    private fun createSection(
        expandedInitially: Boolean,
        onToggle: (Boolean) -> Unit = {},
    ): SpecDetailCollapsibleSectionView {
        return SpecDetailCollapsibleSectionView(
            titleLabel = JBLabel("Composer"),
            content = JPanel(),
            expandedInitially = expandedInitially,
            activeToggleForeground = Color(1, 2, 3),
            inactiveToggleForeground = Color(4, 5, 6),
            disabledToggleForeground = Color(7, 8, 9),
            onToggle = onToggle,
        )
    }
}
