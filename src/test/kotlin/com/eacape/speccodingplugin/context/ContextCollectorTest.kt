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

        val collector = ContextCollector(project) { Result.success(codeGraphSnapshot()) }
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

        val collector = ContextCollector(project) { Result.success(codeGraphSnapshot()) }
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

    private fun makeItem(
        type: ContextType,
        label: String,
        priority: Int,
        tokenEstimate: Int = 25,
    ): ContextItem {
        return ContextItem(
            type = type,
            label = label,
            content = "x".repeat(tokenEstimate * 4),
            filePath = "/tmp/$label",
            priority = priority,
            tokenEstimate = tokenEstimate,
        )
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
