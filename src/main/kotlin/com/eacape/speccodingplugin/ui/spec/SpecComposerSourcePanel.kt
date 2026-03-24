package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SpecComposerSourcePanel(
    private val onAddRequested: () -> Unit = {},
    private val onRemoveRequested: (String) -> Unit = {},
    private val onRestoreRequested: () -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private var workflowId: String? = null
    private var assets: List<WorkflowSourceAsset> = emptyList()
    private var selectedSourceIds: Set<String> = emptySet()
    private var editable: Boolean = false

    private val titleLabel = JBLabel(SpecCodingBundle.message("spec.detail.sources.title"))
    private val metaLabel = JBLabel()
    private val hintLabel = JBLabel()
    private val chipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val addButton = JButton()
    private val restoreButton = JButton(SpecCodingBundle.message("spec.detail.sources.restore"))
    private val removeButtonsBySourceId = linkedMapOf<String, JButton>()

    init {
        isOpaque = true
        background = STRIP_BG
        border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(STRIP_BORDER, JBUI.scale(12)),
            JBUI.Borders.empty(8, 10, 8, 10),
        )

        titleLabel.font = JBUI.Fonts.smallFont().deriveFont(titleLabel.font.style or java.awt.Font.BOLD)
        titleLabel.foreground = TITLE_FG
        metaLabel.font = JBUI.Fonts.miniFont()
        metaLabel.foreground = META_FG
        hintLabel.font = JBUI.Fonts.miniFont()
        hintLabel.foreground = HINT_FG

        chipRow.isOpaque = false

        styleUtilityButton(addButton)
        addButton.icon = ADD_FILE_ICON
        addButton.text = null
        addButton.margin = JBUI.emptyInsets()
        addButton.preferredSize = Dimension(JBUI.scale(30), JBUI.scale(28))
        addButton.minimumSize = addButton.preferredSize
        addButton.maximumSize = addButton.preferredSize
        addButton.toolTipText = SpecCodingBundle.message("spec.detail.sources.add.tooltip")
        addButton.accessibleContext.accessibleName = SpecCodingBundle.message("spec.detail.sources.add")
        addButton.addActionListener { onAddRequested() }

        styleUtilityButton(restoreButton)
        restoreButton.toolTipText = SpecCodingBundle.message("spec.detail.sources.restore.tooltip")
        restoreButton.addActionListener { onRestoreRequested() }

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    add(titleLabel, BorderLayout.NORTH)
                    add(metaLabel, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    add(addButton)
                    add(restoreButton)
                },
                BorderLayout.EAST,
            )
        }

        add(header, BorderLayout.NORTH)
        add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(chipRow, BorderLayout.NORTH)
                add(hintLabel, BorderLayout.SOUTH)
            },
            BorderLayout.CENTER,
        )
        isVisible = false
    }

    fun updateState(
        workflowId: String?,
        assets: List<WorkflowSourceAsset>,
        selectedSourceIds: Set<String>,
        editable: Boolean,
    ) {
        this.workflowId = workflowId?.trim()?.ifBlank { null }
        this.assets = assets.sortedBy(WorkflowSourceAsset::sourceId)
        this.selectedSourceIds = selectedSourceIds.toSet()
        this.editable = editable
        rebuildUi()
    }

    fun clear() {
        updateState(
            workflowId = null,
            assets = emptyList(),
            selectedSourceIds = emptySet(),
            editable = false,
        )
    }

    fun refreshLocalizedTexts() {
        rebuildUi()
    }

    private fun rebuildUi() {
        removeButtonsBySourceId.clear()
        chipRow.removeAll()
        titleLabel.text = SpecCodingBundle.message("spec.detail.sources.title")
        addButton.toolTipText = SpecCodingBundle.message("spec.detail.sources.add.tooltip")
        addButton.accessibleContext.accessibleName = SpecCodingBundle.message("spec.detail.sources.add")
        restoreButton.text = SpecCodingBundle.message("spec.detail.sources.restore")
        restoreButton.toolTipText = SpecCodingBundle.message("spec.detail.sources.restore.tooltip")

        val hasWorkflow = workflowId != null
        val selectedAssets = assets.filter { asset -> selectedSourceIds.contains(asset.sourceId) }
        val hiddenCount = (assets.size - selectedAssets.size).coerceAtLeast(0)

        isVisible = hasWorkflow && editable
        addButton.isEnabled = hasWorkflow && editable
        restoreButton.isVisible = hasWorkflow && editable && hiddenCount > 0
        restoreButton.isEnabled = restoreButton.isVisible

        metaLabel.text = when {
            assets.isEmpty() -> SpecCodingBundle.message("spec.detail.sources.meta.empty")
            hiddenCount > 0 -> SpecCodingBundle.message("spec.detail.sources.meta.hidden", assets.size, hiddenCount)
            else -> SpecCodingBundle.message("spec.detail.sources.meta.saved", assets.size)
        }

        hintLabel.text = when {
            assets.isEmpty() -> SpecCodingBundle.message("spec.detail.sources.empty")
            selectedAssets.isEmpty() -> SpecCodingBundle.message("spec.detail.sources.noneSelected")
            else -> SpecCodingBundle.message("spec.detail.sources.persistedHint")
        }

        selectedAssets.forEach { asset ->
            chipRow.add(createChip(asset))
        }
        chipRow.isVisible = selectedAssets.isNotEmpty()

        revalidate()
        repaint()
    }

    private fun createChip(asset: WorkflowSourceAsset): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0)).apply {
            isOpaque = true
            background = CHIP_BG
            border = JBUI.Borders.empty(1, 4)
            toolTipText = buildTooltip(asset)
        }
        val label = JBLabel(buildDisplayLabel(asset), resolveIcon(asset), SwingConstants.LEADING).apply {
            font = JBUI.Fonts.miniFont()
            foreground = CHIP_TEXT
            iconTextGap = JBUI.scale(2)
            toolTipText = chip.toolTipText
        }
        chip.add(label)

        val removeButton = JButton("x").apply {
            font = font.deriveFont(10f)
            foreground = CHIP_REMOVE_FG
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = JBUI.emptyInsets()
            toolTipText = SpecCodingBundle.message("spec.detail.sources.remove.tooltip")
            val size = JBUI.scale(14)
            preferredSize = Dimension(size, size)
            minimumSize = preferredSize
            maximumSize = preferredSize
            addActionListener { onRemoveRequested(asset.sourceId) }
        }
        removeButtonsBySourceId[asset.sourceId] = removeButton
        chip.add(removeButton)
        return chip
    }

    private fun buildDisplayLabel(asset: WorkflowSourceAsset): String {
        return truncateTail("${asset.sourceId} | ${asset.originalFileName}", MAX_LABEL_LENGTH)
    }

    private fun buildTooltip(asset: WorkflowSourceAsset): String {
        return buildString {
            append("<html>")
            append(escapeHtml(asset.sourceId))
            append("<br/>")
            append(escapeHtml(asset.originalFileName))
            append("<br/>")
            append(escapeHtml(asset.storedRelativePath))
            append("</html>")
        }
    }

    private fun resolveIcon(asset: WorkflowSourceAsset): Icon {
        return when {
            asset.mediaType.startsWith("image/") -> AllIcons.FileTypes.Image
            asset.mediaType.startsWith("text/") -> AllIcons.FileTypes.Text
            else -> AllIcons.FileTypes.Any_type
        }
    }

    private fun styleUtilityButton(button: JButton) {
        button.isOpaque = true
        button.background = BUTTON_BG
        button.foreground = BUTTON_FG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(2, 8),
        )
        button.isFocusPainted = false
    }

    private fun truncateTail(value: String, maxLength: Int): String {
        if (value.length <= maxLength) {
            return value
        }
        return value.take((maxLength - ELLIPSIS.length).coerceAtLeast(0)) + ELLIPSIS
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    internal fun selectedSourceLabelsForTest(): List<String> {
        return chipRow.components
            .mapNotNull { component ->
                (component as? JPanel)
                    ?.components
                    ?.filterIsInstance<JBLabel>()
                    ?.firstOrNull()
                    ?.text
            }
    }

    internal fun metaTextForTest(): String = metaLabel.text.orEmpty()

    internal fun hintTextForTest(): String = hintLabel.text.orEmpty()

    internal fun titleTextForTest(): String = titleLabel.text.orEmpty()

    internal fun isRestoreVisibleForTest(): Boolean = restoreButton.isVisible

    internal fun clickAddForTest() {
        addButton.doClick()
    }

    internal fun isAddIconOnlyForTest(): Boolean = addButton.icon != null && addButton.text.isNullOrBlank()

    internal fun clickRestoreForTest() {
        restoreButton.doClick()
    }

    internal fun clickRemoveForTest(sourceId: String): Boolean {
        val button = removeButtonsBySourceId[sourceId] ?: return false
        button.doClick()
        return true
    }

    companion object {
        private const val MAX_LABEL_LENGTH = 34
        private const val ELLIPSIS = "..."
        private val ADD_FILE_ICON = IconLoader.getIcon("/icons/spec-detail-add-file.svg", SpecComposerSourcePanel::class.java)

        private val STRIP_BG = JBColor(Color(0xF7FAFF), Color(0x4A5564))
        private val STRIP_BORDER = JBColor(Color(0xD2DEEF), Color(0x6D7B8E))
        private val TITLE_FG = JBColor(Color(0x35506F), Color(0xD5E4F5))
        private val META_FG = JBColor(Color(0x6A7890), Color(0xABB7C8))
        private val HINT_FG = JBColor(Color(0x71829C), Color(0xB7C4D6))
        private val CHIP_BG = JBColor(Color(0xEAF2FF), Color(0x3A4350))
        private val CHIP_TEXT = JBColor(Color(0x35506F), Color(0xD5E4F5))
        private val CHIP_REMOVE_FG = JBColor(Color(0x6D778A), Color(0xA8B1C4))
        private val BUTTON_BG = JBColor(Color(0xF4F8FF), Color(0x5C6777))
        private val BUTTON_BORDER = JBColor(Color(0xC7D6EB), Color(0x76869E))
        private val BUTTON_FG = JBColor(Color(0x3D5A7E), Color(0xDCE7F7))
    }
}
