package com.eacape.speccodingplugin.ui

import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.ListModel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

object ComboBoxAutoWidthSupport {
    private const val STATE_CLIENT_PROPERTY = "spec.combo.autoWidth.state"
    private const val MEASURING_CLIENT_PROPERTY = "spec.combo.autoWidth.measuring"

    fun installSelectedItemAutoWidth(
        comboBox: JComboBox<*>,
        minWidth: Int = 0,
        maxWidth: Int = Int.MAX_VALUE,
        height: Int? = null,
    ) {
        val safeMinWidth = minWidth.coerceAtLeast(0)
        val safeMaxWidth = if (maxWidth == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            maxWidth.coerceAtLeast(safeMinWidth)
        }
        val config = AutoWidthConfig(
            minWidth = safeMinWidth,
            maxWidth = safeMaxWidth,
            height = height?.coerceAtLeast(1),
        )
        val existing = comboBox.getClientProperty(STATE_CLIENT_PROPERTY) as? AutoWidthState
        if (existing != null) {
            existing.config = config
            refreshSelectedItemAutoWidth(comboBox)
            return
        }

        val state = AutoWidthState(
            config = config,
            modelListener = object : ListDataListener {
                override fun intervalAdded(e: ListDataEvent?) = refreshSelectedItemAutoWidth(comboBox)

                override fun intervalRemoved(e: ListDataEvent?) = refreshSelectedItemAutoWidth(comboBox)

                override fun contentsChanged(e: ListDataEvent?) = refreshSelectedItemAutoWidth(comboBox)
            },
        )
        comboBox.putClientProperty(STATE_CLIENT_PROPERTY, state)
        attachModelListener(comboBox.model, state.modelListener)
        comboBox.addPropertyChangeListener("model") { event ->
            detachModelListener(event.oldValue, state.modelListener)
            attachModelListener(event.newValue, state.modelListener)
            refreshSelectedItemAutoWidth(comboBox)
        }
        comboBox.addPropertyChangeListener("renderer") { refreshSelectedItemAutoWidth(comboBox) }
        comboBox.addPropertyChangeListener("font") { refreshSelectedItemAutoWidth(comboBox) }
        refreshSelectedItemAutoWidth(comboBox)
    }

    fun refreshSelectedItemAutoWidth(comboBox: JComboBox<*>) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { refreshSelectedItemAutoWidth(comboBox) }
            return
        }
        val state = comboBox.getClientProperty(STATE_CLIENT_PROPERTY) as? AutoWidthState ?: return
        val preferredSize = measureSelectedItemPreferredSize(comboBox)
        val targetWidth = preferredSize.width.coerceIn(state.config.minWidth, state.config.maxWidth)
        val targetHeight = (state.config.height ?: preferredSize.height).coerceAtLeast(1)
        val preferred = Dimension(targetWidth, targetHeight)
        val minimum = Dimension(state.config.minWidth.coerceAtMost(targetWidth), targetHeight)
        comboBox.preferredSize = preferred
        comboBox.minimumSize = minimum
        comboBox.maximumSize = preferred
        comboBox.revalidate()
        comboBox.repaint()
    }

    fun isIntrinsicMeasurementInProgress(comboBox: JComboBox<*>): Boolean {
        return comboBox.getClientProperty(MEASURING_CLIENT_PROPERTY) == true
    }

    fun createLeftAlignedHost(comboBox: JComboBox<*>): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(comboBox)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun measureSelectedItemPreferredSize(comboBox: JComboBox<*>): Dimension {
        val swingCombo = comboBox as JComboBox<Any?>
        val originalPreferredSize = comboBox.preferredSize
        val originalMinimumSize = comboBox.minimumSize
        val originalMaximumSize = comboBox.maximumSize
        val originalPrototype = swingCombo.prototypeDisplayValue
        val measurementValue = swingCombo.selectedItem ?: if (swingCombo.itemCount > 0) swingCombo.getItemAt(0) else null
        return try {
            comboBox.preferredSize = null
            comboBox.minimumSize = null
            comboBox.maximumSize = null
            comboBox.putClientProperty(MEASURING_CLIENT_PROPERTY, true)
            swingCombo.prototypeDisplayValue = measurementValue
            comboBox.preferredSize
        } finally {
            comboBox.putClientProperty(MEASURING_CLIENT_PROPERTY, false)
            swingCombo.prototypeDisplayValue = originalPrototype
            comboBox.preferredSize = originalPreferredSize
            comboBox.minimumSize = originalMinimumSize
            comboBox.maximumSize = originalMaximumSize
        }
    }

    private fun attachModelListener(model: Any?, listener: ListDataListener) {
        (model as? ListModel<*>)?.addListDataListener(listener)
    }

    private fun detachModelListener(model: Any?, listener: ListDataListener) {
        (model as? ListModel<*>)?.removeListDataListener(listener)
    }

    private data class AutoWidthConfig(
        val minWidth: Int,
        val maxWidth: Int,
        val height: Int?,
    )

    private class AutoWidthState(
        var config: AutoWidthConfig,
        val modelListener: ListDataListener,
    )
}
