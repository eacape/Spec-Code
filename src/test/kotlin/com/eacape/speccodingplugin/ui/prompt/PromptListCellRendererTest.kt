package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.ui.components.JBList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

class PromptListCellRendererTest {

    @Test
    fun `resolveRowAction should detect edit icon hit from rendered bounds`() {
        runOnEdt {
            val renderer = PromptListCellRenderer()
            val model = DefaultListModel<PromptTemplate>().apply {
                addElement(
                    PromptTemplate(
                        id = "project-template",
                        name = "Project Template",
                        content = "content",
                        scope = PromptScope.PROJECT,
                    ),
                )
            }
            val list = JBList(model).apply {
                cellRenderer = renderer
                selectedIndex = 0
                fixedCellHeight = -1
                setSize(260, 96)
                doLayout()
            }

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            val rowComponent = rendererComponentFor(list, model[0], cellBounds!!)
            val editLabel = findLabelByTooltip(rowComponent, SpecCodingBundle.message("prompt.manager.edit"))
            assertNotNull(editLabel)

            val editRect = SwingUtilities.convertRectangle(
                editLabel!!.parent,
                editLabel.bounds,
                rowComponent,
            )
            val clickPoint = java.awt.Point(
                cellBounds.x + editRect.x + editRect.width / 2,
                cellBounds.y + editRect.y + editRect.height / 2,
            )

            assertEquals(
                PromptListCellRenderer.RowAction.EDIT,
                renderer.resolveRowAction(list, 0, clickPoint),
            )
        }
    }

    @Test
    fun `resolveRowAction should detect delete icon hit from rendered bounds`() {
        runOnEdt {
            val renderer = PromptListCellRenderer()
            val model = DefaultListModel<PromptTemplate>().apply {
                addElement(
                    PromptTemplate(
                        id = "project-template",
                        name = "Project Template",
                        content = "content",
                        scope = PromptScope.PROJECT,
                    ),
                )
            }
            val list = JBList(model).apply {
                cellRenderer = renderer
                selectedIndex = 0
                fixedCellHeight = -1
                setSize(260, 96)
                doLayout()
            }

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            val rowComponent = rendererComponentFor(list, model[0], cellBounds!!)
            val deleteLabel = findLabelByTooltip(rowComponent, SpecCodingBundle.message("prompt.manager.delete"))
            assertNotNull(deleteLabel)

            val deleteRect = SwingUtilities.convertRectangle(
                deleteLabel!!.parent,
                deleteLabel.bounds,
                rowComponent,
            )
            val clickPoint = java.awt.Point(
                cellBounds.x + deleteRect.x + deleteRect.width / 2,
                cellBounds.y + deleteRect.y + deleteRect.height / 2,
            )

            assertEquals(
                PromptListCellRenderer.RowAction.DELETE,
                renderer.resolveRowAction(list, 0, clickPoint),
            )
        }
    }

    private fun rendererComponentFor(
        list: JBList<PromptTemplate>,
        item: PromptTemplate,
        cellBounds: Rectangle,
    ): Component {
        @Suppress("UNCHECKED_CAST")
        val renderer = list.cellRenderer as ListCellRenderer<PromptTemplate>
        val component = renderer.getListCellRendererComponent(list, item, 0, true, false)
        component.setBounds(0, 0, cellBounds.width, cellBounds.height)
        layoutRecursively(component)
        return component
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) {
            return
        }
        component.doLayout()
        component.components.forEach(::layoutRecursively)
    }

    private fun findLabelByTooltip(component: Component, tooltip: String): JLabel? {
        if (component is JLabel && component.toolTipText == tooltip) {
            return component
        }
        if (component !is Container) {
            return null
        }
        return component.components.asSequence()
            .mapNotNull { child -> findLabelByTooltip(child, tooltip) }
            .firstOrNull()
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
