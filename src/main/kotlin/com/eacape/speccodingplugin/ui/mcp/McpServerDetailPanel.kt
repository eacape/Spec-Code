package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.McpRuntimeLogEntry
import com.eacape.speccodingplugin.mcp.McpRuntimeLogLevel
import com.eacape.speccodingplugin.mcp.McpServer
import com.eacape.speccodingplugin.mcp.McpTool
import com.eacape.speccodingplugin.mcp.ServerStatus
import com.eacape.speccodingplugin.mcp.isBuiltInRuntimeSupported
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * MCP Server 详情面板（右侧）
 * 展示选中 Server 的信息、操作按钮和工具列表
 */
class McpServerDetailPanel(
    private val onStartServer: (String) -> Unit,
    private val onStopServer: (String) -> Unit,
    private val onRestartServer: (String) -> Unit,
    private val onEditServer: (String) -> Unit,
    private val onRefreshLogs: (String) -> Unit,
    private val onClearLogs: (String) -> Unit,
    private val onCopyLogs: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val prettyJson = Json { prettyPrint = true }

    private val serverNameLabel = JBLabel("")
    private val statusLabel = JBLabel("")
    private val commandLabel = JBLabel("")
    private val errorLabel = JBLabel("")
    private val startBtn = JButton()
    private val stopBtn = JButton()
    private val restartBtn = JButton()
    private val editBtn = JButton()

    private val toolsLabel = JBLabel("")
    private val toolListModel = DefaultListModel<McpTool>()
    private val toolList = JBList(toolListModel)
    private val toolDetailArea = JBTextArea()
    private val runtimeLogsLabel = JBLabel(SpecCodingBundle.message("mcp.server.logs"))
    private val runtimeLogArea = JBTextArea()
    private val refreshLogsBtn = JButton()
    private val clearLogsBtn = JButton()
    private val copyLogsBtn = JButton()

    private val emptyLabel = JBLabel(SpecCodingBundle.message("mcp.server.select"))

    private var currentServerId: String? = null
    private var currentServerStatus: ServerStatus = ServerStatus.STOPPED
    private var currentErrorText: String? = null
    private var currentRuntimeLogs: List<McpRuntimeLogEntry> = emptyList()

    init {
        border = JBUI.Borders.empty()
        isOpaque = true
        background = DETAIL_SECTION_BG
        bindActions()
        refreshActionButtonPresentation()
        listOf(startBtn, stopBtn, restartBtn, editBtn, refreshLogsBtn, clearLogsBtn, copyLogsBtn).forEach(::styleActionButton)
        showEmpty()
    }

    private fun bindActions() {
        startBtn.addActionListener { currentServerId?.let(onStartServer) }
        stopBtn.addActionListener { currentServerId?.let(onStopServer) }
        restartBtn.addActionListener { currentServerId?.let(onRestartServer) }
        editBtn.addActionListener { currentServerId?.let(onEditServer) }
        refreshLogsBtn.addActionListener { currentServerId?.let(onRefreshLogs) }
        clearLogsBtn.addActionListener { currentServerId?.let(onClearLogs) }
        copyLogsBtn.addActionListener {
            val text = runtimeLogArea.text
            if (text.isNotBlank()) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                currentServerId?.let(onCopyLogs)
            }
        }
    }

    private fun refreshActionButtonPresentation() {
        configureActionButton(startBtn, MCP_SERVER_START_ICON, "mcp.server.start")
        configureActionButton(stopBtn, MCP_SERVER_STOP_ICON, "mcp.server.stop")
        configureActionButton(restartBtn, MCP_SERVER_RESTART_ICON, "mcp.server.restart")
        configureActionButton(editBtn, MCP_SERVER_EDIT_ICON, "mcp.server.edit")
        configureActionButton(refreshLogsBtn, MCP_SERVER_LOG_REFRESH_ICON, "mcp.server.logs.refresh")
        configureActionButton(clearLogsBtn, MCP_SERVER_LOG_CLEAR_ICON, "mcp.server.logs.clear")
        configureActionButton(copyLogsBtn, MCP_SERVER_LOG_COPY_ICON, "mcp.server.logs.copy")
    }

    private fun configureActionButton(button: JButton, icon: Icon, tooltipKey: String) {
        val tooltip = SpecCodingBundle.message(tooltipKey)
        button.text = ""
        button.icon = icon
        button.disabledIcon = IconLoader.getDisabledIcon(icon)
        button.iconTextGap = 0
        button.toolTipText = tooltip
        button.putClientProperty(DEFAULT_TOOLTIP_CLIENT_KEY, tooltip)
        button.accessibleContext?.accessibleName = tooltip
        button.accessibleContext?.accessibleDescription = tooltip
    }

    private fun buildContentUI() {
        removeAll()
        layout = BorderLayout()
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 6, 0, 6)
                add(createHeaderPanel(), BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6)
                add(createToolsPanel(), BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
        revalidate()
        repaint()
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.background = HEADER_BG
        panel.border = SpecUiStyle.roundedCardBorder(
            lineColor = HEADER_BORDER,
            arc = JBUI.scale(12),
            top = 8,
            left = 10,
            bottom = 8,
            right = 10,
        )
        panel.isOpaque = true

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.isOpaque = false

        serverNameLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        statusLabel.font = JBUI.Fonts.smallFont()
        commandLabel.font = JBUI.Fonts.smallFont()
        errorLabel.font = JBUI.Fonts.smallFont()
        serverNameLabel.foreground = TITLE_FG
        commandLabel.foreground = COMMAND_FG
        errorLabel.foreground = ERROR_FG
        errorLabel.isVisible = false

        infoPanel.add(serverNameLabel)
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(statusLabel)
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(commandLabel)
        infoPanel.add(Box.createVerticalStrut(2))
        infoPanel.add(errorLabel)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        btnPanel.isOpaque = false
        btnPanel.add(startBtn)
        btnPanel.add(stopBtn)
        btnPanel.add(restartBtn)
        btnPanel.add(editBtn)

        panel.add(infoPanel, BorderLayout.CENTER)
        panel.add(btnPanel, BorderLayout.EAST)
        return panel
    }

    private fun createToolsPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        toolsLabel.border = JBUI.Borders.emptyBottom(4)
        toolsLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        toolsLabel.foreground = TITLE_FG

        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        toolList.cellRenderer = ToolCellRenderer()
        toolList.emptyText.text = SpecCodingBundle.message("mcp.server.noTools")
        toolList.background = TOOL_LIST_BG
        toolList.selectionBackground = TOOL_LIST_SELECTED_BG
        toolList.selectionForeground = TOOL_LIST_SELECTED_FG
        toolList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateToolDetail(toolList.selectedValue)
            }
        }

        val listScroll = JBScrollPane(toolList).apply {
            border = JBUI.Borders.empty()
            viewport.background = TOOL_LIST_BG
        }
        listScroll.preferredSize = Dimension(0, 200)

        toolDetailArea.isEditable = false
        toolDetailArea.font = JBUI.Fonts.smallFont()
        toolDetailArea.border = JBUI.Borders.empty(6)
        toolDetailArea.background = TOOL_DETAIL_BG
        toolDetailArea.foreground = TOOL_DETAIL_FG
        toolDetailArea.rows = 2
        val detailScroll = JBScrollPane(toolDetailArea).apply {
            border = JBUI.Borders.empty()
            viewport.background = TOOL_DETAIL_BG
            preferredSize = Dimension(0, JBUI.scale(56))
        }

        val splitPane = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            createSectionContainer(listScroll),
            createSectionContainer(detailScroll),
        )
        splitPane.dividerLocation = 200
        splitPane.dividerSize = JBUI.scale(6)
        splitPane.border = JBUI.Borders.empty()
        splitPane.isContinuousLayout = true
        splitPane.background = DETAIL_SECTION_BG
        SpecUiStyle.applyChatLikeSpecDivider(
            splitPane = splitPane,
            dividerSize = JBUI.scale(6),
        )

        panel.add(toolsLabel, BorderLayout.NORTH)
        panel.add(splitPane, BorderLayout.CENTER)
        panel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(4)
                add(createLogsPanel(), BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
        return panel
    }

    private fun createLogsPanel(): JPanel {
        runtimeLogsLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        runtimeLogsLabel.foreground = TITLE_FG
        runtimeLogsLabel.border = JBUI.Borders.empty(0, 0, 4, 0)

        runtimeLogArea.isEditable = false
        runtimeLogArea.font = JBUI.Fonts.smallFont()
        runtimeLogArea.border = JBUI.Borders.empty(6)
        runtimeLogArea.background = LOG_BG
        runtimeLogArea.foreground = TOOL_DETAIL_FG
        runtimeLogArea.lineWrap = true
        runtimeLogArea.wrapStyleWord = true
        runtimeLogArea.rows = 3

        val actionRow = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(refreshLogsBtn)
            add(clearLogsBtn)
            add(copyLogsBtn)
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(runtimeLogsLabel, BorderLayout.WEST)
            add(actionRow, BorderLayout.EAST)
        }

        val scroll = JBScrollPane(runtimeLogArea).apply {
            border = JBUI.Borders.empty()
            viewport.background = LOG_BG
            preferredSize = Dimension(0, JBUI.scale(88))
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(createSectionContainer(scroll), BorderLayout.CENTER)
        }
    }

    fun updateServer(server: McpServer, tools: List<McpTool>, runtimeLogs: List<McpRuntimeLogEntry>) {
        currentServerId = server.config.id
        currentServerStatus = server.status
        currentErrorText = server.error
        currentRuntimeLogs = runtimeLogs
        serverNameLabel.text = server.config.name
        commandLabel.text = SpecCodingBundle.message(
            "mcp.server.command",
            buildCommandPreview(server.config.command, server.config.args),
        )
        updateStatusLabel(server.status)
        updateErrorLabel(server.status, server.error)
        updateButtonStates(server.status, server.config.trusted, server.config.transport.isBuiltInRuntimeSupported())

        toolsLabel.text = SpecCodingBundle.message("mcp.server.tools", tools.size)
        toolListModel.clear()
        tools.forEach { toolListModel.addElement(it) }

        if (tools.isEmpty()) {
            toolDetailArea.text = if (server.status == ServerStatus.ERROR) {
                val message = server.error?.trim().orEmpty().ifBlank { SpecCodingBundle.message("common.unknown") }
                SpecCodingBundle.message("mcp.server.errorDetail", message)
            } else {
                SpecCodingBundle.message("mcp.server.noTools")
            }
        } else {
            toolDetailArea.text = ""
        }
        updateRuntimeLogArea(runtimeLogs)

        buildContentUI()
    }

    fun updateRuntimeLogs(runtimeLogs: List<McpRuntimeLogEntry>) {
        currentRuntimeLogs = runtimeLogs
        updateRuntimeLogArea(runtimeLogs)
    }

    fun showEmpty() {
        currentServerId = null
        currentServerStatus = ServerStatus.STOPPED
        currentErrorText = null
        currentRuntimeLogs = emptyList()
        removeAll()
        layout = BorderLayout()
        emptyLabel.text = SpecCodingBundle.message("mcp.server.select")
        emptyLabel.foreground = EMPTY_FG
        emptyLabel.font = JBUI.Fonts.smallFont()
        emptyLabel.horizontalAlignment = SwingConstants.CENTER
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = EMPTY_BG
                border = SpecUiStyle.roundedCardBorder(
                    lineColor = EMPTY_BORDER,
                    arc = JBUI.scale(12),
                    top = 6,
                    left = 6,
                    bottom = 6,
                    right = 6,
                )
                add(emptyLabel, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )
        revalidate()
        repaint()
    }

    fun refreshLocalizedTexts() {
        refreshActionButtonPresentation()
        styleActionButton(startBtn)
        styleActionButton(stopBtn)
        styleActionButton(restartBtn)
        styleActionButton(editBtn)
        styleActionButton(refreshLogsBtn)
        styleActionButton(clearLogsBtn)
        styleActionButton(copyLogsBtn)
        toolList.emptyText.text = SpecCodingBundle.message("mcp.server.noTools")
        runtimeLogsLabel.text = SpecCodingBundle.message("mcp.server.logs")
        toolsLabel.text = SpecCodingBundle.message("mcp.server.tools", toolListModel.size())
        if (toolListModel.isEmpty) {
            toolDetailArea.text = if (currentServerStatus == ServerStatus.ERROR) {
                val message = currentErrorText?.trim().orEmpty().ifBlank { SpecCodingBundle.message("common.unknown") }
                SpecCodingBundle.message("mcp.server.errorDetail", message)
            } else {
                SpecCodingBundle.message("mcp.server.noTools")
            }
        }
        updateRuntimeLogArea(currentRuntimeLogs)
        updateErrorLabel(currentServerStatus, currentErrorText)
        if (currentServerId == null) {
            emptyLabel.text = SpecCodingBundle.message("mcp.server.select")
        }
    }

    private fun updateStatusLabel(status: ServerStatus) {
        val (text, color) = when (status) {
            ServerStatus.RUNNING -> SpecCodingBundle.message("mcp.server.status.running") to
                    JBColor(Color(76, 175, 80), Color(76, 175, 80))
            ServerStatus.STOPPED -> SpecCodingBundle.message("mcp.server.status.stopped") to
                    JBColor.GRAY
            ServerStatus.STARTING -> SpecCodingBundle.message("mcp.server.status.starting") to
                    JBColor(Color(255, 152, 0), Color(255, 167, 38))
            ServerStatus.ERROR -> SpecCodingBundle.message("mcp.server.status.error") to
                    JBColor(Color(244, 67, 54), Color(239, 83, 80))
        }
        statusLabel.text = SpecCodingBundle.message("mcp.server.status.label", text)
        statusLabel.foreground = color
    }

    private fun updateButtonStates(status: ServerStatus, trusted: Boolean, builtInRuntimeSupported: Boolean) {
        startBtn.isEnabled = (status == ServerStatus.STOPPED || status == ServerStatus.ERROR) &&
            trusted &&
            builtInRuntimeSupported
        stopBtn.isEnabled = status == ServerStatus.RUNNING || status == ServerStatus.STARTING
        restartBtn.isEnabled = (status == ServerStatus.RUNNING || status == ServerStatus.ERROR) && builtInRuntimeSupported
        editBtn.isEnabled = status != ServerStatus.STARTING

        if (!builtInRuntimeSupported && (status == ServerStatus.STOPPED || status == ServerStatus.ERROR)) {
            startBtn.toolTipText = SpecCodingBundle.message("mcp.server.transportUnsupported")
        } else if (!trusted && (status == ServerStatus.STOPPED || status == ServerStatus.ERROR)) {
            startBtn.toolTipText = SpecCodingBundle.message("mcp.server.untrusted")
        } else {
            startBtn.toolTipText = startBtn.getClientProperty(DEFAULT_TOOLTIP_CLIENT_KEY) as? String
        }
        restartBtn.toolTipText = if (!builtInRuntimeSupported) {
            SpecCodingBundle.message("mcp.server.transportUnsupported")
        } else {
            restartBtn.getClientProperty(DEFAULT_TOOLTIP_CLIENT_KEY) as? String
        }
    }

    private fun updateErrorLabel(status: ServerStatus, error: String?) {
        if (status != ServerStatus.ERROR) {
            errorLabel.text = ""
            errorLabel.toolTipText = null
            errorLabel.isVisible = false
            return
        }
        val message = error?.trim().orEmpty().ifBlank { SpecCodingBundle.message("common.unknown") }
        errorLabel.text = SpecCodingBundle.message("mcp.server.errorDetail", message)
        errorLabel.toolTipText = message
        errorLabel.isVisible = true
    }

    private fun updateToolDetail(tool: McpTool?) {
        if (tool == null) {
            toolDetailArea.text = ""
            return
        }
        val sb = StringBuilder()
        sb.appendLine(SpecCodingBundle.message("mcp.tool.detail.name", tool.name))
        if (tool.description != null) {
            sb.appendLine(SpecCodingBundle.message("mcp.tool.detail.description", tool.description))
        }
        sb.appendLine()
        sb.appendLine(SpecCodingBundle.message("mcp.tool.detail.inputSchema"))
        try {
            sb.appendLine(prettyJson.encodeToString(
                JsonElement.serializer(), tool.inputSchema
            ))
        } catch (_: Exception) {
            sb.appendLine(tool.inputSchema.toString())
        }
        toolDetailArea.text = sb.toString()
        toolDetailArea.caretPosition = 0
    }

    private fun updateRuntimeLogArea(logs: List<McpRuntimeLogEntry>) {
        runtimeLogArea.text = if (logs.isEmpty()) {
            SpecCodingBundle.message("mcp.server.logs.empty")
        } else {
            logs.joinToString("\n") { entry ->
                val timestamp = LOG_TIME_FORMATTER.format(
                    Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault())
                )
                val level = when (entry.level) {
                    McpRuntimeLogLevel.INFO -> "INFO"
                    McpRuntimeLogLevel.WARN -> "WARN"
                    McpRuntimeLogLevel.ERROR -> "ERROR"
                    McpRuntimeLogLevel.STDERR -> "STDERR"
                }
                "[$timestamp] [$level] ${entry.message}"
            }
        }
        runtimeLogArea.caretPosition = runtimeLogArea.document.length
    }

    private class ToolCellRenderer : ListCellRenderer<McpTool> {
        private val panel = JPanel(BorderLayout(4, 2))
        private val nameLabel = JBLabel()
        private val descLabel = JBLabel()

        init {
            panel.border = JBUI.Borders.empty(4)
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 12f)
            descLabel.font = descLabel.font.deriveFont(Font.PLAIN, 11f)
            descLabel.foreground = TOOL_DESC_FG
            panel.add(nameLabel, BorderLayout.NORTH)
            panel.add(descLabel, BorderLayout.SOUTH)
        }

        override fun getListCellRendererComponent(
            list: JList<out McpTool>,
            value: McpTool?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value != null) {
                nameLabel.text = value.name
                val desc = value.description ?: ""
                descLabel.text = if (desc.length > 80) {
                    desc.take(80) + SpecCodingBundle.message("mcp.tool.detail.truncatedSuffix")
                } else {
                    desc
                }
            }
            panel.background = if (isSelected) {
                TOOL_LIST_SELECTED_BG
            } else {
                TOOL_LIST_BG
            }
            nameLabel.foreground = if (isSelected) {
                TOOL_LIST_SELECTED_FG
            } else {
                TOOL_LIST_FG
            }
            descLabel.foreground = if (isSelected) TOOL_DESC_FG_SELECTED else TOOL_DESC_FG
            return panel
        }
    }

    private fun styleActionButton(button: JButton) {
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.isOpaque = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.emptyInsets() else JBUI.insets(1, 4, 1, 4)
        button.foreground = BUTTON_FG
        SpecUiStyle.applyRoundRect(button, arc = 10)
        installActionButtonCursorTracking(button)
        if (iconOnly) {
            installActionIconButtonStateTracking(button)
            applyActionIconButtonVisualState(button)
        } else {
            button.background = BUTTON_BG
            button.border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(1, 6, 1, 6),
            )
        }
        button.preferredSize = if (iconOnly) {
            JBUI.size(JBUI.scale(28), JBUI.scale(28))
        } else {
            JBUI.size(
                maxOf(button.preferredSize.width, JBUI.scale(56)),
                JBUI.scale(28),
            )
        }
    }

    private fun installActionIconButtonStateTracking(button: JButton) {
        if (button.getClientProperty("mcp.detail.iconStyleInstalled") == true) return
        button.putClientProperty("mcp.detail.iconStyleInstalled", true)
        button.isRolloverEnabled = true
        button.addChangeListener { applyActionIconButtonVisualState(button) }
        button.addPropertyChangeListener("enabled") { applyActionIconButtonVisualState(button) }
    }

    private fun installActionButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("mcp.detail.cursorTrackingInstalled") == true) return
        button.putClientProperty("mcp.detail.cursorTrackingInstalled", true)
        updateActionButtonCursor(button)
        button.addPropertyChangeListener("enabled") { updateActionButtonCursor(button) }
    }

    private fun updateActionButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun applyActionIconButtonVisualState(button: JButton) {
        val model = button.model
        val background = when {
            !button.isEnabled -> ICON_BUTTON_BG_DISABLED
            model.isPressed || model.isSelected -> ICON_BUTTON_BG_ACTIVE
            model.isRollover -> ICON_BUTTON_BG_HOVER
            else -> ICON_BUTTON_BG
        }
        val borderColor = when {
            !button.isEnabled -> ICON_BUTTON_BORDER_DISABLED
            model.isPressed || model.isSelected -> ICON_BUTTON_BORDER_ACTIVE
            model.isRollover -> ICON_BUTTON_BORDER_HOVER
            else -> ICON_BUTTON_BORDER
        }
        val borderThickness = if (model.isPressed || model.isSelected) JBUI.scale(2) else 1
        button.background = background
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(borderColor, JBUI.scale(10), thickness = borderThickness),
            JBUI.Borders.empty(4),
        )
    }

    private fun createSectionContainer(content: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = SECTION_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = SECTION_BORDER,
                arc = JBUI.scale(10),
                top = 4,
                left = 4,
                bottom = 4,
                right = 4,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun buildCommandPreview(command: String, args: List<String>): String {
        if (args.isEmpty()) return command
        return (listOf(command) + args).joinToString(" ")
    }

    companion object {
        private const val DEFAULT_TOOLTIP_CLIENT_KEY = "mcp.detail.action.default.tooltip"
        private val MCP_SERVER_START_ICON = IconLoader.getIcon("/icons/mcp-thread-running.svg", McpServerDetailPanel::class.java)
        private val MCP_SERVER_STOP_ICON = IconLoader.getIcon("/icons/mcp-single-stopped-container.svg", McpServerDetailPanel::class.java)
        private val MCP_SERVER_RESTART_ICON = IconLoader.getIcon("/icons/mcp-restart-stop.svg", McpServerDetailPanel::class.java)
        private val MCP_SERVER_EDIT_ICON = IconLoader.getIcon("/icons/mcp-server-edit.svg", McpServerDetailPanel::class.java)
        private val MCP_SERVER_LOG_REFRESH_ICON = AllIcons.Actions.Refresh
        private val MCP_SERVER_LOG_CLEAR_ICON = AllIcons.Actions.GC
        private val MCP_SERVER_LOG_COPY_ICON = AllIcons.Actions.Copy
        private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val HEADER_BG = JBColor(Color(246, 249, 255), Color(57, 62, 70))
        private val HEADER_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val SECTION_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val ICON_BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val ICON_BUTTON_BG_HOVER = JBColor(Color(235, 246, 255), Color(77, 89, 106))
        private val ICON_BUTTON_BG_ACTIVE = JBColor(Color(227, 240, 255), Color(85, 99, 118))
        private val ICON_BUTTON_BG_DISABLED = JBColor(Color(247, 250, 254), Color(66, 72, 83))
        private val ICON_BUTTON_BORDER = JBColor(Color(178, 198, 226), Color(104, 116, 134))
        private val ICON_BUTTON_BORDER_HOVER = JBColor(Color(124, 167, 229), Color(124, 158, 205))
        private val ICON_BUTTON_BORDER_ACTIVE = JBColor(Color(89, 136, 208), Color(143, 182, 232))
        private val ICON_BUTTON_BORDER_DISABLED = JBColor(Color(198, 205, 216), Color(96, 106, 121))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val TITLE_FG = JBColor(Color(52, 72, 106), Color(201, 213, 232))
        private val COMMAND_FG = JBColor(Color(108, 122, 143), Color(162, 176, 197))
        private val DETAIL_SECTION_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val TOOL_LIST_BG = JBColor(Color(248, 251, 255), Color(56, 62, 72))
        private val TOOL_LIST_SELECTED_BG = JBColor(Color(226, 238, 255), Color(75, 91, 114))
        private val TOOL_LIST_FG = JBColor(Color(45, 62, 88), Color(213, 223, 238))
        private val TOOL_LIST_SELECTED_FG = JBColor(Color(35, 55, 86), Color(229, 237, 249))
        private val TOOL_DESC_FG = JBColor(Color(104, 120, 143), Color(168, 181, 202))
        private val TOOL_DESC_FG_SELECTED = JBColor(Color(86, 104, 129), Color(206, 219, 238))
        private val TOOL_DETAIL_BG = JBColor(Color(246, 249, 255), Color(58, 64, 74))
        private val LOG_BG = JBColor(Color(244, 248, 255), Color(54, 60, 70))
        private val TOOL_DETAIL_FG = JBColor(Color(68, 84, 109), Color(204, 216, 236))
        private val EMPTY_BG = JBColor(Color(247, 251, 255), Color(56, 62, 72))
        private val EMPTY_BORDER = JBColor(Color(204, 215, 233), Color(84, 92, 105))
        private val EMPTY_FG = JBColor(Color(106, 121, 141), Color(173, 187, 208))
        private val ERROR_FG = JBColor(Color(176, 60, 73), Color(236, 149, 161))
    }
}
