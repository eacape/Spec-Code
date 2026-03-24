package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.CodeChangeSource
import com.eacape.speccodingplugin.spec.CodeContextCandidateFile
import com.eacape.speccodingplugin.spec.CodeContextCandidateSignal
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.CodeVerificationEntryPoint
import com.eacape.speccodingplugin.spec.ProjectStructureSummary
import com.eacape.speccodingplugin.spec.SpecPhase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel

internal class SpecComposerCodeContextPanel : JPanel(BorderLayout(0, JBUI.scale(6))) {

    private var workflowId: String? = null
    private var codeContextPack: CodeContextPack? = null

    private val titleLabel = JBLabel(SpecCodingBundle.message("spec.detail.codeContext.title"))
    private val metaLabel = JBLabel()
    private val hintLabel = JBLabel()
    private val summaryChipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val candidateChipRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))

    init {
        isOpaque = true
        background = STRIP_BG
        border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(STRIP_BORDER, JBUI.scale(12)),
            JBUI.Borders.empty(8, 10, 8, 10),
        )

        titleLabel.font = JBUI.Fonts.smallFont().deriveFont(titleLabel.font.style or Font.BOLD)
        titleLabel.foreground = TITLE_FG
        metaLabel.font = JBUI.Fonts.miniFont()
        metaLabel.foreground = META_FG
        hintLabel.font = JBUI.Fonts.miniFont()
        hintLabel.foreground = HINT_FG

        summaryChipRow.isOpaque = false
        candidateChipRow.isOpaque = false

        add(
            JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.NORTH)
                add(metaLabel, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(summaryChipRow, BorderLayout.NORTH)
                add(candidateChipRow, BorderLayout.CENTER)
                add(hintLabel, BorderLayout.SOUTH)
            },
            BorderLayout.CENTER,
        )
        isVisible = false
    }

    fun updateState(workflowId: String?, codeContextPack: CodeContextPack?) {
        this.workflowId = workflowId?.trim()?.ifBlank { null }
        this.codeContextPack = codeContextPack
        rebuildUi()
    }

    fun clear() {
        updateState(workflowId = null, codeContextPack = null)
    }

    fun refreshLocalizedTexts() {
        rebuildUi()
    }

    private fun rebuildUi() {
        summaryChipRow.removeAll()
        candidateChipRow.removeAll()
        titleLabel.text = SpecCodingBundle.message("spec.detail.codeContext.title")

        val hasWorkflow = workflowId != null
        val pack = codeContextPack
        isVisible = hasWorkflow

        if (!hasWorkflow) {
            metaLabel.text = ""
            hintLabel.text = ""
            revalidate()
            repaint()
            return
        }

        metaLabel.text = buildMetaText(pack)
        hintLabel.text = buildHintText(pack)

        pack?.let {
            appendSummaryChips(it)
            appendCandidateFileChips(it)
        }

        summaryChipRow.isVisible = summaryChipRow.componentCount > 0
        candidateChipRow.isVisible = candidateChipRow.componentCount > 0

        revalidate()
        repaint()
    }

    private fun appendSummaryChips(pack: CodeContextPack) {
        if (pack.projectStructure?.hasSignals() == true) {
            summaryChipRow.add(
                createChip(
                    label = SpecCodingBundle.message("spec.detail.codeContext.summary.repo"),
                    tooltip = buildProjectStructureTooltip(pack.projectStructure),
                    tone = ChipTone.INFO,
                ),
            )
        }

        summaryChipRow.add(
            createChip(
                label = SpecCodingBundle.message(
                    "spec.detail.codeContext.summary.diff",
                    changeSourceLabel(pack.changeSummary.source),
                ),
                tooltip = buildChangeSummaryTooltip(pack),
                tone = if (pack.changeSummary.available) ChipTone.INFO else ChipTone.MUTED,
            ),
        )

        if (pack.confirmedRelatedFiles.isNotEmpty()) {
            summaryChipRow.add(
                createChip(
                    label = SpecCodingBundle.message(
                        "spec.detail.codeContext.summary.related",
                        pack.confirmedRelatedFiles.size,
                    ),
                    tooltip = buildListTooltip(pack.confirmedRelatedFiles),
                    tone = ChipTone.SUCCESS,
                ),
            )
        }

        if (pack.verificationEntryPoints.isNotEmpty()) {
            summaryChipRow.add(
                createChip(
                    label = SpecCodingBundle.message(
                        "spec.detail.codeContext.summary.verify",
                        pack.verificationEntryPoints.size,
                    ),
                    tooltip = buildVerificationTooltip(pack.verificationEntryPoints),
                    tone = ChipTone.WARNING,
                ),
            )
        }

        if (pack.isDegraded()) {
            summaryChipRow.add(
                createChip(
                    label = SpecCodingBundle.message("spec.detail.codeContext.summary.degraded"),
                    tooltip = buildListTooltip(pack.degradationReasons),
                    tone = ChipTone.ERROR,
                ),
            )
        }
    }

    private fun appendCandidateFileChips(pack: CodeContextPack) {
        pack.candidateFiles.forEach { candidate ->
            candidateChipRow.add(
                createChip(
                    label = truncateTail(candidate.path, MAX_PATH_LABEL_LENGTH),
                    tooltip = buildCandidateTooltip(candidate),
                    tone = ChipTone.MUTED,
                ),
            )
        }
    }

    private fun buildMetaText(pack: CodeContextPack?): String {
        return when {
            pack == null -> SpecCodingBundle.message("spec.detail.codeContext.meta.preparing")
            pack.isDegraded() && !pack.hasAutoContext() -> SpecCodingBundle.message(
                "spec.detail.codeContext.meta.unavailable",
                phaseLabel(pack.phase),
            )
            else -> SpecCodingBundle.message(
                "spec.detail.codeContext.meta.ready",
                phaseLabel(pack.phase),
                pack.candidateFiles.size,
                changeSourceLabel(pack.changeSummary.source),
            )
        }
    }

    private fun buildHintText(pack: CodeContextPack?): String {
        return when {
            pack == null -> SpecCodingBundle.message("spec.detail.codeContext.hint.pending")
            pack.degradationReasons.isNotEmpty() -> pack.degradationReasons.joinToString(" ")
            pack.candidateFiles.isEmpty() -> SpecCodingBundle.message("spec.detail.codeContext.hint.noCandidates")
            else -> SpecCodingBundle.message("spec.detail.codeContext.hint.ready")
        }
    }

    private fun createChip(label: String, tooltip: String?, tone: ChipTone): JPanel {
        val colors = CHIP_COLORS.getValue(tone)
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 1, 0)).apply {
            isOpaque = true
            background = colors.background
            border = BorderFactory.createCompoundBorder(
                SpecUiStyle.roundedLineBorder(colors.border, JBUI.scale(10)),
                JBUI.Borders.empty(1, 6, 1, 6),
            )
            toolTipText = tooltip
        }
        chip.add(
            JBLabel(label).apply {
                font = JBUI.Fonts.miniFont()
                foreground = colors.foreground
                toolTipText = tooltip
            },
        )
        return chip
    }

    private fun buildProjectStructureTooltip(summary: ProjectStructureSummary): String {
        val lines = mutableListOf<String>()
        if (summary.topLevelDirectories.isNotEmpty()) {
            lines += "Top-level directories: ${summary.topLevelDirectories.joinToString(", ")}"
        }
        if (summary.topLevelFiles.isNotEmpty()) {
            lines += "Top-level files: ${summary.topLevelFiles.joinToString(", ")}"
        }
        if (summary.keyPaths.isNotEmpty()) {
            lines += "Key paths: ${summary.keyPaths.joinToString(", ")}"
        }
        return buildListTooltip(lines)
    }

    private fun buildChangeSummaryTooltip(pack: CodeContextPack): String {
        val summary = pack.changeSummary.summary.trim()
        val files = pack.changeSummary.files.map { file -> "${file.status.name}: ${file.path}" }
        val lines = buildList {
            add(summary.ifBlank { SpecCodingBundle.message("spec.detail.codeContext.hint.noCandidates") })
            addAll(files.take(MAX_TOOLTIP_LINES))
        }
        return buildListTooltip(lines)
    }

    private fun buildVerificationTooltip(entryPoints: List<CodeVerificationEntryPoint>): String {
        val lines = entryPoints.map { entry ->
            "${entry.displayName}: ${entry.commandPreview}"
        }
        return buildListTooltip(lines)
    }

    private fun buildCandidateTooltip(candidate: CodeContextCandidateFile): String {
        val signals = candidate.signals
            .map(::signalLabel)
            .sorted()
        val lines = mutableListOf(candidate.path)
        if (signals.isNotEmpty()) {
            lines += "Signals: ${signals.joinToString(", ")}"
        }
        return buildListTooltip(lines)
    }

    private fun buildListTooltip(lines: List<String>): String {
        if (lines.isEmpty()) {
            return ""
        }
        return buildString {
            append("<html>")
            lines
                .filter(String::isNotBlank)
                .take(MAX_TOOLTIP_LINES)
                .forEachIndexed { index, line ->
                    if (index > 0) {
                        append("<br/>")
                    }
                    append(escapeHtml(line))
                }
            append("</html>")
        }
    }

    private fun phaseLabel(phase: SpecPhase): String {
        return when (phase) {
            SpecPhase.SPECIFY -> SpecCodingBundle.message("spec.detail.step.requirements")
            SpecPhase.DESIGN -> SpecCodingBundle.message("spec.detail.step.design")
            SpecPhase.IMPLEMENT -> SpecCodingBundle.message("spec.detail.step.taskList")
        }
    }

    private fun changeSourceLabel(source: CodeChangeSource): String {
        return when (source) {
            CodeChangeSource.VCS_STATUS -> SpecCodingBundle.message("spec.detail.codeContext.diff.git")
            CodeChangeSource.WORKSPACE_CANDIDATES ->
                SpecCodingBundle.message("spec.detail.codeContext.diff.workspace")

            CodeChangeSource.NONE -> SpecCodingBundle.message("spec.detail.codeContext.diff.none")
        }
    }

    private fun signalLabel(signal: CodeContextCandidateSignal): String {
        return when (signal) {
            CodeContextCandidateSignal.EXPLICIT_SELECTION ->
                SpecCodingBundle.message("spec.detail.codeContext.signal.explicit")

            CodeContextCandidateSignal.CONFIRMED_RELATED_FILE ->
                SpecCodingBundle.message("spec.detail.codeContext.signal.related")

            CodeContextCandidateSignal.VCS_CHANGE ->
                SpecCodingBundle.message("spec.detail.codeContext.signal.vcs")

            CodeContextCandidateSignal.WORKSPACE_CANDIDATE ->
                SpecCodingBundle.message("spec.detail.codeContext.signal.workspace")

            CodeContextCandidateSignal.KEY_PROJECT_FILE ->
                SpecCodingBundle.message("spec.detail.codeContext.signal.key")
        }
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

    internal fun summaryChipLabelsForTest(): List<String> {
        return summaryChipRow.components
            .mapNotNull { component ->
                (component as? JPanel)
                    ?.components
                    ?.filterIsInstance<JBLabel>()
                    ?.firstOrNull()
                    ?.text
            }
    }

    internal fun candidateFileLabelsForTest(): List<String> {
        return candidateChipRow.components
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

    private enum class ChipTone {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
        MUTED,
    }

    private data class ChipColors(
        val background: Color,
        val border: Color,
        val foreground: Color,
    )

    private companion object {
        private const val MAX_PATH_LABEL_LENGTH = 48
        private const val MAX_TOOLTIP_LINES = 8
        private const val ELLIPSIS = "..."

        private val STRIP_BG = JBColor(Color(243, 248, 255), Color(49, 57, 70))
        private val STRIP_BORDER = JBColor(Color(193, 212, 240), Color(84, 98, 116))
        private val TITLE_FG = JBColor(Color(44, 68, 112), Color(194, 213, 244))
        private val META_FG = JBColor(Color(76, 97, 132), Color(162, 178, 198))
        private val HINT_FG = JBColor(Color(95, 108, 124), Color(149, 161, 179))
        private val CHIP_COLORS = mapOf(
            ChipTone.INFO to ChipColors(
                background = JBColor(Color(230, 240, 255), Color(65, 79, 100)),
                border = JBColor(Color(177, 201, 238), Color(98, 117, 145)),
                foreground = JBColor(Color(49, 76, 122), Color(214, 225, 244)),
            ),
            ChipTone.SUCCESS to ChipColors(
                background = JBColor(Color(231, 247, 238), Color(57, 84, 66)),
                border = JBColor(Color(180, 222, 195), Color(84, 121, 97)),
                foreground = JBColor(Color(45, 105, 71), Color(212, 240, 221)),
            ),
            ChipTone.WARNING to ChipColors(
                background = JBColor(Color(255, 245, 224), Color(96, 81, 48)),
                border = JBColor(Color(234, 205, 152), Color(133, 111, 71)),
                foreground = JBColor(Color(131, 93, 28), Color(247, 227, 185)),
            ),
            ChipTone.ERROR to ChipColors(
                background = JBColor(Color(255, 236, 236), Color(99, 63, 63)),
                border = JBColor(Color(235, 188, 188), Color(135, 84, 84)),
                foreground = JBColor(Color(145, 57, 57), Color(246, 208, 208)),
            ),
            ChipTone.MUTED to ChipColors(
                background = JBColor(Color(236, 242, 248), Color(63, 70, 81)),
                border = JBColor(Color(197, 208, 220), Color(94, 104, 118)),
                foreground = JBColor(Color(77, 92, 111), Color(205, 212, 223)),
            ),
        )
    }
}
