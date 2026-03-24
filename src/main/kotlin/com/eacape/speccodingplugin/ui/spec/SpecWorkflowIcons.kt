package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.TaskStatus
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal enum class SpecWorkflowActionIcon(
    val debugId: String,
    val icon: Icon,
) {
    ADVANCE("advance", AllIcons.General.ArrowRight),
    SWITCH_WORKFLOW("switchWorkflow", AllIcons.Actions.Search),
    NEXT_STAGE(
        "nextStage",
        IconLoader.getIcon("/icons/spec-workflow-next-stage.svg", SpecWorkflowActionIcon::class.java),
    ),
    EXECUTE("execute", AllIcons.Actions.Execute),
    TASK_EXECUTE(
        "taskExecute",
        IconLoader.getIcon("/icons/spec-task-execute-lightning.svg", SpecWorkflowActionIcon::class.java),
    ),
    RETRY("refresh", AllIcons.General.InlineRefresh),
    COMPLETE("complete", AllIcons.General.GreenCheckmark),
    WAITING_COMPLETE(
        "waitingComplete",
        IconLoader.getIcon("/icons/spec-task-complete-waiting.svg", SpecWorkflowActionIcon::class.java),
    ),
    INTENTION_BULB(
        "intentionBulb",
        IconLoader.getIcon("/icons/spec-workflow-intention-bulb.svg", SpecWorkflowActionIcon::class.java),
    ),
    PAUSE("pause", AllIcons.General.InspectionsPause),
    RESUME("resume", AllIcons.Actions.Resume),
    OPEN_DOCUMENT("openDocument", AllIcons.Actions.MenuOpen),
    HISTORY("history", AllIcons.Vcs.HistoryInline),
    SAVE("save", AllIcons.General.OpenDisk),
    EDIT("edit", AllIcons.Actions.Edit),
    DELETE("delete", AllIcons.Actions.GC),
    RELATED_FILES_EDIT(
        "relatedFilesEdit",
        IconLoader.getIcon("/icons/spec-task-related-files-edit.svg", SpecWorkflowActionIcon::class.java),
    ),
    VERIFICATION_RESULT(
        "verificationResult",
        IconLoader.getIcon("/icons/spec-task-verification-result.svg", SpecWorkflowActionIcon::class.java),
    ),
    ADD("add", AllIcons.General.Add),
    BACK("back", AllIcons.Actions.Back),
    FORWARD("forward", AllIcons.Actions.Forward),
    CLONE("clone", AllIcons.Actions.Copy),
    OVERFLOW(
        "overflow",
        IconLoader.getIcon("/icons/spec-task-secondary-actions.svg", SpecWorkflowActionIcon::class.java),
    ),
    CANCEL("close", AllIcons.Actions.Close),
    BRANCH("branch", AllIcons.Vcs.Branch),
    OPEN_TOOL_WINDOW("openToolWindow", AllIcons.General.OpenInToolWindow),
}

internal object SpecWorkflowIcons {
    val Advance: Icon = SpecWorkflowActionIcon.ADVANCE.icon
    val SwitchWorkflow: Icon = SpecWorkflowActionIcon.SWITCH_WORKFLOW.icon
    val NextStage: Icon = SpecWorkflowActionIcon.NEXT_STAGE.icon
    val Execute: Icon = SpecWorkflowActionIcon.EXECUTE.icon
    val TaskExecute: Icon = SpecWorkflowActionIcon.TASK_EXECUTE.icon
    val Refresh: Icon = SpecWorkflowActionIcon.RETRY.icon
    val Complete: Icon = SpecWorkflowActionIcon.COMPLETE.icon
    val WaitingComplete: Icon = SpecWorkflowActionIcon.WAITING_COMPLETE.icon
    val IntentionBulb: Icon = SpecWorkflowActionIcon.INTENTION_BULB.icon
    val Pause: Icon = SpecWorkflowActionIcon.PAUSE.icon
    val Resume: Icon = SpecWorkflowActionIcon.RESUME.icon
    val OpenDocument: Icon = SpecWorkflowActionIcon.OPEN_DOCUMENT.icon
    val History: Icon = SpecWorkflowActionIcon.HISTORY.icon
    val Save: Icon = SpecWorkflowActionIcon.SAVE.icon
    val Edit: Icon = SpecWorkflowActionIcon.EDIT.icon
    val Delete: Icon = SpecWorkflowActionIcon.DELETE.icon
    val RelatedFilesEdit: Icon = SpecWorkflowActionIcon.RELATED_FILES_EDIT.icon
    val VerificationResult: Icon = SpecWorkflowActionIcon.VERIFICATION_RESULT.icon
    val Add: Icon = SpecWorkflowActionIcon.ADD.icon
    val Back: Icon = SpecWorkflowActionIcon.BACK.icon
    val Forward: Icon = SpecWorkflowActionIcon.FORWARD.icon
    val Clone: Icon = SpecWorkflowActionIcon.CLONE.icon
    val Overflow: Icon = SpecWorkflowActionIcon.OVERFLOW.icon
    val Close: Icon = SpecWorkflowActionIcon.CANCEL.icon
    val Branch: Icon = SpecWorkflowActionIcon.BRANCH.icon
    val OpenToolWindow: Icon = SpecWorkflowActionIcon.OPEN_TOOL_WINDOW.icon

    fun icon(action: SpecWorkflowActionIcon): Icon = action.icon

    fun workbenchAction(kind: SpecWorkflowWorkbenchActionKind): Icon {
        return when (kind) {
            SpecWorkflowWorkbenchActionKind.ADVANCE -> NextStage
            SpecWorkflowWorkbenchActionKind.JUMP -> Advance

            SpecWorkflowWorkbenchActionKind.ROLLBACK -> Back
            SpecWorkflowWorkbenchActionKind.START_TASK,
            SpecWorkflowWorkbenchActionKind.RUN_VERIFY,
            SpecWorkflowWorkbenchActionKind.PREVIEW_VERIFY_PLAN,
            -> Execute

            SpecWorkflowWorkbenchActionKind.RESUME_TASK -> Refresh
            SpecWorkflowWorkbenchActionKind.STOP_TASK_EXECUTION -> Close
            SpecWorkflowWorkbenchActionKind.OPEN_TASK_CHAT -> OpenToolWindow
            SpecWorkflowWorkbenchActionKind.COMPLETE_TASK -> Complete
            SpecWorkflowWorkbenchActionKind.COMPLETE_WORKFLOW -> Complete

            SpecWorkflowWorkbenchActionKind.OPEN_VERIFICATION -> OpenDocument
            SpecWorkflowWorkbenchActionKind.SHOW_DELTA -> History
            SpecWorkflowWorkbenchActionKind.ARCHIVE_WORKFLOW -> Save
        }
    }

    fun taskPrimaryAction(displayStatus: TaskStatus): Icon {
        return when (displayStatus) {
            TaskStatus.PENDING -> TaskExecute
            TaskStatus.BLOCKED -> Refresh
            TaskStatus.IN_PROGRESS,
            TaskStatus.COMPLETED,
            -> Complete

            TaskStatus.CANCELLED -> Close
        }
    }

    fun debugId(icon: Icon?): String {
        return when (icon) {
            null -> ""
            else -> SpecWorkflowActionIcon.entries
                .firstOrNull { action -> action.icon == icon }
                ?.debugId
                ?: icon::class.java.name
        }
    }
}
