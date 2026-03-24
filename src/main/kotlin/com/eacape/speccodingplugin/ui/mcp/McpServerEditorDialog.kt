package com.eacape.speccodingplugin.ui.mcp

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.mcp.McpServerConfig
import com.eacape.speccodingplugin.mcp.TransportType
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.spec.SpecUiStyle
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * MCP Server 编辑对话框
 * 支持添加和编辑 MCP Server 配置
 */
class McpServerEditorDialog(
    private val existing: McpServerConfig? = null,
    private val draft: McpServerConfig? = null,
    private val idExists: ((String) -> Boolean)? = null,
) : DialogWrapper(true) {

    private val idField = JBTextField()
    private val nameField = JBTextField()
    private val commandField = JBTextField()
    private val argsField = JBTextField()
    private val envArea = JBTextArea(4, 40)
    private val transportCombo = ComboBox(buildTransportOptions(existing, draft).toTypedArray())
    private val autoStartCheckBox = JBCheckBox(
        SpecCodingBundle.message("mcp.dialog.field.autoStart")
    )
    private val trustedCheckBox = JBCheckBox(
        SpecCodingBundle.message("mcp.dialog.field.trusted")
    )

    var result: McpServerConfig? = null
        private set

    init {
        title = if (existing != null) {
            SpecCodingBundle.message("mcp.dialog.editServer.title")
        } else {
            SpecCodingBundle.message("mcp.dialog.addServer.title")
        }
        init()
        applyVisualStyle()
        loadExisting()
        styleDialogButtons()
    }

    private fun loadExisting() {
        val source = existing ?: draft ?: return
        idField.text = source.id
        idField.isEditable = existing == null
        nameField.text = source.name
        commandField.text = source.command
        argsField.text = source.args.joinToString(", ")
        envArea.text = source.env.entries.joinToString("\n") { "${it.key}=${it.value}" }
        transportCombo.selectedItem = source.transport
        autoStartCheckBox.isSelected = source.autoStart
        trustedCheckBox.isSelected = source.trusted
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(12)))
        panel.preferredSize = Dimension(JBUI.scale(608), JBUI.scale(430))
        panel.border = JBUI.Borders.empty(10, 10, 8, 10)
        panel.background = DIALOG_BG

        val formPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(createSectionCard("mcp.dialog.section.basic", createBasicFormPanel()))
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(createSectionCard("mcp.dialog.section.runtime", createRuntimeFormPanel()))
        }
        panel.add(formPanel, BorderLayout.CENTER)
        return panel
    }

    private fun createBasicFormPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8, 4, 8)
        }

        idField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.id")
        addFormRow(panel, 0, SpecCodingBundle.message("mcp.dialog.field.id"), idField)

        nameField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.name")
        addFormRow(panel, 1, SpecCodingBundle.message("mcp.dialog.field.name"), nameField)

        commandField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.command")
        addFormRow(panel, 2, SpecCodingBundle.message("mcp.dialog.field.command"), commandField)

        argsField.emptyText.text = SpecCodingBundle.message("mcp.dialog.placeholder.args")
        addFormRow(panel, 3, SpecCodingBundle.message("mcp.dialog.field.args"), argsField)

        addFormRow(panel, 4, SpecCodingBundle.message("mcp.dialog.field.transport"), transportCombo)
        return panel
    }

    private fun createRuntimeFormPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8, 4, 8)
        }
        addEnvRow(panel, 0)

        val checkPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(autoStartCheckBox)
            add(Box.createHorizontalStrut(18))
            add(trustedCheckBox)
            add(Box.createHorizontalGlue())
        }
        panel.add(
            checkPanel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                gridwidth = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, 0)
            }
        )

        return panel
    }

    private fun addFormRow(panel: JPanel, row: Int, label: String, field: JComponent) {
        panel.add(
            createRowLabel(label),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = Insets(0, 0, JBUI.scale(10), JBUI.scale(12))
            }
        )
        panel.add(
            createFieldContainer(field),
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, JBUI.scale(10), 0)
            }
        )
    }

    private fun addEnvRow(panel: JPanel, row: Int) {
        panel.add(
            createRowLabel(SpecCodingBundle.message("mcp.dialog.field.env")),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 0.0
                anchor = GridBagConstraints.NORTHWEST
                fill = GridBagConstraints.NONE
                insets = Insets(2, 0, 0, JBUI.scale(10))
            }
        )

        envArea.font = commandField.font
        val scroll = JBScrollPane(envArea).apply {
            preferredSize = Dimension(0, JBUI.scale(96))
            minimumSize = Dimension(0, JBUI.scale(96))
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(INPUT_BORDER, JBUI.scale(10)),
                JBUI.Borders.empty(),
            )
            viewportBorder = JBUI.Borders.empty()
            viewport.background = INPUT_BG
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        envArea.border = JBUI.Borders.empty(6, 8, 6, 8)
        envArea.background = INPUT_BG
        envArea.foreground = INPUT_FG
        envArea.lineWrap = true
        envArea.wrapStyleWord = true

        panel.add(
            scroll,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.BOTH
                insets = Insets(0, 0, JBUI.scale(4), 0)
            }
        )
    }

    private fun createSectionCard(titleKey: String, content: JComponent): JPanel {
        val titleLabel = JBLabel(SpecCodingBundle.message(titleKey)).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = HEADER_FG
            border = JBUI.Borders.empty(0, 2, 0, 2)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = HEADER_BG
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, HEADER_BORDER)
            add(titleLabel, BorderLayout.WEST)
        }
        val contentHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(content, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CARD_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = CARD_BORDER,
                arc = JBUI.scale(12),
                top = 8,
                left = 10,
                bottom = 8,
                right = 10,
            )
            add(header, BorderLayout.NORTH)
            add(contentHost, BorderLayout.CENTER)
        }
    }

    private fun createRowLabel(label: String): JBLabel {
        return JBLabel(label).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = LABEL_FG
            preferredSize = Dimension(JBUI.scale(LABEL_WIDTH), JBUI.scale(28))
            minimumSize = preferredSize
            toolTipText = label
        }
    }

    private fun createFieldContainer(field: JComponent): JComponent {
        return when (field) {
            is JBTextField -> {
                field.background = INPUT_BG
                field.foreground = INPUT_FG
                field.border = BorderFactory.createCompoundBorder(
                    SpecUiStyle.roundedLineBorder(INPUT_BORDER, JBUI.scale(10)),
                    JBUI.Borders.empty(4, 8, 4, 8),
                )
                field.preferredSize = Dimension(0, JBUI.scale(30))
                field.minimumSize = Dimension(0, JBUI.scale(30))
                field
            }

            is ComboBox<*> -> {
                field.background = INPUT_BG
                field.foreground = INPUT_FG
                field.border = BorderFactory.createCompoundBorder(
                    SpecUiStyle.roundedLineBorder(INPUT_BORDER, JBUI.scale(10)),
                    JBUI.Borders.empty(2, 8, 2, 8),
                )
                ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
                    comboBox = field,
                    minWidth = JBUI.scale(96),
                    maxWidth = JBUI.scale(220),
                    height = JBUI.scale(30),
                )
                ComboBoxAutoWidthSupport.createLeftAlignedHost(field)
            }

            else -> field
        }
    }

    private fun applyVisualStyle() {
        autoStartCheckBox.isOpaque = false
        trustedCheckBox.isOpaque = false
        autoStartCheckBox.foreground = CHECKBOX_FG
        trustedCheckBox.foreground = CHECKBOX_FG
        autoStartCheckBox.font = JBUI.Fonts.smallFont()
        trustedCheckBox.font = JBUI.Fonts.smallFont()
        if (transportCombo.itemCount == 1 && transportCombo.getItemAt(0) == TransportType.STDIO) {
            transportCombo.isEnabled = false
            transportCombo.toolTipText = SpecCodingBundle.message("mcp.dialog.transport.stdioOnly")
        }
    }

    private fun styleDialogButtons() {
        getButton(okAction)?.let { styleDialogButton(it, primary = true) }
        getButton(cancelAction)?.let { styleDialogButton(it, primary = false) }
    }

    private fun styleDialogButton(button: JButton, primary: Boolean) {
        val bg = if (primary) BUTTON_PRIMARY_BG else BUTTON_BG
        val border = if (primary) BUTTON_PRIMARY_BORDER else BUTTON_BORDER
        val fg = if (primary) BUTTON_PRIMARY_FG else BUTTON_FG

        button.isFocusPainted = false
        button.isFocusable = false
        button.background = bg
        button.foreground = fg
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(border, JBUI.scale(10)),
            JBUI.Borders.empty(4, 14, 4, 14),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        button.preferredSize = Dimension(JBUI.scale(90), JBUI.scale(30))
    }

    override fun doValidate(): ValidationInfo? {
        if (idField.text.isBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.idRequired"), idField
            )
        }
        if (!idField.text.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.idFormat"), idField
            )
        }
        if (existing == null && idExists?.invoke(idField.text.trim()) == true) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.idExists"), idField
            )
        }
        if (nameField.text.isBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.nameRequired"), nameField
            )
        }
        if (commandField.text.isBlank()) {
            return ValidationInfo(
                SpecCodingBundle.message("mcp.dialog.validation.commandRequired"), commandField
            )
        }
        return null
    }

    override fun doOKAction() {
        val args = argsField.text
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val env = envArea.text
            .lines()
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }

        result = McpServerConfig(
            id = idField.text.trim(),
            name = nameField.text.trim(),
            command = commandField.text.trim(),
            args = args,
            env = env,
            transport = transportCombo.selectedItem as TransportType,
            autoStart = autoStartCheckBox.isSelected,
            trusted = trustedCheckBox.isSelected
        )

        super.doOKAction()
    }

    companion object {
        private const val LABEL_WIDTH = 156

        private fun buildTransportOptions(
            existing: McpServerConfig?,
            draft: McpServerConfig?,
        ): List<TransportType> {
            val options = mutableListOf(TransportType.STDIO)
            val currentTransport = existing?.transport ?: draft?.transport
            if (currentTransport == TransportType.SSE) {
                options += TransportType.SSE
            }
            return options
        }

        private val DIALOG_BG = JBColor(Color(247, 250, 255), Color(53, 58, 66))
        private val CARD_BG = JBColor(Color(250, 252, 255), Color(57, 62, 70))
        private val CARD_BORDER = JBColor(Color(204, 216, 236), Color(87, 98, 114))
        private val HEADER_BG = JBColor(Color(236, 245, 255), Color(68, 76, 89))
        private val HEADER_BORDER = JBColor(Color(198, 214, 236), Color(97, 110, 129))
        private val HEADER_FG = JBColor(Color(53, 74, 107), Color(213, 224, 242))
        private val LABEL_FG = JBColor(Color(60, 78, 110), Color(203, 214, 232))
        private val INPUT_BG = JBColor(Color(245, 249, 255), Color(63, 69, 80))
        private val INPUT_BORDER = JBColor(Color(184, 200, 226), Color(103, 115, 133))
        private val INPUT_FG = JBColor(Color(44, 67, 101), Color(217, 227, 242))
        private val CHECKBOX_FG = JBColor(Color(70, 88, 118), Color(191, 204, 224))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val BUTTON_PRIMARY_BG = JBColor(Color(213, 228, 250), Color(77, 98, 128))
        private val BUTTON_PRIMARY_BORDER = JBColor(Color(154, 180, 219), Color(116, 137, 169))
        private val BUTTON_PRIMARY_FG = JBColor(Color(37, 57, 89), Color(223, 232, 246))
    }
}
