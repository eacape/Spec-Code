package com.eacape.speccodingplugin.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ExternalMergedOutputCommandSpec(
    val outputLimitChars: Int,
    val threadName: String,
    val timeout: Long,
    val timeoutUnit: TimeUnit,
    val outputJoinTimeoutMillis: Long,
    val timeoutDestroyWait: Long,
    val timeoutDestroyWaitUnit: TimeUnit,
)

internal class ExternalMergedOutputCommandRuntime(
    private val handleStarter: (Process, ExternalMergedOutputCommandSpec, AtomicBoolean) -> ManagedMergedOutputProcess =
        { process, spec, stopRequested ->
            ManagedMergedOutputProcess.start(
                process = process,
                outputLimitChars = spec.outputLimitChars,
                threadName = spec.threadName,
                stopRequested = stopRequested,
            )
        },
    private val completionAwaiter: (ManagedMergedOutputProcess, ExternalMergedOutputCommandSpec) -> ManagedMergedOutputProcessCompletion =
        { handle, spec ->
            handle.awaitCompletion(
                timeout = spec.timeout,
                timeoutUnit = spec.timeoutUnit,
                joinTimeoutMillis = spec.outputJoinTimeoutMillis,
                timeoutDestroyWait = spec.timeoutDestroyWait,
                timeoutDestroyWaitUnit = spec.timeoutDestroyWaitUnit,
            )
        },
) {

    fun start(
        processStarter: () -> Process,
        spec: ExternalMergedOutputCommandSpec,
        stopRequested: AtomicBoolean = AtomicBoolean(false),
    ): Result<ManagedMergedOutputProcess> {
        return runCatching {
            handleStarter(processStarter(), spec, stopRequested)
        }
    }

    fun await(
        handle: ManagedMergedOutputProcess,
        spec: ExternalMergedOutputCommandSpec,
    ): ManagedMergedOutputProcessCompletion {
        return completionAwaiter(handle, spec)
    }

    fun execute(
        processStarter: () -> Process,
        spec: ExternalMergedOutputCommandSpec,
    ): Result<ManagedMergedOutputProcessCompletion> {
        return start(processStarter = processStarter, spec = spec)
            .mapCatching { handle -> await(handle = handle, spec = spec) }
    }
}
