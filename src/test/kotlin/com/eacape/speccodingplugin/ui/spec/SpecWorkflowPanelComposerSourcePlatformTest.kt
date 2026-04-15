package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecEngine
import com.eacape.speccodingplugin.spec.WorkflowSourceImportConstraints
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SpecWorkflowPanelComposerSourcePlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val specEngine = SpecEngine.getInstance(project)
        specEngine.listWorkflows().forEach { workflowId ->
            specEngine.deleteWorkflow(workflowId).getOrThrow()
        }
    }

    fun `test composer sources should import remove restore and reopen persisted state`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Source Import",
            description = "composer source persistence",
        ).getOrThrow()
        val importPath = Path.of(project.basePath!!).resolve("incoming/client-prd.md")
        Files.createDirectories(importPath.parent)
        Files.writeString(
            importPath,
            "# Client PRD\n\n- Keep workflow artifacts file-first.\n",
            StandardCharsets.UTF_8,
        )
        val panel = createPanel(sourceFileChooser = { _, _ -> listOf(importPath) })

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }
        waitUntil {
            panel.composerSourceHintTextForTest().isNotBlank()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickAddWorkflowSourcesForTest()
        }

        waitUntil {
            panel.composerSourceChipLabelsForTest()
                .any { label -> label.contains("SRC-001") && label.contains("client-prd.md") }
        }
        assertTrue(panel.composerSourceMetaTextForTest().contains("1"))

        ApplicationManager.getApplication().invokeAndWait {
            assertTrue(panel.clickRemoveWorkflowSourceForTest("SRC-001"))
        }

        waitUntil {
            panel.composerSourceChipLabelsForTest().isEmpty() &&
                panel.isComposerSourceRestoreVisibleForTest()
        }
        assertTrue(panel.composerSourceHintTextForTest().isNotBlank())
        assertEquals(
            listOf("SRC-001"),
            SpecEngine.getInstance(project)
                .listWorkflowSources(workflow.id)
                .getOrThrow()
                .map { it.sourceId },
        )

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickRestoreWorkflowSourcesForTest()
        }

        waitUntil {
            panel.composerSourceChipLabelsForTest().any { it.contains("SRC-001") }
        }

        ApplicationManager.getApplication().invokeAndWait {
            Disposer.dispose(panel)
        }

        val reopenedPanel = createPanel()
        waitUntil {
            reopenedPanel.isDetailModeForTest() && reopenedPanel.selectedWorkflowIdForTest() == workflow.id
        }
        waitUntil {
            reopenedPanel.composerSourceChipLabelsForTest()
                .any { label -> label.contains("SRC-001") && label.contains("client-prd.md") }
        }
    }

    fun `test composer sources should show validation feedback when import is rejected`() {
        val workflow = SpecEngine.getInstance(project).createWorkflow(
            title = "Rejected Sources",
            description = "composer source validation",
        ).getOrThrow()
        val unsupportedPath = Path.of(project.basePath!!).resolve("incoming/archive.zip")
        val oversizedPath = Path.of(project.basePath!!).resolve("incoming/requirements.pdf")
        Files.createDirectories(unsupportedPath.parent)
        Files.write(unsupportedPath, ByteArray(16) { 2 })
        Files.write(oversizedPath, ByteArray(96) { 3 })
        val missingPath = Path.of(project.basePath!!).resolve("incoming/missing.txt")
        val warnings = mutableListOf<Pair<String, String>>()
        val panel = createPanel(
            sourceFileChooser = { _, _ -> listOf(unsupportedPath, oversizedPath, missingPath) },
            sourceImportConstraints = WorkflowSourceImportConstraints(maxFileSizeBytes = 64L),
            warningDialogPresenter = { _, message, title ->
                warnings += title to message
            },
        )

        waitUntil {
            panel.isDetailModeForTest() && panel.selectedWorkflowIdForTest() == workflow.id
        }
        waitUntil {
            panel.composerSourceHintTextForTest().isNotBlank()
        }

        ApplicationManager.getApplication().invokeAndWait {
            panel.clickAddWorkflowSourcesForTest()
        }

        waitUntil {
            warnings.size == 1 &&
                panel.currentStatusTextForTest() == SpecCodingBundle.message("spec.detail.sources.status.rejected", 3)
        }

        assertEquals(1, warnings.size)
        assertEquals(SpecCodingBundle.message("spec.detail.sources.validation.title"), warnings.single().first)
        assertTrue(warnings.single().second.contains("archive.zip"))
        assertTrue(warnings.single().second.contains("requirements.pdf"))
        assertTrue(warnings.single().second.contains("missing.txt"))
        assertEquals(
            SpecCodingBundle.message("spec.detail.sources.status.rejected", 3),
            panel.currentStatusTextForTest(),
        )
        assertTrue(panel.composerSourceChipLabelsForTest().isEmpty())
        assertTrue(SpecEngine.getInstance(project).listWorkflowSources(workflow.id).getOrThrow().isEmpty())
    }

    private fun createPanel(
        sourceFileChooser: ((Project, WorkflowSourceImportConstraints) -> List<Path>)? = null,
        sourceImportConstraints: WorkflowSourceImportConstraints = WorkflowSourceImportConstraints(),
        warningDialogPresenter: ((Project, String, String) -> Unit)? = null,
    ): SpecWorkflowPanel {
        var panel: SpecWorkflowPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = when {
                sourceFileChooser != null && warningDialogPresenter != null -> {
                    SpecWorkflowPanel(
                        project,
                        sourceFileChooser = sourceFileChooser,
                        sourceImportConstraints = sourceImportConstraints,
                        warningDialogPresenter = warningDialogPresenter,
                    )
                }

                sourceFileChooser != null -> {
                    SpecWorkflowPanel(
                        project,
                        sourceFileChooser = sourceFileChooser,
                        sourceImportConstraints = sourceImportConstraints,
                    )
                }

                warningDialogPresenter != null -> {
                    SpecWorkflowPanel(
                        project,
                        sourceImportConstraints = sourceImportConstraints,
                        warningDialogPresenter = warningDialogPresenter,
                    )
                }

                sourceImportConstraints != WorkflowSourceImportConstraints() -> {
                    SpecWorkflowPanel(
                        project,
                        sourceImportConstraints = sourceImportConstraints,
                    )
                }

                else -> {
                    SpecWorkflowPanel(project)
                }
            }
            Disposer.register(testRootDisposable, panel!!)
        }
        return panel ?: error("Failed to create SpecWorkflowPanel")
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }
}
