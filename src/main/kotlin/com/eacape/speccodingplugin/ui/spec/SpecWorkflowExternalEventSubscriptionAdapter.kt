package com.eacape.speccodingplugin.ui.spec

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

internal interface SpecWorkflowExternalEventSubscriber {
    fun subscribeToolWindowControl(listener: SpecToolWindowControlListener)

    fun subscribeWorkflowChanged(listener: SpecWorkflowChangedListener)

    fun subscribeDocumentFileChanges(after: (List<String>) -> Unit)
}

internal class MessageBusSpecWorkflowExternalEventSubscriber(
    private val project: Project,
    private val parentDisposable: Disposable,
) : SpecWorkflowExternalEventSubscriber {

    override fun subscribeToolWindowControl(listener: SpecToolWindowControlListener) {
        project.messageBus.connect(parentDisposable).subscribe(
            SpecToolWindowControlListener.TOPIC,
            listener,
        )
    }

    override fun subscribeWorkflowChanged(listener: SpecWorkflowChangedListener) {
        project.messageBus.connect(parentDisposable).subscribe(
            SpecWorkflowChangedListener.TOPIC,
            listener,
        )
    }

    override fun subscribeDocumentFileChanges(after: (List<String>) -> Unit) {
        project.messageBus.connect(parentDisposable).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    after(events.map { event -> event.path })
                }
            },
        )
    }
}

internal class SpecWorkflowExternalEventSubscriptionAdapter(
    private val subscriber: SpecWorkflowExternalEventSubscriber,
    private val externalEventCoordinator: SpecWorkflowExternalEventCoordinator,
    private val selectedWorkflowId: () -> String?,
    private val projectBasePath: () -> String?,
    private val invokeLater: (() -> Unit) -> Unit,
    private val isDisposed: () -> Boolean,
    private val onAction: (SpecWorkflowExternalEventAction) -> Unit,
) {
    private var registered: Boolean = false

    fun register() {
        if (registered) {
            return
        }
        registered = true
        subscriber.subscribeToolWindowControl(
            object : SpecToolWindowControlListener {
                override fun onCreateWorkflowRequested(preferredTemplate: com.eacape.speccodingplugin.spec.WorkflowTemplate?) {
                    dispatchOnUi {
                        externalEventCoordinator.resolveCreateWorkflow(preferredTemplate)
                    }
                }

                override fun onSelectWorkflowRequested(workflowId: String) {
                    dispatchOnUi {
                        externalEventCoordinator.resolveSelectWorkflow(workflowId)
                    }
                }

                override fun onOpenWorkflowRequested(request: SpecToolWindowOpenRequest) {
                    dispatchOnUi {
                        externalEventCoordinator.resolveOpenWorkflow(request)
                    }
                }
            },
        )
        subscriber.subscribeWorkflowChanged(
            object : SpecWorkflowChangedListener {
                override fun onWorkflowChanged(event: SpecWorkflowChangedEvent) {
                    dispatchDirect {
                        externalEventCoordinator.resolveWorkflowChanged(event)
                    }
                }
            },
        )
        subscriber.subscribeDocumentFileChanges { eventPaths ->
            dispatchDirect {
                externalEventCoordinator.resolveDocumentReload(
                    eventPaths = eventPaths,
                    basePath = projectBasePath(),
                    selectedWorkflowId = selectedWorkflowId(),
                )
            }
        }
    }

    private fun dispatchOnUi(actionProvider: () -> SpecWorkflowExternalEventAction?) {
        invokeLater {
            if (isDisposed()) {
                return@invokeLater
            }
            actionProvider()?.let(onAction)
        }
    }

    private fun dispatchDirect(actionProvider: () -> SpecWorkflowExternalEventAction?) {
        if (isDisposed()) {
            return
        }
        actionProvider()?.let(onAction)
    }
}
