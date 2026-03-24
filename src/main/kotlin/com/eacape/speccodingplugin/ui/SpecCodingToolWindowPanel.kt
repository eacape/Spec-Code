package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.llm.LlmChunk
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class SpecCodingToolWindowPanel(
    private val project: Project,
) : JBPanel<SpecCodingToolWindowPanel>(BorderLayout()), Disposable {
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val providerComboBox = ComboBox(projectService.availableProviders().toTypedArray())
    private val promptComboBox = ComboBox<PromptTemplate>()
    private val statusLabel = JBLabel(SpecCodingBundle.message("toolwindow.status.ready"))
    private val conversationArea = JBTextArea()
    private val inputField = JBTextField()
    private val sendButton = JButton(SpecCodingBundle.message("toolwindow.send"))
    @Volatile
    private var _isDisposed = false

    init {
        border = JBUI.Borders.empty(12)

        val titleLabel = JBLabel(SpecCodingBundle.message("toolwindow.welcome"))
        titleLabel.font = titleLabel.font.deriveFont(16f)

        val descriptionLabel = JBLabel(SpecCodingBundle.message("toolwindow.description", project.name))
        descriptionLabel.foreground = JBColor.GRAY
        descriptionLabel.border = JBUI.Borders.emptyTop(8)

        val providerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        providerPanel.isOpaque = false
        providerPanel.border = JBUI.Borders.emptyTop(8)
        providerPanel.add(JBLabel(SpecCodingBundle.message("toolwindow.provider.label")))
        providerPanel.add(providerComboBox)
        providerPanel.add(JBLabel(SpecCodingBundle.message("toolwindow.prompt.label")))
        providerPanel.add(promptComboBox)
        statusLabel.foreground = JBColor.GRAY
        providerPanel.add(statusLabel)

        promptComboBox.isEnabled = false
        promptComboBox.renderer = PromptTemplateCellRenderer()
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = providerComboBox,
            minWidth = JBUI.scale(72),
            maxWidth = JBUI.scale(180),
            height = providerComboBox.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = promptComboBox,
            minWidth = JBUI.scale(100),
            maxWidth = JBUI.scale(260),
            height = promptComboBox.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        promptComboBox.addActionListener {
            val selected = promptComboBox.selectedItem as? PromptTemplate ?: return@addActionListener
            ApplicationManager.getApplication().executeOnPooledThread {
                projectService.switchActivePrompt(selected.id)
            }
        }

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel)
        headerPanel.add(descriptionLabel)
        headerPanel.add(providerPanel)

        setupConversationArea()
        val conversationScrollPane = JBScrollPane(conversationArea)
        conversationScrollPane.border = JBUI.Borders.emptyTop(12)

        val inputPanel = JPanel(BorderLayout())
        inputPanel.isOpaque = false
        inputPanel.border = JBUI.Borders.emptyTop(8)
        inputField.emptyText.text = SpecCodingBundle.message("toolwindow.input.placeholder")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        sendButton.addActionListener { sendCurrentInput() }
        inputField.addActionListener { sendCurrentInput() }

        loadPromptTemplatesAsync()
        appendSystemMessage(SpecCodingBundle.message("toolwindow.system.intro"))

        val content = JPanel(BorderLayout())
        content.isOpaque = false
        content.add(headerPanel, BorderLayout.NORTH)
        content.add(conversationScrollPane, BorderLayout.CENTER)
        content.add(inputPanel, BorderLayout.SOUTH)

        add(content, BorderLayout.CENTER)
    }

    private fun setupConversationArea() {
        conversationArea.isEditable = false
        conversationArea.lineWrap = true
        conversationArea.wrapStyleWord = true
        conversationArea.border = JBUI.Borders.empty(8)
    }

    private fun loadPromptTemplatesAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val templates = projectService.availablePrompts()
            val activePromptId = projectService.activePromptId()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || _isDisposed) {
                    return@invokeLater
                }

                promptComboBox.removeAllItems()
                templates.forEach { promptComboBox.addItem(it) }
                promptComboBox.selectedItem = templates.firstOrNull { it.id == activePromptId }
                promptComboBox.isEnabled = templates.isNotEmpty()
            }
        }
    }

    private fun sendCurrentInput() {
        val prompt = inputField.text.trim()
        if (prompt.isBlank() || project.isDisposed || _isDisposed) {
            return
        }

        val providerId = providerComboBox.selectedItem as? String
        appendRoleMessage(SpecCodingBundle.message("toolwindow.message.user"), prompt)
        inputField.text = ""

        setSendingState(true)
        appendAssistantPrefix()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runBlocking {
                    projectService.chat(providerId = providerId, userInput = prompt) { chunk ->
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed || _isDisposed) {
                                return@invokeLater
                            }
                            appendAssistantChunk(chunk)
                        }
                    }
                }
            } catch (error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    appendRoleMessage(
                        SpecCodingBundle.message("toolwindow.message.error"),
                        error.message ?: SpecCodingBundle.message("toolwindow.error.unknown"),
                    )
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || _isDisposed) {
                        return@invokeLater
                    }
                    setSendingState(false)
                }
            }
        }
    }

    private fun appendAssistantPrefix() {
        conversationArea.append(
            SpecCodingBundle.message(
                "toolwindow.message.prefix",
                SpecCodingBundle.message("toolwindow.message.assistant"),
            ),
        )
        scrollConversationToBottom()
    }

    private fun appendAssistantChunk(chunk: LlmChunk) {
        if (chunk.isLast) {
            conversationArea.append("\n\n")
        } else {
            conversationArea.append(chunk.delta)
        }
        scrollConversationToBottom()
    }

    private fun appendSystemMessage(content: String) {
        appendRoleMessage(SpecCodingBundle.message("toolwindow.message.system"), content)
    }

    private fun appendRoleMessage(role: String, content: String) {
        conversationArea.append(SpecCodingBundle.message("toolwindow.message.entry", role, content))
        conversationArea.append("\n\n")
        scrollConversationToBottom()
    }

    private fun scrollConversationToBottom() {
        conversationArea.caretPosition = conversationArea.document.length
    }

    private fun setSendingState(sending: Boolean) {
        sendButton.isEnabled = !sending
        inputField.isEnabled = !sending
        providerComboBox.isEnabled = !sending
        promptComboBox.isEnabled = !sending
        statusLabel.text = if (sending) {
            SpecCodingBundle.message("toolwindow.status.generating")
        } else {
            SpecCodingBundle.message("toolwindow.status.ready")
        }
    }

    override fun dispose() {
        _isDisposed = true
    }
}
