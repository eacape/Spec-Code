package com.eacape.speccodingplugin.ui.worktree

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.eacape.speccodingplugin.worktree.WorktreeManager
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeMergeResult
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JSplitPane

class WorktreePanel(
    private val project: Project,
    private val listWorkflows: () -> List<String> = {
        SpecEngine.getInstance(project).listWorkflows()
    },
    private val loadWorkflow: (workflowId: String) -> Result<SpecWorkflow> = { workflowId ->
        SpecEngine.getInstance(project).loadWorkflow(workflowId)
    },
    private val getActiveWorktree: () -> WorktreeBinding? = {
        WorktreeManager.getInstance(project).getActiveWorktree()
    },
    private val listBindings: () -> List<WorktreeBinding> = {
        WorktreeManager.getInstance(project).listBindings()
    },
    private val switchWorktreeAction: (worktreeId: String) -> Result<WorktreeBinding> = { worktreeId ->
        WorktreeManager.getInstance(project).switchWorktree(worktreeId)
    },
    private val mergeWorktreeAction: (worktreeId: String, targetBranch: String) -> Result<WorktreeMergeResult> = { worktreeId, targetBranch ->
        WorktreeManager.getInstance(project).mergeWorktree(worktreeId, targetBranch)
    },
    private val cleanupWorktreeAction: (worktreeId: String, force: Boolean) -> Result<Unit> = { worktreeId, force ->
        WorktreeManager.getInstance(project).cleanupWorktree(worktreeId, force)
    },
    private val suggestBaseBranch: () -> String = {
        WorktreeManager.getInstance(project).suggestBaseBranch()
    },
    private val createAndOpenWorktreeAction: (
        specTaskId: String,
        shortName: String,
        baseBranch: String,
    ) -> Result<WorktreeBinding> = { specTaskId, shortName, baseBranch ->
        WorktreeManager.getInstance(project).createAndOpenWorktree(specTaskId, shortName, baseBranch)
    },
    private val newWorktreeDialogFactory: (defaultBaseBranch: String) -> NewWorktreeDialog = { defaultBaseBranch ->
        NewWorktreeDialog(baseBranch = defaultBaseBranch)
    },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val listPanel = WorktreeListPanel(
        onWorktreeSelected = ::onWorktreeSelected,
        onCreateWorktree = ::onCreateWorktree,
        onSwitchWorktree = ::onSwitchWorktree,
        onMergeWorktree = ::onMergeWorktree,
        onCleanupWorktree = ::onCleanupWorktree,
    )
    private val detailPanel = WorktreeDetailPanel()

    private val titleLabel = JBLabel(SpecCodingBundle.message("worktree.panel.title"))
    private val statusLabel = JBLabel(SpecCodingBundle.message("worktree.status.count", 0))

    private var selectedWorktreeId: String? = null
    private var currentItems: List<WorktreeListItem> = emptyList()

    init {
        border = JBUI.Borders.empty(8)
        isOpaque = true
        background = PANEL_BG
        setupUI()
        subscribeToLocaleEvents()
        refreshWorktrees()
    }

    private fun setupUI() {
        add(buildHeader(), BorderLayout.NORTH)

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            createSectionContainer(listPanel),
            createSectionContainer(detailPanel),
        ).apply {
            dividerLocation = 320
            resizeWeight = 0.38
            dividerSize = JBUI.scale(8)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_SECTION_BG
            SpecUiStyle.applyChatLikeSpecDivider(
                splitPane = this,
                dividerSize = JBUI.scale(8),
            )
        }
        add(splitPane, BorderLayout.CENTER)
    }

    private fun buildHeader(): JPanel {
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG

        val statusChip = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = STATUS_CHIP_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = STATUS_CHIP_BORDER,
                arc = JBUI.scale(12),
                top = 2,
                left = 8,
                bottom = 2,
                right = 8,
            )
            add(statusLabel, BorderLayout.CENTER)
        }

        val headerCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = HEADER_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = HEADER_BORDER,
                arc = JBUI.scale(14),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(titleLabel, BorderLayout.WEST)
            add(statusChip, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            add(headerCard, BorderLayout.CENTER)
        }
    }

    private fun createSectionContainer(content: Component): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_SECTION_BORDER,
                arc = JBUI.scale(12),
                top = 3,
                left = 3,
                bottom = 3,
                right = 3,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    fun refreshWorktrees() {
        runBackground {
            val workflowsById = listWorkflows()
                .associateWith { id -> loadWorkflow(id).getOrNull() }

            val activeId = getActiveWorktree()?.id
            val items = listBindings()
                .map { binding ->
                    val workflow = workflowsById[binding.specTaskId]
                    WorktreeListItem(
                        id = binding.id,
                        specTaskId = binding.specTaskId,
                        specTitle = workflow?.title?.ifBlank { binding.specTaskId } ?: binding.specTaskId,
                        branchName = binding.branchName,
                        worktreePath = binding.worktreePath,
                        baseBranch = binding.baseBranch,
                        status = binding.status,
                        isActive = binding.id == activeId,
                        updatedAt = binding.updatedAt,
                        lastError = binding.lastError,
                    )
                }

            invokeLaterSafe {
                currentItems = items
                listPanel.updateWorktrees(items)
                statusLabel.text = SpecCodingBundle.message("worktree.status.count", items.size)

                val selected = selectedWorktreeId?.let { id -> items.firstOrNull { it.id == id } }
                    ?: items.firstOrNull()
                selectedWorktreeId = selected?.id
                listPanel.setSelectedWorktree(selected?.id)
                if (selected != null) {
                    detailPanel.updateWorktree(selected)
                } else {
                    detailPanel.showEmpty()
                }
            }
        }
    }

    private fun onWorktreeSelected(worktreeId: String) {
        selectedWorktreeId = worktreeId
        currentItems.firstOrNull { it.id == worktreeId }?.let(detailPanel::updateWorktree)
    }

    private fun onCreateWorktree() {
        runBackground {
            val defaultBaseBranch = suggestBaseBranch().trim().ifBlank { NewWorktreeDialog.DEFAULT_BASE_BRANCH }
            invokeLaterSafe {
                val dialog = newWorktreeDialogFactory(defaultBaseBranch)
                if (!dialog.showAndGet()) {
                    return@invokeLaterSafe
                }

                val specTaskId = dialog.resultSpecTaskId ?: return@invokeLaterSafe
                val shortName = dialog.resultShortName ?: return@invokeLaterSafe
                val baseBranch = dialog.resultBaseBranch ?: return@invokeLaterSafe

                runBackground {
                    createAndOpenWorktreeAction(specTaskId, shortName, baseBranch)
                        .onFailure { error -> logger.warn("Failed to create worktree", error) }
                    invokeLaterSafe { refreshWorktrees() }
                }
            }
        }
    }

    private fun onSwitchWorktree(worktreeId: String) {
        runBackground {
            switchWorktreeAction(worktreeId)
                .onFailure { error -> logger.warn("Failed to switch worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun onMergeWorktree(worktreeId: String) {
        val targetBranch = currentItems.firstOrNull { it.id == worktreeId }?.baseBranch ?: NewWorktreeDialog.DEFAULT_BASE_BRANCH
        runBackground {
            val mergeResult = mergeWorktreeAction(worktreeId, targetBranch)
            mergeResult.onFailure { error ->
                logger.warn("Failed to merge worktree: $worktreeId", error)
            }
            invokeLaterSafe {
                if (mergeResult.isSuccess) {
                    refreshWorktrees()
                } else {
                    val errorText = mergeResult.exceptionOrNull()?.message
                        ?: SpecCodingBundle.message("toolwindow.error.unknown")
                    applyTransientWorktreeError(worktreeId, errorText)
                }
            }
        }
    }

    private fun onCleanupWorktree(worktreeId: String) {
        runBackground {
            cleanupWorktreeAction(worktreeId, true)
                .onFailure { error -> logger.warn("Failed to cleanup worktree: $worktreeId", error) }
            invokeLaterSafe { refreshWorktrees() }
        }
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (runSynchronously) {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
            return
        }

        if (isDisposed) return
        invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    private fun runBackground(task: () -> Unit) {
        if (runSynchronously) {
            task()
            return
        }
        scope.launch(Dispatchers.IO) { task() }
    }

    private fun applyTransientWorktreeError(worktreeId: String, errorText: String) {
        val normalized = errorText.trim().ifBlank { SpecCodingBundle.message("toolwindow.error.unknown") }
        val updated = currentItems.firstOrNull { item -> item.id == worktreeId }?.copy(
            status = WorktreeStatus.ERROR,
            updatedAt = System.currentTimeMillis(),
            lastError = normalized,
        ) ?: return
        currentItems = currentItems.map { item ->
            if (item.id == worktreeId) updated else item
        }
        listPanel.updateWorktrees(currentItems)
        selectedWorktreeId = worktreeId
        listPanel.setSelectedWorktree(worktreeId)
        detailPanel.updateWorktree(updated)
    }

    private fun subscribeToLocaleEvents() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                    }
                }
            },
        )
    }

    private fun refreshLocalizedTexts() {
        titleLabel.text = SpecCodingBundle.message("worktree.panel.title")
        statusLabel.text = SpecCodingBundle.message("worktree.status.count", currentItems.size)
        listPanel.refreshLocalizedTexts()
        detailPanel.refreshLocalizedTexts()
    }

    internal fun selectedWorktreeIdForTest(): String? = selectedWorktreeId

    internal fun itemsForTest(): List<WorktreeListItem> = currentItems

    internal fun listPanelButtonStatesForTest(): Map<String, Boolean> = listPanel.buttonStatesForTest()

    internal fun detailStatusTextForTest(): String = detailPanel.displayedStatusForTest()

    internal fun detailSpecTaskIdTextForTest(): String = detailPanel.displayedSpecTaskIdForTest()

    internal fun detailLastErrorTextForTest(): String = detailPanel.displayedLastErrorForTest()

    internal fun isDetailEmptyForTest(): Boolean = detailPanel.isShowingEmptyForTest()

    internal fun setSelectedWorktreeForTest(worktreeId: String?) {
        selectedWorktreeId = worktreeId
        listPanel.setSelectedWorktree(worktreeId)
        if (worktreeId == null) {
            detailPanel.showEmpty()
            return
        }
        currentItems.firstOrNull { it.id == worktreeId }
            ?.let(detailPanel::updateWorktree)
            ?: detailPanel.showEmpty()
    }

    internal fun clickSwitchForTest() {
        listPanel.clickSwitchForTest()
    }

    internal fun clickMergeForTest() {
        listPanel.clickMergeForTest()
    }

    internal fun clickCleanupForTest() {
        listPanel.clickCleanupForTest()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    companion object {
        private val PANEL_BG = JBColor(Color(247, 250, 255), Color(52, 57, 65))
        private val HEADER_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val HEADER_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val STATUS_CHIP_BG = JBColor(Color(236, 244, 255), Color(66, 76, 91))
        private val STATUS_CHIP_BORDER = JBColor(Color(178, 198, 226), Color(99, 116, 140))
        private val STATUS_TEXT_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val PANEL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
    }
}
