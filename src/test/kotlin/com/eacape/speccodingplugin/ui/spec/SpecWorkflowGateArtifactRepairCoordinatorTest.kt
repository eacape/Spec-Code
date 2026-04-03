package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.RequirementsDraftIssueKind
import com.eacape.speccodingplugin.spec.SpecRequirementsQuickFixResult
import com.eacape.speccodingplugin.spec.SpecRequirementsQuickFixService
import com.eacape.speccodingplugin.spec.SpecTaskMarkdownParser
import com.eacape.speccodingplugin.spec.SpecTasksQuickFixResult
import com.eacape.speccodingplugin.spec.SpecTasksQuickFixService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpecWorkflowGateArtifactRepairCoordinatorTest {

    @Test
    fun `repairTasksArtifact should report no-op when tasks artifact did not change`() {
        val recorder = RecordingEnvironment()
        recorder.tasksRepairResult = tasksResult(
            workflowId = "wf-tasks-none",
            changed = false,
            issuesBefore = listOf(parseIssue(line = 2, message = "Heading must be canonical")),
        )
        val coordinator = coordinator(recorder)

        coordinator.repairTasksArtifact("wf-tasks-none")

        assertEquals(
            listOf(BackgroundCall(SpecCodingBundle.message("spec.action.editor.fixTasks.progress"))),
            recorder.tasksBackgroundCalls,
        )
        assertEquals(listOf(SpecTasksQuickFixService.TRIGGER_GATE_QUICK_FIX), recorder.taskTriggers)
        assertEquals(
            listOf(
                InfoCall(
                    SpecCodingBundle.message("spec.action.editor.fixTasks.none.title"),
                    SpecCodingBundle.message("spec.action.editor.fixTasks.none.message"),
                ),
            ),
            recorder.infoCalls,
        )
        assertEquals(listOf("wf-tasks-none"), recorder.rememberedWorkflowIds)
        assertEquals(listOf("wf-tasks-none"), recorder.refreshedWorkflowIds)
        assertTrue(recorder.openFileCalls.isEmpty())
        assertTrue(recorder.successMessages.isEmpty())
    }

    @Test
    fun `repairTasksArtifact should show partial repair info and open first remaining issue`() {
        val recorder = RecordingEnvironment()
        recorder.tasksRepairResult = tasksResult(
            workflowId = "wf-tasks-partial",
            changed = true,
            issuesBefore = listOf(parseIssue(line = 2, message = "broken before")),
            issuesAfter = listOf(parseIssue(line = 7, message = "still broken")),
        )
        val coordinator = coordinator(recorder)

        coordinator.repairTasksArtifact("wf-tasks-partial")

        assertEquals(
            listOf(
                InfoCall(
                    SpecCodingBundle.message("spec.action.editor.fixTasks.partial.title"),
                    SpecCodingBundle.message(
                        "spec.action.editor.fixTasks.partial.message",
                        1,
                        7,
                        "still broken",
                    ),
                ),
            ),
            recorder.infoCalls,
        )
        assertEquals(listOf(OpenFileCall(Path.of("/tmp/wf-tasks-partial-tasks.md"), 7)), recorder.openFileCalls)
        assertTrue(recorder.successMessages.isEmpty())
    }

    @Test
    fun `repairTasksArtifact should notify success and open repaired tasks artifact`() {
        val recorder = RecordingEnvironment()
        recorder.tasksRepairResult = tasksResult(
            workflowId = "wf-tasks-success",
            changed = true,
            issuesBefore = listOf(
                parseIssue(line = 2, message = "bad heading"),
                parseIssue(line = 5, message = "bad fence"),
            ),
            issuesAfter = emptyList(),
        )
        val coordinator = coordinator(recorder)

        coordinator.repairTasksArtifact("wf-tasks-success")

        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.action.editor.fixTasks.success.message", 2),
            ),
            recorder.successMessages,
        )
        assertEquals(listOf(OpenFileCall(Path.of("/tmp/wf-tasks-success-tasks.md"), null)), recorder.openFileCalls)
        assertTrue(recorder.infoCalls.isEmpty())
    }

    @Test
    fun `repairRequirementsArtifact should report no-op when requirements issues are already absent`() {
        val recorder = RecordingEnvironment()
        recorder.requirementsRepairResult = requirementsResult(
            workflowId = "wf-req-none",
            issuesBefore = emptyList(),
            issuesAfter = emptyList(),
        )
        val coordinator = coordinator(recorder)

        coordinator.repairRequirementsArtifact("wf-req-none")

        assertEquals(
            listOf(BackgroundCall(SpecCodingBundle.message("spec.action.editor.fixRequirements.progress"))),
            recorder.requirementsBackgroundCalls,
        )
        assertEquals(listOf(SpecRequirementsQuickFixService.TRIGGER_GATE_QUICK_FIX), recorder.requirementsTriggers)
        assertEquals(
            listOf(
                InfoCall(
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.none.title"),
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.none.message"),
                ),
            ),
            recorder.infoCalls,
        )
        assertEquals(listOf("wf-req-none"), recorder.rememberedWorkflowIds)
        assertEquals(listOf("wf-req-none"), recorder.refreshedWorkflowIds)
        assertTrue(recorder.openFileCalls.isEmpty())
    }

    @Test
    fun `repairRequirementsArtifact should show partial repair info and open requirements document`() {
        val recorder = RecordingEnvironment()
        recorder.requirementsRepairResult = requirementsResult(
            workflowId = "wf-req-partial",
            issuesBefore = listOf(RequirementsDraftIssueKind.TODO_PLACEHOLDERS),
            issuesAfter = listOf(RequirementsDraftIssueKind.USER_STORY_TEMPLATE),
        )
        val coordinator = coordinator(recorder)

        coordinator.repairRequirementsArtifact("wf-req-partial")

        assertEquals(
            listOf(
                InfoCall(
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.partial.title"),
                    SpecCodingBundle.message("spec.action.editor.fixRequirements.partial.message", 1),
                ),
            ),
            recorder.infoCalls,
        )
        assertEquals(listOf(OpenFileCall(Path.of("/tmp/wf-req-partial-requirements.md"), 1)), recorder.openFileCalls)
        assertTrue(recorder.successMessages.isEmpty())
    }

    @Test
    fun `repairRequirementsArtifact should notify success and open repaired requirements artifact`() {
        val recorder = RecordingEnvironment()
        recorder.requirementsRepairResult = requirementsResult(
            workflowId = "wf-req-success",
            issuesBefore = listOf(
                RequirementsDraftIssueKind.TODO_PLACEHOLDERS,
                RequirementsDraftIssueKind.USER_STORY_TEMPLATE,
            ),
            issuesAfter = emptyList(),
        )
        val coordinator = coordinator(recorder)

        coordinator.repairRequirementsArtifact("wf-req-success")

        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.action.editor.fixRequirements.success.message", 2),
            ),
            recorder.successMessages,
        )
        assertEquals(
            listOf(OpenFileCall(Path.of("/tmp/wf-req-success-requirements.md"), null)),
            recorder.openFileCalls,
        )
        assertTrue(recorder.infoCalls.isEmpty())
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowGateArtifactRepairCoordinator {
        return SpecWorkflowGateArtifactRepairCoordinator(
            backgroundRunner = object : SpecWorkflowGateArtifactRepairBackgroundRunner {
                override fun <T> run(request: SpecWorkflowGateArtifactRepairBackgroundRequest<T>) {
                    when (request.title) {
                        SpecCodingBundle.message("spec.action.editor.fixTasks.progress") -> {
                            recorder.tasksBackgroundCalls += BackgroundCall(request.title)
                        }

                        SpecCodingBundle.message("spec.action.editor.fixRequirements.progress") -> {
                            recorder.requirementsBackgroundCalls += BackgroundCall(request.title)
                        }
                    }
                    request.onSuccess(request.task())
                }
            },
            runTasksRepair = { workflowId, trigger ->
                assertTrue(workflowId.isNotBlank())
                recorder.taskTriggers += trigger
                recorder.tasksRepairResult
            },
            runRequirementsRepair = { workflowId, trigger ->
                assertTrue(workflowId.isNotBlank())
                recorder.requirementsTriggers += trigger
                recorder.requirementsRepairResult
            },
            rememberWorkflow = { workflowId ->
                recorder.rememberedWorkflowIds += workflowId
            },
            showInfo = { title, message ->
                recorder.infoCalls += InfoCall(title, message)
            },
            notifySuccess = { message ->
                recorder.successMessages += message
            },
            openFile = { path, line ->
                recorder.openFileCalls += OpenFileCall(path, line)
            },
            refreshWorkflows = { workflowId ->
                recorder.refreshedWorkflowIds += workflowId
            },
        )
    }

    private fun tasksResult(
        workflowId: String,
        changed: Boolean,
        issuesBefore: List<SpecTaskMarkdownParser.ParseIssue> = emptyList(),
        issuesAfter: List<SpecTaskMarkdownParser.ParseIssue> = emptyList(),
    ): SpecTasksQuickFixResult {
        return SpecTasksQuickFixResult(
            workflowId = workflowId,
            tasksDocumentPath = Path.of("/tmp/$workflowId-tasks.md"),
            changed = changed,
            issuesBefore = issuesBefore,
            issuesAfter = issuesAfter,
        )
    }

    private fun requirementsResult(
        workflowId: String,
        issuesBefore: List<RequirementsDraftIssueKind>,
        issuesAfter: List<RequirementsDraftIssueKind>,
    ): SpecRequirementsQuickFixResult {
        return SpecRequirementsQuickFixResult(
            workflowId = workflowId,
            requirementsDocumentPath = Path.of("/tmp/$workflowId-requirements.md"),
            changed = issuesBefore != issuesAfter,
            issuesBefore = issuesBefore,
            issuesAfter = issuesAfter,
        )
    }

    private fun parseIssue(
        line: Int,
        message: String,
    ): SpecTaskMarkdownParser.ParseIssue {
        return SpecTaskMarkdownParser.ParseIssue(
            line = line,
            message = message,
        )
    }

    private class RecordingEnvironment {
        var tasksRepairResult: SpecTasksQuickFixResult = SpecTasksQuickFixResult(
            workflowId = "missing-tasks",
            tasksDocumentPath = Path.of("/tmp/missing-tasks-tasks.md"),
            changed = false,
            issuesBefore = emptyList(),
            issuesAfter = emptyList(),
        )
        var requirementsRepairResult: SpecRequirementsQuickFixResult = SpecRequirementsQuickFixResult(
            workflowId = "missing-requirements",
            requirementsDocumentPath = Path.of("/tmp/missing-requirements-requirements.md"),
            changed = false,
            issuesBefore = emptyList(),
            issuesAfter = emptyList(),
        )
        val taskTriggers = mutableListOf<String>()
        val requirementsTriggers = mutableListOf<String>()
        val tasksBackgroundCalls = mutableListOf<BackgroundCall>()
        val requirementsBackgroundCalls = mutableListOf<BackgroundCall>()
        val rememberedWorkflowIds = mutableListOf<String>()
        val refreshedWorkflowIds = mutableListOf<String>()
        val infoCalls = mutableListOf<InfoCall>()
        val successMessages = mutableListOf<String>()
        val openFileCalls = mutableListOf<OpenFileCall>()
    }

    private data class BackgroundCall(
        val title: String,
    )

    private data class InfoCall(
        val title: String,
        val message: String,
    )

    private data class OpenFileCall(
        val path: Path,
        val line: Int?,
    )
}
