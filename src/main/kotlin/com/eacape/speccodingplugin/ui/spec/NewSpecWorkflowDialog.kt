package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecChangeIntent
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import com.eacape.speccodingplugin.spec.WorkflowTemplates
import com.intellij.openapi.util.text.StringUtil
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadiness
import com.eacape.speccodingplugin.ui.LocalEnvironmentReadinessSnapshot
import com.eacape.speccodingplugin.ui.ComboBoxAutoWidthSupport
import com.eacape.speccodingplugin.ui.actions.SpecWorkflowActionSupport
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
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
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class NewSpecWorkflowDialog(
    private val project: Project? = null,
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
        text = SpecCodingBundle.message("spec.dialog.template.help.beta")
    }
    private val localSetupArea = createReadOnlyInfoArea(rows = 8)
    private val onboardingArea = createReadOnlyInfoArea(rows = 3)
    private val firstRunStatusArea = createReadOnlyInfoArea(rows = 4)
    private val firstRunGuideArea = createReadOnlyInfoArea(rows = 7)
    private val capabilityGuideArea = createReadOnlyInfoArea(rows = 6)
    private val troubleshootingFaqArea = createReadOnlyInfoArea(rows = 10)
    private val troubleshootingActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        isOpaque = false
        isVisible = false
    }
    private val troubleshootingActionDispatcher = SpecWorkflowTroubleshootingActionDispatcher(
        object : SpecWorkflowTroubleshootingActionDispatcher.Callbacks {
            override fun openSettings() {
                openSettingsAndRefreshReadiness()
            }

            override fun openBundledDemo() {
                openBundledDemoProject()
            }

            override fun selectEntry(entry: SpecWorkflowPrimaryEntry) {
                selectPrimaryEntry(entry)
            }

            override fun refreshAfterEntrySelection() {
                updateFormState()
            }
        },
    )
    private val demoProjectArea = createReadOnlyInfoArea(rows = 3).apply {
        text = SpecCodingBundle.message("spec.dialog.demo.summary")
    }
    private val openSettingsButton = JButton(SpecCodingBundle.message("spec.dialog.localSetup.openSettings")).apply {
        isVisible = false
    }
    private val openBundledDemoButton = JButton(SpecCodingBundle.message("spec.dialog.demo.open"))
    private val entryLabel = JBLabel(SpecCodingBundle.message("spec.dialog.entry.title"))
    private val quickTaskEntryRadio = JBRadioButton(
        SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.QUICK_TASK),
        false,
    )
    private val fullSpecEntryRadio = JBRadioButton(
        SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.FULL_SPEC),
        false,
    )
    private val advancedTemplateEntryRadio = JBRadioButton(
        SpecCodingBundle.message("spec.dialog.entry.advanced"),
        false,
    )
    private val templateLabel = JBLabel(SpecCodingBundle.message("spec.dialog.field.template.advanced"))
    private val advancedTemplateCombo = JComboBox(CollectionComboBoxModel(advancedTemplates())).apply {
        selectedItem = SpecWorkflowEntryPaths.templateForPrimaryEntry(
            entry = SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE,
            advancedTemplate = defaultTemplate,
            availableTemplates = orderedTemplates(),
        )
        renderer = buildTemplateRenderer()
    }
    private val advancedTemplatePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = JComponent.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        isOpaque = false
    }
    private val verifyCheckBox = JBCheckBox(SpecCodingBundle.message("spec.dialog.field.verify"), false).apply {
        toolTipText = SpecCodingBundle.message("spec.dialog.field.verify.help")
    }
    private val templateDetailTitleLabel = JBLabel().apply {
        font = font.deriveFont(font.style or Font.BOLD)
    }
    private val templateDescriptionArea = createReadOnlyInfoArea(rows = 3)
    private val templateBestForArea = createReadOnlyInfoArea(rows = 2)
    private val templateStagesArea = createReadOnlyInfoArea(rows = 6)
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
    private var localReadinessSnapshot: LocalEnvironmentReadinessSnapshot? = null

    init {
        ButtonGroup().apply {
            add(fullIntentRadio)
            add(incrementalIntentRadio)
        }
        ButtonGroup().apply {
            add(quickTaskEntryRadio)
            add(fullSpecEntryRadio)
            add(advancedTemplateEntryRadio)
        }
        quickTaskEntryRadio.toolTipText = buildTemplatePresentation(WorkflowTemplate.QUICK_TASK).bestFor
        fullSpecEntryRadio.toolTipText = buildTemplatePresentation(WorkflowTemplate.FULL_SPEC).bestFor
        advancedTemplateEntryRadio.toolTipText = SpecCodingBundle.message("spec.dialog.entry.advanced.help")
        when (SpecWorkflowEntryPaths.primaryEntryForTemplate(defaultTemplate)) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> quickTaskEntryRadio.isSelected = true
            SpecWorkflowPrimaryEntry.FULL_SPEC -> fullSpecEntryRadio.isSelected = true
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> advancedTemplateEntryRadio.isSelected = true
        }
        advancedTemplateCombo.addActionListener { updateFormState() }
        quickTaskEntryRadio.addActionListener { updateFormState() }
        fullSpecEntryRadio.addActionListener { updateFormState() }
        advancedTemplateEntryRadio.addActionListener { updateFormState() }
        openSettingsButton.addActionListener { openSettingsAndRefreshReadiness() }
        openBundledDemoButton.addActionListener { openBundledDemoProject() }
        verifyCheckBox.addActionListener { updateFormState() }
        fullIntentRadio.addActionListener { updateFormState() }
        incrementalIntentRadio.addActionListener { updateFormState() }
        init()
        title = SpecCodingBundle.message("spec.dialog.newWorkflow.title")
        updateLocalSetupPresentation()
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

        entryLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(entryLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))
        onboardingArea.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(onboardingArea)
        if (project != null) {
            panel.add(javax.swing.Box.createVerticalStrut(4))
            openSettingsButton.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(openSettingsButton)
            panel.add(javax.swing.Box.createVerticalStrut(8))
            val firstRunStatusLabel = JBLabel(SpecCodingBundle.message("spec.dialog.firstRun.status.title"))
            firstRunStatusLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(firstRunStatusLabel)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            firstRunStatusArea.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(firstRunStatusArea)
        }
        panel.add(javax.swing.Box.createVerticalStrut(8))

        quickTaskEntryRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        fullSpecEntryRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        advancedTemplateEntryRadio.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(quickTaskEntryRadio)
        panel.add(javax.swing.Box.createVerticalStrut(2))
        panel.add(fullSpecEntryRadio)
        panel.add(javax.swing.Box.createVerticalStrut(2))
        panel.add(advancedTemplateEntryRadio)
        panel.add(javax.swing.Box.createVerticalStrut(4))

        templateLabel.alignmentY = JComponent.CENTER_ALIGNMENT
        advancedTemplateCombo.alignmentX = JComponent.LEFT_ALIGNMENT
        ComboBoxAutoWidthSupport.installSelectedItemAutoWidth(
            comboBox = advancedTemplateCombo,
            minWidth = JBUI.scale(160),
            maxWidth = JBUI.scale(640),
            height = JBUI.scale(30),
        )
        advancedTemplatePanel.add(templateLabel)
        advancedTemplatePanel.add(javax.swing.Box.createHorizontalStrut(8))
        advancedTemplatePanel.add(advancedTemplateCombo)
        panel.add(advancedTemplatePanel)
        panel.add(javax.swing.Box.createVerticalStrut(8))
        templateHelpArea.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(templateHelpArea)
        panel.add(javax.swing.Box.createVerticalStrut(8))
        if (project != null) {
            val firstRunGuideLabel = JBLabel(SpecCodingBundle.message("spec.dialog.firstRun.title"))
            firstRunGuideLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(firstRunGuideLabel)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            firstRunGuideArea.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(firstRunGuideArea)
            panel.add(javax.swing.Box.createVerticalStrut(8))
            val capabilityGuideLabel = JBLabel(SpecCodingBundle.message("spec.dialog.capabilityGuide.title"))
            capabilityGuideLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(capabilityGuideLabel)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            capabilityGuideArea.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(capabilityGuideArea)
            panel.add(javax.swing.Box.createVerticalStrut(8))
            val troubleshootingFaqLabel = JBLabel(SpecCodingBundle.message("spec.dialog.troubleshooting.title"))
            troubleshootingFaqLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(troubleshootingFaqLabel)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            troubleshootingFaqArea.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(troubleshootingFaqArea)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            val troubleshootingActionsLabel = JBLabel(SpecCodingBundle.message("spec.dialog.troubleshooting.actions.title"))
            troubleshootingActionsLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(troubleshootingActionsLabel)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            panel.add(troubleshootingActionsPanel)
            panel.add(javax.swing.Box.createVerticalStrut(8))
        }
        val demoProjectLabel = JBLabel(SpecCodingBundle.message("spec.dialog.demo.title"))
        demoProjectLabel.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(demoProjectLabel)
        panel.add(javax.swing.Box.createVerticalStrut(4))
        demoProjectArea.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(demoProjectArea)
        panel.add(javax.swing.Box.createVerticalStrut(4))
        openBundledDemoButton.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(openBundledDemoButton)
        panel.add(javax.swing.Box.createVerticalStrut(8))
        verifyCheckBox.alignmentX = JComponent.LEFT_ALIGNMENT
        panel.add(verifyCheckBox)
        panel.add(javax.swing.Box.createVerticalStrut(8))
        if (project != null) {
            val localSetupLabel = JBLabel(SpecCodingBundle.message("spec.dialog.localSetup.title"))
            localSetupLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(localSetupLabel)
            panel.add(javax.swing.Box.createVerticalStrut(4))
            localSetupArea.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(localSetupArea)
            panel.add(javax.swing.Box.createVerticalStrut(8))
        }
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
        localReadinessSnapshot?.let { readiness ->
            SpecWorkflowOnboardingCoordinator.blockedEntryValidationMessage(
                entry = selectedPrimaryEntry(),
                readiness = readiness,
            )?.let { message ->
                return ValidationInfo(message, entryValidationComponent(selectedPrimaryEntry()))
            }
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
        val advancedSelected = selectedPrimaryEntry() == SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE
        advancedTemplatePanel.isVisible = advancedSelected
        advancedTemplateCombo.isVisible = advancedSelected
        advancedTemplateCombo.isEnabled = advancedSelected
        localReadinessSnapshot?.let { readiness ->
            updateOnboardingPresentation(readiness)
            updateFirstRunGuide(readiness)
            updateTroubleshootingFaq(readiness)
        }
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

    private fun selectedPrimaryEntry(): SpecWorkflowPrimaryEntry {
        return when {
            quickTaskEntryRadio.isSelected -> SpecWorkflowPrimaryEntry.QUICK_TASK
            fullSpecEntryRadio.isSelected -> SpecWorkflowPrimaryEntry.FULL_SPEC
            else -> SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE
        }
    }

    private fun entryValidationComponent(entry: SpecWorkflowPrimaryEntry): JComponent {
        return when (entry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> quickTaskEntryRadio
            SpecWorkflowPrimaryEntry.FULL_SPEC -> fullSpecEntryRadio
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> advancedTemplateCombo
        }
    }

    private fun selectPrimaryEntry(entry: SpecWorkflowPrimaryEntry) {
        when (entry) {
            SpecWorkflowPrimaryEntry.QUICK_TASK -> quickTaskEntryRadio.isSelected = true
            SpecWorkflowPrimaryEntry.FULL_SPEC -> fullSpecEntryRadio.isSelected = true
            SpecWorkflowPrimaryEntry.ADVANCED_TEMPLATE -> advancedTemplateEntryRadio.isSelected = true
        }
    }

    private fun selectedTemplate(): WorkflowTemplate {
        return SpecWorkflowEntryPaths.templateForPrimaryEntry(
            entry = selectedPrimaryEntry(),
            advancedTemplate = advancedTemplateCombo.selectedItem as? WorkflowTemplate,
            availableTemplates = orderedTemplates(),
        )
    }

    private fun selectedVerifyEnabled(template: WorkflowTemplate): Boolean? {
        return if (templateSupportsVerifySelection(template)) {
            verifyCheckBox.isSelected
        } else {
            null
        }
    }

    private fun buildTemplateRenderer() = SimpleListCellRenderer.create<WorkflowTemplate> { label, value, index ->
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
        advancedTemplateCombo.toolTipText = presentation.bestFor
        templateDetailTitleLabel.text = SpecWorkflowOverviewPresenter.templateLabel(template)
        templateDescriptionArea.text = presentation.description
        templateBestForArea.text = presentation.bestFor
        templateStagesArea.text = presentation.stageMeaningSummary
        templateStagesArea.toolTipText = presentation.stageSummary
        templateArtifactsArea.text = presentation.artifactSummary
    }

    private fun updateLocalSetupPresentation() {
        val activeProject = project ?: return
        val readiness = LocalEnvironmentReadiness.inspect(activeProject)
        localReadinessSnapshot = readiness
        val onboarding = SpecWorkflowOnboardingCoordinator.build(
            requestedTemplate = selectedTemplate(),
            readiness = readiness,
        )
        if (
            !SpecWorkflowOnboardingCoordinator.isEntryReady(selectedPrimaryEntry(), readiness) &&
            SpecWorkflowOnboardingCoordinator.isEntryReady(onboarding.recommendedEntry, readiness)
        ) {
            selectPrimaryEntry(onboarding.recommendedEntry)
        }
        updateOnboardingPresentation(readiness)
        updateFirstRunStatus(readiness)
        updateCapabilityGuide(readiness)
        updateTroubleshootingFaq(readiness)
        localSetupArea.text = buildString {
            appendLine(readiness.summary)
            appendLine()
            append(LocalEnvironmentReadiness.formatDetails(readiness))
        }
    }

    private fun updateOnboardingPresentation(readiness: LocalEnvironmentReadinessSnapshot) {
        val onboarding = SpecWorkflowOnboardingCoordinator.build(
            requestedTemplate = selectedTemplate(),
            readiness = readiness,
        )
        onboardingArea.text = buildString {
            appendLine(onboarding.summary)
            append(onboarding.nextStep)
        }
        openSettingsButton.isVisible = onboarding.showSettingsShortcut
    }

    private fun updateFirstRunGuide(readiness: LocalEnvironmentReadinessSnapshot) {
        val guide = SpecWorkflowFirstRunGuideCoordinator.build(
            selectedEntry = selectedPrimaryEntry(),
            template = selectedTemplate(),
            readiness = readiness,
        )
        firstRunGuideArea.text = buildString {
            appendLine(guide.summary)
            appendLine()
            guide.steps.forEachIndexed { index, step ->
                append(index + 1)
                append(". ")
                append(step)
                if (index < guide.steps.lastIndex) {
                    appendLine()
                }
            }
        }
    }

    private fun updateFirstRunStatus(readiness: LocalEnvironmentReadinessSnapshot) {
        val activeProject = project ?: return
        val status = SpecWorkflowFirstRunStatusCoordinator.build(
            readiness = readiness,
            tracking = SpecWorkflowFirstRunTrackingStore.getInstance(activeProject).snapshot(),
        )
        firstRunStatusArea.text = buildString {
            appendLine(status.summary)
            if (status.details.isNotEmpty()) {
                appendLine()
                append(status.details.joinToString("\n"))
            }
        }
    }

    private fun updateCapabilityGuide(readiness: LocalEnvironmentReadinessSnapshot) {
        val activeProject = project ?: return
        val guide = SpecWorkflowCapabilityEnablementGuideCoordinator.build(
            readiness = readiness,
            tracking = SpecWorkflowFirstRunTrackingStore.getInstance(activeProject).snapshot(),
        )
        capabilityGuideArea.text = buildString {
            appendLine(guide.summary)
            appendLine()
            guide.items.forEachIndexed { index, item ->
                append(index + 1)
                append(". ")
                append(item.title())
                append(" [")
                append(item.timing.presentation())
                append("]: ")
                append(item.detail)
                if (index < guide.items.lastIndex) {
                    appendLine()
                }
            }
        }
    }

    private fun updateTroubleshootingFaq(readiness: LocalEnvironmentReadinessSnapshot) {
        val activeProject = project ?: return
        val faq = SpecWorkflowTroubleshootingFaqCoordinator.build(
            readiness = readiness,
            tracking = SpecWorkflowFirstRunTrackingStore.getInstance(activeProject).snapshot(),
            template = selectedTemplate(),
        )
        troubleshootingFaqArea.text = buildString {
            appendLine(faq.summary)
            appendLine()
            faq.items.forEachIndexed { index, item ->
                append(index + 1)
                append(". ")
                append(item.question)
                appendLine()
                append(item.answer)
                if (index < faq.items.lastIndex) {
                    appendLine()
                    appendLine()
                }
            }
        }
        updateTroubleshootingActions(faq.actions)
    }

    private fun updateTroubleshootingActions(actions: List<SpecWorkflowTroubleshootingAction>) {
        troubleshootingActionsPanel.removeAll()
        actions.forEach { action ->
            val button = JButton(action.label).apply {
                isFocusable = false
                addActionListener { troubleshootingActionDispatcher.perform(action) }
            }
            troubleshootingActionsPanel.add(button)
        }
        troubleshootingActionsPanel.isVisible = actions.isNotEmpty()
        troubleshootingActionsPanel.revalidate()
        troubleshootingActionsPanel.repaint()
    }

    private fun openSettingsAndRefreshReadiness() {
        val activeProject = project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(
            activeProject,
            "com.eacape.speccodingplugin.settings",
        )
        updateLocalSetupPresentation()
        updateFormState()
    }

    private fun openBundledDemoProject() {
        val demoProject = runCatching {
            SpecWorkflowBundledDemoProjectSupport.materializeDefault()
        }.getOrElse { error ->
            showDemoOpenError(error)
            return
        }
        val readmePath = demoProject.readmePath
        val openedInEditor = project?.let { activeProject ->
            SpecWorkflowActionSupport.openFile(activeProject, readmePath)
        } == true
        if (!openedInEditor) {
            BrowserUtil.browse(readmePath.toUri())
        }
    }

    private fun showDemoOpenError(error: Throwable) {
        val message = SpecCodingBundle.message(
            "spec.dialog.demo.open.failed",
            error.message ?: error.javaClass.simpleName,
        )
        val title = SpecCodingBundle.message("spec.dialog.demo.title")
        if (project != null) {
            Messages.showErrorDialog(project, message, title)
        } else {
            Messages.showErrorDialog(message, title)
        }
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
            val stageMeaningSummary: String,
            val artifactSummary: String,
        )

        internal fun orderedTemplates(): List<WorkflowTemplate> {
            return SpecWorkflowEntryPaths.prioritizedTemplates()
        }

        internal fun advancedTemplates(): List<WorkflowTemplate> {
            return SpecWorkflowEntryPaths.advancedTemplates(orderedTemplates())
        }

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
            val stageGuide = SpecWorkflowTemplateStageGuideCoordinator.build(
                template = template,
                verifyEnabled = verifyEnabled,
            )
            return TemplatePresentation(
                description = SpecCodingBundle.message(templateMessageKey("description", template)),
                bestFor = SpecCodingBundle.message(templateMessageKey("bestFor", template)),
                stageSummary = stageGuide.stageSummary,
                stageMeaningSummary = stageGuide.stageMeaningSummary,
                artifactSummary = buildArtifactSummary(
                    template = template,
                    definition = definition,
                    verifyEnabled = verifyEnabled,
                ),
            )
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
