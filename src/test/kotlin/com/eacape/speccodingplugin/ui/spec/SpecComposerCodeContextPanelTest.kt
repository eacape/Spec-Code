package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.CodeChangeFile
import com.eacape.speccodingplugin.spec.CodeChangeFileStatus
import com.eacape.speccodingplugin.spec.CodeChangeSource
import com.eacape.speccodingplugin.spec.CodeChangeSummary
import com.eacape.speccodingplugin.spec.CodeContextCandidateFile
import com.eacape.speccodingplugin.spec.CodeContextCandidateSignal
import com.eacape.speccodingplugin.spec.CodeContextCollectionStrategy
import com.eacape.speccodingplugin.spec.CodeContextPack
import com.eacape.speccodingplugin.spec.CodeVerificationEntryPoint
import com.eacape.speccodingplugin.spec.ProjectStructureSummary
import com.eacape.speccodingplugin.spec.SpecPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class SpecComposerCodeContextPanelTest {

    @Test
    fun `panel should show repo diff related and candidate file summary when auto context is available`() {
        val panel = SpecComposerCodeContextPanel()

        runOnEdt {
            panel.updateState(
                workflowId = "wf-1",
                codeContextPack = CodeContextPack(
                    phase = SpecPhase.IMPLEMENT,
                    strategy = CodeContextCollectionStrategy.forPhase(SpecPhase.IMPLEMENT),
                    projectStructure = ProjectStructureSummary(
                        topLevelDirectories = listOf("src"),
                        topLevelFiles = listOf("README.md"),
                        keyPaths = listOf("build.gradle.kts"),
                    ),
                    confirmedRelatedFiles = listOf("src/main/kotlin/com/example/App.kt"),
                    candidateFiles = listOf(
                        CodeContextCandidateFile(
                            path = "src/main/kotlin/com/example/App.kt",
                            signals = setOf(CodeContextCandidateSignal.CONFIRMED_RELATED_FILE),
                        ),
                        CodeContextCandidateFile(
                            path = "src/test/kotlin/com/example/AppTest.kt",
                            signals = setOf(
                                CodeContextCandidateSignal.VCS_CHANGE,
                                CodeContextCandidateSignal.WORKSPACE_CANDIDATE,
                            ),
                        ),
                    ),
                    changeSummary = CodeChangeSummary(
                        source = CodeChangeSource.VCS_STATUS,
                        files = listOf(
                            CodeChangeFile(
                                path = "src/main/kotlin/com/example/App.kt",
                                status = CodeChangeFileStatus.MODIFIED,
                            ),
                        ),
                        summary = "Git working tree reports 1 changed file(s).",
                        available = true,
                    ),
                    verificationEntryPoints = listOf(
                        CodeVerificationEntryPoint(
                            commandId = "unit",
                            displayName = "Unit Tests",
                            workingDirectory = ".",
                            commandPreview = "./gradlew test",
                        ),
                    ),
                ),
            )
        }

        assertTrue(panel.isVisible)
        assertEquals(
            listOf(
                SpecCodingBundle.message("spec.detail.codeContext.summary.repo"),
                SpecCodingBundle.message(
                    "spec.detail.codeContext.summary.diff",
                    SpecCodingBundle.message("spec.detail.codeContext.diff.git"),
                ),
                SpecCodingBundle.message("spec.detail.codeContext.summary.related", 1),
                SpecCodingBundle.message("spec.detail.codeContext.summary.verify", 1),
            ),
            panel.summaryChipLabelsForTest(),
        )
        assertEquals(
            listOf(
                "src/main/kotlin/com/example/App.kt",
                "src/test/kotlin/com/example/AppTest.kt",
            ),
            panel.candidateFileLabelsForTest(),
        )
        assertTrue(panel.metaTextForTest().contains("2"))
        assertEquals(
            SpecCodingBundle.message("spec.detail.codeContext.hint.ready"),
            panel.hintTextForTest(),
        )
    }

    @Test
    fun `panel should surface degraded state when automatic code context is unavailable`() {
        val panel = SpecComposerCodeContextPanel()
        val degradedReason = "Project base path is unavailable; local code context collection was skipped."

        runOnEdt {
            panel.updateState(
                workflowId = "wf-2",
                codeContextPack = CodeContextPack(
                    phase = SpecPhase.SPECIFY,
                    strategy = CodeContextCollectionStrategy.forPhase(SpecPhase.SPECIFY),
                    degradationReasons = listOf(degradedReason),
                ),
            )
        }

        assertTrue(panel.summaryChipLabelsForTest().contains(SpecCodingBundle.message("spec.detail.codeContext.summary.degraded")))
        assertTrue(panel.metaTextForTest().contains(SpecCodingBundle.message("spec.detail.step.requirements")))
        assertEquals(degradedReason, panel.hintTextForTest())
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
