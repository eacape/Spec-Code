package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

class ContextCollectorPlatformTest : BasePlatformTestCase() {

    fun `test collectContext should capture cold and first warm baseline on a medium fixture`() {
        val mainFile = createMediumFixture()
        openInEditor(mainFile)

        val capturedTelemetries = mutableListOf<ContextCollectionTelemetry>()
        val collector = ContextCollector(
            project = project,
            telemetryConsumer = capturedTelemetries::add,
        )
        val config = ContextConfig(
            tokenBudget = 24_000,
            includeSelectedCode = false,
            includeContainingScope = false,
            includeCurrentFile = true,
            includeImportDependencies = true,
            includeProjectStructure = true,
            preferGraphRelatedContext = true,
            maxFileItems = 96,
            maxSymbolItems = 96,
            maxContentBytes = 240_000,
            maxCollectionTimeMs = 2_000,
        )

        repeat(3) {
            collector.collectContext(config)
        }

        val coldRun = capturedTelemetries[0]
        val firstWarmRun = capturedTelemetries[1]
        val secondWarmRun = capturedTelemetries[2]

        assertEquals("cold", coldRun.baselineComparison?.phase)
        assertEquals("warm", firstWarmRun.baselineComparison?.phase)
        assertEquals("warm", secondWarmRun.baselineComparison?.phase)

        assertStageOutcome(coldRun, stage = "codeGraph", expectedOutcome = "miss")
        assertStageOutcome(coldRun, stage = "relatedFiles", expectedOutcome = "miss")
        assertStageOutcome(coldRun, stage = "projectStructure", expectedOutcome = "miss")

        assertStageOutcome(firstWarmRun, stage = "codeGraph", expectedOutcome = "hit")
        assertStageOutcome(firstWarmRun, stage = "relatedFiles", expectedOutcome = "hit")
        assertStageOutcome(firstWarmRun, stage = "projectStructure", expectedOutcome = "hit")

        assertTrue(firstWarmRun.baselineComparison?.firstWarmAfterCold == true)
        assertTrue(firstWarmRun.baselineComparison?.hasComparableBaseline() == true)
        assertTrue(firstWarmRun.baselineComparison?.hasImprovement() == true)
        assertTrue(shouldEmitContextTelemetryInfo(firstWarmRun))

        assertTrue(secondWarmRun.baselineComparison?.firstWarmAfterCold == false)
        assertFalse(shouldEmitContextTelemetryInfo(secondWarmRun))
    }

    private fun assertStageOutcome(
        telemetry: ContextCollectionTelemetry,
        stage: String,
        expectedOutcome: String,
    ) {
        val outcome = telemetry.cacheView
            ?.runOutcomes
            ?.firstOrNull { it.stage == stage }
            ?.outcome
        assertEquals(expectedOutcome, outcome, "Unexpected cache outcome for stage=$stage")
    }

    private fun createMediumFixture(): VirtualFile {
        repeat(40) { index ->
            val suffix = index.toString().padStart(2, '0')
            val group = index / 5
            createProjectFile(
                "src/main/kotlin/demo/helpers/group$group/Helper$suffix.kt",
                """
                package demo.helpers.group$group

                fun helper$suffix(input: String): String {
                    return input
                        .trim()
                        .replace(" ", "-")
                        .plus("-$suffix")
                }

                fun helper${suffix}Label(): String = "helper-$suffix"
                """.trimIndent(),
            )
            createProjectFile(
                "docs/guides/guide-$suffix.md",
                """
                # Guide $suffix

                This is fixture guide $suffix for context collection warm-up coverage.
                """.trimIndent(),
            )
        }
        repeat(16) { index ->
            val suffix = index.toString().padStart(2, '0')
            createProjectFile(
                "specs/changes/change-$suffix/tasks.md",
                """
                ## Change $suffix

                - [ ] Review helper$suffix usage
                - [ ] Verify fixture warm-up path
                """.trimIndent(),
            )
        }

        val imports = (0 until 40).joinToString(separator = "\n") { index ->
            val suffix = index.toString().padStart(2, '0')
            val group = index / 5
            "import demo.helpers.group$group.helper$suffix"
        }
        val calls = (0 until 40).joinToString(separator = "\n") { index ->
            val suffix = index.toString().padStart(2, '0')
            "    current = helper$suffix(current)"
        }

        return createProjectFile(
            "src/main/kotlin/demo/Main.kt",
            """
            package demo

            $imports

            fun runWorkflow(input: String): String {
                var current = input
            $calls
                return current
            }
            """.trimIndent(),
        )
    }

    private fun openInEditor(file: VirtualFile) {
        myFixture.openFileInEditor(file)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun createProjectFile(relativePath: String, content: String): VirtualFile {
        val baseDir = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(project.basePath!!)
            ?: error("Project base dir not found")
        val segments = relativePath.split('/').filter(String::isNotBlank)
        require(segments.isNotEmpty()) { "relativePath must not be blank" }

        var current = baseDir
        var createdFile: VirtualFile? = null
        WriteAction.runAndWait<Throwable> {
            segments.dropLast(1).forEach { segment ->
                current = current.findChild(segment) ?: current.createChildDirectory(this@ContextCollectorPlatformTest, segment)
            }
            val fileName = segments.last()
            createdFile = current.findChild(fileName) ?: current.createChildData(this@ContextCollectorPlatformTest, fileName)
            VfsUtil.saveText(createdFile!!, content)
        }
        return createdFile ?: error("Failed to create file $relativePath")
    }
}
