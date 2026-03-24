package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ExecutionLivePhase
import com.eacape.speccodingplugin.spec.SpecTaskDependencyRules
import com.eacape.speccodingplugin.spec.StructuredTask
import com.eacape.speccodingplugin.spec.TaskExecutionLiveProgress
import com.eacape.speccodingplugin.spec.TaskVerificationResult
import com.eacape.speccodingplugin.spec.TaskStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

internal class SpecWorkflowTasksPanel(
    private val onTransitionStatus: (taskId: String, to: TaskStatus) -> Unit = { _, _ -> },
    private val onCancelExecution: (taskId: String) -> Unit = {},
    private val onExecuteTask: (taskId: String, retry: Boolean) -> Unit = { _, _ -> },
    private val onOpenWorkflowChat: (workflowId: String, taskId: String) -> Unit = { _, _ -> },
    private val onUpdateDependsOn: (taskId: String, dependsOn: List<String>) -> Unit = { _, _ -> },
    private val onCompleteWithRelatedFiles: (
        taskId: String,
        files: List<String>,
        verificationResult: TaskVerificationResult?,
    ) -> Unit = { _, _, _ -> },
    private val onUpdateVerificationResult: (taskId: String, verificationResult: TaskVerificationResult?) -> Unit = { _, _ -> },
    private val suggestRelatedFiles: (taskId: String, existingRelatedFiles: List<String>) -> List<String> = { _, existing -> existing },
    private val onTaskSelected: (taskId: String?) -> Unit = {},
    private val showHeader: Boolean = true,
    private val fixedViewportHeight: Int? = null,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    internal enum class TaskListRefreshMode {
        FULL_REBUILD,
        IN_PLACE_UPDATE,
    }

    private val headerTitleLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(12.5f)
        foreground = HEADER_FG
    }
    private val headerSummaryLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = HEADER_SECONDARY_FG
    }
    private val headerRefreshedLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.RIGHT
        font = JBUI.Fonts.smallFont().deriveFont(10.5f)
        foreground = HEADER_SECONDARY_FG
    }
    private val headerPanel = buildHeader()

    private val listModel = DefaultListModel<StructuredTask>()
    private val taskCellRenderer = TaskCellRenderer()
    private val tasksList = object : JBList<StructuredTask>(listModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            return resolveTaskRowPrimaryActionAt(event.point)?.presentation?.tooltip
        }
    }.apply {
        cellRenderer = taskCellRenderer
        visibleRowCount = -1
        fixedCellHeight = -1
        isOpaque = false
        border = JBUI.Borders.empty()
        emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
    }
    private val tasksScrollPane = JBScrollPane(tasksList).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        viewport.isOpaque = false
        isOpaque = false
        SpecUiStyle.applyFastVerticalScrolling(this)
    }

    private val executeTaskButton = createIconActionButton {
        handleExecuteTask()
    }.apply {
        isVisible = false
    }
    private val openWorkflowChatButton = createIconActionButton {
        handleOpenWorkflowChat()
    }
    private val secondaryActionsButton = createIconActionButton {
        showSecondaryActionsMenu()
    }
    private val editDependsOnButton = createIconActionButton {
        handleEditDependsOn()
    }
    private val editVerificationResultButton = createIconActionButton {
        handleEditVerificationResult()
    }

    private var currentWorkflowId: String? = null
    private var currentTasksById: Map<String, StructuredTask> = emptyMap()
    private var currentLiveProgressByTaskId: Map<String, TaskExecutionLiveProgress> = emptyMap()
    private var lastRefreshedAtMillis: Long? = null
    private val controlsPanel = buildControls()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 4, 6, 4)
        if (showHeader) {
            add(headerPanel, BorderLayout.NORTH)
        }
        add(tasksScrollPane, BorderLayout.CENTER)
        add(controlsPanel, BorderLayout.SOUTH)

        tasksList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateControlsForSelection()
                onTaskSelected(tasksList.selectedValue?.id)
            }
        }
        tasksList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    handleTaskListClick(event)
                }

                override fun mouseExited(event: MouseEvent) {
                    tasksList.cursor = Cursor.getDefaultCursor()
                }
            },
        )
        tasksList.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    updateTaskListCursor(event.point)
                }
            },
        )
        refreshLocalizedTexts()
        showEmpty()
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val fixedHeight = fixedViewportHeight?.let(JBUI::scale)
        if (fixedHeight != null) {
            return Dimension(base.width, fixedHeight)
        }
        val height = insets.top +
            insets.bottom +
            dynamicListHeight() +
            controlsPanel.preferredSize.height +
            preferredVerticalGaps() +
            if (showHeader) headerPanel.preferredSize.height else 0
        return Dimension(base.width, height)
    }

    override fun getMinimumSize(): Dimension {
        val base = super.getMinimumSize()
        val fixedHeight = fixedViewportHeight?.let(JBUI::scale) ?: return base
        return Dimension(base.width, fixedHeight)
    }

    private fun dynamicListHeight(): Int {
        val listHeight = tasksList.preferredSize.height.takeIf { it > 0 } ?: EMPTY_LIST_HEIGHT
        val scrollBorderInsets = tasksScrollPane.border?.getBorderInsets(tasksScrollPane)
        val verticalBorder = (scrollBorderInsets?.top ?: 0) + (scrollBorderInsets?.bottom ?: 0)
        return listHeight + verticalBorder
    }

    private fun preferredVerticalGaps(): Int = if (showHeader) JBUI.scale(12) else JBUI.scale(6)

    fun refreshLocalizedTexts() {
        headerTitleLabel.text = SpecCodingBundle.message("spec.toolwindow.tasks.title")
        applyActionButtonPresentation()
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
        updateHeader()
        updateControlsForSelection()
    }

    fun showEmpty() {
        currentWorkflowId = null
        currentTasksById = emptyMap()
        currentLiveProgressByTaskId = emptyMap()
        lastRefreshedAtMillis = null
        listModel.clear()
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.empty")
        updateHeader()
        updateControlsForSelection()
    }

    fun showLoading() {
        currentWorkflowId = null
        currentTasksById = emptyMap()
        currentLiveProgressByTaskId = emptyMap()
        lastRefreshedAtMillis = null
        listModel.clear()
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.loading")
        updateHeader()
        updateControlsForSelection()
    }

    fun updateTasks(
        workflowId: String,
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress> = emptyMap(),
        refreshedAtMillis: Long,
    ) {
        val previousSelection = tasksList.selectedValue?.id
        currentWorkflowId = workflowId
        currentTasksById = tasks.associateBy { it.id }
        currentLiveProgressByTaskId = liveProgressByTaskId
        lastRefreshedAtMillis = refreshedAtMillis

        val refreshMode = replaceTaskRows(tasks)
        restoreSelection(previousSelection, tasks)
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow")
        updateHeader()
        updateControlsForSelection()
        if (refreshMode == TaskListRefreshMode.FULL_REBUILD) {
            tasksList.revalidate()
        }
    }

    fun updateLiveProgress(
        tasks: List<StructuredTask>,
        liveProgressByTaskId: Map<String, TaskExecutionLiveProgress>,
    ) {
        val workflowId = currentWorkflowId ?: return
        val previousSelection = tasksList.selectedValue?.id
        currentTasksById = tasks.associateBy { it.id }
        currentLiveProgressByTaskId = liveProgressByTaskId
        val refreshMode = replaceTaskRows(tasks)
        restoreSelection(previousSelection, tasks)
        tasksList.emptyText.text = SpecCodingBundle.message("spec.toolwindow.tasks.emptyForWorkflow")
        currentWorkflowId = workflowId
        updateControlsForSelection()
        if (refreshMode == TaskListRefreshMode.FULL_REBUILD) {
            tasksList.revalidate()
        }
        tasksList.repaint()
    }

    internal fun taskListRefreshModeForTest(nextTaskIds: List<String>): TaskListRefreshMode {
        return resolveTaskListRefreshMode(
            existingTaskIds = currentTaskIdsInView(),
            incomingTaskIds = nextTaskIds,
        )
    }

    internal fun snapshotForTest(): Map<String, String> {
        val selectedTask = tasksList.selectedValue
        val selectedProgress = selectedTask?.let(::executionPresentation)
        val tasks = (0 until listModel.size()).joinToString(" | ") { index ->
            val task = listModel[index]
            "${task.id}:${task.displayStatus.name}:${task.priority.name}"
        }
        val selectedId = tasksList.selectedValue?.id.orEmpty()
        return mapOf(
            "workflowId" to currentWorkflowId.orEmpty(),
            "headerVisible" to showHeader.toString(),
            "headerTitle" to headerTitleLabel.text.orEmpty(),
            "headerSummary" to headerSummaryLabel.text.orEmpty(),
            "headerRefreshed" to headerRefreshedLabel.text.orEmpty(),
            "tasks" to tasks,
            "selectedTaskId" to selectedId,
            "selectedTaskMeta" to selectedTask?.let(::taskMetaText).orEmpty(),
            "selectedTaskChip" to selectedTask?.let(::taskChipText).orEmpty(),
            "selectedTaskPhase" to selectedProgress?.phase?.name.orEmpty(),
            "selectedTaskExecutionDetail" to selectedProgress?.detailText.orEmpty(),
            "executeText" to executeTaskButton.text.orEmpty(),
            "executeIconId" to SpecWorkflowIcons.debugId(executeTaskButton.icon),
            "executeHasIcon" to (executeTaskButton.icon != null).toString(),
            "executeRolloverEnabled" to executeTaskButton.isRolloverEnabled.toString(),
            "executeFocusable" to executeTaskButton.isFocusable.toString(),
            "executeEnabled" to executeTaskButton.isEnabled.toString(),
            "executeVisible" to executeTaskButton.isVisible.toString(),
            "executeTooltip" to executeTaskButton.toolTipText.orEmpty(),
            "executeAccessibleName" to executeTaskButton.accessibleContext.accessibleName.orEmpty(),
            "executeAccessibleDescription" to executeTaskButton.accessibleContext.accessibleDescription.orEmpty(),
            "chatText" to openWorkflowChatButton.text.orEmpty(),
            "chatIconId" to SpecWorkflowIcons.debugId(openWorkflowChatButton.icon),
            "chatHasIcon" to (openWorkflowChatButton.icon != null).toString(),
            "chatRolloverEnabled" to openWorkflowChatButton.isRolloverEnabled.toString(),
            "chatFocusable" to openWorkflowChatButton.isFocusable.toString(),
            "chatEnabled" to openWorkflowChatButton.isEnabled.toString(),
            "chatTooltip" to openWorkflowChatButton.toolTipText.orEmpty(),
            "chatAccessibleName" to openWorkflowChatButton.accessibleContext.accessibleName.orEmpty(),
            "chatAccessibleDescription" to openWorkflowChatButton.accessibleContext.accessibleDescription.orEmpty(),
            "secondaryText" to secondaryActionsButton.text.orEmpty(),
            "secondaryIconId" to SpecWorkflowIcons.debugId(secondaryActionsButton.icon),
            "secondaryTooltip" to secondaryActionsButton.toolTipText.orEmpty(),
            "secondaryHasIcon" to (secondaryActionsButton.icon != null).toString(),
            "secondaryRolloverEnabled" to secondaryActionsButton.isRolloverEnabled.toString(),
            "secondaryFocusable" to secondaryActionsButton.isFocusable.toString(),
            "secondaryEnabled" to secondaryActionsButton.isEnabled.toString(),
            "secondaryAccessibleName" to secondaryActionsButton.accessibleContext.accessibleName.orEmpty(),
            "secondaryAccessibleDescription" to secondaryActionsButton.accessibleContext.accessibleDescription.orEmpty(),
            "dependsOnText" to editDependsOnButton.text.orEmpty(),
            "dependsOnIconId" to SpecWorkflowIcons.debugId(editDependsOnButton.icon),
            "dependsOnTooltip" to editDependsOnButton.toolTipText.orEmpty(),
            "dependsOnHasIcon" to (editDependsOnButton.icon != null).toString(),
            "dependsOnRolloverEnabled" to editDependsOnButton.isRolloverEnabled.toString(),
            "dependsOnFocusable" to editDependsOnButton.isFocusable.toString(),
            "dependsOnAccessibleName" to editDependsOnButton.accessibleContext.accessibleName.orEmpty(),
            "dependsOnAccessibleDescription" to editDependsOnButton.accessibleContext.accessibleDescription.orEmpty(),
            "verificationText" to editVerificationResultButton.text.orEmpty(),
            "verificationIconId" to SpecWorkflowIcons.debugId(editVerificationResultButton.icon),
            "verificationHasIcon" to (editVerificationResultButton.icon != null).toString(),
            "verificationRolloverEnabled" to editVerificationResultButton.isRolloverEnabled.toString(),
            "verificationFocusable" to editVerificationResultButton.isFocusable.toString(),
            "verificationEnabled" to editVerificationResultButton.isEnabled.toString(),
            "verificationTooltip" to editVerificationResultButton.toolTipText.orEmpty(),
            "verificationAccessibleName" to editVerificationResultButton.accessibleContext.accessibleName.orEmpty(),
            "verificationAccessibleDescription" to editVerificationResultButton.accessibleContext.accessibleDescription.orEmpty(),
            "dependsOnEnabled" to editDependsOnButton.isEnabled.toString(),
            "emptyText" to tasksList.emptyText.text.orEmpty(),
        )
    }

    internal fun selectTaskForTest(taskId: String) {
        selectTask(taskId)
    }

    internal fun selectTask(taskId: String): Boolean {
        for (index in 0 until listModel.size()) {
            if (listModel[index].id == taskId) {
                tasksList.selectedIndex = index
                return true
            }
        }
        return false
    }

    internal fun clearTaskSelection() {
        tasksList.clearSelection()
    }

    internal fun selectedTaskId(): String? = tasksList.selectedValue?.id

    internal fun requestCompletionForTask(taskId: String): Boolean {
        if (!selectTask(taskId)) {
            return false
        }
        val selectedTask = tasksList.selectedValue ?: return false
        requestCompletionWithRelatedFiles(selectedTask)
        return true
    }

    internal fun requestExecutionForTask(taskId: String): Boolean {
        if (!selectTask(taskId)) {
            return false
        }
        return requestExecutionForSelection()
    }

    internal fun clickExecuteTaskForTest() {
        executeTaskButton.doClick()
    }

    internal fun clickOpenWorkflowChatForTest() {
        openWorkflowChatButton.doClick()
    }

    internal fun taskRowPrimaryActionSnapshotForTest(taskId: String): Map<String, String> {
        val task = currentTasksById[taskId]
        val action = task?.let(::resolveTaskRowPrimaryAction)
        return mapOf(
            "visible" to (action != null).toString(),
            "enabled" to (action?.presentation?.enabled ?: false).toString(),
            "iconId" to SpecWorkflowIcons.debugId(action?.presentation?.icon),
            "tooltip" to action?.presentation?.tooltip.orEmpty(),
            "accessibleName" to action?.presentation?.accessibleName.orEmpty(),
        )
    }

    internal fun triggerTaskRowPrimaryActionForTest(taskId: String): Boolean {
        val task = currentTasksById[taskId] ?: return false
        if (resolveTaskRowPrimaryAction(task) == null) {
            return false
        }
        return performTaskRowPrimaryAction(taskId)
    }

    internal fun taskRowRenderSnapshotForTest(
        taskId: String,
        listWidth: Int,
    ): Map<String, String> {
        val task = currentTasksById[taskId] ?: return emptyMap()
        val index = (0 until listModel.size()).firstOrNull { candidate -> listModel[candidate].id == taskId } ?: return emptyMap()
        tasksList.setSize(listWidth, tasksList.height.takeIf { it > 0 } ?: JBUI.scale(56))
        taskCellRenderer.getListCellRendererComponent(
            tasksList,
            task,
            index,
            tasksList.selectedIndex == index,
            false,
        )
        return taskCellRenderer.renderSnapshotForTest()
    }

    internal fun triggerSecondaryActionForTest(targetStatus: TaskStatus): Boolean {
        val selectedTask = tasksList.selectedValue ?: return false
        val action = buildSecondaryActions(selectedTask).firstOrNull { it.targetStatus == targetStatus } ?: return false
        if (!action.enabled) {
            return false
        }
        action.perform()
        return true
    }

    internal fun triggerStopExecutionForTest(): Boolean {
        val selectedTask = tasksList.selectedValue ?: return false
        val action = buildSecondaryActions(selectedTask)
            .firstOrNull { it.actionId == TaskSecondaryActionId.STOP_EXECUTION }
        if (action != null) {
            action.perform()
            return true
        }
        val progressPhase = executionPresentation(selectedTask)?.phase
        if (progressPhase != null && progressPhase != ExecutionLivePhase.WAITING_CONFIRMATION && progressPhase != ExecutionLivePhase.CANCELLING) {
            executeTaskButton.doClick()
            return true
        }
        if (selectedTask.displayStatus == TaskStatus.IN_PROGRESS) {
            executeTaskButton.doClick()
            return true
        }
        return false
    }

    private fun replaceTaskRows(tasks: List<StructuredTask>): TaskListRefreshMode {
        val refreshMode = resolveTaskListRefreshMode(
            existingTaskIds = currentTaskIdsInView(),
            incomingTaskIds = tasks.map(StructuredTask::id),
        )
        if (refreshMode == TaskListRefreshMode.FULL_REBUILD) {
            listModel.clear()
            tasks.forEach { listModel.addElement(it) }
            return refreshMode
        }
        tasks.forEachIndexed { index, task ->
            if (listModel[index] != task) {
                listModel[index] = task
            }
        }
        return refreshMode
    }

    private fun restoreSelection(previousSelection: String?, tasks: List<StructuredTask>) {
        if (previousSelection == null) {
            return
        }
        val index = tasks.indexOfFirst { it.id == previousSelection }
        if (index >= 0) {
            tasksList.selectedIndex = index
        } else {
            tasksList.clearSelection()
        }
    }

    private fun currentTaskIdsInView(): List<String> {
        return (0 until listModel.size()).map { index -> listModel[index].id }
    }

    private fun buildHeader(): JPanel {
        val left = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(headerTitleLabel, BorderLayout.NORTH)
            add(headerSummaryLabel, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(left, BorderLayout.CENTER)
            add(headerRefreshedLabel, BorderLayout.EAST)
        }
    }

    private fun buildControls(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(openWorkflowChatButton)
            add(secondaryActionsButton)
            add(editVerificationResultButton)
            add(editDependsOnButton)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(row, BorderLayout.CENTER)
        }
    }

    private fun createIconActionButton(onClick: () -> Unit): JButton {
        return JButton().apply {
            isEnabled = false
            isFocusable = false
            addActionListener { onClick() }
            SpecUiStyle.styleIconActionButton(this, size = 24, arc = 12)
        }
    }

    private fun applyActionButtonPresentation() {
        updateExecuteButtonPresentation(tasksList.selectedValue)
        updateOpenWorkflowChatButtonPresentation(tasksList.selectedValue)
        updateSecondaryActionsButtonPresentation(tasksList.selectedValue)
        updateVerificationButtonPresentation(tasksList.selectedValue)
        updateDependsOnButtonPresentation(tasksList.selectedValue)
    }

    private fun updateHeader() {
        val tasks = currentTasksById.values.toList()
        if (tasks.isEmpty()) {
            headerSummaryLabel.text = SpecCodingBundle.message("spec.toolwindow.tasks.summary.none")
        } else {
            val total = tasks.size
            val completed = tasks.count { it.status == TaskStatus.COMPLETED }
            val blocked = tasks.count { it.status == TaskStatus.BLOCKED }
            headerSummaryLabel.text = SpecCodingBundle.message(
                "spec.toolwindow.tasks.summary",
                total,
                completed,
                blocked,
            )
        }

        val refreshed = lastRefreshedAtMillis
        headerRefreshedLabel.text = refreshed?.let { millis ->
            REFRESHED_AT_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }.orEmpty()
    }

    private fun updateControlsForSelection() {
        val selected = tasksList.selectedValue
        if (selected == null) {
            updateExecuteButtonPresentation(null)
            updateOpenWorkflowChatButtonPresentation(null)
            updateSecondaryActionsButtonPresentation(null)
            updateVerificationButtonPresentation(null)
            updateDependsOnButtonPresentation(null)
            return
        }

        updateExecuteButtonPresentation(selected)
        updateOpenWorkflowChatButtonPresentation(selected)
        updateSecondaryActionsButtonPresentation(selected)
        updateVerificationButtonPresentation(selected)
        updateDependsOnButtonPresentation(selected)
    }

    private fun updateExecuteButtonPresentation(selected: StructuredTask?) {
        val taskId = selected?.id.orEmpty()
        val progress = selected?.let(::executionPresentation)
        val progressPhase = progress?.phase
        val presentation = when {
            selected == null -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.PENDING),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.unavailable"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.none"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.unavailable"),
            )

            progressPhase == ExecutionLivePhase.WAITING_CONFIRMATION -> waitingCompletionPresentation(taskId)

            progressPhase == ExecutionLivePhase.CANCELLING -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Close,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelling.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelling"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelling.tooltip", taskId),
            )

            progressPhase != null || selected.displayStatus == TaskStatus.IN_PROGRESS -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Close,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop"),
                enabled = true,
            )

            selected.displayStatus == TaskStatus.PENDING -> {
                val blockedReason = selected.executionBlockedReason(retry = false)
                SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.PENDING),
                    tooltip = blockedReason
                        ?: SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", taskId),
                    accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start"),
                    enabled = blockedReason == null,
                    disabledReason = blockedReason,
                )
            }

            selected.displayStatus == TaskStatus.BLOCKED -> {
                val blockedReason = selected.executionBlockedReason(retry = true)
                SpecIconActionPresentation(
                    icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.BLOCKED),
                    tooltip = blockedReason
                        ?: SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume.tooltip", taskId),
                    accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume"),
                    enabled = blockedReason == null,
                    disabledReason = blockedReason,
                )
            }

            selected.displayStatus == TaskStatus.COMPLETED -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.COMPLETED),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.done.tooltip", taskId),
            )

            else -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.taskPrimaryAction(TaskStatus.CANCELLED),
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.execute.cancelled.tooltip", taskId),
            )
        }
        SpecUiStyle.applyIconActionPresentation(executeTaskButton, presentation)
    }

    private fun updateOpenWorkflowChatButtonPresentation(selected: StructuredTask?) {
        val workflowId = currentWorkflowId?.trim().orEmpty()
        val taskId = selected?.id.orEmpty()
        val disabledReason = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.disabled")
            workflowId.isBlank() -> SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.disabled.noWorkflow")
            else -> null
        }
        SpecUiStyle.applyIconActionPresentation(
            button = openWorkflowChatButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.OpenToolWindow,
                tooltip = disabledReason ?: SpecCodingBundle.message("spec.toolwindow.tasks.chat.open.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.chat.open"),
                enabled = disabledReason == null,
                disabledReason = disabledReason,
            ),
        )
    }

    private fun updateVerificationButtonPresentation(selected: StructuredTask?) {
        val hasVerification = selected?.verificationResult != null
        val taskId = selected?.id.orEmpty()
        val presentation = when {
            selected == null -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.VerificationResult,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.unavailable"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.verification.button"),
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.verification.unavailable"),
            )

            selected.status == TaskStatus.CANCELLED -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.VerificationResult,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit.tooltip", taskId),
                accessibleName = if (hasVerification) {
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit")
                } else {
                    SpecCodingBundle.message("spec.toolwindow.tasks.verification.button")
                },
                enabled = false,
                disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.verification.cancelled", taskId),
            )

            hasVerification -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.VerificationResult,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.verification.edit"),
                enabled = true,
            )

            else -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.VerificationResult,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.verification.record.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.verification.button"),
                enabled = true,
            )
        }
        SpecUiStyle.applyIconActionPresentation(editVerificationResultButton, presentation)
    }

    private fun updateSecondaryActionsButtonPresentation(selected: StructuredTask?) {
        val taskId = selected?.id.orEmpty()
        val actions = selected?.let(::buildSecondaryActions).orEmpty()
        val progress = selected?.let(::executionPresentation)
        val disabledReason = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.secondary.unavailable")
            progress?.phase == ExecutionLivePhase.WAITING_CONFIRMATION -> SpecCodingBundle.message(
                "spec.toolwindow.tasks.secondary.waitingConfirmation",
                taskId,
            )

            progress?.phase == ExecutionLivePhase.CANCELLING -> SpecCodingBundle.message(
                "spec.toolwindow.tasks.secondary.cancelling",
                taskId,
            )

            actions.isEmpty() && progress != null -> SpecCodingBundle.message("spec.toolwindow.tasks.secondary.inProgress", taskId)
            actions.isEmpty() -> SpecCodingBundle.message("spec.toolwindow.tasks.secondary.none", taskId)
            else -> null
        }
        val tooltip = if (disabledReason != null) {
            disabledReason
        } else {
            SpecCodingBundle.message("spec.toolwindow.tasks.secondary.tooltip", taskId)
        }
        SpecUiStyle.applyIconActionPresentation(
            button = secondaryActionsButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Overflow,
                tooltip = tooltip,
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.button"),
                enabled = actions.isNotEmpty(),
                disabledReason = disabledReason,
            ),
        )
    }

    private fun updateDependsOnButtonPresentation(selected: StructuredTask?) {
        val taskId = selected?.id.orEmpty()
        val disabledReason = when {
            selected == null -> SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.unavailable")
            selected.status == TaskStatus.COMPLETED || selected.status == TaskStatus.CANCELLED -> {
                SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.locked", taskId)
            }
            else -> null
        }
        SpecUiStyle.applyIconActionPresentation(
            button = editDependsOnButton,
            presentation = SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Edit,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.dependsOn.edit"),
                enabled = disabledReason == null,
                disabledReason = disabledReason,
            ),
        )
    }

    private fun waitingCompletionPresentation(taskId: String): SpecIconActionPresentation {
        return SpecIconActionPresentation(
            icon = SpecWorkflowIcons.WaitingComplete,
            tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete.tooltip", taskId),
            accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.complete"),
            enabled = true,
        )
    }

    private fun handleExecuteTask() {
        requestExecutionForSelection()
    }

    private fun handleOpenWorkflowChat() {
        val selectedTask = tasksList.selectedValue ?: return
        val workflowId = currentWorkflowId?.trim()?.ifBlank { null } ?: return
        onOpenWorkflowChat(workflowId, selectedTask.id)
    }

    private fun handleTaskListClick(event: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(event)) {
            return
        }
        val index = tasksList.locationToIndex(event.point)
        if (index < 0) {
            return
        }
        val cellBounds = tasksList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(event.point)) {
            return
        }
        tasksList.selectedIndex = index
        val action = resolveTaskRowPrimaryActionAt(event.point) ?: return
        if (!action.presentation.enabled) {
            return
        }
        if (performTaskRowPrimaryAction(action.taskId)) {
            event.consume()
        }
    }

    private fun updateTaskListCursor(point: Point) {
        val action = resolveTaskRowPrimaryActionAt(point)
        tasksList.cursor = if (action?.presentation?.enabled == true) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun resolveTaskRowPrimaryActionAt(point: Point): TaskRowPrimaryAction? {
        val index = tasksList.locationToIndex(point)
        if (index < 0) {
            return null
        }
        val cellBounds = tasksList.getCellBounds(index, index) ?: return null
        if (!cellBounds.contains(point)) {
            return null
        }
        val task = listModel.getElementAt(index)
        val action = resolveTaskRowPrimaryAction(task) ?: return null
        val actionBounds = taskCellRenderer.resolvePrimaryActionBounds(
            list = tasksList,
            value = task,
            index = index,
            isSelected = tasksList.selectedIndex == index,
        ) ?: return null
        return action.takeIf { actionBounds.contains(point) }
    }

    private fun resolveTaskRowPrimaryAction(task: StructuredTask): TaskRowPrimaryAction? {
        val taskId = task.id
        val progress = executionPresentation(task)
        val progressPhase = progress?.phase
        val presentation = when {
            progressPhase == ExecutionLivePhase.WAITING_CONFIRMATION -> waitingCompletionPresentation(taskId)
            progressPhase == ExecutionLivePhase.CANCELLING -> null
            progressPhase != null || task.displayStatus == TaskStatus.IN_PROGRESS -> SpecIconActionPresentation(
                icon = SpecWorkflowIcons.Close,
                tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop.tooltip", taskId),
                accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.stop"),
                enabled = true,
            )

            task.displayStatus == TaskStatus.PENDING -> {
                val blockedReason = task.executionBlockedReason(retry = false)
                if (blockedReason != null) {
                    null
                } else {
                    SpecIconActionPresentation(
                        icon = SpecWorkflowIcons.TaskExecute,
                        tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start.tooltip", taskId),
                        accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.start"),
                        enabled = true,
                    )
                }
            }

            task.displayStatus == TaskStatus.BLOCKED -> {
                val blockedReason = task.executionBlockedReason(retry = true)
                if (blockedReason != null) {
                    null
                } else {
                    SpecIconActionPresentation(
                        icon = SpecWorkflowIcons.Refresh,
                        tooltip = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume.tooltip", taskId),
                        accessibleName = SpecCodingBundle.message("spec.toolwindow.tasks.execute.resume"),
                        enabled = true,
                    )
                }
            }

            else -> null
        }
        return presentation?.let { TaskRowPrimaryAction(taskId = taskId, presentation = it) }
    }

    private fun performTaskRowPrimaryAction(taskId: String): Boolean {
        if (!selectTask(taskId)) {
            return false
        }
        return requestExecutionForSelection()
    }

    private fun requestExecutionForSelection(): Boolean {
        val selectedTask = tasksList.selectedValue ?: return false
        val progressPhase = executionPresentation(selectedTask)?.phase
        when {
            progressPhase == ExecutionLivePhase.WAITING_CONFIRMATION -> requestCompletionWithRelatedFiles(selectedTask)
            progressPhase == ExecutionLivePhase.CANCELLING -> return false
            progressPhase != null || selectedTask.displayStatus == TaskStatus.IN_PROGRESS -> onCancelExecution(selectedTask.id)

            selectedTask.displayStatus == TaskStatus.PENDING -> {
                if (selectedTask.executionBlockedReason(retry = false) != null) {
                    return false
                }
                onExecuteTask(selectedTask.id, false)
            }

            selectedTask.displayStatus == TaskStatus.BLOCKED -> {
                if (selectedTask.executionBlockedReason(retry = true) != null) {
                    return false
                }
                onExecuteTask(selectedTask.id, true)
            }

            else -> return false
        }
        return true
    }

    private fun showSecondaryActionsMenu() {
        val selectedTask = tasksList.selectedValue ?: return
        val actions = buildSecondaryActions(selectedTask)
        if (actions.isEmpty()) {
            return
        }
        val menu = JPopupMenu()
        actions.forEach { action ->
            menu.add(
                JMenuItem(action.label).apply {
                    icon = action.icon
                    isEnabled = action.enabled
                    toolTipText = action.tooltip
                    accessibleContext.accessibleName = action.label
                    accessibleContext.accessibleDescription = action.tooltip
                    addActionListener {
                        if (action.enabled) {
                            action.perform()
                        }
                    }
                },
            )
        }
        menu.show(secondaryActionsButton, 0, secondaryActionsButton.height)
    }

    private fun requestCompletionWithRelatedFiles(selectedTask: StructuredTask) {
        val taskId = selectedTask.id
        val existing = selectedTask.relatedFiles
        val previousCursor = cursor
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        SpecUiStyle.setIconActionEnabled(
            executeTaskButton,
            enabled = false,
            disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
        )
        SpecUiStyle.setIconActionEnabled(
            secondaryActionsButton,
            enabled = false,
            disabledReason = SpecCodingBundle.message("spec.toolwindow.tasks.complete.progress"),
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            val suggestedRelatedFiles = runCatching {
                suggestRelatedFiles(taskId, existing)
            }.getOrDefault(existing)

            ApplicationManager.getApplication().invokeLater {
                cursor = previousCursor
                updateControlsForSelection()
                val result = SpecTaskCompletionDialogs.showCompletionConfirmation(
                    taskId = taskId,
                    initialRelatedFiles = suggestedRelatedFiles,
                    initialVerificationResult = selectedTask.verificationResult,
                ) ?: return@invokeLater
                onCompleteWithRelatedFiles(taskId, result.relatedFiles, result.verificationResult)
            }
        }
    }

    private fun buildSecondaryActions(task: StructuredTask): List<TaskSecondaryAction> {
        if (executionPresentation(task) != null) {
            return emptyList()
        }
        val cancellationBlockedReason = task.cancellationBlockedReason()
        return when (task.displayStatus) {
            TaskStatus.PENDING -> listOf(
                lifecycleSecondaryAction(
                    taskId = task.id,
                    targetStatus = TaskStatus.BLOCKED,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.block", task.id),
                    icon = SpecWorkflowIcons.Pause,
                ),
                lifecycleSecondaryAction(
                    taskId = task.id,
                    targetStatus = TaskStatus.CANCELLED,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.cancel", task.id),
                    icon = SpecWorkflowIcons.Close,
                    enabled = cancellationBlockedReason == null,
                    tooltip = cancellationBlockedReason
                        ?: SpecCodingBundle.message("spec.toolwindow.tasks.secondary.cancel", task.id),
                ),
            )

            TaskStatus.BLOCKED -> listOf(
                lifecycleSecondaryAction(
                    taskId = task.id,
                    targetStatus = TaskStatus.PENDING,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.reopen", task.id),
                    icon = SpecWorkflowIcons.Back,
                ),
                lifecycleSecondaryAction(
                    taskId = task.id,
                    targetStatus = TaskStatus.CANCELLED,
                    label = SpecCodingBundle.message("spec.toolwindow.tasks.secondary.cancel", task.id),
                    icon = SpecWorkflowIcons.Close,
                    enabled = cancellationBlockedReason == null,
                    tooltip = cancellationBlockedReason
                        ?: SpecCodingBundle.message("spec.toolwindow.tasks.secondary.cancel", task.id),
                ),
            )

            TaskStatus.IN_PROGRESS -> emptyList()

            TaskStatus.COMPLETED,
            TaskStatus.CANCELLED,
            -> emptyList()
        }
    }

    private fun lifecycleSecondaryAction(
        taskId: String,
        targetStatus: TaskStatus,
        label: String,
        icon: javax.swing.Icon,
        enabled: Boolean = true,
        tooltip: String = label,
    ): TaskSecondaryAction {
        return TaskSecondaryAction(
            actionId = TaskSecondaryActionId.LIFECYCLE,
            targetStatus = targetStatus,
            label = label,
            icon = icon,
            enabled = enabled,
            tooltip = tooltip,
            perform = { onTransitionStatus(taskId, targetStatus) },
        )
    }

    private fun StructuredTask.executionBlockedReason(retry: Boolean): String? {
        val constraint = SpecTaskDependencyRules.executionConstraint(this, currentTasksById.values)
        if (constraint.executable) {
            return null
        }
        val messageKey = if (retry) {
            "spec.toolwindow.tasks.execute.dependenciesBlocked.retry"
        } else {
            "spec.toolwindow.tasks.execute.dependenciesBlocked"
        }
        return SpecCodingBundle.message(
            messageKey,
            id,
            constraint.unmetDependencyIds.joinToString(", "),
        )
    }

    private fun StructuredTask.cancellationBlockedReason(): String? {
        val constraint = SpecTaskDependencyRules.cancellationConstraint(this, currentTasksById.values)
        if (constraint.cancellable) {
            return null
        }
        return SpecCodingBundle.message(
            "spec.toolwindow.tasks.secondary.cancel.blocked",
            id,
            constraint.blockingDependentTaskIds.joinToString(", "),
        )
    }

    private fun handleEditDependsOn() {
        val selectedTask = tasksList.selectedValue ?: return
        SpecTaskDependencySelectorPopup(
            selectedTask = selectedTask,
            tasks = currentTasksById.values,
            initialDependencies = selectedTask.dependsOn,
        ) { dependsOn ->
            if (dependsOn == selectedTask.dependsOn) {
                return@SpecTaskDependencySelectorPopup
            }
            onUpdateDependsOn(selectedTask.id, dependsOn)
        }.showUnderneathOf(editDependsOnButton)
    }

    private fun handleEditVerificationResult() {
        val selectedTask = tasksList.selectedValue ?: return
        val dialog = TaskVerificationResultDialog(
            taskId = selectedTask.id,
            initialResult = selectedTask.verificationResult,
            title = SpecCodingBundle.message("spec.toolwindow.tasks.verification.dialog.title", selectedTask.id),
        )
        if (!dialog.showAndGet()) {
            return
        }
        onUpdateVerificationResult(selectedTask.id, dialog.result)
    }

    private fun executionPresentation(task: StructuredTask): SpecWorkflowExecutionProgressPresentation? {
        return SpecWorkflowExecutionProgressUi.resolve(
            task = task,
            liveProgress = currentLiveProgressByTaskId[task.id],
        )
    }

    private fun taskMetaText(task: StructuredTask): String {
        val progress = executionPresentation(task)
        return if (progress != null) {
            SpecCodingBundle.message(
                "spec.toolwindow.tasks.row.meta.live",
                task.priority.name,
                progress.phaseLabel,
                progress.elapsedText,
                progress.lastActivityText,
                progress.activitySummaryText,
            )
        } else {
            SpecCodingBundle.message(
                "spec.toolwindow.tasks.row.meta.default",
                task.priority.name,
                task.dependsOn.size,
                task.relatedFiles.size,
            )
        }
    }

    private fun taskChipText(task: StructuredTask): String {
        return executionPresentation(task)?.chipLabel ?: task.displayStatus.name
    }

    private inner class TaskCellRenderer : javax.swing.ListCellRenderer<StructuredTask> {
        private val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        private val titleLabel = JBLabel()
        private val metaLabel = JBLabel()
        private val statusChipLabel = JBLabel()
        private val primaryActionLabel = JBLabel()
        private val primaryActionContainer = JPanel(BorderLayout())
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(6, 8, 6, 12)

            titleLabel.font = JBUI.Fonts.label().deriveFont(12f)
            titleLabel.foreground = TITLE_FG
            metaLabel.font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            metaLabel.foreground = META_FG

            statusChipLabel.isOpaque = true
            statusChipLabel.font = JBUI.Fonts.smallFont().deriveFont(10.5f)
            statusChipLabel.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)

            rightPanel.isOpaque = false
            primaryActionLabel.preferredSize = JBUI.size(16, 16)
            primaryActionLabel.minimumSize = primaryActionLabel.preferredSize
            primaryActionLabel.isOpaque = false
            primaryActionLabel.horizontalAlignment = SwingConstants.CENTER
            primaryActionLabel.verticalAlignment = SwingConstants.CENTER
            primaryActionContainer.isOpaque = false
            primaryActionContainer.border = JBUI.Borders.empty(2)
            primaryActionContainer.add(primaryActionLabel, BorderLayout.CENTER)
            rightPanel.add(primaryActionContainer)
            rightPanel.add(statusChipLabel)

            panel.add(
                JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(titleLabel, BorderLayout.NORTH)
                    add(metaLabel, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            panel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out StructuredTask>?,
            value: StructuredTask?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value == null) {
                titleLabel.text = ""
                metaLabel.text = ""
                statusChipLabel.text = ""
                primaryActionLabel.icon = null
                primaryActionLabel.toolTipText = null
                titleLabel.toolTipText = null
                metaLabel.toolTipText = null
                primaryActionContainer.isVisible = false
                return panel
            }
            val background = if (isSelected) SELECTED_BG else ROW_BG
            panel.background = background
            val fullTitleText = "${value.id}: ${value.title}"
            val fullMetaText = taskMetaText(value)
            statusChipLabel.text = taskChipText(value)
            applyPrimaryActionStyle(value, isSelected)
            applyChipStyle(statusChipLabel, value, isSelected)
            val availableTextWidth = availableTextWidth(list)
            titleLabel.text = clipTextToWidth(
                text = fullTitleText,
                fontMetrics = titleLabel.getFontMetrics(titleLabel.font),
                maxWidth = availableTextWidth,
            )
            titleLabel.toolTipText = fullTitleText.takeIf { it != titleLabel.text }
            metaLabel.text = clipTextToWidth(
                text = fullMetaText,
                fontMetrics = metaLabel.getFontMetrics(metaLabel.font),
                maxWidth = availableTextWidth,
            )
            metaLabel.toolTipText = fullMetaText.takeIf { it != metaLabel.text }
            return panel
        }

        fun renderSnapshotForTest(): Map<String, String> {
            return mapOf(
                "title" to titleLabel.text.orEmpty(),
                "meta" to metaLabel.text.orEmpty(),
                "titleTooltip" to titleLabel.toolTipText.orEmpty(),
                "metaTooltip" to metaLabel.toolTipText.orEmpty(),
                "chip" to statusChipLabel.text.orEmpty(),
            )
        }

        fun resolvePrimaryActionBounds(
            list: JList<out StructuredTask>,
            value: StructuredTask,
            index: Int,
            isSelected: Boolean,
        ): Rectangle? {
            val cellBounds = list.getCellBounds(index, index) ?: return null
            val rendererComponent = getListCellRendererComponent(list, value, index, isSelected, false)
            rendererComponent.setBounds(0, 0, cellBounds.width, cellBounds.height)
            layoutRecursively(rendererComponent)
            if (!primaryActionContainer.isVisible || primaryActionLabel.icon == null) {
                return null
            }
            val actionRect = SwingUtilities.convertRectangle(
                primaryActionContainer.parent,
                primaryActionContainer.bounds,
                rendererComponent,
            )
            return Rectangle(
                cellBounds.x + actionRect.x,
                cellBounds.y + actionRect.y,
                actionRect.width,
                actionRect.height,
            )
        }

        private fun applyPrimaryActionStyle(task: StructuredTask, isSelected: Boolean) {
            val action = resolveTaskRowPrimaryAction(task)
            primaryActionLabel.icon = action?.presentation?.icon
            primaryActionLabel.toolTipText = action?.presentation?.tooltip
            primaryActionContainer.isVisible = action != null
            if (action == null) {
                primaryActionContainer.isOpaque = false
                primaryActionContainer.border = JBUI.Borders.empty(2)
                return
            }
            val isWaitingConfirmation = executionPresentation(task)?.phase == ExecutionLivePhase.WAITING_CONFIRMATION
            if (isWaitingConfirmation) {
                primaryActionContainer.isOpaque = true
                primaryActionContainer.background = if (isSelected) WAITING_ACTION_BG_SELECTED else WAITING_ACTION_BG
                primaryActionContainer.border = BorderFactory.createCompoundBorder(
                    SpecUiStyle.roundedLineBorder(WAITING_ACTION_BORDER, JBUI.scale(10)),
                    BorderFactory.createEmptyBorder(2, 2, 2, 2),
                )
            } else {
                primaryActionContainer.isOpaque = false
                primaryActionContainer.border = JBUI.Borders.empty(2)
            }
        }

        private fun layoutRecursively(component: Component) {
            if (component !is Container) {
                return
            }
            component.doLayout()
            component.components.forEach(::layoutRecursively)
        }

        private fun availableTextWidth(list: JList<out StructuredTask>?): Int {
            val listWidth = list?.width?.takeIf { it > 0 } ?: DEFAULT_RENDER_WIDTH
            val insets = panel.insets
            val horizontalPadding = insets.left + insets.right + JBUI.scale(10)
            val rightWidth = rightPanel.preferredSize.width + (panel.layout as BorderLayout).hgap
            return (listWidth - horizontalPadding - rightWidth).coerceAtLeast(MIN_TEXT_WIDTH)
        }

        private fun clipTextToWidth(
            text: String,
            fontMetrics: java.awt.FontMetrics,
            maxWidth: Int,
        ): String {
            val normalized = text.trim()
            if (normalized.isEmpty() || maxWidth <= 0 || fontMetrics.stringWidth(normalized) <= maxWidth) {
                return normalized
            }
            if (fontMetrics.stringWidth(ELLIPSIS) >= maxWidth) {
                return ELLIPSIS
            }
            var low = 0
            var high = normalized.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                val candidate = normalized.take(mid).trimEnd() + ELLIPSIS
                if (fontMetrics.stringWidth(candidate) <= maxWidth) {
                    low = mid
                } else {
                    high = mid - 1
                }
            }
            return normalized.take(low).trimEnd() + ELLIPSIS
        }

        private fun applyChipStyle(label: JBLabel, task: StructuredTask, selected: Boolean) {
            val progress = executionPresentation(task)
            val paletteStatus = when (progress?.phase) {
                ExecutionLivePhase.WAITING_CONFIRMATION -> TaskStatus.PENDING
                ExecutionLivePhase.CANCELLING -> TaskStatus.BLOCKED
                else -> task.displayStatus
            }
            val palette = when (paletteStatus) {
                TaskStatus.PENDING -> ChipPalette(
                    bg = JBColor(Color(230, 239, 252), Color(72, 86, 104)),
                    fg = JBColor(Color(32, 89, 163), Color(195, 220, 255)),
                    border = JBColor(Color(185, 207, 238), Color(92, 106, 128)),
                )

                TaskStatus.IN_PROGRESS -> ChipPalette(
                    bg = JBColor(Color(229, 247, 238), Color(62, 92, 82)),
                    fg = JBColor(Color(22, 109, 78), Color(193, 239, 216)),
                    border = JBColor(Color(182, 225, 205), Color(80, 122, 108)),
                )

                TaskStatus.BLOCKED -> ChipPalette(
                    bg = JBColor(Color(255, 243, 224), Color(92, 74, 62)),
                    fg = JBColor(Color(166, 92, 0), Color(255, 222, 181)),
                    border = JBColor(Color(237, 203, 153), Color(120, 94, 78)),
                )

                TaskStatus.COMPLETED -> ChipPalette(
                    bg = JBColor(Color(232, 245, 233), Color(54, 82, 62)),
                    fg = JBColor(Color(24, 111, 41), Color(186, 232, 205)),
                    border = JBColor(Color(176, 219, 188), Color(74, 108, 84)),
                )

                TaskStatus.CANCELLED -> ChipPalette(
                    bg = JBColor(Color(245, 245, 245), Color(74, 79, 86)),
                    fg = JBColor(Color(102, 102, 102), Color(208, 214, 222)),
                    border = JBColor(Color(214, 214, 214), Color(92, 98, 108)),
                )
            }
            label.background = if (selected) palette.bg.darker() else palette.bg
            label.foreground = palette.fg
            label.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(palette.border, JBUI.scale(10)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6),
            )
        }

    }

    private enum class TaskSecondaryActionId {
        LIFECYCLE,
        STOP_EXECUTION,
    }

    private data class TaskSecondaryAction(
        val actionId: TaskSecondaryActionId,
        val targetStatus: TaskStatus?,
        val label: String,
        val icon: javax.swing.Icon,
        val enabled: Boolean,
        val tooltip: String,
        val perform: () -> Unit,
    )

    private data class TaskRowPrimaryAction(
        val taskId: String,
        val presentation: SpecIconActionPresentation,
    )

    private data class ChipPalette(
        val bg: Color,
        val fg: Color,
        val border: Color,
    )

    companion object {
        private val EMPTY_LIST_HEIGHT = JBUI.scale(72)
        private val HEADER_FG = JBColor(Color(35, 40, 47), Color(222, 226, 232))
        private val HEADER_SECONDARY_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val REFRESHED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val TITLE_FG = JBColor(Color(30, 36, 44), Color(225, 229, 235))
        private val META_FG = JBColor(Color(86, 96, 110), Color(175, 182, 190))
        private val ROW_BG = JBColor(Color(255, 255, 255), Color(47, 51, 56))
        private val SELECTED_BG = JBColor(Color(231, 241, 255), Color(59, 77, 92))
        private val WAITING_ACTION_BG = JBColor(Color(236, 246, 238), Color(57, 78, 64))
        private val WAITING_ACTION_BG_SELECTED = JBColor(Color(221, 238, 225), Color(67, 90, 73))
        private val WAITING_ACTION_BORDER = JBColor(Color(176, 219, 188), Color(83, 112, 91))
        private const val DEFAULT_RENDER_WIDTH = 360
        private const val MIN_TEXT_WIDTH = 72
        private const val ELLIPSIS = "..."

        internal fun resolveTaskListRefreshMode(
            existingTaskIds: List<String>,
            incomingTaskIds: List<String>,
        ): TaskListRefreshMode {
            return if (existingTaskIds == incomingTaskIds) {
                TaskListRefreshMode.IN_PLACE_UPDATE
            } else {
                TaskListRefreshMode.FULL_REBUILD
            }
        }
    }
}
