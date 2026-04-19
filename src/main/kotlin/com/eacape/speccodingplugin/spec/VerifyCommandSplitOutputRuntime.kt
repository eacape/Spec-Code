package com.eacape.speccodingplugin.spec

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class VerifyCommandSplitOutputSpec(
    val outputLimitChars: Int,
    val stdoutThreadName: String,
    val stderrThreadName: String,
    val timeout: Long,
    val timeoutUnit: TimeUnit,
    val outputJoinTimeoutMillis: Long,
    val timeoutDestroyGraceWait: Long,
    val timeoutDestroyGraceWaitUnit: TimeUnit,
    val timeoutDestroyForceWait: Long,
    val timeoutDestroyForceWaitUnit: TimeUnit,
)

internal class VerifyCommandSplitOutputRuntime(
    private val handleStarter: (Process, VerifyCommandSplitOutputSpec, AtomicBoolean) -> ManagedSplitOutputProcess =
        { process, spec, stopRequested ->
            ManagedSplitOutputProcess.start(
                process = process,
                outputLimitChars = spec.outputLimitChars,
                stdoutThreadName = spec.stdoutThreadName,
                stderrThreadName = spec.stderrThreadName,
                stopRequested = stopRequested,
            )
        },
    private val completionAwaiter: (ManagedSplitOutputProcess, VerifyCommandSplitOutputSpec) -> ManagedSplitOutputProcessCompletion =
        { handle, spec ->
            handle.awaitCompletion(
                timeout = spec.timeout,
                timeoutUnit = spec.timeoutUnit,
                joinTimeoutMillis = spec.outputJoinTimeoutMillis,
                timeoutDestroyGraceWait = spec.timeoutDestroyGraceWait,
                timeoutDestroyGraceWaitUnit = spec.timeoutDestroyGraceWaitUnit,
                timeoutDestroyForceWait = spec.timeoutDestroyForceWait,
                timeoutDestroyForceWaitUnit = spec.timeoutDestroyForceWaitUnit,
            )
        },
) {

    fun start(
        processStarter: () -> Process,
        spec: VerifyCommandSplitOutputSpec,
        stopRequested: AtomicBoolean = AtomicBoolean(false),
    ): Result<ManagedSplitOutputProcess> {
        return runCatching {
            handleStarter(processStarter(), spec, stopRequested)
        }
    }

    fun await(
        handle: ManagedSplitOutputProcess,
        spec: VerifyCommandSplitOutputSpec,
    ): ManagedSplitOutputProcessCompletion {
        return completionAwaiter(handle, spec)
    }

    fun execute(
        processStarter: () -> Process,
        spec: VerifyCommandSplitOutputSpec,
    ): Result<ManagedSplitOutputProcessCompletion> {
        return start(processStarter = processStarter, spec = spec)
            .mapCatching { handle -> await(handle = handle, spec = spec) }
    }
}
