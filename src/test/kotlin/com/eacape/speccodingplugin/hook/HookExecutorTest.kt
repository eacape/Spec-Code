package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.core.OperationResult
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookExecutorTest {

    @Test
    fun `execute should run actions and return success log`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        val modeManager = mockk<OperationModeManager>()
        every { modeManager.checkOperation(any()) } returns OperationResult.Allowed()

        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { modeManager },
            processRunner = { _, _ -> HookActionExecutionResult(true, "command ok") },
            notificationSender = { _, _ -> },
        )

        val hook = HookDefinition(
            id = "chain",
            name = "Chain Hook",
            event = HookEvent.FILE_SAVED,
            actions = listOf(
                HookAction(
                    type = HookActionType.RUN_COMMAND,
                    command = "gradle",
                ),
                HookAction(
                    type = HookActionType.SHOW_NOTIFICATION,
                    message = "saved {{file.path}}",
                ),
            ),
        )

        val log = executor.execute(hook, HookTriggerContext(filePath = "src/A.kt"))

        assertTrue(log.success)
        assertTrue(log.message.contains("Notification sent"))
    }

    @Test
    fun `execute should stop on failed command`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        val modeManager = mockk<OperationModeManager>()
        every { modeManager.checkOperation(any()) } returns OperationResult.Allowed()

        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { modeManager },
            processRunner = { _, _ -> HookActionExecutionResult(false, "failed") },
            notificationSender = { _, _ -> },
        )

        val hook = HookDefinition(
            id = "cmd",
            name = "Command Hook",
            event = HookEvent.FILE_SAVED,
            actions = listOf(
                HookAction(
                    type = HookActionType.RUN_COMMAND,
                    command = "bad",
                ),
                HookAction(
                    type = HookActionType.SHOW_NOTIFICATION,
                    message = "never",
                ),
            ),
        )

        val log = executor.execute(hook, HookTriggerContext())
        assertFalse(log.success)
        assertTrue(log.message.contains("failed"))
    }

    @Test
    fun `execute should deny command in current operation mode`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        val modeManager = mockk<OperationModeManager>()
        every { modeManager.checkOperation(any()) } returns OperationResult.Denied("not allowed")

        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { modeManager },
            processRunner = { _, _ -> HookActionExecutionResult(true, "should not run") },
            notificationSender = { _, _ -> },
        )

        val hook = HookDefinition(
            id = "cmd",
            name = "Command Hook",
            event = HookEvent.FILE_SAVED,
            actions = listOf(
                HookAction(
                    type = HookActionType.RUN_COMMAND,
                    command = "gradle",
                )
            ),
        )

        val log = executor.execute(hook, HookTriggerContext())
        assertFalse(log.success)
        assertTrue(log.message.contains("denied"))
        verify(exactly = 1) { modeManager.checkOperation(any()) }
    }
}
