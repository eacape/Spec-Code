package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.core.OperationResult
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HookLintOnSaveAcceptanceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `file saved lint hook should be triggered successfully from hooks yaml`() {
        val projectRoot = tempDir.resolve("repo")
        val project = fakeProject(projectRoot)
        writeHooksYaml(
            projectRoot,
            """
            version: 1
            hooks:
              - id: auto-lint-on-save
                name: Auto Lint On Save
                event: FILE_SAVED
                enabled: true
                conditions:
                  filePattern: "**/*.kt"
                actions:
                  - type: RUN_COMMAND
                    command: lint-tool
                    args: ["--file", "{{file.path}}"]
                  - type: SHOW_NOTIFICATION
                    message: "Lint passed: {{file.path}}"
                    level: INFO
            """.trimIndent()
        )

        val hookStore = HookConfigStore(project)
        val modeManager = mockk<OperationModeManager>()
        every { modeManager.checkOperation(any()) } returns OperationResult.Allowed()

        val lintCalls = mutableListOf<String>()
        val notifications = mutableListOf<String>()
        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { modeManager },
            processRunner = { action, context ->
                val renderedCommand = HookExecutor.renderTemplate(action.command.orEmpty(), context)
                val renderedArgs = action.args.map { HookExecutor.renderTemplate(it, context) }
                lintCalls += (listOf(renderedCommand) + renderedArgs).joinToString(" ")
                HookActionExecutionResult(success = true, message = "lint ok")
            },
            notificationSender = { _, message -> notifications += message },
        )

        val manager = HookManager(
            project = project,
            configStoreProvider = { hookStore },
            executorProvider = { executor },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        manager.trigger(
            event = HookEvent.FILE_SAVED,
            triggerContext = HookTriggerContext(filePath = "src/main/kotlin/App.kt"),
        )

        val logs = manager.getExecutionLogs()
        assertEquals(1, logs.size)
        assertTrue(logs.first().success)
        assertTrue(lintCalls.any { it.contains("lint-tool --file src/main/kotlin/App.kt") })
        assertTrue(notifications.any { it.contains("Lint passed: src/main/kotlin/App.kt") })
    }

    @Test
    fun `file saved lint hook should not run when file pattern does not match`() {
        val projectRoot = tempDir.resolve("repo-non-match")
        val project = fakeProject(projectRoot)
        writeHooksYaml(
            projectRoot,
            """
            version: 1
            hooks:
              - id: auto-lint-on-save
                name: Auto Lint On Save
                event: FILE_SAVED
                enabled: true
                conditions:
                  filePattern: "**/*.kt"
                actions:
                  - type: RUN_COMMAND
                    command: lint-tool
                    args: ["--file", "{{file.path}}"]
            """.trimIndent()
        )

        val hookStore = HookConfigStore(project)
        val modeManager = mockk<OperationModeManager>()
        every { modeManager.checkOperation(any()) } returns OperationResult.Allowed()

        val lintCalls = mutableListOf<String>()
        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { modeManager },
            processRunner = { action, context ->
                lintCalls += "${action.command} ${context.filePath}"
                HookActionExecutionResult(success = true, message = "lint ok")
            },
            notificationSender = { _, _ -> },
        )

        val manager = HookManager(
            project = project,
            configStoreProvider = { hookStore },
            executorProvider = { executor },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        manager.trigger(
            event = HookEvent.FILE_SAVED,
            triggerContext = HookTriggerContext(filePath = "README.md"),
        )

        assertTrue(lintCalls.isEmpty())
        assertTrue(manager.getExecutionLogs().isEmpty())
    }

    private fun fakeProject(projectRoot: Path): Project {
        return mockk<Project>(relaxed = true).also { project ->
            every { project.basePath } returns projectRoot.toString()
        }
    }

    private fun writeHooksYaml(projectRoot: Path, content: String) {
        val hooksDir = projectRoot.resolve(".spec-coding")
        Files.createDirectories(hooksDir)
        Files.writeString(hooksDir.resolve("hooks.yaml"), content)
    }
}
