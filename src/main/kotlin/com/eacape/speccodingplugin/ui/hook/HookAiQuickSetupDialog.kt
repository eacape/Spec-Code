package com.eacape.speccodingplugin.ui.hook

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.ModelInfo
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel

class HookAiQuickSetupDialog(
    providers: List<String>,
    private val modelsByProvider: Map<String, List<ModelInfo>>,
    preferredProviderId: String?,
    preferredModelId: String?,
) : DialogWrapper(true) {

    private val inputArea = JBTextArea(8, 62)
    private val providerCombo = ComboBox<String>()
    private val modelCombo = ComboBox<ModelChoice>()

    var promptText: String = ""
        private set

    var selectedProviderId: String? = null
        private set

    var selectedModelId: String? = null
        private set

    init {
        title = SpecCodingBundle.message("hook.ai.dialog.title")
        val availableProviders = providers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        availableProviders.forEach { providerCombo.addItem(it) }
        providerCombo.renderer = SimpleListCellRenderer.create<String> { label, value, _ ->
            label.text = value?.let(::providerDisplayName)?.let(::toUiLowercase).orEmpty()
        }
        modelCombo.renderer = SimpleListCellRenderer.create<ModelChoice> { label, value, _ ->
            label.text = value?.label?.let(::toUiLowercase).orEmpty()
        }
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = providerCombo,
            minWidth = JBUI.scale(72),
            maxWidth = JBUI.scale(180),
            height = providerCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = modelCombo,
            minWidth = JBUI.scale(96),
            maxWidth = JBUI.scale(320),
            height = modelCombo.preferredSize.height.takeIf { it > 0 } ?: JBUI.scale(24),
        )
        providerCombo.addActionListener { refreshModelOptions(preferredModelId) }

        val initialProvider = availableProviders.firstOrNull { it == preferredProviderId }
            ?: availableProviders.firstOrNull()
        providerCombo.selectedItem = initialProvider
        refreshModelOptions(preferredModelId)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8)))
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(280))

        val guidance = JBLabel(
            "<html>${SpecCodingBundle.message("hook.ai.dialog.message")}</html>"
        )
        guidance.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        panel.add(guidance, BorderLayout.NORTH)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        controls.isOpaque = false
        controls.add(JBLabel(SpecCodingBundle.message("hook.ai.dialog.provider")))
        controls.add(providerCombo)
        controls.add(JBLabel(SpecCodingBundle.message("hook.ai.dialog.model")))
        controls.add(modelCombo)

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.emptyText.text = SpecCodingBundle.message("hook.ai.dialog.placeholder")

        val center = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(8)))
        center.isOpaque = false
        center.add(controls, BorderLayout.NORTH)
        center.add(JBScrollPane(inputArea), BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (inputArea.text.trim().isEmpty()) {
            return ValidationInfo(
                SpecCodingBundle.message("hook.ai.dialog.validation.promptRequired"),
                inputArea,
            )
        }
        return null
    }

    override fun doOKAction() {
        promptText = inputArea.text.trim()
        selectedProviderId = (providerCombo.selectedItem as? String)?.trim()?.ifBlank { null }
        selectedModelId = (modelCombo.selectedItem as? ModelChoice)?.id
        super.doOKAction()
    }

    private fun refreshModelOptions(preferredModelId: String?) {
        val providerId = providerCombo.selectedItem as? String
        val models = providerId
            ?.let { modelsByProvider[it].orEmpty() }
            .orEmpty()
        val previousSelectedModelId = (modelCombo.selectedItem as? ModelChoice)?.id
        modelCombo.removeAllItems()

        val auto = ModelChoice(
            id = null,
            label = SpecCodingBundle.message("hook.ai.dialog.model.auto"),
        )
        modelCombo.addItem(auto)
        models.forEach { model ->
            modelCombo.addItem(ModelChoice(id = model.id, label = model.name))
        }

        val selectedId = previousSelectedModelId
            ?: preferredModelId?.trim()?.ifBlank { null }
        val selected = (0 until modelCombo.itemCount)
            .asSequence()
            .mapNotNull { index -> modelCombo.getItemAt(index) }
            .firstOrNull { it.id == selectedId }
            ?: auto
        modelCombo.selectedItem = selected
    }

    private fun providerDisplayName(providerId: String): String {
        return when (providerId) {
            ClaudeCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.claudeCli")
            CodexCliLlmProvider.ID -> SpecCodingBundle.message("statusbar.modelSelector.provider.codexCli")
            else -> providerId.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }
    }

    private fun toUiLowercase(value: String): String = value.lowercase(Locale.ROOT)

    private data class ModelChoice(
        val id: String?,
        val label: String,
    )
}

