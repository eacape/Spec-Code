package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class VerifyCommandSplitOutputRuntimeContractTest {

    @Test
    fun `verify split output runtime should own start await execute lifecycle`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/spec/VerifyCommandSplitOutputRuntime.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("fun start("))
        assertTrue(source.contains("fun await("))
        assertTrue(source.contains("fun execute("))
        assertTrue(source.contains("ManagedSplitOutputProcess.start("))
        assertTrue(source.contains("handle.awaitCompletion("))
        assertFalse(source.contains("process.waitFor("))
        assertFalse(source.contains("process.inputStream.bufferedReader().use"))
        assertFalse(source.contains("process.errorStream.bufferedReader().use"))
    }
}
