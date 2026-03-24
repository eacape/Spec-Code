package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.ui.chat.ChangesetTimelinePanel
import com.eacape.speccodingplugin.ui.spec.SpecWorkflowPanel
import com.eacape.speccodingplugin.ui.worktree.WorktreePanel
import com.eacape.speccodingplugin.window.WindowStateStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContentUiType
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.SwingUtilities

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    companion object {
        internal const val TOOL_WINDOW_ID = "Spec Code"
        private val CHAT_CONTENT_KEY = Key.create<Boolean>("SpecCoding.ChatContent")
        private val SPEC_CONTENT_KEY = Key.create<Boolean>("SpecCoding.SpecContent")
        private val CHANGES_CONTENT_KEY = Key.create<Boolean>("SpecCoding.ChangesContent")
        private val WORKTREE_CONTENT_KEY = Key.create<Boolean>("SpecCoding.WorktreeContent")
        private val CHANGES_TITLE_ICON =
            IconLoader.getIcon("/icons/toolwindow-changes.svg", ChatToolWindowFactory::class.java)

        internal fun isPrimaryContent(content: Content?): Boolean = isChatContent(content) || isSpecContent(content)

        internal fun isChatContent(content: Content?): Boolean = content?.getUserData(CHAT_CONTENT_KEY) == true

        internal fun isSpecContent(content: Content?): Boolean = content?.getUserData(SPEC_CONTENT_KEY) == true

        internal fun selectChatContent(toolWindow: ToolWindow, project: Project? = null): Boolean {
            return selectContent(
                toolWindow = toolWindow,
                matcher = ::isChatContent,
                ensureContent = project?.let { { ensurePrimaryContents(it, toolWindow).chatContent } },
            )
        }

        internal fun selectSpecContent(toolWindow: ToolWindow, project: Project? = null): Boolean {
            return selectContent(
                toolWindow = toolWindow,
                matcher = ::isSpecContent,
                ensureContent = project?.let { { ensurePrimaryContents(it, toolWindow).specContent } },
            )
        }

        internal fun selectContent(
            toolWindow: ToolWindow,
            matcher: (Content?) -> Boolean,
            ensureContent: (() -> Content?)? = null,
        ): Boolean {
            ensureTabbedContentUi(toolWindow)
            val contentManager = toolWindow.contentManager
            val content = contentManager.contents.firstOrNull(matcher)
                ?: ensureContent?.invoke()
                ?: return false
            contentManager.setSelectedContent(content)
            return true
        }

        internal fun ensurePrimaryContents(project: Project, toolWindow: ToolWindow): PrimaryContents {
            ensureTabbedContentUi(toolWindow)
            val contentManager = toolWindow.contentManager
            val chatContent = ensureContent(
                contentManager = contentManager,
                matcher = ::isChatContent,
                create = { createChatContent(project) },
            )
            val specContent = ensureContent(
                contentManager = contentManager,
                matcher = ::isSpecContent,
                create = { createSpecContent(project) },
            )
            chatContent.displayName = SpecCodingBundle.message("toolwindow.tab.chat")
            specContent.displayName = SpecCodingBundle.message("spec.tab.title")
            return PrimaryContents(
                chatContent = chatContent,
                specContent = specContent,
            )
        }

        internal fun ensureTabbedContentUi(toolWindow: ToolWindow) {
            toolWindow.setDefaultContentUiType(ToolWindowContentUiType.TABBED)
            if (toolWindow.contentUiType == ToolWindowContentUiType.TABBED) {
                return
            }
            toolWindow.setContentUiType(ToolWindowContentUiType.TABBED, null)
            (toolWindow as? ToolWindowEx)?.updateContentUi()
        }

        internal fun ensureContent(
            contentManager: ContentManager,
            matcher: (Content) -> Boolean,
            create: () -> Content,
        ): Content {
            val existing = contentManager.contents.firstOrNull(matcher)
            if (existing != null) {
                return existing
            }
            return create().also(contentManager::addContent)
        }

        private fun createChatContent(project: Project): Content {
            val chatPanel = ImprovedChatPanel(project)
            return ContentFactory.getInstance()
                .createContent(chatPanel, SpecCodingBundle.message("toolwindow.tab.chat"), false)
                .also { content ->
                    content.putUserData(CHAT_CONTENT_KEY, true)
                    Disposer.register(content, chatPanel)
                }
        }

        private fun createSpecContent(project: Project): Content {
            val specPanel = SpecWorkflowPanel(project)
            return ContentFactory.getInstance()
                .createContent(specPanel, SpecCodingBundle.message("spec.tab.title"), false)
                .also { content ->
                    content.putUserData(SPEC_CONTENT_KEY, true)
                    Disposer.register(content, specPanel)
                }
        }
    }

    internal data class PrimaryContents(
        val chatContent: Content,
        val specContent: Content,
    )

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = SpecCodingBundle.message("toolwindow.title")
        ensureTabbedContentUi(toolWindow)
        val windowStateStore = WindowStateStore.getInstance(project)

        val newSessionTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                focusChatTab(toolWindow, project)
                currentChatPanel(toolWindow)?.requestNewSessionFromTitleAction()
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.session.new.tooltip")
                e.presentation.icon = AllIcons.General.Add
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val openHistoryTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                currentChatPanel(toolWindow)?.requestOpenHistoryFromTitleAction()
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.history.open.tooltip")
                e.presentation.icon = AllIcons.Vcs.History
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val openChangesTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                openOrSelectTransientContent(
                    toolWindow = toolWindow,
                    contentKey = CHANGES_CONTENT_KEY,
                    title = SpecCodingBundle.message("toolwindow.tab.changes"),
                    createPanel = { ChangesetTimelinePanel(project) },
                )
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.changes.open.tooltip")
                e.presentation.icon = CHANGES_TITLE_ICON
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val openWorktreeTitleAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (project.isDisposed) return
                openOrSelectTransientContent(
                    toolWindow = toolWindow,
                    contentKey = WORKTREE_CONTENT_KEY,
                    title = SpecCodingBundle.message("worktree.tab.title"),
                    createPanel = { WorktreePanel(project) },
                )
            }

            override fun update(e: AnActionEvent) {
                val text = SpecCodingBundle.message("toolwindow.worktree.open.tooltip")
                e.presentation.icon = AllIcons.Vcs.Branch
                e.presentation.text = text
                e.presentation.description = text
                e.presentation.isEnabledAndVisible = !project.isDisposed
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

        val settingsAction = ActionManager.getInstance().getAction("SpecCoding.OpenSettings")
        val titleActions = mutableListOf<AnAction>(
            newSessionTitleAction,
            openHistoryTitleAction,
            openChangesTitleAction,
            openWorktreeTitleAction,
        )
        if (settingsAction != null) {
            titleActions.add(settingsAction)
        }
        toolWindow.setTitleActions(titleActions)

        ensurePrimaryContents(project, toolWindow)

        val contentManager = toolWindow.contentManager
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (!isPrimaryContent(event.content)) {
                    return
                }
                SwingUtilities.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    ensurePrimaryContents(project, toolWindow)
                }
            }

            override fun selectionChanged(event: ContentManagerEvent) {
                val selectedContent = event.content ?: return
                val selectedTabTitle = selectedContent.displayName
                    .takeIf { it.isNotBlank() }
                    ?: when {
                        isChatContent(selectedContent) -> SpecCodingBundle.message("toolwindow.tab.chat")
                        isSpecContent(selectedContent) -> SpecCodingBundle.message("spec.tab.title")
                        else -> null
                    }
                windowStateStore.updateSelectedTabTitle(selectedTabTitle)
                if (isSpecContent(selectedContent)) {
                    (selectedContent.component as? SpecWorkflowPanel)?.let { panel ->
                        panel.syncToolbarSelectionFromSettings()
                        panel.refreshWorkflows()
                    }
                }
            }
        })

        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: com.eacape.speccodingplugin.i18n.LocaleChangedEvent) {
                    SwingUtilities.invokeLater {
                        if (project.isDisposed) return@invokeLater

                        toolWindow.title = SpecCodingBundle.message("toolwindow.title")
                        ensurePrimaryContents(project, toolWindow).also { contents ->
                            contents.chatContent.displayName = SpecCodingBundle.message("toolwindow.tab.chat")
                            contents.specContent.displayName = SpecCodingBundle.message("spec.tab.title")
                        }
                        updateTransientContentDisplayName(
                            toolWindow = toolWindow,
                            contentKey = CHANGES_CONTENT_KEY,
                            title = SpecCodingBundle.message("toolwindow.tab.changes"),
                        )
                        updateTransientContentDisplayName(
                            toolWindow = toolWindow,
                            contentKey = WORKTREE_CONTENT_KEY,
                            title = SpecCodingBundle.message("worktree.tab.title"),
                        )
                    }
                }
            },
        )

        val restoredTabTitle = windowStateStore.snapshot().selectedTabTitle
        when {
            restoredTabTitle == SpecCodingBundle.message("spec.tab.title") -> selectSpecContent(toolWindow, project)
            restoredTabTitle == SpecCodingBundle.message("toolwindow.tab.chat") -> selectChatContent(toolWindow, project)
            else -> contentManager.contents.firstOrNull { it.displayName == restoredTabTitle }
                ?.let(contentManager::setSelectedContent)
                ?: selectChatContent(toolWindow, project)
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        return true
    }

    private fun focusChatTab(toolWindow: ToolWindow, project: Project) {
        selectChatContent(toolWindow, project)
        toolWindow.activate(null)
    }

    private fun currentChatPanel(toolWindow: ToolWindow): ImprovedChatPanel? {
        return toolWindow.contentManager.contents
            .firstOrNull(::isChatContent)
            ?.component as? ImprovedChatPanel
    }

    private fun openOrSelectTransientContent(
        toolWindow: ToolWindow,
        contentKey: Key<Boolean>,
        title: String,
        createPanel: () -> javax.swing.JPanel,
    ) {
        val contentManager = toolWindow.contentManager
        val existing = contentManager.contents.firstOrNull { it.getUserData(contentKey) == true }
        if (existing != null) {
            existing.displayName = title
            contentManager.setSelectedContent(existing)
            return
        }

        val panel = createPanel()
        val content = ContentFactory.getInstance().createContent(panel, title, false)
        content.putUserData(contentKey, true)
        if (panel is Disposable) {
            Disposer.register(content, panel)
        }
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)

        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (contentManager.selectedContent != content && contentManager.contents.contains(content)) {
                    contentManager.removeContentManagerListener(this)
                    contentManager.removeContent(content, true)
                }
            }
        })
    }

    private fun updateTransientContentDisplayName(
        toolWindow: ToolWindow,
        contentKey: Key<Boolean>,
        title: String,
    ) {
        toolWindow.contentManager.contents
            .firstOrNull { it.getUserData(contentKey) == true }
            ?.displayName = title
    }
}
