package com.eacape.speccodingplugin.ui.completion

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.engine.CliSlashCommandInfo
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptScope
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompletionProviderTest {

    private lateinit var project: Project
    private lateinit var promptManager: PromptManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        promptManager = mockk()
    }

    @Test
    fun `slash completion should return codex commands when codex provider selected`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
            cliSlashCommands = listOf(
                CliSlashCommandInfo(
                    providerId = "codex-cli",
                    command = "review",
                    description = "Run a code review non-interactively",
                ),
                CliSlashCommandInfo(
                    providerId = "claude-cli",
                    command = "add-dir",
                    description = "Additional directories to allow tool access to",
                ),
            ),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.SLASH,
                triggerOffset = 0,
                query = "rev",
            ),
            selectedProviderId = "codex-cli",
        )

        assertEquals(1, completions.size)
        assertEquals("/review", completions[0].displayText)
        assertEquals("/review", completions[0].insertText)
        assertTrue(completions[0].description.contains("Codex CLI"))
    }

    @Test
    fun `slash completion should return claude commands when claude provider selected`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
            cliSlashCommands = listOf(
                CliSlashCommandInfo(
                    providerId = "codex-cli",
                    command = "review",
                    description = "Run a code review non-interactively",
                ),
                CliSlashCommandInfo(
                    providerId = "claude-cli",
                    command = "add-dir",
                    description = "Additional directories to allow tool access to",
                ),
            ),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.SLASH,
                triggerOffset = 0,
                query = "add",
            ),
            selectedProviderId = "claude-cli",
        )

        assertEquals(1, completions.size)
        assertEquals("/add-dir", completions.first().insertText)
        assertTrue(completions.first().description.contains("Claude CLI"))
    }

    @Test
    fun `template completion should filter templates by query`() {
        every { promptManager.listPromptTemplates() } returns listOf(
            PromptTemplate(
                id = "design",
                name = "Design Plan",
                content = "design content",
                scope = PromptScope.PROJECT,
            ),
            PromptTemplate(
                id = "debug",
                name = "Debug Checklist",
                content = "debug content",
                scope = PromptScope.PROJECT,
            )
        )

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.ANGLE,
                triggerOffset = 0,
                query = "des",
            )
        )

        assertEquals(1, completions.size)
        assertEquals("Design Plan", completions[0].displayText)
        assertEquals("design content", completions[0].insertText)
        assertEquals(SpecCodingBundle.message("completion.template.description.prompt"), completions[0].description)
    }

    @Test
    fun `hash prompt completion should insert template name instead of id`() {
        every { promptManager.listPromptTemplates() } returns listOf(
            PromptTemplate(
                id = "ide",
                name = "中文IDE实现助手模板",
                content = "content 1",
                scope = PromptScope.PROJECT,
            ),
            PromptTemplate(
                id = "prompt",
                name = "老工程师",
                content = "content 2",
                scope = PromptScope.PROJECT,
            ),
        )

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "ide",
            ),
        )

        assertTrue(completions.any { item ->
            item.displayText == "#中文IDE实现助手模板" &&
                item.insertText == "#中文IDE实现助手模板" &&
                item.description.endsWith("· ide")
        })
    }

    @Test
    fun `file completion should return injected file candidates with context`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val fileCompletion = CompletionItem(
            displayText = "Main.kt",
            insertText = "@src/Main.kt",
            description = "src/Main.kt",
            contextItem = ContextItem(
                type = ContextType.REFERENCED_FILE,
                label = "Main.kt",
                content = "",
                filePath = "/workspace/src/Main.kt",
                priority = 60,
            ),
        )

        val provider = createProvider(
            fileCompletions = listOf(fileCompletion),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.AT,
                triggerOffset = 0,
                query = "main",
            )
        )

        assertEquals(1, completions.size)
        assertEquals("Main.kt", completions[0].displayText)
        assertEquals("@src/Main.kt", completions[0].insertText)
        assertEquals("src/Main.kt", completions[0].description)
        assertEquals("Main.kt", completions[0].contextItem?.label)
        assertEquals(60, completions[0].contextItem?.priority)
        assertTrue(completions[0].contextItem?.filePath?.endsWith("/src/Main.kt") == true)
    }

    @Test
    fun `file completion should return empty when provider yields none`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = emptyArray(),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.AT,
                triggerOffset = 0,
                query = "config",
            )
        )

        assertTrue(completions.isEmpty())
    }

    @Test
    fun `symbol completion should return class matches when indexing is ready`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = arrayOf("FooService", "BarService", "BazClient"),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "ser",
            )
        )

        assertEquals(2, completions.size)
        assertTrue(completions.any { it.displayText == "FooService" && it.insertText == "#FooService" })
        assertTrue(completions.any { it.displayText == "BarService" && it.insertText == "#BarService" })
    }

    @Test
    fun `symbol completion should return empty when project is in dumb mode`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = true,
            classNames = arrayOf("FooService"),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "ser",
            )
        )

        assertTrue(completions.isEmpty())
    }

    @Test
    fun `symbol completion should return empty when query is too short`() {
        every { promptManager.listPromptTemplates() } returns emptyList()

        val provider = createProvider(
            fileCompletions = emptyList(),
            isDumbMode = false,
            classNames = arrayOf("FooService"),
        )

        val completions = provider.getCompletions(
            TriggerParseResult(
                triggerType = TriggerType.HASH,
                triggerOffset = 0,
                query = "a",
            )
        )

        assertTrue(completions.isEmpty())
    }

    private fun createProvider(
        fileCompletions: List<CompletionItem>,
        isDumbMode: Boolean,
        classNames: Array<String>,
        cliSlashCommands: List<CliSlashCommandInfo> = emptyList(),
    ): CompletionProvider {
        return CompletionProvider(
            project = project,
            promptManagerProvider = { promptManager },
            fileCompletionsProvider = { fileCompletions },
            isDumbModeProvider = { isDumbMode },
            classNamesProvider = { classNames },
            cliSlashCommandsProvider = { cliSlashCommands },
        )
    }
}
