package com.eacape.speccodingplugin.ui.spec

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.JViewport
import javax.swing.border.AbstractBorder
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

internal data class SpecIconActionPresentation(
    val icon: Icon,
    val text: String = "",
    val tooltip: String,
    val accessibleName: String = tooltip,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

internal object SpecUiStyle {
    fun roundedLineBorder(
        lineColor: Color,
        arc: Int,
        thickness: Int = 1,
    ): Border = RoundedLineBorder(
        lineColor = lineColor,
        arc = arc,
        thickness = thickness,
    )

    fun roundedCardBorder(
        lineColor: Color,
        arc: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    ): Border = CompoundBorder(
        roundedLineBorder(lineColor = lineColor, arc = arc),
        JBUI.Borders.empty(top, left, bottom, right),
    )

    fun applyRoundRect(button: AbstractButton, arc: Int) {
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(arc))
    }

    fun configureIconActionButton(
        button: JButton,
        icon: Icon,
        text: String = "",
        tooltip: String,
        accessibleName: String = tooltip,
    ) {
        val label = text.trim()
        button.text = label
        button.icon = icon
        button.disabledIcon = IconLoader.getDisabledIcon(icon)
        button.iconTextGap = if (label.isEmpty()) 0 else JBUI.scale(6)
        button.horizontalTextPosition = SwingConstants.RIGHT
        button.verticalTextPosition = SwingConstants.CENTER
        button.horizontalAlignment = SwingConstants.CENTER
        button.margin = if (label.isEmpty()) JBUI.insets(0) else JBUI.insets(0, 10, 0, 10)
        button.putClientProperty(ICON_ACTION_TOOLTIP_KEY, tooltip)
        button.putClientProperty(ICON_ACTION_ACCESSIBLE_NAME_KEY, accessibleName)
        syncIconActionButtonSize(button)
        syncIconActionButtonSemantics(button)
    }

    fun applyIconActionPresentation(button: JButton, presentation: SpecIconActionPresentation) {
        configureIconActionButton(
            button = button,
            icon = presentation.icon,
            text = presentation.text,
            tooltip = presentation.tooltip,
            accessibleName = presentation.accessibleName,
        )
        setIconActionEnabled(
            button = button,
            enabled = presentation.enabled,
            disabledReason = presentation.disabledReason,
        )
    }

    fun setIconActionEnabled(
        button: JButton,
        enabled: Boolean,
        disabledReason: String? = null,
    ) {
        button.putClientProperty(ICON_ACTION_DISABLED_REASON_KEY, disabledReason)
        button.isEnabled = enabled
        syncIconActionButtonSemantics(button)
    }

    fun styleIconActionButton(
        button: JButton,
        size: Int = 24,
        arc: Int = 10,
    ) {
        button.isFocusable = true
        button.isRequestFocusEnabled = true
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.isOpaque = true
        button.margin = JBUI.insets(0)
        applyRoundRect(button, arc)
        installIconActionButtonCursorTracking(button)
        installIconActionButtonStateTracking(button)
        applyIconActionButtonVisualState(button)
        val scaledSize = JBUI.scale(size)
        button.putClientProperty(ICON_ACTION_SIZE_KEY, scaledSize)
        button.preferredSize = JBUI.size(scaledSize, scaledSize)
        button.minimumSize = button.preferredSize
    }

    fun applySlimHorizontalScrollBar(scrollPane: JScrollPane, height: Int = 7) {
        val barHeight = JBUI.scale(height)
        val trackColor = JBColor(Color(224, 233, 247), Color(72, 80, 92))
        val thumbColor = JBColor(Color(143, 171, 214), Color(122, 143, 171))
        val topLineColor = JBColor(Color(191, 206, 229), Color(87, 97, 112))

        scrollPane.viewportBorder = JBUI.Borders.emptyBottom(barHeight + JBUI.scale(3))
        scrollPane.horizontalScrollBar.apply {
            preferredSize = JBUI.size(0, barHeight)
            minimumSize = JBUI.size(0, barHeight)
            maximumSize = JBUI.size(Int.MAX_VALUE, barHeight)
            unitIncrement = JBUI.scale(24)
            blockIncrement = JBUI.scale(96)
            isOpaque = true
            background = trackColor
            foreground = thumbColor
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, topLineColor)
            putClientProperty("JScrollBar.showButtons", false)
            putClientProperty("JComponent.sizeVariant", "regular")
            putClientProperty("JScrollBar.trackColor", trackColor)
            putClientProperty("JScrollBar.thumbColor", thumbColor)
        }
        scrollPane.putClientProperty("JScrollPane.smoothScrolling", true)
    }

    fun applyFastVerticalScrolling(
        scrollPane: JScrollPane,
        unitIncrement: Int = 24,
        blockIncrement: Int = 96,
    ) {
        val unit = JBUI.scale(unitIncrement)
        val block = JBUI.scale(blockIncrement).coerceAtLeast(unit * 2)
        scrollPane.verticalScrollBar.unitIncrement = unit
        scrollPane.verticalScrollBar.blockIncrement = block
        scrollPane.verticalScrollBar.putClientProperty("JScrollBar.fastWheelScrolling", true)
        scrollPane.viewport.scrollMode = JViewport.BLIT_SCROLL_MODE
        scrollPane.putClientProperty("JScrollPane.smoothScrolling", true)
    }

    fun applySplitPaneDivider(
        splitPane: JSplitPane,
        dividerSize: Int = JBUI.scale(8),
        dividerBackground: Color = JBColor(Color(236, 240, 246), Color(66, 72, 82)),
        dividerBorderColor: Color = JBColor(Color(217, 223, 232), Color(78, 86, 98, 110)),
        drawDarkBorder: Boolean = false,
    ) {
        val horizontal = splitPane.orientation == JSplitPane.HORIZONTAL_SPLIT
        val cursorType = if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR
        val cursor = Cursor.getPredefinedCursor(cursorType)
        splitPane.dividerSize = dividerSize
        splitPane.isOneTouchExpandable = false
        (splitPane.ui as? BasicSplitPaneUI)?.divider?.let { divider ->
            divider.cursor = cursor
            divider.background = dividerBackground
            divider.border = if (horizontal) {
                if (StartupUiUtil.isUnderDarcula && !drawDarkBorder) {
                    JBUI.Borders.empty()
                } else {
                    JBUI.Borders.customLine(dividerBorderColor, 0, 1, 0, 1)
                }
            } else {
                if (StartupUiUtil.isUnderDarcula && !drawDarkBorder) {
                    JBUI.Borders.empty()
                } else {
                    JBUI.Borders.customLine(dividerBorderColor, 1, 0, 1, 0)
                }
            }
        }
    }

    fun applyChatLikeSpecDivider(
        splitPane: JSplitPane,
        dividerSize: Int = JBUI.scale(4),
    ) {
        splitPane.ui = GripSplitPaneUI()
        applySplitPaneDivider(
            splitPane = splitPane,
            dividerSize = dividerSize,
            dividerBackground = JBColor(
                Color(236, 240, 246),
                Color(74, 80, 89),
            ),
            dividerBorderColor = JBColor(
                Color(217, 223, 232),
                Color(87, 94, 105),
            ),
            drawDarkBorder = true,
        )
    }

    private fun installIconActionButtonStateTracking(button: JButton) {
        if (button.getClientProperty("spec.iconActionButton.stateTrackingInstalled") == true) return
        button.putClientProperty("spec.iconActionButton.stateTrackingInstalled", true)
        button.isRolloverEnabled = true
        button.addChangeListener { applyIconActionButtonVisualState(button) }
        button.addPropertyChangeListener("enabled") {
            applyIconActionButtonVisualState(button)
            syncIconActionButtonSemantics(button)
        }
    }

    private fun installIconActionButtonCursorTracking(button: JButton) {
        if (button.getClientProperty("spec.iconActionButton.cursorTrackingInstalled") == true) return
        button.putClientProperty("spec.iconActionButton.cursorTrackingInstalled", true)
        updateIconActionButtonCursor(button)
        button.addPropertyChangeListener("enabled") { updateIconActionButtonCursor(button) }
    }

    private fun updateIconActionButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun syncIconActionButtonSemantics(button: JButton) {
        val enabledTooltip = (button.getClientProperty(ICON_ACTION_TOOLTIP_KEY) as? String).orEmpty()
        val accessibleName = (button.getClientProperty(ICON_ACTION_ACCESSIBLE_NAME_KEY) as? String)
            ?.takeIf { it.isNotBlank() }
            ?: enabledTooltip
        val disabledReason = (button.getClientProperty(ICON_ACTION_DISABLED_REASON_KEY) as? String)
            ?.takeIf { it.isNotBlank() }
        val effectiveTooltip = if (!button.isEnabled && disabledReason != null) disabledReason else enabledTooltip
        val accessibleDescription = if (!button.isEnabled && disabledReason != null) {
            "$accessibleName. $disabledReason"
        } else {
            enabledTooltip
        }
        button.toolTipText = effectiveTooltip.ifBlank { null }
        button.accessibleContext?.accessibleName = accessibleName.ifBlank { null }
        button.accessibleContext?.accessibleDescription = accessibleDescription.ifBlank { null }
    }

    private fun syncIconActionButtonSize(button: JButton) {
        val configuredSize = (button.getClientProperty(ICON_ACTION_SIZE_KEY) as? Int) ?: JBUI.scale(24)
        val label = button.text.orEmpty()
        if (label.isBlank()) {
            val size = JBUI.size(configuredSize, configuredSize)
            button.preferredSize = size
            button.minimumSize = size
            return
        }
        val textWidth = button.getFontMetrics(button.font).stringWidth(label)
        val iconWidth = button.icon?.iconWidth ?: 0
        val width = maxOf(
            configuredSize,
            textWidth + iconWidth + button.iconTextGap + button.margin.left + button.margin.right + JBUI.scale(10),
        )
        val size = JBUI.size(width, maxOf(configuredSize, JBUI.scale(26)))
        button.preferredSize = size
        button.minimumSize = size
    }

    private fun applyIconActionButtonVisualState(button: JButton) {
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
            roundedLineBorder(borderColor, JBUI.scale(10), thickness = borderThickness),
            JBUI.Borders.empty(1),
        )
    }

    private class GripSplitPaneUI : BasicSplitPaneUI() {
        override fun createDefaultDivider(): BasicSplitPaneDivider {
            return object : BasicSplitPaneDivider(this) {
                override fun paint(g: Graphics) {
                    super.paint(g)
                    val g2 = g as? Graphics2D ?: return
                    val horizontal = splitPane?.orientation == JSplitPane.HORIZONTAL_SPLIT
                    val w = width
                    val h = height
                    if (w <= 0 || h <= 0) return

                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (horizontal) {
                        paintVerticalGrip(g2, w, h)
                    } else {
                        paintHorizontalGrip(g2, w, h)
                    }
                }

                private fun paintVerticalGrip(g2: Graphics2D, w: Int, h: Int) {
                    val trackWidth = JBUI.scale(3)
                    val trackHeight = JBUI.scale(30)
                    val trackX = (w - trackWidth) / 2
                    val trackY = (h - trackHeight) / 2
                    g2.color = JBColor(
                        Color(225, 232, 242),
                        Color(92, 99, 111),
                    )
                    g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackWidth, trackWidth)

                    val dotSize = JBUI.scale(2)
                    val dotStep = JBUI.scale(5)
                    val dotX = (w - dotSize) / 2
                    val dotStart = trackY + JBUI.scale(5)
                    val dotEnd = trackY + trackHeight - dotSize - JBUI.scale(4)
                    g2.color = JBColor(
                        Color(152, 166, 186),
                        Color(167, 179, 196),
                    )
                    var y = dotStart
                    while (y <= dotEnd) {
                        g2.fillOval(dotX, y, dotSize, dotSize)
                        y += dotStep
                    }
                }

                private fun paintHorizontalGrip(g2: Graphics2D, w: Int, h: Int) {
                    val trackWidth = JBUI.scale(30)
                    val trackHeight = JBUI.scale(3)
                    val trackX = (w - trackWidth) / 2
                    val trackY = (h - trackHeight) / 2
                    g2.color = JBColor(
                        Color(225, 232, 242),
                        Color(92, 99, 111),
                    )
                    g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackHeight, trackHeight)

                    val dotSize = JBUI.scale(2)
                    val dotStep = JBUI.scale(5)
                    val dotY = (h - dotSize) / 2
                    val dotStart = trackX + JBUI.scale(5)
                    val dotEnd = trackX + trackWidth - dotSize - JBUI.scale(4)
                    g2.color = JBColor(
                        Color(152, 166, 186),
                        Color(167, 179, 196),
                    )
                    var x = dotStart
                    while (x <= dotEnd) {
                        g2.fillOval(x, dotY, dotSize, dotSize)
                        x += dotStep
                    }
                }
            }
        }
    }

    private class RoundedLineBorder(
        private val lineColor: Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: java.awt.Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = lineColor
                val safeThickness = thickness.coerceAtLeast(1)
                repeat(safeThickness) { index ->
                    val offset = index + 0.5f
                    val drawWidth = width - index * 2 - 1f
                    val drawHeight = height - index * 2 - 1f
                    if (drawWidth <= 0f || drawHeight <= 0f) {
                        return@repeat
                    }
                    val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                    g2.draw(
                        RoundRectangle2D.Float(
                            x + offset,
                            y + offset,
                            drawWidth,
                            drawHeight,
                            arcSize,
                            arcSize,
                        ),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: java.awt.Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: java.awt.Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }

    private val ICON_BUTTON_BG = JBColor(Color(246, 250, 255), Color(68, 75, 87))
    private val ICON_BUTTON_BORDER = JBColor(Color(178, 198, 226), Color(104, 116, 134))
    private val ICON_BUTTON_BG_HOVER = JBColor(Color(236, 246, 255), Color(76, 86, 100))
    private val ICON_BUTTON_BORDER_HOVER = JBColor(Color(124, 167, 229), Color(124, 158, 205))
    private val ICON_BUTTON_BG_ACTIVE = JBColor(Color(226, 239, 255), Color(84, 97, 116))
    private val ICON_BUTTON_BORDER_ACTIVE = JBColor(Color(89, 136, 208), Color(143, 182, 232))
    private val ICON_BUTTON_BG_DISABLED = JBColor(Color(247, 250, 254), Color(66, 72, 83))
    private val ICON_BUTTON_BORDER_DISABLED = JBColor(Color(198, 205, 216), Color(96, 106, 121))
    private const val ICON_ACTION_SIZE_KEY = "spec.iconActionButton.size"
    private const val ICON_ACTION_TOOLTIP_KEY = "spec.iconActionButton.tooltip"
    private const val ICON_ACTION_ACCESSIBLE_NAME_KEY = "spec.iconActionButton.accessibleName"
    private const val ICON_ACTION_DISABLED_REASON_KEY = "spec.iconActionButton.disabledReason"
}
