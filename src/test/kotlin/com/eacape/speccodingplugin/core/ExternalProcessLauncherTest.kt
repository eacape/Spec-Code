package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ExternalProcessLauncherTest {

    @Test
    fun `build should apply working directory redirect flag and env overrides`() {
        val builder = ExternalProcessLauncher.build(
            ExternalProcessLaunchSpec(
                command = listOf("demo", "--stdio"),
                workingDirectory = File("D:/repo"),
                environmentOverrides = mapOf("DEMO_TOKEN" to "secret"),
                redirectErrorStream = false,
            ),
        )

        assertEquals(listOf("demo", "--stdio"), builder.command())
        assertEquals(File("D:/repo"), builder.directory())
        assertFalse(builder.redirectErrorStream())
        assertEquals("secret", builder.environment()["DEMO_TOKEN"])
    }

    @Test
    fun `build should leave directory unset and default to merged stderr when omitted`() {
        val builder = ExternalProcessLauncher.build(
            ExternalProcessLaunchSpec(
                command = listOf("git", "status"),
            ),
        )

        assertEquals(listOf("git", "status"), builder.command())
        assertEquals(null, builder.directory())
        assertTrue(builder.redirectErrorStream())
    }
}
