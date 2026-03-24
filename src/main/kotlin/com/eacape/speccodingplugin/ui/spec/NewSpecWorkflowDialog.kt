package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.spec.WorkflowTemplates
import com.intellij.openapi.util.text.StringUtil
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class NewSpecWorkflowDialog(
    workflowOptions: List<WorkflowOption> = emptyList(),
    defaultTemplate: WorkflowTemplate = WorkflowTemplate.FULL_SPEC,
) : DialogWrapper(true) {

    data class WorkflowOption(
        val workflowId: String,
        val title: String,
        val description: String = "",
    ) {
        override fun toString(): String {
            return SpecCodingBundle.message("spec.delta.workflow.option", title, workflowId)
        }
    }

    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea(4, 40)
    private val descriptionScrollPane = JScrollPane(descriptionArea).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        lockFixedHeight(
            component = this,
            height = JBUI.scale(DESCRIPTION_AREA_HEIGHT),
            preferredWidth = JBUI.scale(CONTENT_WIDTH),
        )
    }
    private val templateHelpArea = createReadOnlyInfoArea(rows = 2).apply {
        text = SpecCodingBundle.message("spec.dialog.template.help")
    }
    private val templateLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.template"))
    private val templateCombo = JComboBox(CollectionComboBoxModel(WorkflowTemplate.entries.toList())).apply {
        selectedItem = defaultTemplate
        renderer = SimpleListCellRenderer.create<WorkflowTemplate> { label, value, index ->
            val template = value
            if (template == null) {
                label.text = ""
                label.toolTipText = null
                return@create
            }
            val detail = buildTemplatePresentation(template)
            val templateLabel = SpecWorkflowOverviewPresenter.templateLabel(template)
            label.toolTipText = detail.bestFor
            label.text = if (index < 0) {
                templateLabel
            } else {
                buildComboEntryHtml(
                    title = templateLabel,
                    subtitle = detail.bestFor,
                )
            }
        }
    }
    private val verifyCheckBox = JBCheckBox(SpecCodingBundle.message("spec.dialog.field.verify"), false).apply {
        toolTipText = SpecCodingBundle.message("spec.dialog.field.verify.help")
    }
    private val templateDetailTitleLabel = JBLabel().apply {
        font = font.deriveFont(font.style or Font.BOLD)
    }
    private val templateDescriptionArea = createReadOnlyInfoArea(rows = 3)
    private val templateBestForArea = createReadOnlyInfoArea(rows = 2)
    private val templateStagesArea = createReadOnlyInfoArea(rows = 2)
    private val templateArtifactsArea = createReadOnlyInfoArea(rows = 2)
    private val templateDetailPanel = createTemplateDetailPanel()
    private val intentLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.intent"))
    private val fullIntentRadio = JBRadioButton(SpecCodingBundle.message("spec.dialog.intent.full"), true)
    private val incrementalIntentRadio = JBRadioButton(SpecCodingBundle.message("spec.dialog.intent.incremental"), false)
    private val baselineLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.baseline"))
    private val baselineCombo = JComboBox(
        CollectionComboBoxModel(
            buildBaselineOptions(workflowOptions),
        ),
    )

    var resultTitle: String? = null
        private set
    var resultDescription: String? = null
        private set
    var resultTemplate: WorkflowTemplate = defaultTemplate
        private set
    var resultVerifyEnabled: Boolean? = null
        private set
    var resultChangeIntent: SpecChangeIntent = SpecChangeIntent.FULL
        private set
    var resultBaselineWorkflowId: String? = null
        private set

    init {
        ButtonGroup().apply {
            add(fullIntentRadio)
            add(incrementalIntentRadio)
        }
        templateCombo.addActionListener { updateFormState() }
        verifyCheckBox.addActionListener { updateFormState() }
        fullIntentRadio.addActionListener { updateFormState() }
        incrementalIntentRadio.addActionListener { updateFormState() }
        init()
        title = SpecCodingBundle.message("spec.dialog.newWorkflow.title")
        updateFormState()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        val titleLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.title"))
        titleLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        titleField.alignmentX = JComponent.LEFT_ALIGNMENT
        titleField.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        panel.add(titleField)
        panel.add(javax.swing.Box.createVerticalStrut(12))

        val descLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.description"))
        descLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(descLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        panel.add(descriptionScrollPane)
        panel.add(javax.swing.Box.createVerticalStrut(12))

        templateLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(templateLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        val templateRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        }
        templateCombo.alignmentX = JComponent.LEFT_ALIGNMENT
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = templateCombo,
            minWidth = JBUI.scale(160),
            maxWidth = JBUI.scale(640),
            height = JBUI.scale(30),
        )
        verifyCheckBox.alignmentY = JComponent.CENTER_ALIGNMENT
        templateRow.add(templateCombo)
        templateRow.add(javax.swing.Box.createHorizontalStrut(8))
        templateRow.add(verifyCheckBox)
        panel.add(templateRow)
        panel.add(javax.swing.Box.createVerticalStrut(8))
        templateHelpArea.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(templateHelpArea)
        panel.add(javax.swing.Box.createVerticalStrut(8))
        panel.add(templateDetailPanel)
        panel.add(javax.swing.Box.createVerticalStrut(12))

        intentLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(intentLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        fullIntentRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        incrementalIntentRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(fullIntentRadio)
        panel.add(javax.swing.Box.createVerticalStrut(2))
        panel.add(incrementalIntentRadio)
        panel.add(javax.swing.Box.createVerticalStrut(8))

        baselineLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(baselineLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))
        baselineCombo.alignmentX = JComponent.LEFT_ALIGNMENT
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = baselineCombo,
            minWidth = JBUI.scale(160),
            maxWidth = JBUI.scale(520),
            height = JBUI.scale(30),
        )
        panel.add(baselineCombo)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isNullOrBlank()) {
            return ValidationInfo(SpecCodingBundle.message("spec.dialog.validation.titleRequired"), titleField)
        }
        if (templateSupportsRequirementScope(selectedTemplate()) && incrementalIntentRadio.isSelected) {
            if (descriptionArea.text.isNullOrBlank()) {
                return ValidationInfo(
                    SpecCodingBundle.message("spec.dialog.validation.changeSummaryRequired"),
                    descriptionArea,
                )
            }
        }
        return null
    }

    override fun doOKAction() {
        resultTitle = titleField.text.trim()
        resultDescription = descriptionArea.text.trim()
        resultTemplate = selectedTemplate()
        resultVerifyEnabled = selectedVerifyEnabled(resultTemplate)
        resultChangeIntent = normalizeChangeIntent(
            template = resultTemplate,
            requestedIntent = if (incrementalIntentRadio.isSelected) {
                SpecChangeIntent.INCREMENTAL
            } else {
                SpecChangeIntent.FULL
            },
        )
        resultBaselineWorkflowId = if (resultChangeIntent == SpecChangeIntent.INCREMENTAL) {
            (baselineCombo.selectedItem as? WorkflowOption)?.workflowId
        } else {
            null
        }
        super.doOKAction()
    }

    private fun updateFormState() {
        val template = selectedTemplate()
        val supportsVerifySelection = templateSupportsVerifySelection(template)
        verifyCheckBox.isVisible = supportsVerifySelection
        verifyCheckBox.isEnabled = supportsVerifySelection
        if (!supportsVerifySelection) {
            verifyCheckBox.isSelected = false
        }

        updateTemplatePresentation(
            template = template,
            verifyEnabled = selectedVerifyEnabled(template),
        )
        val supportsRequirementScope = templateSupportsRequirementScope(template)
        intentLabel.isVisible = supportsRequirementScope
        fullIntentRadio.isVisible = supportsRequirementScope
        incrementalIntentRadio.isVisible = supportsRequirementScope

        val incremental = supportsRequirementScope && incrementalIntentRadio.isSelected
        baselineLabel.isVisible = incremental
        baselineCombo.isVisible = incremental
        baselineCombo.isEnabled = incremental
    }

    private fun buildBaselineOptions(workflowOptions: List<WorkflowOption>): List<Any> {
        val candidates = workflowOptions.filter { it.workflowId.isNotBlank() }
        val noneOption = SpecCodingBundle.message("spec.dialog.baseline.none")
        return listOf(noneOption) + candidates
    }

    override fun getInitialSize(): Dimension {
        val base = super.getInitialSize()
        val minWidth = JBUI.scale(INITIAL_DIALOG_WIDTH)
        val minHeight = JBUI.scale(INITIAL_DIALOG_HEIGHT)
        return Dimension(
            (base?.width ?: 0).coerceAtLeast(minWidth),
            (base?.height ?: 0).coerceAtLeast(minHeight),
        )
    }

    private fun selectedTemplate(): WorkflowTemplate {
        return (templateCombo.selectedItem as? WorkflowTemplate) ?: WorkflowTemplate.FULL_SPEC
    }

    private fun selectedVerifyEnabled(template: WorkflowTemplate): Boolean? {
        return if (templateSupportsVerifySelection(template)) {
            verifyCheckBox.isSelected
        } else {
            null
        }
    }

    private fun createTemplateDetailPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(12),
        )

        templateDetailTitleLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(templateDetailTitleLabel)
        panel.add(javax.swing.Box.createVerticalStrut(10))
        panel.add(createTemplateDetailSection("spec.dialog.template.detail.description", templateDescriptionArea))
        panel.add(javax.swing.Box.createVerticalStrut(8))
        panel.add(createTemplateDetailSection("spec.dialog.template.detail.bestFor", templateBestForArea))
        panel.add(javax.swing.Box.createVerticalStrut(8))
        panel.add(createTemplateDetailSection("spec.dialog.template.detail.stages", templateStagesArea))
        panel.add(javax.swing.Box.createVerticalStrut(8))
        panel.add(createTemplateDetailSection("spec.dialog.template.detail.artifacts", templateArtifactsArea))
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun createTemplateDetailSection(messageKey: String, valueArea: JBTextArea): JComponent {
        val section = JPanel()
        section.layout = BoxLayout(section, BoxLayout.Y_AXIS)
        section.alignmentX = JComponent.LEFT_ALIGNMENT

        JBLabel(SpecCodingBundle.message(messageKey)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            section.add(this)
        }
        section.add(javax.swing.Box.createVerticalStrut(2))
        valueArea.alignmentX = JComponent.LEFT_ALIGNMENT
        section.add(valueArea)
        return section
    }

    private fun updateTemplatePresentation(template: WorkflowTemplate, verifyEnabled: Boolean?) {
        val presentation = buildTemplatePresentation(
            template = template,
            verifyEnabled = verifyEnabled,
        )
        templateCombo.toolTipText = presentation.bestFor
        templateDetailTitleLabel.text = SpecWorkflowOverviewPresenter.templateLabel(template)
        templateDescriptionArea.text = presentation.description
        templateBestForArea.text = presentation.bestFor
        templateStagesArea.text = presentation.stageSummary
        templateArtifactsArea.text = presentation.artifactSummary
    }

    override fun getPreferredFocusedComponent() = titleField

    companion object {
        private const val INITIAL_DIALOG_WIDTH = 720
        private const val INITIAL_DIALOG_HEIGHT = 540
        private const val CONTENT_WIDTH = 560
        private const val DESCRIPTION_AREA_HEIGHT = 96
        private const val READ_ONLY_INFO_COLUMNS = 48

        internal data class TemplatePresentation(
            val description: String,
            val bestFor: String,
            val stageSummary: String,
            val artifactSummary: String,
        )

        internal fun templateSupportsRequirementScope(template: WorkflowTemplate): Boolean {
            return WorkflowTemplates
                .definitionOf(template)
                .buildStagePlan()
                .activeStages
                .contains(StageId.REQUIREMENTS)
        }

        internal fun templateSupportsVerifySelection(template: WorkflowTemplate): Boolean {
            return WorkflowTemplates
                .definitionOf(template)
                .stagePlan
                .any { item -> item.id == StageId.VERIFY && item.optional }
        }

        internal fun normalizeChangeIntent(
            template: WorkflowTemplate,
            requestedIntent: SpecChangeIntent,
        ): SpecChangeIntent {
            return if (templateSupportsRequirementScope(template)) {
                requestedIntent
            } else {
                SpecChangeIntent.FULL
            }
        }

        internal fun buildTemplatePresentation(
            template: WorkflowTemplate,
            verifyEnabled: Boolean? = null,
        ): TemplatePresentation {
            val definition = WorkflowTemplates.definitionOf(template)
            return TemplatePresentation(
                description = SpecCodingBundle.message(templateMessageKey("description", template)),
                bestFor = SpecCodingBundle.message(templateMessageKey("bestFor", template)),
                stageSummary = buildStageSummary(
                    definition = definition,
                    verifyEnabled = verifyEnabled,
                ),
                artifactSummary = buildArtifactSummary(
                    template = template,
                    definition = definition,
                    verifyEnabled = verifyEnabled,
                ),
            )
        }

        private fun buildStageSummary(
            definition: com.eacape.speccodingplugin.spec.TemplateDefinition,
            verifyEnabled: Boolean?,
        ): String {
            return definition.stagePlan.mapNotNull { item ->
                when {
                    item.id == StageId.VERIFY && templateSupportsVerifySelection(definition.template) -> when (verifyEnabled) {
                        false -> null
                        true -> SpecWorkflowOverviewPresenter.stageLabel(item.id)
                        else -> decorateOptional(
                            value = SpecWorkflowOverviewPresenter.stageLabel(item.id),
                            optional = item.optional,
                        )
                    }

                    else -> decorateOptional(
                        value = SpecWorkflowOverviewPresenter.stageLabel(item.id),
                        optional = item.optional,
                    )
                }
            }.joinToString(" -> ")
        }

        private fun buildArtifactSummary(
            template: WorkflowTemplate,
            definition: com.eacape.speccodingplugin.spec.TemplateDefinition,
            verifyEnabled: Boolean?,
        ): String {
            val artifacts = mutableListOf<String>()
            if (template == WorkflowTemplate.DIRECT_IMPLEMENT) {
                artifacts += SpecCodingBundle.message(
                    "spec.dialog.template.generatedValue",
                    StageId.TASKS.artifactFileName.orEmpty(),
                )
            }
            definition.stagePlan.forEach { item ->
                val fileName = item.id.artifactFileName ?: return@forEach
                if (item.id == StageId.VERIFY && templateSupportsVerifySelection(definition.template)) {
                    when (verifyEnabled) {
                        false -> Unit
                        true -> artifacts += fileName
                        else -> artifacts += decorateOptional(fileName, item.optional)
                    }
                } else {
                    artifacts += decorateOptional(fileName, item.optional)
                }
            }
            return artifacts.distinct().joinToString(", ")
        }

        private fun decorateOptional(value: String, optional: Boolean): String {
            return if (optional) {
                SpecCodingBundle.message("spec.dialog.template.optionalValue", value)
            } else {
                value
            }
        }

        private fun templateMessageKey(section: String, template: WorkflowTemplate): String {
            return "spec.dialog.template.$section.${template.name.lowercase()}"
        }

        private fun createReadOnlyInfoArea(rows: Int): JBTextArea {
            return JBTextArea(rows, READ_ONLY_INFO_COLUMNS).apply {
                isEditable = false
                isOpaque = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty()
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        }

        private fun lockFixedHeight(
            component: JComponent,
            height: Int,
            preferredWidth: Int,
        ) {
            component.minimumSize = Dimension(0, height)
            component.preferredSize = Dimension(preferredWidth, height)
            component.maximumSize = Dimension(Int.MAX_VALUE, height)
        }

        private fun buildComboEntryHtml(title: String, subtitle: String): String {
            return buildString {
                append("<html><div><b>")
                append(StringUtil.escapeXmlEntities(title))
                append("</b><br/><span>")
                append(StringUtil.escapeXmlEntities(subtitle))
                append("</span></div></html>")
            }
        }
    }
}
