package com.eacape.speccodingplugin.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ExternalProcessStartupFailureClassifierContractTest {

    @Test
    fun `shared external process classifier should be used by runtime diagnostics`() {
        val runtimeFiles = listOf(
            "src/main/kotlin/com/eacape/speccodingplugin/engine/CliCommandRuntime.kt",
            "src/main/kotlin/com/eacape/speccodingplugin/hook/HookCommandRuntime.kt",
            "src/main/kotlin/com/eacape/speccodingplugin/spec/VerifyCommandRuntime.kt",
            "src/main/kotlin/com/eacape/speccodingplugin/mcp/McpServerProcessRuntime.kt",
            "src/main/kotlin/com/eacape/speccodingplugin/core/WorkflowCommandProcessRuntime.kt",
            "src/main/kotlin/com/eacape/speccodingplugin/core/GitCliFailureDiagnostic.kt",
        )

        runtimeFiles.forEach { path ->
            val source = Files.readString(Paths.get(path), StandardCharsets.UTF_8)
            assertTrue(
                source.contains("ExternalProcessStartupFailureClassifier.classify("),
                "Expected $path to delegate startup failure classification to the shared classifier",
            )
        }
    }
}
