package com.eacape.speccodingplugin.ui.spec

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

internal data class SpecDetailActionBarLayout(
    val buttonPanel: JPanel,
    val footerContainer: JPanel,
)

internal class SpecDetailActionBarLayoutBuilder(
    private val buttons: SpecDetailActionBarButtons,
    private val presenter: SpecDetailActionBarPresenter,
    private val chromePresenter: SpecDetailActionBarChromePresenter,
    private val commandAdapter: SpecDetailActionBarCommandAdapter,
    private val footerDivider: Color,
    private val initializePresentation: () -> Unit,
) {

    fun build(): SpecDetailActionBarLayout {
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
        }

        initializePresentation()
        chromePresenter.applySetupVisibility()
        commandAdapter.bind()
        buttons.composerActions().forEach(buttonPanel::add)
        presenter.disableAll()

        return SpecDetailActionBarLayout(
            buttonPanel = buttonPanel,
            footerContainer = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(JBUI.scale(1), 0, 0, 0, footerDivider),
                    JBUI.Borders.empty(8, 2, 0, 2),
                )
                add(
                    JBScrollPane(buttonPanel).apply {
                        border = JBUI.Borders.empty(1, 3)
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                        viewport.isOpaque = false
                        isOpaque = false
                        SpecUiStyle.applySlimHorizontalScrollBar(this, height = 7)
                    },
                    BorderLayout.CENTER,
                )
            },
        )
    }
}
