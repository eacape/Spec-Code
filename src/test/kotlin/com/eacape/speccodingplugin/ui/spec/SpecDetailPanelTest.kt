package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.ArtifactDraftState
import com.eacape.speccodingplugin.spec.ArtifactDraftStateSupport
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMetadata
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.StageProgress
import com.eacape.speccodingplugin.spec.StageState
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDetailPanelTest {

    @Test
    fun `showEmpty should clear preview and disable all actions`() {
        val panel = createPanel()

        panel.showEmpty()

        assertEquals("", panel.currentPreviewTextForTest())
        assertEquals(SpecCodingBundle.message("spec.detail.noWorkflow"), panel.currentValidationTextForTest())

        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertFalse(states["nextEnabled"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertFalse(states["completeVisible"] as Boolean)
        assertFalse(states["pauseResumeEnabled"] as Boolean)
        assertFalse(states["pauseResumeVisible"] as Boolean)
        assertFalse(states["openEditorEnabled"] as Boolean)
        assertFalse(states["historyDiffEnabled"] as Boolean)
    }

    @Test
    fun `updateWorkflow should show current phase preview and button states`() {
        val panel = createPanel()
        val designContent = """
            ## 架构设计
            - 使用三层架构

            ## 技术选型
            - Kotlin + IntelliJ Platform SDK

            ## 数据模型
            - SpecWorkflow / SpecDocument
        """.trimIndent()
        val workflow = SpecWorkflow(
            id = "wf-1",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = designContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Demo",
            description = "Demo workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        assertEquals(designContent, panel.currentPreviewTextForTest())
        assertEquals(
            SpecCodingBundle.message("spec.detail.validation.passed"),
            panel.currentValidationTextForTest(),
        )

        val states = panel.buttonStatesForTest()
        assertTrue(states["generateEnabled"] as Boolean)
        assertEquals("execute", states["generateIconId"])
        assertTrue(states["generateFocusable"] as Boolean)
        assertTrue(states["nextEnabled"] as Boolean)
        assertEquals("advance", states["nextIconId"])
        assertTrue(states["nextFocusable"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertEquals("back", states["goBackIconId"])
        assertTrue(states["goBackFocusable"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertFalse(states["completeVisible"] as Boolean)
        assertFalse(states["pauseResumeEnabled"] as Boolean)
        assertFalse(states["pauseResumeVisible"] as Boolean)
        assertTrue(states["openEditorEnabled"] as Boolean)
        assertEquals("openToolWindow", states["openEditorIconId"])
        assertTrue(states["openEditorFocusable"] as Boolean)
        assertTrue(states["historyDiffEnabled"] as Boolean)
        assertEquals("history", states["historyDiffIconId"])
        assertTrue(states["historyDiffFocusable"] as Boolean)
        assertEquals(0, panel.documentToolbarActionCountForTest())
        assertEquals(
            listOf("generate", "openEditor", "historyDiff", "edit"),
            panel.visibleComposerActionOrderForTest(),
        )
    }

    @Test
    fun `updateWorkflow should keep full markdown when mermaid block exists in design document`() {
        val panel = createPanel()
        val designContent = """
            # 设计文档示例
            
            ## 架构设计
            - 使用三层架构
            
            ```mermaid
            erDiagram
              TEAM ||--o{ TEAM_ALIAS : has
            ```
            
            ## 技术选型
            - Kotlin
        """.trimIndent()
        val workflow = SpecWorkflow(
            id = "wf-mermaid-preview",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = designContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Mermaid",
            description = "Preview should keep full markdown",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val preview = panel.currentPreviewTextForTest()
        assertTrue(preview.contains("## 架构设计"))
        assertTrue(preview.contains("## 技术选型"))
        assertTrue(preview.contains("erDiagram"))
    }

    @Test
    fun `updateWorkflow should keep generate tooltip for skeleton artifact`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-generate-mode",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = ArtifactDraftStateSupport.defaultSkeletonFor(StageId.TASKS).trim(),
                    valid = false,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Generate mode",
            description = "skeleton should stay generate",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertEquals(SpecCodingBundle.message("spec.detail.generate"), states["generateTooltip"])
        assertEquals(SpecCodingBundle.message("spec.detail.generate"), states["generateAccessibleName"])
    }

    @Test
    fun `updateWorkflow should switch generate and clarify actions to revise for materialized artifact`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-revise-mode",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = """
                        # Design Document

                        ## Architecture Design
                        - Split the workflow UI from storage services.
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Revise mode",
            description = "materialized document should switch action copy",
            artifactDraftStates = mapOf(StageId.DESIGN to ArtifactDraftState.MATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val readyStates = panel.buttonStatesForTest()
        assertEquals(SpecCodingBundle.message("spec.detail.revise"), readyStates["generateTooltip"])
        assertEquals(SpecCodingBundle.message("spec.detail.revise"), readyStates["generateAccessibleName"])

        panel.showClarificationDraft(
            phase = SpecPhase.DESIGN,
            input = "expand integration details",
            questionsMarkdown = "1. Which persistence boundary changes?",
            suggestedDetails = "- keep YAML storage",
        )

        val clarifyingStates = panel.buttonStatesForTest()
        assertEquals(SpecCodingBundle.message("spec.detail.clarify.confirmRevise"), clarifyingStates["confirmGenerateTooltip"])
        assertEquals(SpecCodingBundle.message("spec.detail.clarify.confirmRevise"), clarifyingStates["confirmGenerateAccessibleName"])
    }

    @Test
    fun `updateWorkflow should switch revise placeholder and clarify hint for materialized artifact`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-revise-placeholder",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = """
                        # Design Document

                        ## Architecture Design
                        - Keep workflow state file-first.
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Revise placeholder",
            description = "materialized document should switch placeholder copy",
            artifactDraftStates = mapOf(StageId.DESIGN to ArtifactDraftState.MATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        assertEquals(
            SpecCodingBundle.message("spec.detail.input.placeholder.design.revise"),
            panel.currentInputPlaceholderForTest(),
        )

        panel.showClarificationDraft(
            phase = SpecPhase.DESIGN,
            input = "add writeback constraints",
            questionsMarkdown = "1. Which design sections need revision?",
            suggestedDetails = "- preserve storage boundaries",
        )

        assertEquals(
            SpecCodingBundle.message("spec.detail.clarify.input.placeholder.revise"),
            panel.currentInputPlaceholderForTest(),
        )
        assertEquals(
            SpecCodingBundle.message("spec.workflow.clarify.hint.revise"),
            panel.currentValidationTextForTest(),
        )
    }

    @Test
    fun `paused revise workflow should expose revise disabled reason on primary action`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-revise-paused",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = """
                        # Design Document

                        ## Architecture Design
                        - Preserve current sections.
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.PAUSED,
            title = "Paused revise",
            description = "paused materialized document",
            artifactDraftStates = mapOf(StageId.DESIGN to ArtifactDraftState.MATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        val expectedReason = SpecCodingBundle.message(
            "spec.detail.action.disabled.status.revise",
            SpecCodingBundle.message("spec.workflow.status.paused"),
        )
        assertFalse(states["generateEnabled"] as Boolean)
        assertEquals(expectedReason, states["generateTooltip"])
        assertEquals(SpecCodingBundle.message("spec.detail.revise"), states["generateAccessibleName"])
        assertTrue((states["generateAccessibleDescription"] as String).contains(expectedReason))
    }

    @Test
    fun `completed requirements should stay read only until revision is explicitly started`() {
        val panel = createPanel()
        val workflow = completedRequirementsAndDesignWorkflow()

        panel.updateWorkflow(workflow)
        panel.selectPhaseForTest(SpecPhase.SPECIFY)

        val lockedStates = panel.buttonStatesForTest()
        assertFalse(lockedStates["generateEnabled"] as Boolean)
        assertTrue(lockedStates["editEnabled"] as Boolean)
        assertEquals(SpecCodingBundle.message("spec.detail.revision.start"), lockedStates["editTooltip"])
        assertEquals(SpecCodingBundle.message("spec.detail.revision.start"), lockedStates["editAccessibleName"])
        assertFalse(lockedStates["inputEnabled"] as Boolean)
        assertFalse(lockedStates["inputEditable"] as Boolean)
        assertEquals(
            SpecCodingBundle.message("spec.detail.revision.locked.action", SpecCodingBundle.message("spec.detail.step.requirements")),
            lockedStates["generateTooltip"],
        )
        assertEquals(
            SpecCodingBundle.message("spec.detail.revision.locked.banner", SpecCodingBundle.message("spec.detail.step.requirements")),
            panel.currentValidationTextForTest(),
        )

        panel.clickEditForTest()

        val editingStates = panel.buttonStatesForTest()
        assertTrue(editingStates["saveVisible"] as Boolean)
        assertTrue(editingStates["cancelEditVisible"] as Boolean)

        panel.clickCancelEditForTest()

        val relockedStates = panel.buttonStatesForTest()
        assertEquals(SpecCodingBundle.message("spec.detail.revision.start"), relockedStates["editTooltip"])
        assertFalse(relockedStates["inputEditable"] as Boolean)
        assertEquals(
            SpecCodingBundle.message("spec.detail.revision.locked.banner", SpecCodingBundle.message("spec.detail.step.requirements")),
            panel.currentValidationTextForTest(),
        )
    }

    @Test
    fun `completed requirements preview should block checklist writeback until revision is started`() {
        var saveCalls = 0
        val panel = createPanel(
            onSaveDocument = { _, _, onDone ->
                saveCalls += 1
                onDone(Result.failure(IllegalStateException("should stay locked")))
            },
        )
        val workflow = completedRequirementsAndDesignWorkflow(
            requirementsContent = """
                # Requirements
                - [ ] Keep the existing API contract
            """.trimIndent(),
        )

        panel.updateWorkflow(workflow)
        panel.selectPhaseForTest(SpecPhase.SPECIFY)
        panel.togglePreviewChecklistForTest(1)

        assertEquals(0, saveCalls)
    }

    @Test
    fun `tasks should remain writable while completed requirements stay protected`() {
        val panel = createPanel()
        val workflow = completedRequirementsAndDesignWorkflow()

        panel.updateWorkflow(workflow)
        panel.selectPhaseForTest(SpecPhase.IMPLEMENT)

        val states = panel.buttonStatesForTest()
        assertTrue(states["generateEnabled"] as Boolean)
        assertTrue(states["editEnabled"] as Boolean)
        assertEquals(SpecCodingBundle.message("spec.detail.edit"), states["editTooltip"])
        assertTrue(states["inputEnabled"] as Boolean)
        assertTrue(states["inputEditable"] as Boolean)
    }

    @Test
    fun `updateWorkflow should follow current phase when requested`() {
        val panel = createPanel()
        val specifyContent = "requirements content"
        val designContent = "design content"

        val initial = SpecWorkflow(
            id = "wf-follow",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = specifyContent,
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = designContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Follow",
            description = "Follow current phase",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(initial)
        assertEquals(specifyContent, panel.currentPreviewTextForTest())
        panel.setInputTextForTest("temporary user input")

        val moved = initial.copy(currentPhase = SpecPhase.DESIGN, updatedAt = 3L)
        panel.updateWorkflow(moved)
        assertEquals(specifyContent, panel.currentPreviewTextForTest())
        assertEquals("temporary user input", panel.currentInputTextForTest())

        panel.updateWorkflow(moved, followCurrentPhase = true)
        assertEquals(designContent, panel.currentPreviewTextForTest())
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `preview checklist toggle should save updated task document`() {
        var savedPhase: SpecPhase? = null
        var savedContent: String? = null
        lateinit var currentWorkflow: SpecWorkflow
        val panel = createPanel(
            onSaveDocument = { phase, content, onDone ->
                savedPhase = phase
                savedContent = content
                currentWorkflow = currentWorkflow.copy(
                    documents = currentWorkflow.documents + (
                        SpecPhase.IMPLEMENT to document(
                            phase = SpecPhase.IMPLEMENT,
                            content = content,
                            valid = true,
                        )
                    ),
                    updatedAt = currentWorkflow.updatedAt + 1,
                )
                onDone(Result.success(currentWorkflow))
            },
        )
        val tasksContent = """
            ### T-002: 本地开发基础设施与容器编排
            - [ ] 提供本地 Docker Compose 依赖
            - [x] 输出数据源迁移脚本
        """.trimIndent()
        currentWorkflow = SpecWorkflow(
            id = "wf-checklist-preview",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = tasksContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Preview",
            description = "toggle markdown checklist in preview",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(currentWorkflow)
        panel.togglePreviewChecklistForTest(1)

        assertEquals(SpecPhase.IMPLEMENT, savedPhase)
        assertEquals(
            """
            ### T-002: 本地开发基础设施与容器编排
            - [x] 提供本地 Docker Compose 依赖
            - [x] 输出数据源迁移脚本
            """.trimIndent(),
            savedContent,
        )
        assertEquals(savedContent, panel.currentPreviewTextForTest())

        panel.togglePreviewChecklistForTest(2)

        assertEquals(
            """
            ### T-002: 本地开发基础设施与容器编排
            - [x] 提供本地 Docker Compose 依赖
            - [ ] 输出数据源迁移脚本
            """.trimIndent(),
            savedContent,
        )
        assertEquals(savedContent, panel.currentPreviewTextForTest())
    }

    @Test
    fun `composer should auto collapse for non current documents and reopen for current stage`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-composer",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Composer",
            description = "Composer visibility",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)
        assertTrue(panel.isComposerExpandedForTest())

        panel.updateWorkbenchState(
            state = workbenchState(
                currentStage = StageId.DESIGN,
                focusedStage = StageId.REQUIREMENTS,
                documentPhase = SpecPhase.SPECIFY,
                title = "requirements.md",
            ),
            syncSelection = true,
        )
        assertFalse(panel.isComposerExpandedForTest())

        panel.updateWorkbenchState(
            state = workbenchState(
                currentStage = StageId.DESIGN,
                focusedStage = StageId.DESIGN,
                documentPhase = SpecPhase.DESIGN,
                title = "design.md",
            ),
            syncSelection = true,
        )
        assertTrue(panel.isComposerExpandedForTest())

        panel.toggleComposerExpandedForTest()
        assertFalse(panel.isComposerExpandedForTest())

        panel.updateWorkflow(workflow.copy(updatedAt = 3L))
        assertFalse(panel.isComposerExpandedForTest())

        panel.updateWorkbenchState(
            state = workbenchState(
                currentStage = StageId.DESIGN,
                focusedStage = StageId.REQUIREMENTS,
                documentPhase = SpecPhase.SPECIFY,
                title = "requirements.md",
            ),
            syncSelection = true,
        )
        panel.updateWorkbenchState(
            state = workbenchState(
                currentStage = StageId.DESIGN,
                focusedStage = StageId.DESIGN,
                documentPhase = SpecPhase.DESIGN,
                title = "design.md",
            ),
            syncSelection = true,
        )
        assertTrue(panel.isComposerExpandedForTest())
    }

    @Test
    fun `composer container wrapper should cap maximum height to preferred height`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-composer-height-cap",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Composer Height",
            description = "cap composer blank space",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        assertTrue(panel.isComposerExpandedForTest())
        assertEquals(
            panel.composerContainerPreferredHeightForTest(),
            panel.composerContainerMaximumHeightForTest(),
        )
    }

    @Test
    fun `updateWorkbenchState should switch to verification artifact preview with fallback open action`() {
        val openedArtifacts = mutableListOf<String>()
        val panel = createPanel(onOpenArtifactInEditor = openedArtifacts::add)
        val workflow = SpecWorkflow(
            id = "wf-verify-preview",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Verify",
            description = "verification artifact preview",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val verificationContent = """
            # Verification Document

            ## Result
            conclusion: PASS
            summary: All checks green
        """.trimIndent()

        panel.updateWorkflow(workflow)
        panel.updateWorkbenchState(
            state = SpecWorkflowStageWorkbenchState(
                currentStage = StageId.IMPLEMENT,
                focusedStage = StageId.VERIFY,
                progress = SpecWorkflowStageProgressView(
                    stepIndex = 5,
                    totalSteps = 6,
                    stageStatus = com.eacape.speccodingplugin.spec.StageProgress.IN_PROGRESS,
                    completedCheckCount = 0,
                    totalCheckCount = 0,
                    completionChecks = emptyList(),
                ),
                primaryAction = null,
                overflowActions = emptyList(),
                blockers = emptyList(),
                artifactBinding = SpecWorkflowStageArtifactBinding(
                    stageId = StageId.VERIFY,
                    title = "verification.md",
                    fileName = "verification.md",
                    documentPhase = null,
                    mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                    fallbackEditable = false,
                    available = true,
                    previewContent = verificationContent,
                ),
                visibleSections = setOf(SpecWorkflowWorkspaceSectionId.DOCUMENTS),
            ),
            syncSelection = true,
        )

        assertEquals(verificationContent, panel.currentPreviewTextForTest())
        assertEquals(
            SpecCodingBundle.message("spec.detail.workbench.readOnly", "verification.md"),
            panel.currentValidationTextForTest(),
        )
        assertEquals("", panel.currentDocumentMetaTextForTest())
        assertEquals(null, panel.selectedPhaseNameForTest())
        assertFalse(panel.areDocumentTabsVisibleForTest())

        val states = panel.buttonStatesForTest()
        assertTrue(states["openEditorEnabled"] as Boolean)
        assertFalse(states["historyDiffEnabled"] as Boolean)
        assertFalse(states["confirmGenerateEnabled"] as Boolean)

        panel.clickOpenEditorForTest()
        assertEquals(listOf("verification.md"), openedArtifacts)
    }

    @Test
    fun `updateWorkbenchState should explain missing requirements document in workbench preview`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-requirements-empty",
            currentPhase = SpecPhase.SPECIFY,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Requirements Empty",
            description = "requirements empty state",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val emptyMessage = SpecCodingBundle.message("spec.detail.workbench.requirements.missing")

        panel.updateWorkflow(workflow)
        panel.updateWorkbenchState(
            state = SpecWorkflowStageWorkbenchState(
                currentStage = StageId.REQUIREMENTS,
                focusedStage = StageId.REQUIREMENTS,
                progress = SpecWorkflowStageProgressView(
                    stepIndex = 1,
                    totalSteps = 6,
                    stageStatus = com.eacape.speccodingplugin.spec.StageProgress.IN_PROGRESS,
                    completedCheckCount = 0,
                    totalCheckCount = 4,
                    completionChecks = emptyList(),
                ),
                primaryAction = SpecWorkflowWorkbenchAction(
                    kind = SpecWorkflowWorkbenchActionKind.ADVANCE,
                    label = "continue",
                    enabled = false,
                    disabledReason = "blocked",
                ),
                overflowActions = emptyList(),
                blockers = listOf("blocked"),
                artifactBinding = SpecWorkflowStageArtifactBinding(
                    stageId = StageId.REQUIREMENTS,
                    title = "requirements.md",
                    fileName = "requirements.md",
                    documentPhase = SpecPhase.SPECIFY,
                    mode = SpecWorkflowWorkbenchDocumentMode.PREVIEW,
                    fallbackEditable = false,
                    available = false,
                    previewContent = null,
                    emptyStateMessage = emptyMessage,
                    unavailableMessage = emptyMessage,
                ),
                visibleSections = setOf(SpecWorkflowWorkspaceSectionId.DOCUMENTS),
            ),
            syncSelection = true,
        )

        assertEquals(emptyMessage, panel.currentPreviewTextForTest())
        assertEquals(emptyMessage, panel.currentValidationTextForTest())
        assertEquals("", panel.currentDocumentMetaTextForTest())
        assertEquals(SpecPhase.SPECIFY.name, panel.selectedPhaseNameForTest())
        assertFalse(panel.areDocumentTabsVisibleForTest())
    }

    @Test
    fun `updateWorkbenchState should explain missing verification artifact in workbench preview`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-verify-empty",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = emptyMap(),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Verify Empty",
            description = "verify empty state",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val emptyMessage = SpecCodingBundle.message("spec.detail.workbench.verify.missing")

        panel.updateWorkflow(workflow)
        panel.updateWorkbenchState(
            state = SpecWorkflowStageWorkbenchState(
                currentStage = StageId.VERIFY,
                focusedStage = StageId.VERIFY,
                progress = SpecWorkflowStageProgressView(
                    stepIndex = 5,
                    totalSteps = 6,
                    stageStatus = com.eacape.speccodingplugin.spec.StageProgress.IN_PROGRESS,
                    completedCheckCount = 1,
                    totalCheckCount = 3,
                    completionChecks = emptyList(),
                ),
                primaryAction = SpecWorkflowWorkbenchAction(
                    kind = SpecWorkflowWorkbenchActionKind.RUN_VERIFY,
                    label = "Run verification",
                    enabled = true,
                ),
                overflowActions = emptyList(),
                blockers = emptyList(),
                artifactBinding = SpecWorkflowStageArtifactBinding(
                    stageId = StageId.VERIFY,
                    title = "verification.md",
                    fileName = "verification.md",
                    documentPhase = null,
                    mode = SpecWorkflowWorkbenchDocumentMode.READ_ONLY,
                    fallbackEditable = false,
                    available = false,
                    previewContent = null,
                    emptyStateMessage = emptyMessage,
                    unavailableMessage = emptyMessage,
                ),
                visibleSections = setOf(SpecWorkflowWorkspaceSectionId.DOCUMENTS),
            ),
            syncSelection = true,
        )

        assertEquals(emptyMessage, panel.currentPreviewTextForTest())
        assertEquals(emptyMessage, panel.currentValidationTextForTest())
        assertEquals("", panel.currentDocumentMetaTextForTest())
        assertEquals(null, panel.selectedPhaseNameForTest())
        assertFalse(panel.areDocumentTabsVisibleForTest())
    }

    @Test
    fun `document viewport should keep a fixed height for long content`() {
        val panel = createPanel()
        val longTasksContent = buildString {
            appendLine("# Tasks")
            repeat(80) { index ->
                appendLine("## Item ${index + 1}")
                appendLine("- detail line ${index + 1}")
            }
        }.trim()
        val workflow = SpecWorkflow(
            id = "wf-document-scroll",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = longTasksContent,
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Long Document",
            description = "Document viewport should stay fixed",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        assertEquals(JBUI.scale(360), panel.documentViewportPreferredHeightForTest())
        assertEquals(panel.documentViewportPreferredHeightForTest(), panel.documentViewportMinimumHeightForTest())
    }

    @Test
    fun `document toolbar should not render legacy preview mode buttons`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-no-legacy-mode-buttons",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "No Legacy Buttons",
            description = "toolbar should stay focused",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)
        assertFalse(panel.hasLegacyDocumentModeButtonsForTest())

        panel.showClarificationDraft(
            phase = SpecPhase.DESIGN,
            input = "clarify the design",
            questionsMarkdown = "1. Explain the trade-offs",
            suggestedDetails = "Keep the current module split",
        )

        assertFalse(panel.hasLegacyDocumentModeButtonsForTest())
    }

    @Test
    fun `updateWorkflow should keep retired toolbar actions hidden in implement phase`() {
        val panel = createPanel()

        val workflow = SpecWorkflow(
            id = "wf-2",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "tasks content",
                    valid = true,
                )
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Impl",
            description = "Impl workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertFalse(states["completeEnabled"] as Boolean)
        assertFalse(states["completeVisible"] as Boolean)
        assertFalse(states["pauseResumeEnabled"] as Boolean)
        assertFalse(states["pauseResumeVisible"] as Boolean)
        assertEquals(
            listOf("generate", "openEditor", "historyDiff", "edit"),
            panel.visibleComposerActionOrderForTest(),
        )
    }

    @Test
    fun `paused workflow should show resume text and disable generate related actions`() {
        val panel = createPanel()

        val workflow = SpecWorkflow(
            id = "wf-3",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = "design content",
                    valid = true,
                )
            ),
            status = WorkflowStatus.PAUSED,
            title = "Paused",
            description = "Paused workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )

        panel.updateWorkflow(workflow)

        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertFalse(states["nextEnabled"] as Boolean)
        assertFalse(states["goBackEnabled"] as Boolean)
        assertFalse(states["completeEnabled"] as Boolean)
        assertFalse(states["completeVisible"] as Boolean)
        assertFalse(states["pauseResumeEnabled"] as Boolean)
        assertFalse(states["pauseResumeVisible"] as Boolean)
        assertTrue(states["historyDiffEnabled"] as Boolean)
        assertEquals(
            listOf("generate", "openEditor", "historyDiff", "edit"),
            panel.visibleComposerActionOrderForTest(),
        )
    }

    @Test
    fun `clarification loading should animate status and lock clarify actions until draft is ready`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-clarify",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = """
                        ## 功能需求
                        - 用户可创建任务
                        
                        ## 非功能需求
                        - 响应时间 < 1s
                        
                        ## 用户故事
                        As a user, I want to create tasks, so that I can track work.
                        
                        ## 验收标准
                        - [ ] 创建成功
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify",
            description = "Clarification workflow",
            artifactDraftStates = mapOf(StageId.REQUIREMENTS to ArtifactDraftState.UNMATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showClarificationGenerating(
            phase = SpecPhase.SPECIFY,
            input = "build a todo app",
            suggestedDetails = "build a todo app",
        )

        assertTrue(
            panel.currentValidationTextForTest().contains(
                SpecCodingBundle.message("spec.workflow.clarify.generating"),
            ),
        )
        assertFalse(panel.isClarificationPreviewVisibleForTest())
        val generatingStates = panel.buttonStatesForTest()
        assertFalse(generatingStates["confirmGenerateEnabled"] as Boolean)
        assertFalse(generatingStates["regenerateClarificationEnabled"] as Boolean)
        assertFalse(generatingStates["skipClarificationEnabled"] as Boolean)
        assertFalse(generatingStates["cancelClarificationEnabled"] as Boolean)

        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build a todo app",
            questionsMarkdown = "1. Should this support offline mode?",
            suggestedDetails = "- need offline cache",
        )

        val readyStatus = panel.currentValidationTextForTest()
        assertTrue(readyStatus.contains(SpecCodingBundle.message("spec.workflow.clarify.hint")))
        assertFalse(readyStatus.contains("◐"))
        assertTrue(panel.isClarificationPreviewVisibleForTest())
        assertTrue(panel.isInputSectionVisibleForTest())
        assertFalse(panel.isBottomCollapsedForChecklistForTest())
        val readyStates = panel.buttonStatesForTest()
        assertTrue(readyStates["confirmGenerateEnabled"] as Boolean)
        assertTrue(readyStates["regenerateClarificationEnabled"] as Boolean)
        assertTrue(readyStates["skipClarificationEnabled"] as Boolean)
        assertTrue(readyStates["cancelClarificationEnabled"] as Boolean)
    }

    @Test
    fun `clarification checklist should support click-to-confirm without manual input`() {
        var confirmedInput: String? = null
        var confirmedContext: String? = null
        val panel = createPanel(
            onClarificationConfirm = { input, context ->
                confirmedInput = input
                confirmedContext = context
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-checklist",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist",
            description = "Clarification checklist",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "RunnerGo needs redis nodes support",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "是否需要多机房容灾？",
                "性能指标要求是多少？",
            ),
        )

        assertFalse(panel.isInputEditableForTest())
        assertFalse(panel.isInputSectionVisibleForTest())
        assertTrue(panel.isBottomCollapsedForChecklistForTest())
        panel.clickConfirmGenerateForTest()
        assertEquals(null, confirmedContext)
        assertTrue(
            panel.currentValidationTextForTest().contains(
                SpecCodingBundle.message("spec.detail.clarify.detailsRequired"),
            ),
        )

        panel.toggleClarificationQuestionForTest(0)
        panel.clickConfirmGenerateForTest()
        assertEquals(null, confirmedContext)
        assertTrue(
            panel.currentValidationTextForTest().contains(
                SpecCodingBundle.message("spec.detail.clarify.checklist.detail.required", "是否需要多机房容灾？"),
            ),
        )

        panel.setClarificationQuestionDetailForTest(0, "至少两地三中心，RPO<5s，RTO<30s")
        panel.clickConfirmGenerateForTest()

        assertEquals("RunnerGo needs redis nodes support", confirmedInput)
        assertTrue((confirmedContext ?: "").contains("**${SpecCodingBundle.message("spec.detail.clarify.confirmed.title")}**"))
        assertTrue((confirmedContext ?: "").contains("是否需要多机房容灾？"))
        assertTrue(
            (confirmedContext ?: "").contains(
                "${SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")}: 至少两地三中心，RPO<5s，RTO<30s",
            ),
        )
        assertFalse((confirmedContext ?: "").contains("## "))
    }

    @Test
    fun `clarification checklist should support not applicable and detail serialization without checkbox markers`() {
        var confirmedContext: String? = null
        val panel = createPanel(
            onClarificationConfirm = { _, context ->
                confirmedContext = context
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-checklist-tristate",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Tri-state",
            description = "Clarification checklist tri-state",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build a storage scheduler",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "是否要求跨区域容灾？",
                "目标吞吐量是多少？",
            ),
        )

        panel.toggleClarificationQuestionForTest(0)
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(0))
        panel.setClarificationQuestionDetailForTest(0, "容灾等级按 P0 执行")

        panel.markClarificationQuestionNotApplicableForTest(1)
        assertEquals("NOT_APPLICABLE", panel.currentChecklistDecisionForTest(1))

        panel.clickConfirmGenerateForTest()

        val context = confirmedContext ?: ""
        assertTrue(context.contains("**${SpecCodingBundle.message("spec.detail.clarify.confirmed.title")}**"))
        assertTrue(context.contains("是否要求跨区域容灾？"))
        assertTrue(
            context.contains(
                "${SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")}: 容灾等级按 P0 执行",
            ),
        )
        assertTrue(context.contains("**${SpecCodingBundle.message("spec.detail.clarify.notApplicable.title")}**"))
        assertTrue(context.contains("目标吞吐量是多少？"))
        assertFalse(context.contains("[x]"))
        assertFalse(context.contains("[ ]"))
        assertFalse(context.contains("## "))
    }

    @Test
    fun `clarification checklist should auto collapse previous detail editor when switching items`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-checklist-auto-collapse",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Collapse",
            description = "clarification checklist collapse",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "clarify details",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "问题A",
                "问题B",
            ),
        )

        panel.toggleClarificationQuestionForTest(0)
        panel.setClarificationQuestionDetailForTest(0, "A 的补充")
        assertEquals(0, panel.activeChecklistDetailIndexForTest())
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(0))

        panel.toggleClarificationQuestionForTest(1)
        assertEquals(1, panel.activeChecklistDetailIndexForTest())
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(0))
        assertEquals("CONFIRMED", panel.currentChecklistDecisionForTest(1))
    }

    @Test
    fun `clarification checklist should lock edits after confirm generate`() {
        var confirmCalls = 0
        val panel = createPanel(
            onClarificationConfirm = { _, _ ->
                confirmCalls += 1
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-checklist-lock-after-confirm",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Checklist Lock",
            description = "clarification checklist lock",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "clarify details",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "问题A",
                "问题B",
            ),
        )
        panel.toggleClarificationQuestionForTest(0)
        panel.setClarificationQuestionDetailForTest(0, "A 的补充")
        val originalDetail = panel.currentChecklistDetailForTest(0)

        panel.clickConfirmGenerateForTest()

        assertEquals(1, confirmCalls)
        assertTrue(panel.isClarificationChecklistReadOnlyForTest())

        panel.toggleClarificationQuestionForTest(1)
        assertEquals("UNDECIDED", panel.currentChecklistDecisionForTest(1))

        panel.setClarificationQuestionDetailForTest(0, "被忽略的修改")
        assertEquals(originalDetail, panel.currentChecklistDetailForTest(0))
    }

    @Test
    fun `clarification collapse toggle should use explicit text labels`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-clarify-toggle-text",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify Toggle Text",
            description = "clarify toggle text",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build scheduler",
            questionsMarkdown = "1. Should support offline?",
            suggestedDetails = "",
        )

        assertEquals(SpecCodingBundle.message("spec.detail.toggle.collapse"), panel.clarificationQuestionsToggleTextForTest())
        assertTrue(panel.clarificationQuestionsToggleHasEnoughWidthForTest())
        assertTrue(panel.clarificationQuestionsToggleCanFitTextForTest("Collapse"))
        panel.toggleClarificationQuestionsExpandedForTest()
        assertEquals(SpecCodingBundle.message("spec.detail.toggle.expand"), panel.clarificationQuestionsToggleTextForTest())
        assertTrue(panel.clarificationQuestionsToggleHasEnoughWidthForTest())
        assertTrue(panel.clarificationQuestionsToggleCanFitTextForTest("Expand"))
    }

    @Test
    fun `checklist markdown bold markers should be parsed into bold segments`() {
        val panel = createPanel()

        val segments = panel.parseChecklistQuestionSegmentsForTest("**VIBE 你好** 和 **SPEC** 需要确认")

        assertEquals(
            listOf(
                "VIBE 你好" to true,
                " 和 " to false,
                "SPEC" to true,
                " 需要确认" to false,
            ),
            segments,
        )
    }

    @Test
    fun `checklist markdown inline code markers should be parsed into code segments`() {
        val panel = createPanel()

        val segments = panel.parseChecklistQuestionSegmentsWithStyleForTest(
            "执行 `gradle test` 或 ``./gradlew.bat buildPlugin``",
        )

        assertEquals(
            listOf(
                Triple("执行 ", false, false),
                Triple("gradle test", false, true),
                Triple(" 或 ", false, false),
                Triple("./gradlew.bat buildPlugin", false, true),
            ),
            segments,
        )
    }

    @Test
    fun `checklist question text should normalize multiline whitespace`() {
        val panel = createPanel()

        val segments = panel.parseChecklistQuestionSegmentsWithStyleForTest(
            "  第一行 \n   **重点**   \n  ``foo`bar``   ",
        )

        assertEquals(
            listOf(
                Triple("第一行 ", false, false),
                Triple("重点", true, false),
                Triple(" ", false, false),
                Triple("foo`bar", false, true),
            ),
            segments,
        )
    }

    @Test
    fun `process timeline should show entries and clear properly`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-process",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Process",
            description = "timeline",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.appendProcessTimelineEntry(
            text = "Prepare clarification context",
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )
        panel.appendProcessTimelineEntry(
            text = "Calling model to generate content (30%)",
            state = SpecDetailPanel.ProcessTimelineState.ACTIVE,
        )

        assertTrue(panel.isProcessTimelineVisibleForTest())
        val timelineText = panel.currentProcessTimelineTextForTest()
        assertTrue(timelineText.contains("Prepare clarification context"))
        assertTrue(timelineText.contains("Calling model to generate content"))

        panel.clearProcessTimeline()

        assertFalse(panel.isProcessTimelineVisibleForTest())
        assertEquals("", panel.currentProcessTimelineTextForTest())
    }

    @Test
    fun `process timeline should support collapse toggle`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-process-collapse",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Process Collapse",
            description = "timeline collapse",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.appendProcessTimelineEntry(
            text = "Prepare clarification context",
            state = SpecDetailPanel.ProcessTimelineState.DONE,
        )

        assertTrue(panel.isProcessTimelineExpandedForTest())
        panel.toggleProcessTimelineExpandedForTest()
        assertFalse(panel.isProcessTimelineExpandedForTest())
        panel.toggleProcessTimelineExpandedForTest()
        assertTrue(panel.isProcessTimelineExpandedForTest())
    }

    @Test
    fun `clarification sections should support collapse toggles`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-clarify-collapse",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify Collapse",
            description = "clarify collapse",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build scheduler",
            questionsMarkdown = "1. Should support offline?",
            suggestedDetails = "",
        )

        assertTrue(panel.isClarificationQuestionsExpandedForTest())
        assertTrue(panel.isClarificationPreviewExpandedForTest())
        panel.toggleClarificationQuestionsExpandedForTest()
        panel.toggleClarificationPreviewExpandedForTest()
        assertFalse(panel.isClarificationQuestionsExpandedForTest())
        assertFalse(panel.isClarificationPreviewExpandedForTest())
    }

    @Test
    fun `clarification reply input should autosave on text changes`() {
        var autosaveCalls = 0
        var autosavedContext = ""
        var autosavedQuestions = emptyList<String>()
        val panel = createPanel(
            onClarificationDraftAutosave = { _, context, _, questions ->
                autosaveCalls += 1
                autosavedContext = context
                autosavedQuestions = questions
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-clarify-autosave",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Clarify Autosave",
            description = "clarify autosave",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.SPECIFY,
            input = "build storage service",
            questionsMarkdown = "1. ???",
            suggestedDetails = "",
            structuredQuestions = listOf(
                "是否需要多租户隔离？",
                "是否需要跨区域容灾？",
            ),
        )
        panel.toggleClarificationQuestionForTest(0)
        panel.setClarificationQuestionDetailForTest(0, "需要，按租户维度做资源与权限隔离")

        assertTrue(autosaveCalls > 0)
        assertTrue(
            autosavedContext.contains(
                "${SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")}: 需要，按租户维度做资源与权限隔离",
            ),
        )
        assertEquals(2, autosavedQuestions.size)
    }

    @Test
    fun `clicking generate should clear input area`() {
        var generatedInput: String? = null
        val panel = createPanel(
            onGenerate = { generatedInput = it },
        )
        val workflow = SpecWorkflow(
            id = "wf-generate-clear",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Generate",
            description = "Generate workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.setInputTextForTest("new requirements input")

        panel.clickGenerateForTest()

        assertEquals("new requirements input", generatedInput)
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `clicking generate with empty requirements should prompt input and not trigger generation`() {
        var generateCallCount = 0
        val panel = createPanel(
            onGenerate = { generateCallCount += 1 },
        )
        val workflow = SpecWorkflow(
            id = "wf-generate-empty",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Generate Empty",
            description = "Generate empty workflow",
            artifactDraftStates = mapOf(StageId.REQUIREMENTS to ArtifactDraftState.UNMATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.setInputTextForTest("")

        panel.clickGenerateForTest()

        assertEquals(0, generateCallCount)
        assertEquals(SpecCodingBundle.message("spec.detail.input.required"), panel.currentValidationTextForTest())
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `clicking revise with empty requirements should prompt revise input and not trigger generation`() {
        var generateCallCount = 0
        val panel = createPanel(
            onGenerate = { generateCallCount += 1 },
        )
        val workflow = SpecWorkflow(
            id = "wf-revise-empty",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = """
                        # Requirements Document

                        ## Functional Requirements
                        - Existing requirement.
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Revise Empty",
            description = "Revise empty workflow",
            artifactDraftStates = mapOf(StageId.REQUIREMENTS to ArtifactDraftState.MATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.setInputTextForTest("")

        panel.clickGenerateForTest()

        assertEquals(0, generateCallCount)
        assertEquals(SpecCodingBundle.message("spec.detail.input.required.revise"), panel.currentValidationTextForTest())
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `clicking generate with empty requirements should continue when reusable input is available`() {
        var generatedInput: String? = null
        val panel = createPanel(
            onGenerate = { generatedInput = it },
            canGenerateWithEmptyInput = { true },
        )
        val workflow = SpecWorkflow(
            id = "wf-generate-empty-resume",
            currentPhase = SpecPhase.SPECIFY,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = "requirements content",
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Generate Empty Resume",
            description = "Generate empty workflow resume",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.setInputTextForTest("")

        panel.clickGenerateForTest()

        assertEquals("", generatedInput)
        assertEquals("", panel.currentInputTextForTest())
    }

    @Test
    fun `validation failure should provide interactive repair guidance`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-validation",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "draft tasks",
                    valid = false,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Validation",
            description = "Validation workflow",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showValidationFailureInteractive(
            phase = SpecPhase.IMPLEMENT,
            validation = ValidationResult(
                valid = false,
                errors = listOf("Missing required section: Implementation Steps"),
                warnings = listOf("Task count is low"),
                suggestions = listOf("Add priority labels"),
            ),
        )

        assertTrue(panel.currentValidationTextForTest().contains(SpecCodingBundle.message("spec.detail.validation.failed")))
        val preview = panel.currentPreviewTextForTest()
        assertTrue(preview.contains("draft tasks"))
        assertTrue(preview.contains(SpecCodingBundle.message("spec.detail.validation.issues.errors")))
        assertTrue(preview.contains("Implementation Steps"))
        assertTrue(preview.contains("Task count is low"))

        val states = panel.buttonStatesForTest()
        assertTrue(states["generateEnabled"] as Boolean)
    }

    @Test
    fun `showGenerating should switch revise progress copy and disable primary action`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-revise-progress",
            currentPhase = SpecPhase.DESIGN,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = """
                        # Design Document

                        ## Architecture Design
                        - Existing design content.
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Revise progress",
            description = "show revise progress copy",
            artifactDraftStates = mapOf(StageId.DESIGN to ArtifactDraftState.MATERIALIZED),
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)

        panel.showGenerating(0.35)

        assertTrue(
            panel.currentValidationTextForTest().contains(
                SpecCodingBundle.message("spec.detail.revising.percent", 35),
            ),
        )
        val states = panel.buttonStatesForTest()
        assertFalse(states["generateEnabled"] as Boolean)
        assertEquals(
            SpecCodingBundle.message("spec.detail.action.disabled.running.revise"),
            states["generateTooltip"],
        )
    }

    @Test
    fun `validation failure should keep clarification actions when clarification state exists`() {
        val panel = createPanel()
        val workflow = SpecWorkflow(
            id = "wf-validation-clarify",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = "draft tasks",
                    valid = false,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Validation Clarify",
            description = "Validation with clarify state",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.IMPLEMENT,
            input = "",
            questionsMarkdown = "1. Is rollback required?",
            suggestedDetails = "- keep rollback steps",
        )
        panel.setInputTextForTest("- keep rollback steps")

        panel.showValidationFailureInteractive(
            phase = SpecPhase.IMPLEMENT,
            validation = ValidationResult(
                valid = false,
                errors = listOf("Missing required section: Implementation Steps"),
            ),
        )

        val states = panel.buttonStatesForTest()
        assertTrue(states["confirmGenerateEnabled"] as Boolean)
        assertTrue(states["regenerateClarificationEnabled"] as Boolean)
        assertTrue(states["skipClarificationEnabled"] as Boolean)
        assertEquals("- keep rollback steps", panel.currentInputTextForTest())
    }

    @Test
    fun `clarification confirm should allow empty details in implement phase`() {
        var confirmedInput: String? = null
        var confirmedContext: String? = null
        val panel = createPanel(
            onClarificationConfirm = { input, context ->
                confirmedInput = input
                confirmedContext = context
            },
        )
        val workflow = SpecWorkflow(
            id = "wf-implement-clarify",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = """
                        ## 架构设计
                        - layered
                        
                        ## 技术选型
                        - Kotlin
                        
                        ## 数据模型
                        - Task
                    """.trimIndent(),
                    valid = true,
                ),
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Implement Clarify",
            description = "implement clarification",
            createdAt = 1L,
            updatedAt = 2L,
        )
        panel.updateWorkflow(workflow)
        panel.showClarificationDraft(
            phase = SpecPhase.IMPLEMENT,
            input = "",
            questionsMarkdown = "1. 任务优先级如何拆分？",
            suggestedDetails = "",
        )
        panel.setInputTextForTest("")

        panel.clickConfirmGenerateForTest()

        assertEquals("", confirmedInput)
        assertEquals("", confirmedContext)
    }

    private fun createPanel(
        onGenerate: (String) -> Unit = {},
        canGenerateWithEmptyInput: () -> Boolean = { false },
        onClarificationConfirm: (String, String) -> Unit = { _, _ -> },
        onClarificationRegenerate: (String, String) -> Unit = { _, _ -> },
        onOpenArtifactInEditor: (String) -> Unit = {},
        onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit = { _, _, onDone ->
            onDone(Result.failure(IllegalStateException("not implemented")))
        },
        onClarificationDraftAutosave: (String, String, String, List<String>) -> Unit = { _, _, _, _ -> },
    ): SpecDetailPanel {
        return SpecDetailPanel(
            onGenerate = onGenerate,
            canGenerateWithEmptyInput = canGenerateWithEmptyInput,
            onAddWorkflowSourcesRequested = {},
            onRemoveWorkflowSourceRequested = {},
            onRestoreWorkflowSourcesRequested = {},
            onClarificationConfirm = onClarificationConfirm,
            onClarificationRegenerate = onClarificationRegenerate,
            onClarificationSkip = {},
            onClarificationCancel = {},
            onNextPhase = {},
            onGoBack = {},
            onComplete = {},
            onPauseResume = {},
            onOpenInEditor = {},
            onOpenArtifactInEditor = onOpenArtifactInEditor,
            onShowHistoryDiff = {},
            onSaveDocument = onSaveDocument,
            onClarificationDraftAutosave = onClarificationDraftAutosave,
        )
    }

    private fun completedRequirementsAndDesignWorkflow(
        requirementsContent: String = """
            # Requirements

            - Keep workflow artifacts file-first.
        """.trimIndent(),
        designContent: String = """
            # Design

            - Preserve layered boundaries.
        """.trimIndent(),
        tasksContent: String = """
            # Tasks

            - [ ] Ship the revision guard.
        """.trimIndent(),
    ): SpecWorkflow {
        return SpecWorkflow(
            id = "wf-completed-stages",
            currentPhase = SpecPhase.IMPLEMENT,
            documents = mapOf(
                SpecPhase.SPECIFY to document(
                    phase = SpecPhase.SPECIFY,
                    content = requirementsContent,
                    valid = true,
                ),
                SpecPhase.DESIGN to document(
                    phase = SpecPhase.DESIGN,
                    content = designContent,
                    valid = true,
                ),
                SpecPhase.IMPLEMENT to document(
                    phase = SpecPhase.IMPLEMENT,
                    content = tasksContent,
                    valid = true,
                ),
            ),
            stageStates = mapOf(
                StageId.REQUIREMENTS to StageState(active = true, status = StageProgress.DONE),
                StageId.DESIGN to StageState(active = true, status = StageProgress.DONE),
                StageId.TASKS to StageState(active = true, status = StageProgress.IN_PROGRESS),
            ),
            artifactDraftStates = mapOf(
                StageId.REQUIREMENTS to ArtifactDraftState.MATERIALIZED,
                StageId.DESIGN to ArtifactDraftState.MATERIALIZED,
                StageId.TASKS to ArtifactDraftState.MATERIALIZED,
            ),
            status = WorkflowStatus.IN_PROGRESS,
            title = "Completed upstream stages",
            description = "requirements/design should stay protected",
            createdAt = 1L,
            updatedAt = 2L,
        )
    }

    private fun document(phase: SpecPhase, content: String, valid: Boolean): SpecDocument {
        return SpecDocument(
            id = "doc-${phase.name.lowercase()}",
            phase = phase,
            content = content,
            metadata = SpecMetadata(
                title = phase.displayName,
                description = "test",
            ),
            validationResult = ValidationResult(valid = valid),
        )
    }

    private fun workbenchState(
        currentStage: StageId,
        focusedStage: StageId,
        documentPhase: SpecPhase?,
        title: String,
        fileName: String? = title,
    ): SpecWorkflowStageWorkbenchState {
        return SpecWorkflowStageWorkbenchState(
            currentStage = currentStage,
            focusedStage = focusedStage,
            progress = SpecWorkflowStageProgressView(
                stepIndex = 1,
                totalSteps = 6,
                stageStatus = com.eacape.speccodingplugin.spec.StageProgress.IN_PROGRESS,
                completedCheckCount = 0,
                totalCheckCount = 4,
                completionChecks = emptyList(),
            ),
            primaryAction = null,
            overflowActions = emptyList(),
            blockers = emptyList(),
            artifactBinding = SpecWorkflowStageArtifactBinding(
                stageId = focusedStage,
                title = title,
                fileName = fileName,
                documentPhase = documentPhase,
                mode = if (documentPhase != null) {
                    SpecWorkflowWorkbenchDocumentMode.PREVIEW
                } else {
                    SpecWorkflowWorkbenchDocumentMode.READ_ONLY
                },
                fallbackEditable = false,
                available = true,
            ),
            visibleSections = setOf(SpecWorkflowWorkspaceSectionId.DOCUMENTS),
        )
    }
}
