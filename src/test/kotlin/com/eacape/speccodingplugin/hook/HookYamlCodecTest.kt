package com.eacape.speccodingplugin.hook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookYamlCodecTest {

    @Test
    fun `deserialize should parse valid hook yaml`() {
        val yaml = """
            version: 1
            hooks:
              - id: auto-lint
                name: Auto Lint
                event: FILE_SAVED
                enabled: true
                conditions:
                  filePattern: "**/*.kt"
                actions:
                  - type: RUN_COMMAND
                    command: gradle
                    args: ["ktlintFormat", "{{file.path}}"]
                    timeoutMillis: 120000
                  - type: SHOW_NOTIFICATION
                    message: "lint done: {{file.path}}"
                    level: INFO
        """.trimIndent()

        val hooks = HookYamlCodec.deserialize(yaml)
        assertEquals(1, hooks.size)

        val hook = hooks.first()
        assertEquals("auto-lint", hook.id)
        assertEquals(HookEvent.FILE_SAVED, hook.event)
        assertTrue(hook.enabled)
        assertEquals("**/*.kt", hook.conditions.filePattern)
        assertEquals(2, hook.actions.size)
        assertEquals(HookActionType.RUN_COMMAND, hook.actions[0].type)
        assertEquals(120_000L, hook.actions[0].timeoutMillis)
    }

    @Test
    fun `deserialize should skip invalid hook and invalid action`() {
        val yaml = """
            hooks:
              - id: ""
                name: Missing Id
                event: FILE_SAVED
                actions:
                  - type: SHOW_NOTIFICATION
                    message: "ok"
              - id: invalid-action
                name: Invalid Action
                event: FILE_SAVED
                actions:
                  - type: UNKNOWN
                    message: "will be dropped"
              - id: valid
                name: Valid Hook
                event: FILE_SAVED
                actions:
                  - type: SHOW_NOTIFICATION
                    message: "ok"
        """.trimIndent()

        val hooks = HookYamlCodec.deserialize(yaml)
        assertEquals(1, hooks.size)
        assertEquals("valid", hooks.first().id)
    }

    @Test
    fun `serialize should round trip basic fields`() {
        val origin = listOf(
            HookDefinition(
                id = "notify",
                name = "Notify",
                event = HookEvent.FILE_SAVED,
                enabled = false,
                conditions = HookConditions(filePattern = "**/*.md", specStage = "design"),
                actions = listOf(
                    HookAction(
                        type = HookActionType.SHOW_NOTIFICATION,
                        message = "changed",
                        level = HookNotificationLevel.WARNING,
                    )
                ),
            )
        )

        val yaml = HookYamlCodec.serialize(origin)
        val decoded = HookYamlCodec.deserialize(yaml)

        assertEquals(1, decoded.size)
        assertEquals("notify", decoded.first().id)
        assertFalse(decoded.first().enabled)
        assertEquals("**/*.md", decoded.first().conditions.filePattern)
        assertEquals(HookNotificationLevel.WARNING, decoded.first().actions.first().level)
    }
}
