package com.eacape.speccodingplugin.core

import java.io.File

internal data class ExternalProcessLaunchSpec(
    val command: List<String>,
    val workingDirectory: File? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val redirectErrorStream: Boolean = true,
)

internal object ExternalProcessLauncher {

    fun build(spec: ExternalProcessLaunchSpec): ProcessBuilder {
        return ProcessBuilder(spec.command).apply {
            spec.workingDirectory?.let(::directory)
            if (spec.environmentOverrides.isNotEmpty()) {
                environment().putAll(spec.environmentOverrides)
            }
            redirectErrorStream(spec.redirectErrorStream)
        }
    }

    fun start(spec: ExternalProcessLaunchSpec): Process = build(spec).start()
}
