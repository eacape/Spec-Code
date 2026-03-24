package com.eacape.speccodingplugin.ui.history

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.ui.ChatToolWindowFactory
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.session.ConversationSession
import com.eacape.speccodingplugin.session.displayWorkflowChatSessionTitle
import com.eacape.speccodingplugin.session.SessionContextSnapshot
import com.eacape.speccodingplugin.session.SessionFilter
import com.eacape.speccodingplugin.session.SessionManager
import com.eacape.speccodingplugin.session.SessionSummary
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JPanel

class HistoryPanel(
    private val project: Project,
    private val searchSessions: (query: String, filter: SessionFilter, limit: Int) -> List<SessionSummary> = { query, filter, limit ->
        SessionManager.getInstance(project).searchSessions(query = query, filter = filter, limit = limit)
    },
    private val deleteSession: (sessionId: String) -> Result<Unit> = { sessionId ->
        SessionManager.getInstance(project).deleteSession(sessionId)
    },
    private val saveContextSnapshot: (
        sessionId: String,
        messageId: String?,
        title: String?,
        metadataJson: String?,
    ) -> Result<SessionContextSnapshot> = { sessionId, messageId, title, metadataJson ->
        SessionManager.getInstance(project).saveContextSnapshot(sessionId, messageId, title, metadataJson)
    },
    private val continueFromSnapshot: (snapshotId: String, branchName: String?) -> Result<ConversationSession> = { snapshotId, branchName ->
        SessionManager.getInstance(project).continueFromSnapshot(snapshotId, branchName)
    },
    private val forkSession: (sourceSessionId: String, fromMessageId: String?, branchName: String?) -> Result<ConversationSession> = { sourceSessionId, fromMessageId, branchName ->
        SessionManager.getInstance(project).forkSession(sourceSessionId, fromMessageId, branchName)
    },
    private val runSynchronously: Boolean = false,
) : JPanel(BorderLayout()), Disposable {

    private val logger = thisLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isDisposed = false

    private val searchField = JBTextField()
    private val filterCombo = JComboBox(SessionFilter.entries.toTypedArray())
    private val statusLabel = JBLabel("")

    private val listPanel = HistorySessionListPanel(
        onSessionSelected = ::onSessionSelected,
        onOpenSession = ::onOpenSession,
        onContinueSession = ::onContinueSession,
        onDeleteSession = ::onDeleteSession,
        onBranchSession = ::onBranchSession,
    )

    private var selectedSessionId: String? = null

    init {
        border = JBUI.Borders.empty(8)
        setupUi()
        refreshSessions()
    }

    private fun setupUi() {
        add(buildToolbar(), BorderLayout.NORTH)
        add(createSectionContainer(listPanel), BorderLayout.CENTER)
    }

    private fun buildToolbar(): JPanel {
        searchField.columns = 18
        searchField.emptyText.text = SpecCodingBundle.message("history.search.placeholder")
        searchField.preferredSize = JBUI.size(280, 30)
        searchField.minimumSize = JBUI.size(150, 30)
        searchField.addActionListener { refreshSessions() }

        filterCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = sessionFilterLabel(value as? SessionFilter ?: SessionFilter.ALL)
                return this
            }
        }
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = filterCombo,
            minWidth = JBUI.scale(70),
            maxWidth = JBUI.scale(140),
            height = JBUI.scale(30),
        )
        filterCombo.addActionListener { refreshSessions() }

        val searchLabel = JBLabel(SpecCodingBundle.message("history.search.label"))
        val filterLabel = JBLabel(SpecCodingBundle.message("history.filter.label"))
        styleToolbarLabel(searchLabel)
        styleToolbarLabel(filterLabel)

        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = STATUS_TEXT_FG
        val statusChip = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = STATUS_CHIP_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(STATUS_CHIP_BORDER, 1),
                JBUI.Borders.empty(3, 8),
            )
            add(statusLabel, BorderLayout.CENTER)
        }

        val controls = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        val constraints = GridBagConstraints().apply {
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 0, 0, 8)
        }

        constraints.gridx = 0
        constraints.weightx = 0.0
        controls.add(searchLabel, constraints)

        constraints.gridx = 1
        constraints.weightx = 1.0
        controls.add(searchField, constraints)

        constraints.gridx = 2
        constraints.weightx = 0.0
        controls.add(filterLabel, constraints)

        constraints.gridx = 3
        constraints.weightx = 0.0
        constraints.insets = JBUI.insets(0, 0, 0, 0)
        controls.add(filterCombo, constraints)

        val toolbarCard = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = true
            background = TOOLBAR_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TOOLBAR_BORDER, 1),
                JBUI.Borders.empty(8, 10),
            )
            add(controls, BorderLayout.CENTER)
            add(statusChip, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            add(toolbarCard, BorderLayout.CENTER)
        }
    }

    private fun styleToolbarLabel(label: JBLabel) {
        label.font = JBUI.Fonts.smallFont()
        label.foreground = TOOLBAR_LABEL_FG
    }

    private fun createSectionContainer(content: Component): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_SECTION_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PANEL_SECTION_BORDER, 1),
                JBUI.Borders.empty(2),
            )
            add(content, BorderLayout.CENTER)
        }
    }

    fun refreshSessions() {
        val query = searchField.text
        val filter = filterCombo.selectedItem as? SessionFilter ?: SessionFilter.ALL

        runBackground {
            val sessions = searchSessions(query, filter, 200)
            invokeLaterSafe {
                listPanel.updateSessions(sessions)
                statusLabel.text = buildStatusText(sessions.size, query, filter)

                val selected = selectedSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                    ?: sessions.firstOrNull()
                selectedSessionId = selected?.id
                listPanel.setSelectedSession(selected?.id)
            }
        }
    }

    private fun onSessionSelected(sessionId: String) {
        selectedSessionId = sessionId
    }

    private fun onOpenSession(sessionId: String) {
        onSessionSelected(sessionId)
        project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
            .onSessionOpenRequested(sessionId)
        focusChatTabOnly()
        statusLabel.text = SpecCodingBundle.message("history.status.opened")
    }

    private fun focusChatTabOnly() {
        runCatching {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.TOOL_WINDOW_ID)
                ?: return
            ChatToolWindowFactory.ensurePrimaryContents(project, toolWindow)
            if (!ChatToolWindowFactory.selectChatContent(toolWindow, project)) return
            toolWindow.activate(null)
        }
    }

    private fun onDeleteSession(sessionId: String) {
        runBackground {
            val result = deleteSession(sessionId)
            invokeLaterSafe {
                result.onSuccess {
                    if (selectedSessionId == sessionId) {
                        selectedSessionId = null
                    }
                    refreshSessions()
                }.onFailure { error ->
                    logger.warn("Failed to delete session: $sessionId", error)
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.deleteFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onBranchSession(sessionId: String) {
        runBackground {
            val result = forkSession(sessionId, null, null)
            invokeLaterSafe {
                result.onSuccess { branched ->
                    selectedSessionId = branched.id
                    refreshSessions()
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.branchCreated",
                        displayWorkflowChatSessionTitle(branched.title).orEmpty(),
                    )
                }.onFailure { error ->
                    logger.warn("Failed to branch session: $sessionId", error)
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.branchFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun onContinueSession(sessionId: String) {
        runBackground {
            runCatching {
                val snapshot = saveContextSnapshot(sessionId, null, null, null).getOrThrow()
                continueFromSnapshot(snapshot.id, null).getOrThrow()
            }.onSuccess { continued ->
                invokeLaterSafe {
                    selectedSessionId = continued.id
                    refreshSessions()
                    project.messageBus.syncPublisher(HistorySessionOpenListener.TOPIC)
                        .onSessionOpenRequested(continued.id)
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.continued",
                        displayWorkflowChatSessionTitle(continued.title).orEmpty(),
                    )
                }
            }.onFailure { error ->
                logger.warn("Failed to continue session from snapshot: $sessionId", error)
                invokeLaterSafe {
                    statusLabel.text = SpecCodingBundle.message(
                        "history.status.continueFailed",
                        error.message ?: SpecCodingBundle.message("common.unknown"),
                    )
                }
            }
        }
    }

    private fun buildStatusText(count: Int, query: String, filter: SessionFilter): String {
        val countText = SpecCodingBundle.message("history.status.count", count)
        val normalizedQuery = query.trim()
        val filterLabel = sessionFilterLabel(filter)
        return when {
            normalizedQuery.isBlank() && filter == SessionFilter.ALL -> countText
            normalizedQuery.isBlank() -> "$countText · $filterLabel"
            else -> "$countText · $filterLabel · \"$normalizedQuery\""
        }
    }

    private fun sessionFilterLabel(filter: SessionFilter): String {
        return when (filter) {
            SessionFilter.ALL -> SpecCodingBundle.message("history.filter.all")
            SessionFilter.SPEC -> SpecCodingBundle.message("history.filter.workflow")
            SessionFilter.VIBE -> SpecCodingBundle.message("history.filter.vibe")
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

    internal fun setSearchQueryForTest(query: String) {
        searchField.text = query
    }

    internal fun setFilterForTest(filter: SessionFilter) {
        filterCombo.selectedItem = filter
    }

    internal fun statusTextForTest(): String = statusLabel.text

    internal fun selectedSessionIdForTest(): String? = selectedSessionId

    internal fun selectedFilterLabelForTest(): String =
        sessionFilterLabel(filterCombo.selectedItem as? SessionFilter ?: SessionFilter.ALL)

    internal fun sessionsForTest(): List<SessionSummary> = listPanel.sessionsForTest()

    internal fun selectSessionForTest(sessionId: String?) {
        listPanel.setSelectedSession(sessionId)
        selectedSessionId = sessionId
    }

    internal fun clickOpenForTest() {
        listPanel.clickOpenForTest()
    }

    internal fun clickBranchForTest() {
        listPanel.clickBranchForTest()
    }

    internal fun clickContinueForTest() {
        listPanel.clickContinueForTest()
    }

    internal fun clickDeleteForTest() {
        listPanel.clickDeleteForTest()
    }

    override fun dispose() {
        isDisposed = true
        scope.cancel()
    }

    companion object {
        private val TOOLBAR_BG = JBColor(Color(248, 250, 253), Color(58, 63, 71))
        private val TOOLBAR_BORDER = JBColor(Color(214, 222, 236), Color(82, 90, 102))
        private val TOOLBAR_LABEL_FG = JBColor(Color(82, 92, 108), Color(171, 180, 194))
        private val STATUS_CHIP_BG = JBColor(Color(239, 245, 253), Color(64, 74, 88))
        private val STATUS_CHIP_BORDER = JBColor(Color(186, 201, 224), Color(96, 111, 131))
        private val STATUS_TEXT_FG = JBColor(Color(60, 76, 100), Color(194, 207, 225))
        private val PANEL_SECTION_BG = JBColor(Color(251, 252, 254), Color(50, 54, 61))
        private val PANEL_SECTION_BORDER = JBColor(Color(211, 218, 232), Color(79, 85, 96))
    }
}

