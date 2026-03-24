package com.eacape.speccodingplugin.hook

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HookManagerTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
    }

    @Test
    fun `matchHooks should filter by event enabled and filePattern`() {
        val store = mockk<HookConfigStore>()
        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { mockk(relaxed = true) },
            processRunner = { _, _ -> HookActionExecutionResult(true, "ok") },
            notificationSender = { _, _ -> },
        )

        every { store.listHooks() } returns listOf(
            HookDefinition(
                id = "kt-hook",
                name = "Kotlin Hook",
                event = HookEvent.FILE_SAVED,
                enabled = true,
                conditions = HookConditions(filePattern = "**/*.kt"),
                actions = listOf(
                    HookAction(
                        type = HookActionType.SHOW_NOTIFICATION,
                        message = "kt",
                    )
                ),
            ),
            HookDefinition(
                id = "md-hook",
                name = "Markdown Hook",
                event = HookEvent.FILE_SAVED,
                enabled = true,
                conditions = HookConditions(filePattern = "**/*.md"),
                actions = listOf(
                    HookAction(
                        type = HookActionType.SHOW_NOTIFICATION,
                        message = "md",
                    )
                ),
            ),
            HookDefinition(
                id = "disabled",
                name = "Disabled Hook",
                event = HookEvent.FILE_SAVED,
                enabled = false,
                actions = listOf(
                    HookAction(
                        type = HookActionType.SHOW_NOTIFICATION,
                        message = "disabled",
                    )
                ),
            ),
            HookDefinition(
                id = "commit",
                name = "Commit Hook",
                event = HookEvent.GIT_COMMIT,
                enabled = true,
                actions = listOf(
                    HookAction(
                        type = HookActionType.SHOW_NOTIFICATION,
                        message = "commit",
                    )
                ),
            ),
        )

        val manager = HookManager(
            project = project,
            configStoreProvider = { store },
            executorProvider = { executor },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )

        val matched = manager.matchHooks(
            event = HookEvent.FILE_SAVED,
            triggerContext = HookTriggerContext(filePath = "src/main/kotlin/App.kt"),
        )

        assertEquals(1, matched.size)
        assertEquals("kt-hook", matched.first().id)
    }

    @Test
    fun `trigger should execute matched hooks and append logs`() = runBlocking {
        val store = mockk<HookConfigStore>()

        val hook = HookDefinition(
            id = "notify",
            name = "Notify",
            event = HookEvent.FILE_SAVED,
            actions = listOf(
                HookAction(
                    type = HookActionType.SHOW_NOTIFICATION,
                    message = "ok",
                )
            ),
        )

        every { store.listHooks() } returns listOf(hook)

        val executor = HookExecutor(
            project = project,
            modeManagerProvider = { mockk(relaxed = true) },
            processRunner = { _, _ -> HookActionExecutionResult(true, "ok") },
            notificationSender = { _, _ -> }
        )

        val manager = HookManager(
            project = project,
            configStoreProvider = { store },
            executorProvider = { executor },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
        )

        manager.trigger(HookEvent.FILE_SAVED, HookTriggerContext(filePath = "a.kt"))

        assertTrue(manager.getExecutionLogs().any { it.hookId == "notify" })
    }
}
