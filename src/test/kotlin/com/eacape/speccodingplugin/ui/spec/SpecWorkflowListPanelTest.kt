package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

class SpecWorkflowListPanelTest {

    @Test
    fun `updateWorkflows should replace list items and selection can be set`() {
        val panel = SpecWorkflowListPanel(
            onWorkflowFocused = {},
            onOpenWorkflow = {},
            onCreateWorkflow = {},
            onEditWorkflow = {},
            onDeleteWorkflow = {},
        )

        panel.updateWorkflows(
            listOf(
                item(id = "wf-1", title = "Workflow 1", phase = SpecPhase.SPECIFY),
                item(id = "wf-2", title = "Workflow 2", phase = SpecPhase.DESIGN),
            )
        )

        assertEquals(2, panel.itemsForTest().size)
        assertEquals("Workflow 1", panel.itemsForTest()[0].title)
        assertEquals("Workflow 2", panel.itemsForTest()[1].title)

        panel.setSelectedWorkflow("wf-2")
        assertEquals("wf-2", panel.selectedWorkflowIdForTest())

        panel.setSelectedWorkflow(null)
        assertNull(panel.selectedWorkflowIdForTest())
    }

    @Test
    fun `toolbar buttons should trigger callbacks`() {
        var createCalls = 0
        val openedIds = mutableListOf<String>()
        val editedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()

        val panel = SpecWorkflowListPanel(
            onWorkflowFocused = {},
            onOpenWorkflow = { openedIds += it },
            onCreateWorkflow = { createCalls += 1 },
            onEditWorkflow = { editedIds += it },
            onDeleteWorkflow = { deletedIds += it },
        )

        panel.updateWorkflows(listOf(item(id = "wf-del", title = "To Delete", phase = SpecPhase.SPECIFY)))

        panel.clickNewForTest()
        assertEquals(1, createCalls)

        panel.setSelectedWorkflow("wf-del")
        panel.clickOpenForTest()
        assertEquals(listOf("wf-del"), openedIds)

        panel.clickDeleteForTest()
        assertEquals(listOf("wf-del"), deletedIds)

        panel.clickEditForTest()
        assertEquals(listOf("wf-del"), editedIds)
    }

    @Test
    fun `workflowOptionsForCreate should expose current list as dialog options`() {
        val panel = SpecWorkflowListPanel(
            onWorkflowFocused = {},
            onOpenWorkflow = {},
            onCreateWorkflow = {},
            onEditWorkflow = {},
            onDeleteWorkflow = {},
        )
        panel.updateWorkflows(
            listOf(
                SpecWorkflowListPanel.WorkflowListItem(
                    workflowId = "wf-incremental",
                    title = "Incremental Workflow",
                    description = "desc",
                    currentPhase = SpecPhase.SPECIFY,
                    status = WorkflowStatus.IN_PROGRESS,
                    updatedAt = 1L,
                    changeIntent = SpecChangeIntent.INCREMENTAL,
                    baselineWorkflowId = "wf-base",
                ),
            ),
        )

        val options = panel.workflowOptionsForCreate()
        assertEquals(1, options.size)
        assertEquals("wf-incremental", options.first().workflowId)
        assertEquals("Incremental Workflow", options.first().title)
    }

