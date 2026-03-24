package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.JPanel

/**
 * 阶段指示器面板
 * 显示 Specify -> Design -> Implement 三个阶段的进度
 */
class SpecPhaseIndicatorPanel : JPanel() {

    private var currentPhase: SpecPhase = SpecPhase.SPECIFY
    private var completedPhases: Set<SpecPhase> = emptySet()

    private val completedColor = JBColor(Color(108, 149, 203), Color(126, 165, 215))
    private val activeColor = JBColor(Color(86, 133, 194), Color(143, 184, 232))
    private val activeHaloColor = JBColor(Color(233, 240, 249), Color(82, 97, 118))
    private val pendingColor = JBColor(Color(186, 196, 210), Color(107, 116, 129))
    private val textColor = JBColor(Color(68, 83, 106), Color(213, 223, 236))
    private val cardBg = JBColor(Color(248, 250, 253), Color(58, 64, 74))
    private val cardBorder = JBColor(Color(218, 225, 236), Color(87, 98, 114))
    private val trackBg = JBColor(Color(208, 216, 228), Color(89, 99, 115))

    init {
        preferredSize = Dimension(0, JBUI.scale(68))
        minimumSize = Dimension(0, JBUI.scale(68))
        isOpaque = false
    }

    fun updatePhase(workflow: SpecWorkflow) {
        currentPhase = workflow.currentPhase
        completedPhases = SpecPhase.entries
            .filter { phase -> workflow.documents.containsKey(phase) && phase != currentPhase }
            .toSet()
        repaint()
    }

    fun reset() {
        currentPhase = SpecPhase.SPECIFY
        completedPhases = emptySet()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val phases = SpecPhase.entries
        if (phases.isEmpty()) return

        val outerPaddingX = JBUI.scale(12)
        val outerPaddingY = JBUI.scale(6)
        val cardWidth = (width - outerPaddingX * 2).coerceAtLeast(JBUI.scale(180))
        val cardHeight = (height - outerPaddingY * 2).coerceAtLeast(JBUI.scale(50))
        val cardX = ((width - cardWidth) / 2).coerceAtLeast(0)
        val cardY = ((height - cardHeight) / 2).coerceAtLeast(0)
        val cardArc = JBUI.scale(12)

        g2.color = cardBg
        g2.fillRoundRect(cardX, cardY, cardWidth, cardHeight, cardArc, cardArc)
        g2.color = cardBorder
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(cardX, cardY, cardWidth - 1, cardHeight - 1, cardArc, cardArc)

        val phaseLabels = phases.map { it.displayName.lowercase() }
        val circleRadius = JBUI.scale(9)
        val labelFont = g2.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        val activeLabelFont = labelFont.deriveFont(Font.BOLD)
        val numberFont = g2.font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        g2.font = labelFont
        val labelMetrics = g2.fontMetrics
        val labelWidths = phaseLabels.map { labelMetrics.stringWidth(it) }
        val leftPadding = maxOf(
            JBUI.scale(18),
            (labelWidths.firstOrNull() ?: 0) / 2 + JBUI.scale(8),
        )
        val rightPadding = maxOf(
            JBUI.scale(18),
            (labelWidths.lastOrNull() ?: 0) / 2 + JBUI.scale(8),
        )
        val totalWidth = (cardWidth - leftPadding - rightPadding).coerceAtLeast(JBUI.scale(140))
        val startX = cardX + leftPadding
        val contentTop = cardY + JBUI.scale(6)
        val contentBottom = cardY + cardHeight - JBUI.scale(6)
        val labelGap = JBUI.scale(5)
        val minCenterY = contentTop + circleRadius
        val maxCenterY = contentBottom - circleRadius - labelGap - labelMetrics.height
        val centerY = (contentTop + circleRadius + JBUI.scale(1))
            .coerceIn(minCenterY, maxCenterY.coerceAtLeast(minCenterY))
        val labelBaseline = centerY + circleRadius + labelGap + labelMetrics.ascent
        val spacing = totalWidth / (phases.size - 1).coerceAtLeast(1)
        val currentIndex = phases.indexOf(currentPhase).coerceAtLeast(0)
        val furthestCompletedIndex = phases.indexOfLast { it in completedPhases }.coerceAtLeast(0)
        val progressIndex = maxOf(currentIndex, furthestCompletedIndex)

        val trackStartX = startX + circleRadius
        val trackEndX = startX + (phases.size - 1) * spacing - circleRadius
        g2.color = trackBg
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(trackStartX, centerY, trackEndX, centerY)

        val progressEndX = startX + progressIndex * spacing - circleRadius
        if (progressEndX > trackStartX) {
            g2.color = completedColor
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawLine(trackStartX, centerY, progressEndX, centerY)
        }

        for ((index, phase) in phases.withIndex()) {
            val cx = startX + index * spacing
            val color = when {
                phase in completedPhases -> completedColor
                phase == currentPhase -> activeColor
                else -> pendingColor
            }

            if (phase == currentPhase) {
                g2.color = activeHaloColor
                val haloRadius = circleRadius + JBUI.scale(3)
                g2.fillOval(cx - haloRadius, centerY - haloRadius, haloRadius * 2, haloRadius * 2)
            }
            g2.color = color
            g2.fillOval(cx - circleRadius, centerY - circleRadius, circleRadius * 2, circleRadius * 2)

            g2.color = Color.WHITE
            g2.font = numberFont
            val numStr = if (phase in completedPhases) "\u2713" else "${index + 1}"
            val fm = g2.fontMetrics
            g2.drawString(numStr, cx - fm.stringWidth(numStr) / 2, centerY + fm.ascent / 2 - 1)

            val label = phaseLabels[index]
            g2.font = if (phase == currentPhase) activeLabelFont else labelFont
            val lm = g2.fontMetrics
            val labelX = cx - lm.stringWidth(label) / 2
            val labelY = labelBaseline

            g2.color = when {
                phase == currentPhase -> textColor
                phase in completedPhases -> completedColor
                else -> pendingColor
            }
            g2.drawString(label, labelX, labelY)
        }
    }
}
