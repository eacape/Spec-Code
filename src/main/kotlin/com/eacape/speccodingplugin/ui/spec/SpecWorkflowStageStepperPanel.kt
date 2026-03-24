package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class SpecWorkflowStageStepperPanel(
    private val onStageSelected: (StageId) -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val stagesPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
    }

    private var currentState: SpecWorkflowStageStepperState? = null
    private var currentFocusedStage: StageId? = null
    private val stageChips = linkedMapOf<StageId, JPanel>()

    init {
        isOpaque = false
        add(stagesPanel, BorderLayout.CENTER)
        clear()
    }

    fun updateState(
        state: SpecWorkflowStageStepperState,
        focusedStage: StageId? = null,
    ) {
        currentState = state
        currentFocusedStage = focusedStage
            ?: state.stages.firstOrNull { it.current }?.stageId
            ?: state.stages.firstOrNull()?.stageId
        render()
    }

    fun clear() {
        currentState = null
        currentFocusedStage = null
        render()
    }

    fun refreshLocalizedTexts() {
        render()
    }

    internal fun snapshotForTest(): Map<String, String> {
        val firstStageChip = stageChips.values.firstOrNull()
        return mapOf(
            "stages" to currentState
                ?.stages
                ?.joinToString(" | ") { stage ->
                    buildString {
                        append(SpecWorkflowOverviewPresenter.stageLabel(stage.stageId))
                        append(':')
                        append(statusText(stage))
                        append(':')
                        append(if (stage.current) "current" else "not-current")
                        append(':')
                        append(if (stage.stageId == currentFocusedStage) "focused" else "not-focused")
                    }
                }
                .orEmpty(),
            "focusedStage" to currentFocusedStage?.name.orEmpty(),
            "stageChipOpaque" to firstStageChip?.isOpaque?.toString().orEmpty(),
            "stageChipInsets" to firstStageChip?.border?.getBorderInsets(firstStageChip)?.let { insets ->
                "${insets.top},${insets.left},${insets.bottom},${insets.right}"
            }.orEmpty(),
        )
    }

    internal fun clickStageForTest(stageId: StageId) {
        if (stageChips.containsKey(stageId)) {
            onStageSelected(stageId)
        }
    }

    private fun render() {
        stagesPanel.removeAll()
        stageChips.clear()
        val state = currentState
        if (state == null) {
            revalidate()
            repaint()
            return
        }

        state.stages.forEachIndexed { index, stage ->
            val chip = createStageChip(stage, focused = stage.stageId == currentFocusedStage)
            stageChips[stage.stageId] = chip
            stagesPanel.add(chip)
            if (index < state.stages.lastIndex) {
                stagesPanel.add(
                    JBLabel(ARROW_TEXT).apply {
                        foreground = CONNECTOR_FG
                        border = JBUI.Borders.empty()
                    },
                )
            }
        }
        revalidate()
        repaint()
    }

    private fun createStageChip(
        stage: SpecWorkflowStageStepState,
        focused: Boolean,
    ): JPanel {
        val palette = chipPalette(stage, focused)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = palette.background
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(palette.border, JBUI.scale(14), thickness = palette.borderThickness),
                JBUI.Borders.empty(6, 10, 6, 10),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = SpecWorkflowOverviewPresenter.stageLabel(stage.stageId)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        onStageSelected(stage.stageId)
                    }
                },
            )
            add(
                JBLabel(SpecWorkflowOverviewPresenter.stageLabel(stage.stageId)).apply {
                    foreground = palette.titleForeground
                    horizontalAlignment = SwingConstants.LEFT
                    alignmentX = LEFT_ALIGNMENT
                    font = JBUI.Fonts.smallFont().deriveFont(
                        if (stage.current || focused) Font.BOLD else Font.PLAIN,
                    )
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(
                JBLabel(statusText(stage)).apply {
                    foreground = palette.statusForeground
                    font = JBUI.Fonts.smallFont().deriveFont(10.0f)
                    horizontalAlignment = SwingConstants.LEFT
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }
    }

    private fun statusText(stage: SpecWorkflowStageStepState): String {
        return when {
            !stage.active -> SpecCodingBundle.message("spec.toolwindow.stage.state.inactive")
            stage.progress == StageProgress.DONE -> SpecCodingBundle.message("spec.action.stage.state.done")
            stage.current || stage.progress == StageProgress.IN_PROGRESS ->
                SpecCodingBundle.message("spec.action.stage.state.inProgress")
            else -> SpecCodingBundle.message("spec.action.stage.state.pending")
        }
    }

    private fun chipPalette(
        stage: SpecWorkflowStageStepState,
        focused: Boolean,
    ): ChipPalette {
        return when {
            stage.current -> ChipPalette(
                background = CURRENT_BG,
                border = CURRENT_BORDER,
                titleForeground = CURRENT_FG,
                statusForeground = CURRENT_FG,
                borderThickness = 2,
            )

            focused -> ChipPalette(
                background = FOCUSED_BG,
                border = FOCUSED_BORDER,
                titleForeground = FOCUSED_FG,
                statusForeground = FOCUSED_FG,
                borderThickness = 2,
            )

            !stage.active -> ChipPalette(
                background = INACTIVE_BG,
                border = INACTIVE_BORDER,
                titleForeground = INACTIVE_FG,
                statusForeground = INACTIVE_FG,
                borderThickness = 1,
            )

            stage.progress == StageProgress.DONE -> ChipPalette(
                background = DONE_BG,
                border = DONE_BORDER,
                titleForeground = DONE_FG,
                statusForeground = DONE_FG,
                borderThickness = 1,
            )

            else -> ChipPalette(
                background = ACTIVE_BG,
                border = ACTIVE_BORDER,
                titleForeground = ACTIVE_FG,
                statusForeground = ACTIVE_FG,
                borderThickness = 1,
            )
        }
    }

    private data class ChipPalette(
        val background: Color,
        val border: Color,
        val titleForeground: Color,
        val statusForeground: Color,
        val borderThickness: Int,
    )

    companion object {
        private const val ARROW_TEXT = "→"

        private val CONNECTOR_FG = JBColor(Color(132, 141, 153), Color(122, 130, 142))

        private val CURRENT_BG = JBColor(Color(241, 246, 255), Color(53, 71, 109))
        private val CURRENT_FG = JBColor(Color(33, 72, 153), Color(212, 224, 255))
        private val CURRENT_BORDER = JBColor(Color(160, 185, 238), Color(109, 130, 176))

        private val FOCUSED_BG = JBColor(Color(245, 249, 255), Color(62, 71, 86))
        private val FOCUSED_FG = JBColor(Color(52, 84, 148), Color(216, 227, 244))
        private val FOCUSED_BORDER = JBColor(Color(123, 164, 230), Color(132, 164, 211))

        private val DONE_BG = JBColor(Color(239, 248, 242), Color(49, 74, 57))
        private val DONE_FG = JBColor(Color(39, 94, 57), Color(200, 234, 208))
        private val DONE_BORDER = JBColor(Color(173, 210, 184), Color(87, 134, 102))

        private val ACTIVE_BG = JBColor(Color(247, 249, 252), Color(64, 70, 79))
        private val ACTIVE_FG = JBColor(Color(70, 79, 91), Color(218, 224, 231))
        private val ACTIVE_BORDER = JBColor(Color(213, 220, 230), Color(98, 108, 121))

        private val INACTIVE_BG = JBColor(Color(248, 249, 251), Color(62, 65, 71))
        private val INACTIVE_FG = JBColor(Color(131, 139, 149), Color(162, 170, 180))
        private val INACTIVE_BORDER = JBColor(Color(221, 225, 231), Color(96, 102, 112))
    }
}