    @Test
    fun `single click on edit icon should trigger edit callback`() {
        val editedIds = mutableListOf<String>()
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = {},
                onOpenWorkflow = {},
                onCreateWorkflow = {},
                onEditWorkflow = { editedIds += it },
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-edit",
                        title = "Editable Workflow",
                        description = "",
                        currentPhase = SpecPhase.SPECIFY,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            val rowComponent = rendererComponentFor(list, panel.itemsForTest().first(), cellBounds!!)
            val editLabel = findLabelByTooltip(rowComponent, SpecCodingBundle.message("spec.workflow.edit"))
            assertNotNull(editLabel)

            val editRect = SwingUtilities.convertRectangle(
                editLabel!!.parent,
                editLabel.bounds,
                rowComponent,
            )
            val clickX = cellBounds.x + editRect.x + editRect.width / 2
            val clickY = cellBounds.y + editRect.y + editRect.height / 2
            list.dispatchEvent(
                MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    clickX,
                    clickY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals(listOf("wf-edit"), editedIds)
    }

    @Test
    fun `single click on open icon should trigger open callback`() {
        val openedIds = mutableListOf<String>()
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = {},
                onOpenWorkflow = { openedIds += it },
                onCreateWorkflow = {},
                onEditWorkflow = {},
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-open",
                        title = "Open Workflow",
                        description = "",
                        currentPhase = SpecPhase.SPECIFY,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            val rowComponent = rendererComponentFor(list, panel.itemsForTest().first(), cellBounds!!)
            val openLabel = findLabelByTooltip(rowComponent, SpecCodingBundle.message("spec.workflow.open"))
            assertNotNull(openLabel)

            val openRect = SwingUtilities.convertRectangle(
                openLabel!!.parent,
                openLabel.bounds,
                rowComponent,
            )
            val clickX = cellBounds.x + openRect.x + openRect.width / 2
            val clickY = cellBounds.y + openRect.y + openRect.height / 2
            list.dispatchEvent(
                MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    clickX,
                    clickY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals(listOf("wf-open"), openedIds)
    }

    @Test
    fun `single click on row body should focus workflow without opening details`() {
        val focusedIds = mutableListOf<String>()
        val openedIds = mutableListOf<String>()
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = { focusedIds += it },
                onOpenWorkflow = { openedIds += it },
                onCreateWorkflow = {},
                onEditWorkflow = {},
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-focus",
                        title = "Focused Workflow",
                        description = "desc",
                        currentPhase = SpecPhase.SPECIFY,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            list.dispatchEvent(
                MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    cellBounds!!.x + 12,
                    cellBounds.y + cellBounds.height / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals(listOf("wf-focus"), focusedIds)
        assertTrue(openedIds.isEmpty())
    }

    @Test
    fun `renderer should prefer explicit current stage label`() {
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = {},
                onOpenWorkflow = {},
                onCreateWorkflow = {},
                onEditWorkflow = {},
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-verify",
                        title = "Verify Workflow",
                        description = "",
                        currentPhase = SpecPhase.IMPLEMENT,
                        currentStageLabel = "Verify",
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()

            val cellBounds = list.getCellBounds(0, 0)
            val rowComponent = rendererComponentFor(list, panel.itemsForTest().first(), cellBounds!!)
            val phaseLabel = findLabelContaining(rowComponent, "verify")

            assertNotNull(phaseLabel)
            assertTrue(phaseLabel!!.text.contains("verify"))
        }
    }

    @Test
    fun `single click on delete icon in unselected row should trigger delete callback`() {
        val deletedIds = mutableListOf<String>()
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = {},
                onOpenWorkflow = {},
                onCreateWorkflow = {},
                onEditWorkflow = {},
                onDeleteWorkflow = { deletedIds += it },
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-a",
                        title = "Workflow A",
                        description = "",
                        currentPhase = SpecPhase.SPECIFY,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-b",
                        title = "Workflow B",
                        description = "",
                        currentPhase = SpecPhase.DESIGN,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 2L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 170)
            list.doLayout()
            panel.setSelectedWorkflow("wf-a")

            val secondCellBounds = list.getCellBounds(1, 1)
            assertNotNull(secondCellBounds)
            val rowComponent = rendererComponentFor(list, panel.itemsForTest()[1], secondCellBounds!!)
            val deleteLabel = findLabelByTooltip(rowComponent, SpecCodingBundle.message("spec.workflow.delete"))
            assertNotNull(deleteLabel)

            val deleteRect = SwingUtilities.convertRectangle(
                deleteLabel!!.parent,
                deleteLabel.bounds,
                rowComponent,
            )
            val clickX = secondCellBounds.x + deleteRect.x + deleteRect.width / 2
            val clickY = secondCellBounds.y + deleteRect.y + deleteRect.height / 2
            list.dispatchEvent(
                MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    clickX,
                    clickY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals(listOf("wf-b"), deletedIds)
    }

    @Test
    fun `single click on row body should focus workflow without opening it`() {
        val focusedIds = mutableListOf<String>()
        val openedIds = mutableListOf<String>()
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = { focusedIds += it },
                onOpenWorkflow = { openedIds += it },
                onCreateWorkflow = {},
                onEditWorkflow = {},
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-focus",
                        title = "Focus Workflow",
                        description = "browse first",
                        currentPhase = SpecPhase.DESIGN,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()

            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)
            val clickX = cellBounds!!.x + 16
            val clickY = cellBounds.y + cellBounds.height / 2
            list.dispatchEvent(
                MouseEvent(
                    list,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    clickX,
                    clickY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals(listOf("wf-focus"), focusedIds)
        assertTrue(openedIds.isEmpty())
    }

    @Test
    fun `workflow row actions should keep the same official icons regardless of selection state`() {
        val panel = runOnEdtResult {
            SpecWorkflowListPanel(
                onWorkflowFocused = {},
                onOpenWorkflow = {},
                onCreateWorkflow = {},
                onEditWorkflow = {},
                onDeleteWorkflow = {},
            )
        }

        runOnEdt {
            panel.updateWorkflows(
                listOf(
                    SpecWorkflowListPanel.WorkflowListItem(
                        workflowId = "wf-icons",
                        title = "Icon Workflow",
                        description = "icon consistency",
                        currentPhase = SpecPhase.SPECIFY,
                        status = WorkflowStatus.IN_PROGRESS,
                        updatedAt = 1L,
                    ),
                ),
            )

            val list = extractWorkflowList(panel)
            list.setSize(220, 100)
            list.doLayout()
            val cellBounds = list.getCellBounds(0, 0)
            assertNotNull(cellBounds)

            val selectedRow = rendererComponentFor(list, panel.itemsForTest().first(), cellBounds!!, isSelected = true)
            val unselectedRow = rendererComponentFor(list, panel.itemsForTest().first(), cellBounds, isSelected = false)

            assertEquals("openDocument", actionIconId(selectedRow, SpecCodingBundle.message("spec.workflow.open")))
            assertEquals("edit", actionIconId(selectedRow, SpecCodingBundle.message("spec.workflow.edit")))
            assertEquals("delete", actionIconId(selectedRow, SpecCodingBundle.message("spec.workflow.delete")))
            assertEquals("openDocument", actionIconId(unselectedRow, SpecCodingBundle.message("spec.workflow.open")))
            assertEquals("edit", actionIconId(unselectedRow, SpecCodingBundle.message("spec.workflow.edit")))
            assertEquals("delete", actionIconId(unselectedRow, SpecCodingBundle.message("spec.workflow.delete")))
        }
    }

    private fun item(id: String, title: String, phase: SpecPhase): SpecWorkflowListPanel.WorkflowListItem {
        return SpecWorkflowListPanel.WorkflowListItem(
            workflowId = id,
            title = title,
            description = "desc",
            currentPhase = phase,
            status = WorkflowStatus.IN_PROGRESS,
            updatedAt = 1L,
        )
    }

    private fun extractWorkflowList(panel: SpecWorkflowListPanel): JList<SpecWorkflowListPanel.WorkflowListItem> {
        val field = SpecWorkflowListPanel::class.java.getDeclaredField("workflowList")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(panel) as JList<SpecWorkflowListPanel.WorkflowListItem>
    }

    private fun rendererComponentFor(
        list: JList<SpecWorkflowListPanel.WorkflowListItem>,
        item: SpecWorkflowListPanel.WorkflowListItem,
        cellBounds: Rectangle,
        isSelected: Boolean = true,
    ): Component {
        @Suppress("UNCHECKED_CAST")
        val renderer = list.cellRenderer as ListCellRenderer<SpecWorkflowListPanel.WorkflowListItem>
        val component = renderer.getListCellRendererComponent(list, item, 0, isSelected, false)
        component.setBounds(0, 0, cellBounds.width, cellBounds.height)
        layoutRecursively(component)
        return component
    }

    private fun actionIconId(component: Component, tooltip: String): String {
        val label = findLabelByTooltip(component, tooltip)
        assertNotNull(label)
        return SpecWorkflowIcons.debugId(label!!.icon)
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach { child ->
            layoutRecursively(child)
        }
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

    private fun findLabelContaining(component: Component, text: String): JLabel? {
        if (component is JLabel && component.text?.contains(text, ignoreCase = true) == true) {
            return component
        }
        if (component !is Container) {
            return null
        }
        return component.components.asSequence()
            .mapNotNull { child -> findLabelContaining(child, text) }
            .firstOrNull()
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }

    private fun <T> runOnEdtResult(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
