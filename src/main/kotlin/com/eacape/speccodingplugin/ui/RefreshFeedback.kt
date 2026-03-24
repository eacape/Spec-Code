package com.eacape.speccodingplugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import java.awt.Color
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.Timer

object RefreshFeedback {
    private const val BUTTON_TIMER_KEY = "spec.refresh.feedback.button.timer"
    private const val LABEL_TIMER_KEY = "spec.refresh.feedback.label.timer"
    private val SUCCESS_ICON = AllIcons.General.GreenCheckmark
    private const val FEEDBACK_DURATION_MILLIS = 1200

    fun flashButtonSuccess(
        button: AbstractButton,
        successText: String,
        durationMillis: Int = FEEDBACK_DURATION_MILLIS,
    ) {
        val host = button as? JComponent ?: return
        stopExistingTimer(host, BUTTON_TIMER_KEY)

        val originalIcon = button.icon
        val originalDisabledIcon = button.disabledIcon
        val originalToolTip = button.toolTipText
        val originalAccessibleName = button.accessibleContext?.accessibleName
        val originalAccessibleDescription = button.accessibleContext?.accessibleDescription

        button.icon = SUCCESS_ICON
        button.disabledIcon = IconLoader.getDisabledIcon(SUCCESS_ICON)
        button.toolTipText = successText
        button.accessibleContext?.accessibleName = successText
        button.accessibleContext?.accessibleDescription = successText

        val timer = Timer(durationMillis) {
            button.icon = originalIcon
            button.disabledIcon = originalDisabledIcon
            button.toolTipText = originalToolTip
            button.accessibleContext?.accessibleName = originalAccessibleName
            button.accessibleContext?.accessibleDescription = originalAccessibleDescription
            host.putClientProperty(BUTTON_TIMER_KEY, null)
        }
        timer.isRepeats = false
        host.putClientProperty(BUTTON_TIMER_KEY, timer)
        timer.start()
    }

    fun flashLabelSuccess(
        label: JLabel,
        successText: String,
        successColor: Color? = null,
        durationMillis: Int = FEEDBACK_DURATION_MILLIS,
    ) {
        stopExistingTimer(label, LABEL_TIMER_KEY)

        val originalText = label.text
        val originalToolTip = label.toolTipText
        val originalForeground = label.foreground
        label.text = successText
        label.toolTipText = null
        if (successColor != null) {
            label.foreground = successColor
        }

        val timer = Timer(durationMillis) {
            if (label.text == successText) {
                label.text = originalText
                label.toolTipText = originalToolTip
                label.foreground = originalForeground
            }
            label.putClientProperty(LABEL_TIMER_KEY, null)
        }
        timer.isRepeats = false
        label.putClientProperty(LABEL_TIMER_KEY, timer)
        timer.start()
    }

    private fun stopExistingTimer(component: JComponent, key: String) {
        (component.getClientProperty(key) as? Timer)?.stop()
    }
}
