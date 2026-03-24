package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * 模型选择器状态栏 Widget 工厂
 */
class ModelSelectorWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ModelSelectorWidget.ID

    override fun getDisplayName(): String = SpecCodingBundle.message("statusbar.modelSelector.displayName")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return ModelSelectorWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
