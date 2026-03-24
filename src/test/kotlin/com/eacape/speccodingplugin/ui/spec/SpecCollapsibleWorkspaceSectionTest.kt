package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel

class SpecCollapsibleWorkspaceSectionTest {

    @Test
    fun `toggle button should keep english labels fully visible`() {
        val section = SpecCollapsibleWorkspaceSection(
            titleProvider = { "Documents" },
            content = JPanel(),
            expandedInitially = false,
        )

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.expand"), section.toggleButtonTextForTest())
        assertTrue(section.toggleButtonHasEnoughWidthForTextForTest())
        assertTrue(section.toggleButtonCanFitTextForTest("Expand"))

        section.setExpanded(true, notify = false)

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.collapse"), section.toggleButtonTextForTest())
        assertTrue(section.toggleButtonHasEnoughWidthForTextForTest())
        assertTrue(section.toggleButtonCanFitTextForTest("Collapse"))
    }

    @Test
    fun `expanded body height should clamp to configured max height`() {
        val content = JPanel().apply {
            preferredSize = Dimension(320, 480)
        }
        val section = SpecCollapsibleWorkspaceSection(
            titleProvider = { "Tasks" },
            content = content,
            expandedInitially = true,
            maxExpandedBodyHeight = 220,
        )

        assertEquals(220, section.bodyPreferredHeightForTest())
        assertEquals(220, section.bodyMaximumHeightForTest())
    }

    @Test
    fun `expanded body height should keep natural height when under max`() {
        val content = JPanel().apply {
            preferredSize = Dimension(320, 140)
        }
        val section = SpecCollapsibleWorkspaceSection(
            titleProvider = { "Checks" },
            content = content,
            expandedInitially = true,
            maxExpandedBodyHeight = 220,
        )

        assertEquals(140, section.bodyPreferredHeightForTest())
        assertEquals(140, section.bodyMaximumHeightForTest())
    }
}
