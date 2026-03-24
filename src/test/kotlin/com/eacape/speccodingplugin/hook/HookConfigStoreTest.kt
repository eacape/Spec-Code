package com.eacape.speccodingplugin.hook

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HookConfigStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.resolve("repo").toString()
    }

    @Test
    fun `save and list hooks should persist to hooks yaml`() {
        val store = HookConfigStore(project)

        val hook = HookDefinition(
            id = "auto-lint",
            name = "Auto Lint",
            event = HookEvent.FILE_SAVED,
            actions = listOf(
                HookAction(
                    type = HookActionType.RUN_COMMAND,
                    command = "gradle",
                    args = listOf("ktlintCheck"),
                )
            ),
        )
        store.saveHook(hook)

        val hooks = store.listHooks()
        assertEquals(1, hooks.size)
        assertEquals("auto-lint", hooks.first().id)

        val hooksPath = tempDir.resolve("repo").resolve(".spec-coding").resolve("hooks.yaml")
        assertTrue(Files.exists(hooksPath))
        val content = Files.readString(hooksPath)
        assertTrue(content.contains("auto-lint"))
    }

    @Test
    fun `setHookEnabled and deleteHook should update storage`() {
        val store = HookConfigStore(project)
        store.saveHook(
            HookDefinition(
                id = "notify",
                name = "Notify",
                event = HookEvent.FILE_SAVED,
                actions = listOf(
                    HookAction(
                        type = HookActionType.SHOW_NOTIFICATION,
                        message = "saved",
                    )
                ),
            )
        )

        val disabled = store.setHookEnabled("notify", enabled = false)
        assertTrue(disabled)
        assertFalse(store.getHookById("notify")!!.enabled)

        val deleted = store.deleteHook("notify")
        assertTrue(deleted)
        assertTrue(store.listHooks().isEmpty())
    }

    @Test
    fun `constructor should load existing hooks yaml`() {
        val hooksDir = tempDir.resolve("repo").resolve(".spec-coding")
        Files.createDirectories(hooksDir)
        val hooksFile = hooksDir.resolve("hooks.yaml")
        Files.writeString(
            hooksFile,
            """
                hooks:
                  - id: existing
                    name: Existing Hook
                    event: FILE_SAVED
                    actions:
                      - type: SHOW_NOTIFICATION
                        message: ok
            """.trimIndent()
        )

        val store = HookConfigStore(project)
        val loaded = store.listHooks()

        assertEquals(1, loaded.size)
        assertEquals("existing", loaded.first().id)
        assertNotNull(store.getHookById("existing"))
    }

    @Test
    fun `listHooks should reload after hooks yaml is externally updated`() {
        val hooksDir = tempDir.resolve("repo").resolve(".spec-coding")
        Files.createDirectories(hooksDir)
        val hooksFile = hooksDir.resolve("hooks.yaml")
        Files.writeString(
            hooksFile,
            """
                version: 1
                hooks:
                  - id: first
                    name: First Hook
                    event: FILE_SAVED
                    actions:
                      - type: SHOW_NOTIFICATION
                        message: first
            """.trimIndent()
        )

        val store = HookConfigStore(project)
        assertEquals(listOf("first"), store.listHooks().map { it.id })

        Files.writeString(
            hooksFile,
            """
                version: 1
                hooks:
                  - id: second
                    name: Second Hook
                    event: FILE_SAVED
                    actions:
                      - type: SHOW_NOTIFICATION
                        message: second
                  - id: notify
                    name: Notify Hook
                    event: GIT_COMMIT
                    actions:
                      - type: SHOW_NOTIFICATION
                        message: notify
            """.trimIndent()
        )

        val reloaded = store.listHooks()
        assertEquals(2, reloaded.size)
        assertEquals(setOf("second", "notify"), reloaded.map { it.id }.toSet())
        assertNotNull(store.getHookById("second"))
        assertNotNull(store.getHookById("notify"))
    }
}
