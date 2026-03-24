package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.StageId
import com.eacape.speccodingplugin.spec.WorkflowSourceAsset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class SpecComposerSourcePanelTest {

    @Test
    fun `panel should show selected source chips and restore action for hidden sources`() {
        val panel = SpecComposerSourcePanel()
        val assets = listOf(
            sourceAsset("SRC-001", "client-prd.md", "text/markdown"),
            sourceAsset("SRC-002", "wireframe.png", "image/png"),
        )

        runOnEdt {
            panel.updateState(
                workflowId = "wf-1",
                assets = assets,
                selectedSourceIds = setOf("SRC-001"),
                editable = true,
            )
        }

        assertTrue(panel.isVisible)
        assertEquals(listOf("SRC-001 | client-prd.md"), panel.selectedSourceLabelsForTest())
        assertTrue(panel.isRestoreVisibleForTest())
        assertTrue(panel.metaTextForTest().contains("2"))
    }

    @Test
    fun `panel should surface callbacks for remove and restore`() {
        val removed = mutableListOf<String>()
        var restored = false
        val panel = SpecComposerSourcePanel(
            onRemoveRequested = { removed += it },
            onRestoreRequested = { restored = true },
        )
        val assets = listOf(sourceAsset("SRC-001", "client-prd.md", "text/markdown"))

        runOnEdt {
            panel.updateState(
                workflowId = "wf-1",
                assets = assets,
                selectedSourceIds = emptySet(),
                editable = true,
            )
            panel.updateState(
                workflowId = "wf-1",
                assets = assets,
                selectedSourceIds = setOf("SRC-001"),
                editable = true,
            )
            assertTrue(panel.clickRemoveForTest("SRC-001"))
            panel.updateState(
                workflowId = "wf-1",
                assets = assets,
                selectedSourceIds = emptySet(),
                editable = true,
            )
            panel.clickRestoreForTest()
        }

        assertEquals(listOf("SRC-001"), removed)
        assertTrue(restored)
        assertFalse(panel.selectedSourceLabelsForTest().isNotEmpty())
        assertTrue(panel.hintTextForTest().isNotBlank())
    }

    @Test
    fun `panel should expose add action as icon button`() {
        var addRequested = false
        val panel = SpecComposerSourcePanel(onAddRequested = { addRequested = true })

        runOnEdt {
            panel.updateState(
                workflowId = "wf-1",
                assets = emptyList(),
                selectedSourceIds = emptySet(),
                editable = true,
            )
            panel.clickAddForTest()
        }

        assertTrue(panel.isAddIconOnlyForTest())
        assertTrue(addRequested)
    }

    private fun sourceAsset(
        sourceId: String,
        originalFileName: String,
        mediaType: String,
    ): WorkflowSourceAsset {
        return WorkflowSourceAsset(
            sourceId = sourceId,
            originalFileName = originalFileName,
            storedRelativePath = "sources/$sourceId-$originalFileName",
            mediaType = mediaType,
            fileSize = 128,
            contentHash = "hash-$sourceId",
            importedAt = "2026-03-17T10:00:00Z",
            importedFromStage = StageId.REQUIREMENTS,
            importedFromEntry = "SPEC_COMPOSER",
        )
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
