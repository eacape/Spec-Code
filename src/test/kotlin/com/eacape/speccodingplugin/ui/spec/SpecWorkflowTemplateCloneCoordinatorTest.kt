package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.TemplateCloneResult
import com.eacape.speccodingplugin.spec.TemplateSwitchArtifactImpact
import com.eacape.speccodingplugin.spec.TemplateSwitchArtifactStrategy
import com.eacape.speccodingplugin.spec.TemplateSwitchPreview
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.spec.WorkflowTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecWorkflowTemplateCloneCoordinatorTest {

    @Test
    fun `requestClone should ignore missing workflow and same template`() {
        val missingWorkflowRecorder = RecordingEnvironment(workflow = null)
        coordinator(missingWorkflowRecorder).requestClone(WorkflowTemplate.DIRECT_IMPLEMENT)
        assertTrue(missingWorkflowRecorder.events.isEmpty())

        val sameTemplateRecorder = RecordingEnvironment(workflow = sampleWorkflow(template = WorkflowTemplate.FULL_SPEC))
        coordinator(sameTemplateRecorder).requestClone(WorkflowTemplate.FULL_SPEC)
        assertTrue(sameTemplateRecorder.events.isEmpty())
        assertTrue(sameTemplateRecorder.confirmDialogs.isEmpty())
        assertTrue(sameTemplateRecorder.cloneRequests.isEmpty())
    }

    @Test
    fun `requestClone should show blocked preview and stop before confirm`() {
        val recorder = RecordingEnvironment(
            workflow = sampleWorkflow(),
            preview = samplePreview(
                impacts = listOf(
                    sampleArtifactImpact(
                        fileName = "tasks.md",
                        strategy = TemplateSwitchArtifactStrategy.BLOCK_SWITCH,
                        exists = false,
                    ),
                ),
            ),
        )

        coordinator(recorder).requestClone(WorkflowTemplate.DIRECT_IMPLEMENT)

        assertEquals(
            listOf(
                "runPreview:${SpecCodingBundle.message("spec.action.template.clone.preview")}",
                "preview:wf-source:DIRECT_IMPLEMENT",
            ),
            recorder.events,
        )
        assertEquals(1, recorder.blockedDialogs.size)
        assertEquals(
            SpecCodingBundle.message("spec.action.template.clone.confirm.title"),
            recorder.blockedDialogs.single().title,
        )
        assertTrue(
            recorder.blockedDialogs.single().message.contains(
                SpecCodingBundle.message("spec.action.template.clone.summary.blocked"),
            ),
        )
        assertTrue(recorder.confirmDialogs.isEmpty())
        assertTrue(recorder.editRequests.isEmpty())
        assertTrue(recorder.cloneRequests.isEmpty())
    }

    @Test
    fun `requestClone should stop when preview confirmation is rejected`() {
        val recorder = RecordingEnvironment(
            workflow = sampleWorkflow(),
            preview = samplePreview(),
            confirmResult = false,
        )

        coordinator(recorder).requestClone(WorkflowTemplate.DIRECT_IMPLEMENT)

        assertEquals(
            listOf(
                "runPreview:${SpecCodingBundle.message("spec.action.template.clone.preview")}",
                "preview:wf-source:DIRECT_IMPLEMENT",
            ),
            recorder.events,
        )
        assertEquals(1, recorder.confirmDialogs.size)
        assertTrue(recorder.editRequests.isEmpty())
        assertTrue(recorder.cloneRequests.isEmpty())
        assertTrue(recorder.successMessages.isEmpty())
    }

    @Test
    fun `requestClone should edit and execute cloned workflow copy`() {
        val recorder = RecordingEnvironment(
            workflow = sampleWorkflow(
                title = "Source Workflow",
                description = "Source description",
            ),
            preview = samplePreview(),
            editResult = SpecWorkflowTemplateCloneEditResult(
                title = "Cloned Workflow",
                description = "Cloned description",
            ),
            cloneResult = TemplateCloneResult(
                sourceWorkflowId = "wf-source",
                previewId = "preview-1",
                workflow = sampleWorkflow(
                    id = "wf-clone",
                    title = "Cloned Workflow",
                    description = "Cloned description",
                    template = WorkflowTemplate.DIRECT_IMPLEMENT,
                ),
                copiedArtifacts = listOf("requirements.md"),
                generatedArtifacts = listOf("tasks.md"),
            ),
        )

        coordinator(recorder).requestClone(WorkflowTemplate.DIRECT_IMPLEMENT)

        assertEquals(
            listOf(
                "runPreview:${SpecCodingBundle.message("spec.action.template.clone.preview")}",
                "preview:wf-source:DIRECT_IMPLEMENT",
                "runClone:${SpecCodingBundle.message("spec.action.template.clone.executing")}",
                "clone:wf-source:preview-1:Cloned Workflow:Cloned description",
            ),
            recorder.events,
        )
        assertEquals(1, recorder.confirmDialogs.size)
        assertTrue(
            recorder.confirmDialogs.single().message.contains("wf-source"),
        )
        assertEquals(
            listOf(
                SpecWorkflowTemplateCloneEditRequest(
                    initialTitle = "Source Workflow (${SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.DIRECT_IMPLEMENT)})",
                    initialDescription = "Source description",
                    dialogTitle = SpecCodingBundle.message("spec.action.template.clone.dialog.title"),
                ),
            ),
            recorder.editRequests,
        )
        assertEquals(
            listOf(
                CloneRequest(
                    workflowId = "wf-source",
                    previewId = "preview-1",
                    title = "Cloned Workflow",
                    description = "Cloned description",
                ),
            ),
            recorder.cloneRequests,
        )
        assertEquals(
            listOf(
                SpecCodingBundle.message(
                    "spec.action.template.clone.success",
                    "Cloned Workflow",
                    SpecWorkflowOverviewPresenter.templateLabel(WorkflowTemplate.DIRECT_IMPLEMENT),
                ),
            ),
            recorder.successMessages,
        )
        assertEquals(listOf("wf-clone"), recorder.createdWorkflowIds)
    }

    private fun coordinator(recorder: RecordingEnvironment): SpecWorkflowTemplateCloneCoordinator {
        return SpecWorkflowTemplateCloneCoordinator(
            workflowProvider = { recorder.workflow },
            previewTemplateSwitch = { workflowId, targetTemplate ->
                recorder.events += "preview:$workflowId:${targetTemplate.name}"
                Result.success(
                    recorder.preview.copy(
                        workflowId = workflowId,
                        fromTemplate = recorder.workflow?.template ?: recorder.preview.fromTemplate,
                        toTemplate = targetTemplate,
                    ),
                )
            },
            cloneWorkflowWithTemplate = { workflowId, previewId, title, description ->
                recorder.events += "clone:$workflowId:$previewId:$title:${description.orEmpty()}"
                recorder.cloneRequests += CloneRequest(workflowId, previewId, title, description)
                Result.success(recorder.cloneResult)
            },
            runPreviewInBackground = { title, task, onSuccess ->
                recorder.events += "runPreview:$title"
                onSuccess(task())
            },
            runCloneInBackground = { title, task, onSuccess ->
                recorder.events += "runClone:$title"
                onSuccess(task())
            },
            showBlockedPreview = { title, message ->
                recorder.blockedDialogs += DialogCall(title, message)
            },
            confirmPreview = { title, message ->
                recorder.confirmDialogs += DialogCall(title, message)
                recorder.confirmResult
            },
            editClone = { request ->
                recorder.editRequests += request
                recorder.editResult
            },
            notifySuccess = { message ->
                recorder.successMessages += message
            },
            onCloneCreated = { workflowId ->
                recorder.createdWorkflowIds += workflowId
            },
        )
    }

    private companion object {
        fun sampleWorkflow(
            id: String = "wf-source",
            title: String = "Source Workflow",
            description: String = "Source description",
            template: WorkflowTemplate = WorkflowTemplate.FULL_SPEC,
        ): SpecWorkflow {
            return SpecWorkflow(
                id = id,
                currentPhase = SpecPhase.DESIGN,
                documents = emptyMap(),
                status = WorkflowStatus.IN_PROGRESS,
                title = title,
                description = description,
                template = template,
                currentStage = StageId.DESIGN,
            )
        }

        fun samplePreview(
            impacts: List<TemplateSwitchArtifactImpact> = listOf(
                sampleArtifactImpact(
                    fileName = "requirements.md",
                    strategy = TemplateSwitchArtifactStrategy.REUSE_EXISTING,
                ),
                sampleArtifactImpact(
                    fileName = "tasks.md",
                    strategy = TemplateSwitchArtifactStrategy.GENERATE_SKELETON,
                ),
            ),
        ): TemplateSwitchPreview {
            return TemplateSwitchPreview(
                previewId = "preview-1",
                workflowId = "wf-source",
                fromTemplate = WorkflowTemplate.FULL_SPEC,
                toTemplate = WorkflowTemplate.DIRECT_IMPLEMENT,
                currentStage = StageId.DESIGN,
                resultingStage = StageId.IMPLEMENT,
                addedActiveStages = listOf(StageId.IMPLEMENT),
                deactivatedStages = listOf(StageId.REQUIREMENTS),
                gateAddedStages = listOf(StageId.VERIFY),
                gateRemovedStages = emptyList(),
                artifactImpacts = impacts,
            )
        }

        fun sampleArtifactImpact(
            fileName: String,
            strategy: TemplateSwitchArtifactStrategy,
            exists: Boolean = true,
        ): TemplateSwitchArtifactImpact {
            return TemplateSwitchArtifactImpact(
                stageId = StageId.IMPLEMENT,
                fileName = fileName,
                exists = exists,
                strategy = strategy,
            )
        }
    }

    private data class DialogCall(
        val title: String,
        val message: String,
    )

    private data class CloneRequest(
        val workflowId: String,
        val previewId: String,
        val title: String,
        val description: String?,
    )

    private class RecordingEnvironment(
        val workflow: SpecWorkflow?,
        val preview: TemplateSwitchPreview = samplePreview(),
        val confirmResult: Boolean = true,
        val editResult: SpecWorkflowTemplateCloneEditResult? = null,
        val cloneResult: TemplateCloneResult = TemplateCloneResult(
            sourceWorkflowId = "wf-source",
            previewId = "preview-1",
            workflow = sampleWorkflow(
                id = "wf-clone",
                title = "Workflow Clone",
                template = WorkflowTemplate.DIRECT_IMPLEMENT,
            ),
            copiedArtifacts = emptyList(),
            generatedArtifacts = emptyList(),
        ),
    ) {
        val events = mutableListOf<String>()
        val blockedDialogs = mutableListOf<DialogCall>()
        val confirmDialogs = mutableListOf<DialogCall>()
        val editRequests = mutableListOf<SpecWorkflowTemplateCloneEditRequest>()
        val cloneRequests = mutableListOf<CloneRequest>()
        val successMessages = mutableListOf<String>()
        val createdWorkflowIds = mutableListOf<String>()
    }
}
