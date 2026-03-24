package com.eacape.speccodingplugin.ui.editor

import com.eacape.speccodingplugin.rollback.Changeset
import com.eacape.speccodingplugin.rollback.FileChange
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.worktree.WorktreeBinding
import com.eacape.speccodingplugin.worktree.WorktreeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class EditorInsightResolverTest {

    @Test
    fun `resolve should pick latest ai change and active worktree spec association`() {
        val filePath = "D:/repo/spec-feature/src/Main.kt"
        val resolver = EditorInsightResolver(
            recentChangesetsProvider = {
                listOf(
                    changeset(
                        id = "old-change",
                        timestampMillis = 1_000L,
                        filePath = "D:\\repo\\spec-feature\\src\\Main.kt",
                        changeType = FileChange.ChangeType.CREATED,
                    ),
                    changeset(
                        id = "new-change",
                        timestampMillis = 2_000L,
                        filePath = "D:/repo/spec-feature/src/Main.kt",
                        changeType = FileChange.ChangeType.MODIFIED,
                    ),
                )
            },
            activeBindingsProvider = {
                listOf(
                    binding(specTaskId = "spec-123", worktreePath = "D:\\repo\\spec-feature"),
                )
            },
            workflowProvider = { workflowId ->
                if (workflowId == "spec-123") workflow(workflowId, WorkflowStatus.IN_PROGRESS, SpecPhase.DESIGN) else null
            },
        )

        val insight = resolver.resolve(filePath)

        assertNotNull(insight.aiChange)
        assertEquals("new-change", insight.aiChange?.changesetId)
        assertEquals(FileChange.ChangeType.MODIFIED, insight.aiChange?.changeType)
        assertEquals("spec-123", insight.specAssociation?.specTaskId)
        assertEquals(WorkflowStatus.IN_PROGRESS, insight.specAssociation?.workflowStatus)
        assertEquals(SpecPhase.DESIGN, insight.specAssociation?.workflowPhase)
    }

    @Test
    fun `resolve should fallback to spec storage path when no worktree binding matches`() {
        val resolver = EditorInsightResolver(
            recentChangesetsProvider = { emptyList() },
            activeBindingsProvider = { emptyList() },
            workflowProvider = { workflowId ->
                if (workflowId == "spec-456") workflow(workflowId, WorkflowStatus.COMPLETED, SpecPhase.IMPLEMENT) else null
            },
        )

        val insight = resolver.resolve("D:/repo/.spec-coding/specs/spec-456/design.md")

        assertNull(insight.aiChange)
        assertEquals("spec-456", insight.specAssociation?.specTaskId)
        assertEquals(WorkflowStatus.COMPLETED, insight.specAssociation?.workflowStatus)
        assertEquals(SpecPhase.IMPLEMENT, insight.specAssociation?.workflowPhase)
    }

    @Test
    fun `resolve should choose the longest matched worktree path`() {
        val resolver = EditorInsightResolver(
            recentChangesetsProvider = { emptyList() },
            activeBindingsProvider = {
                listOf(
                    binding(specTaskId = "spec-root", worktreePath = "D:/repo"),
                    binding(specTaskId = "spec-nested", worktreePath = "D:/repo/spec-nested"),
                )
            },
            workflowProvider = { workflowId -> workflow(workflowId, WorkflowStatus.PAUSED, SpecPhase.SPECIFY) },
        )

        val insight = resolver.resolve("D:/repo/spec-nested/src/Nested.kt")

        assertEquals("spec-nested", insight.specAssociation?.specTaskId)
    }

    @Test
    fun `resolve should return empty insight when no source matches`() {
        val resolver = EditorInsightResolver(
            recentChangesetsProvider = { emptyList() },
            activeBindingsProvider = { emptyList() },
            workflowProvider = { null },
        )

        val insight = resolver.resolve("D:/repo/src/Plain.kt")

        assertNull(insight.aiChange)
        assertNull(insight.specAssociation)
    }

    private fun changeset(
        id: String,
        timestampMillis: Long,
        filePath: String,
        changeType: FileChange.ChangeType,
    ): Changeset {
        return Changeset(
            id = id,
            description = id,
            changes = listOf(
                FileChange(
                    filePath = filePath,
                    beforeContent = null,
                    afterContent = "content",
                    changeType = changeType,
                    timestamp = Instant.ofEpochMilli(timestampMillis),
                )
            ),
            timestamp = Instant.ofEpochMilli(timestampMillis),
        )
    }

    private fun binding(specTaskId: String, worktreePath: String): WorktreeBinding {
        return WorktreeBinding(
            id = specTaskId,
            specTaskId = specTaskId,
            branchName = "spec/$specTaskId-feature",
            worktreePath = worktreePath,
            baseBranch = "main",
            status = WorktreeStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private fun workflow(id: String, status: WorkflowStatus, phase: SpecPhase): SpecWorkflow {
        return SpecWorkflow(
            id = id,
            currentPhase = phase,
            documents = emptyMap(),
            status = status,
            title = id,
            description = id,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
}
