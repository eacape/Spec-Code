package com.eacape.speccodingplugin.hook

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class HookGitCommitWatcherProcessRuntimeContractTest {

    @Test
    fun `hook git commit watcher should delegate git process execution to shared hook runtime`() {
        val source = Files.readString(
            Paths.get("src/main/kotlin/com/eacape/speccodingplugin/hook/HookGitCommitWatcher.kt"),
            StandardCharsets.UTF_8,
        )

        assertTrue(source.contains("private val gitCommandRuntime = HookGitCommandRuntime()"))
        assertTrue(source.contains("gitCommandRuntime.execute("))
        assertFalse(source.contains("ProcessBuilder("))
        assertFalse(source.contains("redirectErrorStream(true)"))
        assertFalse(source.contains("process.waitFor("))
    }
}
