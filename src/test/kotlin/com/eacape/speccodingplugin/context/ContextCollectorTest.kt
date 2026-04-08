package com.eacape.speccodingplugin.context

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.ArrayDeque

class ContextCollectorTest {

    private lateinit var project: Project
    private lateinit var collector: ContextCollector

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        collector = ContextCollector(project)
        mockkObject(EditorContextProvider)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(EditorContextProvider)
    }

    @Test
    fun `collectContext should include enabled editor context items`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns makeItem(
            type = ContextType.SELECTED_CODE,
            label = "selection",
            priority = 90,
        )
        every { EditorContextProvider.getContainingScopeContext(project) } returns makeItem(
            type = ContextType.CONTAINING_SCOPE,
            label = "scope",
            priority = 80,
        )
        every { EditorContextProvider.getCurrentFileContext(project) } returns makeItem(
            type = ContextType.CURRENT_FILE,
            label = "file",
            priority = 70,
        )

        val snapshot = collector.collectContext(
            ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = true,
                includeContainingScope = true,
                includeCurrentFile = true,
            )
        )

        assertEquals(3, snapshot.items.size)
        assertEquals(listOf("selection", "scope", "file"), snapshot.items.map { it.label })
        assertEquals(75, snapshot.totalTokenEstimate)
        assertTrue(!snapshot.wasTrimmed)
    }

    @Test
    fun `collectContext should skip disabled providers`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns makeItem(
            type = ContextType.SELECTED_CODE,
            label = "selection",
            priority = 90,
        )
        every { EditorContextProvider.getContainingScopeContext(project) } returns makeItem(
            type = ContextType.CONTAINING_SCOPE,
            label = "scope",
            priority = 80,
        )
        every { EditorContextProvider.getCurrentFileContext(project) } returns makeItem(
            type = ContextType.CURRENT_FILE,
            label = "file",
            priority = 70,
        )

        val snapshot = collector.collectContext(
            ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = true,
            )
        )

        assertEquals(1, snapshot.items.size)
        assertEquals("file", snapshot.items.first().label)

        verify(exactly = 0) { EditorContextProvider.getSelectedCodeContext(project) }
        verify(exactly = 0) { EditorContextProvider.getContainingScopeContext(project) }
        verify(exactly = 1) { EditorContextProvider.getCurrentFileContext(project) }
    }

    @Test
    fun `collectForItems should keep explicit item when duplicated with auto context`() {
        val explicit = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "ExplicitMain.kt",
            content = "explicit-content",
            filePath = "/src/Main.kt",
            priority = 95,
            tokenEstimate = 5,
        )

        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "AutoMain.kt",
            content = "auto-content",
            filePath = "/src/Main.kt",
            priority = 70,
            tokenEstimate = 5,
        )

        val snapshot = collector.collectForItems(
            explicitItems = listOf(explicit),
            config = ContextConfig(
                tokenBudget = 100,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = true,
            )
        )

        assertEquals(1, snapshot.items.size)
        assertEquals("ExplicitMain.kt", snapshot.items.first().label)
        assertEquals("explicit-content", snapshot.items.first().content)
    }

    @Test
    fun `collectForItems should prefer graph related item when graph trimming enabled`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        val collector = ContextCollector(
            project = project,
            codeGraphSnapshotProvider = { Result.success(codeGraphSnapshot()) },
        )
        val unrelated = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Other.kt",
            content = "other",
            filePath = "/repo/src/Other.kt",
            priority = 70,
            tokenEstimate = 50,
        )
        val related = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Service.kt",
            content = "service",
            filePath = "/repo/src/Service.kt",
            priority = 10,
            tokenEstimate = 50,
        )

        val snapshot = collector.collectForItems(
            explicitItems = listOf(unrelated, related),
            config = ContextConfig(
                tokenBudget = 50,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                preferGraphRelatedContext = true,
            ),
        )

        assertEquals(1, snapshot.items.size)
        assertEquals("Service.kt", snapshot.items.first().label)
    }

    @Test
    fun `collectForItems should keep priority order when graph trimming disabled`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        val collector = ContextCollector(
            project = project,
            codeGraphSnapshotProvider = { Result.success(codeGraphSnapshot()) },
        )
        val unrelated = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Other.kt",
            content = "other",
            filePath = "/repo/src/Other.kt",
            priority = 70,
            tokenEstimate = 50,
        )
        val related = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Service.kt",
            content = "service",
            filePath = "/repo/src/Service.kt",
            priority = 10,
            tokenEstimate = 50,
        )

        val snapshot = collector.collectForItems(
            explicitItems = listOf(unrelated, related),
            config = ContextConfig(
                tokenBudget = 50,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                preferGraphRelatedContext = false,
            ),
        )

        assertEquals(1, snapshot.items.size)
        assertEquals("Other.kt", snapshot.items.first().label)
    }

    @Test
    fun `collectForItems should enforce layered file symbol and byte budgets`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        val collector = ContextCollector(project)
        val oversizedFile = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Oversized.kt",
            content = "x".repeat(96),
            filePath = "/repo/src/Oversized.kt",
            priority = 100,
            tokenEstimate = 24,
        )
        val acceptedFile = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Accepted.kt",
            content = "x".repeat(24),
            filePath = "/repo/src/Accepted.kt",
            priority = 90,
            tokenEstimate = 6,
        )
        val acceptedSymbol = ContextItem(
            type = ContextType.REFERENCED_SYMBOL,
            label = "run",
            content = "x".repeat(20),
            priority = 80,
            tokenEstimate = 5,
        )
        val skippedSymbol = ContextItem(
            type = ContextType.REFERENCED_SYMBOL,
            label = "render",
            content = "x".repeat(12),
            priority = 70,
            tokenEstimate = 3,
        )

        val snapshot = collector.collectForItems(
            explicitItems = listOf(oversizedFile, acceptedFile, acceptedSymbol, skippedSymbol),
            config = ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                preferGraphRelatedContext = false,
                maxFileItems = 1,
                maxSymbolItems = 1,
                maxContentBytes = 80,
                maxCollectionTimeMs = 1_000,
            ),
        )

        assertEquals(listOf("Accepted.kt", "run"), snapshot.items.map { it.label })
        assertEquals(11, snapshot.totalTokenEstimate)
    }

    @Test
    fun `collectForItems should skip project structure when explicit files already exhaust file budget`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        var projectStructureCalls = 0
        val collector = ContextCollector(
            project = project,
            codeGraphSnapshotProvider = { Result.success(codeGraphSnapshot()) },
            projectStructureProvider = {
                projectStructureCalls += 1
                Result.success(
                    makeItem(
                        type = ContextType.PROJECT_STRUCTURE,
                        label = "Project Structure",
                        priority = 30,
                        filePath = "/repo",
                    ),
                )
            },
        )
        val explicitFile = ContextItem(
            type = ContextType.REFERENCED_FILE,
            label = "Explicit.kt",
            content = "x".repeat(24),
            filePath = "/repo/src/Explicit.kt",
            priority = 95,
            tokenEstimate = 6,
        )

        val snapshot = collector.collectForItems(
            explicitItems = listOf(explicitFile),
            config = ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                includeProjectStructure = true,
                preferGraphRelatedContext = false,
                maxFileItems = 1,
                maxCollectionTimeMs = 1_000,
            ),
        )

        assertEquals(0, projectStructureCalls)
        assertEquals(listOf("Explicit.kt"), snapshot.items.map { it.label })
    }

    @Test
    fun `collectContext should skip optional providers when time budget is already exhausted`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        var relatedFilesCalls = 0
        var projectStructureCalls = 0
        val collector = ContextCollector(
            project = project,
            relatedFilesProvider = {
                relatedFilesCalls += 1
                Result.success(
                    listOf(
                        makeItem(
                            type = ContextType.IMPORT_DEPENDENCY,
                            label = "Dependency.kt",
                            priority = 40,
                            filePath = "/repo/src/Dependency.kt",
                        ),
                    ),
                )
            },
            projectStructureProvider = {
                projectStructureCalls += 1
                Result.success(
                    makeItem(
                        type = ContextType.PROJECT_STRUCTURE,
                        label = "Project Structure",
                        priority = 30,
                        filePath = "/repo",
                    ),
                )
            },
            nanoTimeProvider = sequenceNanoTimeProvider(
                0L,
                300_000_000L,
                300_000_000L,
                300_000_000L,
            ),
        )

        val snapshot = collector.collectContext(
            ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                includeImportDependencies = true,
                includeProjectStructure = true,
                preferGraphRelatedContext = false,
                maxCollectionTimeMs = 200,
            ),
        )

        assertTrue(snapshot.items.isEmpty())
        assertEquals(0, relatedFilesCalls)
        assertEquals(0, projectStructureCalls)
    }

    @Test
    fun `collectContext should not build graph when there are no graph eligible items`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns makeItem(
            type = ContextType.SELECTED_CODE,
            label = "selection",
            priority = 90,
        )
        every { EditorContextProvider.getContainingScopeContext(project) } returns makeItem(
            type = ContextType.CONTAINING_SCOPE,
            label = "scope",
            priority = 80,
        )
        every { EditorContextProvider.getCurrentFileContext(project) } returns makeItem(
            type = ContextType.CURRENT_FILE,
            label = "file",
            priority = 70,
        )

        var graphBuildCalls = 0
        val collector = ContextCollector(
            project = project,
            codeGraphSnapshotProvider = {
                graphBuildCalls += 1
                Result.success(codeGraphSnapshot())
            },
        )

        val snapshot = collector.collectContext(
            ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = true,
                includeContainingScope = true,
                includeCurrentFile = true,
                preferGraphRelatedContext = true,
            ),
        )

        assertEquals(0, graphBuildCalls)
        assertEquals(listOf("selection", "scope", "file"), snapshot.items.map { it.label })
    }

    @Test
    fun `collectContext should include enabled stage cache stats in telemetry`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        var capturedTelemetry: ContextCollectionTelemetry? = null
        val collector = ContextCollector(
            project = project,
            relatedFilesProvider = { Result.success(emptyList()) },
            projectStructureProvider = { Result.success(null) },
            codeGraphCacheStatsProvider = {
                CodeGraphCacheStats(
                    hitCount = 2,
                    missCount = 1,
                    lastInvalidationReason = "psi-change:Main.kt",
                )
            },
            relatedFileCacheStatsProvider = {
                RelatedFileCacheStats(
                    hitCount = 3,
                    missCount = 1,
                    lastInvalidationReason = "document-change:main.ts",
                )
            },
            projectStructureCacheStatsProvider = {
                ProjectStructureCacheStats(
                    hitCount = 4,
                    missCount = 2,
                    lastInvalidationReason = "vfs-create:docs/guide.md",
                )
            },
            telemetryConsumer = { telemetry -> capturedTelemetry = telemetry },
        )

        collector.collectContext(
            ContextConfig(
                tokenBudget = 200,
                includeSelectedCode = false,
                includeContainingScope = false,
                includeCurrentFile = false,
                includeImportDependencies = true,
                includeProjectStructure = true,
                preferGraphRelatedContext = true,
            ),
        )

        val telemetry = capturedTelemetry ?: error("Expected telemetry to be captured")
        val summary = telemetry.summary()

        assertTrue(summary.contains("cacheView=codeGraph{cacheHits=2"))
        assertTrue(summary.contains("relatedFiles{cacheHits=3"))
        assertTrue(summary.contains("projectStructure{cacheHits=4"))
    }

    @Test
    fun `collectContext should compare warm run against previous cold run in telemetry`() {
        every { EditorContextProvider.getSelectedCodeContext(project) } returns null
        every { EditorContextProvider.getContainingScopeContext(project) } returns null
        every { EditorContextProvider.getCurrentFileContext(project) } returns null

        val relatedFileStats = ArrayDeque(
            listOf(
                RelatedFileCacheStats(
                    hitCount = 0,
                    missCount = 0,
                    lastInvalidationReason = "cold-start",
                ),
                RelatedFileCacheStats(
                    hitCount = 0,
                    missCount = 1,
                    lastInvalidationReason = "document-change:main.ts",
                ),
                RelatedFileCacheStats(
                    hitCount = 0,
                    missCount = 1,
                    lastInvalidationReason = "document-change:main.ts",
                ),
                RelatedFileCacheStats(
                    hitCount = 1,
                    missCount = 1,
                    lastInvalidationReason = "document-change:main.ts",
                ),
            ),
        )
        val capturedTelemetries = mutableListOf<ContextCollectionTelemetry>()
        val collector = ContextCollector(
            project = project,
            relatedFilesProvider = { Result.success(emptyList()) },
            relatedFileCacheStatsProvider = { relatedFileStats.removeFirst() },
            nanoTimeProvider = sequenceNanoTimeProvider(
                0L,
                10_000_000L,
                320_000_000L,
                1_000_000_000L,
                1_010_000_000L,
                1_090_000_000L,
            ),
            telemetryConsumer = capturedTelemetries::add,
        )

        repeat(2) {
            collector.collectContext(
                ContextConfig(
                    tokenBudget = 200,
                    includeSelectedCode = false,
                    includeContainingScope = false,
                    includeCurrentFile = false,
                    includeImportDependencies = true,
                    includeProjectStructure = false,
                    preferGraphRelatedContext = false,
                    maxCollectionTimeMs = 500,
                ),
            )
        }

        val coldRun = capturedTelemetries.first()
        val warmRun = capturedTelemetries.last()

        assertEquals(listOf("relatedFiles:miss"), coldRun.cacheView?.runOutcomes?.map { it.summary() })
        assertEquals("cold", coldRun.baselineComparison?.phase)
        assertEquals(listOf("relatedFiles:hit"), warmRun.cacheView?.runOutcomes?.map { it.summary() })
        assertEquals("warm", warmRun.baselineComparison?.phase)
        assertEquals(coldRun.elapsedMs, warmRun.baselineComparison?.coldElapsedMs)
        assertEquals(warmRun.elapsedMs, warmRun.baselineComparison?.warmElapsedMs)
        assertTrue((warmRun.baselineComparison?.savedPercent ?: 0) > 0)
    }

    private fun makeItem(
        type: ContextType,
        label: String,
        priority: Int,
        tokenEstimate: Int = 25,
        filePath: String = "/tmp/$label",
    ): ContextItem {
        return ContextItem(
            type = type,
            label = label,
            content = "x".repeat(tokenEstimate * 4),
            filePath = filePath,
            priority = priority,
            tokenEstimate = tokenEstimate,
        )
    }

    private fun sequenceNanoTimeProvider(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        val lastValue = values.lastOrNull() ?: 0L
        return {
            if (queue.isEmpty()) {
                lastValue
            } else {
                queue.removeFirst()
            }
        }
    }

    private fun codeGraphSnapshot(): CodeGraphSnapshot {
        return CodeGraphSnapshot(
            generatedAt = 1L,
            rootFilePath = "/repo/src/Main.kt",
            rootFileName = "Main.kt",
            nodes = listOf(
                CodeGraphNode("file:/repo/src/Main.kt", "Main.kt", CodeGraphNodeType.FILE),
                CodeGraphNode("file:/repo/src/Service.kt", "Service.kt", CodeGraphNodeType.FILE),
                CodeGraphNode("symbol:main@10", "main", CodeGraphNodeType.SYMBOL),
                CodeGraphNode("symbol:run@20", "run", CodeGraphNodeType.SYMBOL),
            ),
            edges = listOf(
                CodeGraphEdge(
                    fromId = "file:/repo/src/Main.kt",
                    toId = "file:/repo/src/Service.kt",
                    type = CodeGraphEdgeType.DEPENDS_ON,
                ),
                CodeGraphEdge(
                    fromId = "symbol:main@10",
                    toId = "symbol:run@20",
                    type = CodeGraphEdgeType.CALLS,
                ),
            ),
        )
    }
}
