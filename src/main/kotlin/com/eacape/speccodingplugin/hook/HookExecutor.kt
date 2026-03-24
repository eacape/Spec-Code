package com.eacape.speccodingplugin.hook

import com.eacape.speccodingplugin.core.Operation
import com.eacape.speccodingplugin.core.OperationModeManager
import com.eacape.speccodingplugin.core.OperationRequest
import com.eacape.speccodingplugin.core.OperationResult
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class HookExecutor internal constructor(
    private val project: Project,
    private val modeManagerProvider: () -> OperationModeManager,
    private val processRunner: suspend (HookAction, HookTriggerContext) -> HookActionExecutionResult,
    private val notificationSender: (HookAction, String) -> Unit,
) {
    constructor(project: Project) : this(
        project = project,
        modeManagerProvider = { OperationModeManager.getInstance(project) },
        processRunner = { action, context -> runCommand(action, context, project) },
        notificationSender = { action, message -> showNotification(project, action, message) },
    )

    suspend fun execute(
        hook: HookDefinition,
        triggerContext: HookTriggerContext,
    ): HookExecutionLog {
        var lastMessage = "Hook executed successfully"

        for (action in hook.actions) {
            val actionResult = runAction(action, triggerContext)
            if (!actionResult.success) {
                return HookExecutionLog(
                    hookId = hook.id,
                    hookName = hook.name,
                    event = hook.event,
                    success = false,
                    message = actionResult.message,
                    timestamp = System.currentTimeMillis(),
                )
            }
            lastMessage = actionResult.message
        }

        return HookExecutionLog(
            hookId = hook.id,
            hookName = hook.name,
            event = hook.event,
            success = true,
            message = lastMessage,
            timestamp = System.currentTimeMillis(),
        )
    }

    private suspend fun runAction(
        action: HookAction,
        triggerContext: HookTriggerContext,
    ): HookActionExecutionResult {
        return when (action.type) {
            HookActionType.RUN_COMMAND -> {
                val command = action.command ?: return HookActionExecutionResult(
                    success = false,
                    message = "RUN_COMMAND action missing command",
                )

                val gateResult = modeManagerProvider().checkOperation(
                    OperationRequest(
                        operation = Operation.EXECUTE_COMMAND,
                        description = "Run hook command",
                        details = mapOf("command" to command),
                    )
                )
                when (gateResult) {
                    is OperationResult.Allowed -> processRunner(action, triggerContext)
                    is OperationResult.RequiresConfirmation -> HookActionExecutionResult(
                        success = false,
                        message = "Hook command requires confirmation in current mode: $command",
                    )

                    is OperationResult.Denied -> HookActionExecutionResult(
                        success = false,
                        message = "Hook command denied by operation mode: ${gateResult.reason}",
                    )
                }
            }
            HookActionType.SHOW_NOTIFICATION -> {
                val rawMessage = action.message ?: return HookActionExecutionResult(
                    success = false,
                    message = "SHOW_NOTIFICATION action missing message",
                )
                val message = renderTemplate(rawMessage, triggerContext)
                notificationSender(action, message)
                HookActionExecutionResult(
                    success = true,
                    message = "Notification sent: $message",
                )
            }
        }
    }

    internal companion object {
        fun getInstance(project: Project): HookExecutor = project.service()

        suspend fun runCommand(
            action: HookAction,
            triggerContext: HookTriggerContext,
            project: Project,
        ): HookActionExecutionResult = withContext(Dispatchers.IO) {
            val commandText = action.command ?: return@withContext HookActionExecutionResult(
                success = false,
                message = "RUN_COMMAND action missing command",
            )

            try {
                val renderedCommand = renderTemplate(commandText, triggerContext)
                val renderedArgs = action.args.map { renderTemplate(it, triggerContext) }
                val process = ProcessBuilder(listOf(renderedCommand) + renderedArgs)
                    .directory(project.basePath?.let(::File))
                    .redirectErrorStream(true)
                    .start()

                val outputBuffer = StringBuilder()
                val outputReaderThread = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (outputBuffer.length < MAX_COMMAND_OUTPUT_CHARS) {
                                if (outputBuffer.isNotEmpty()) {
                                    outputBuffer.append('\n')
                                }
                                outputBuffer.append(line)
                            }
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                val completed = process.waitFor(action.timeoutMillis, TimeUnit.MILLISECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    outputReaderThread.join(2_000)
                    return@withContext HookActionExecutionResult(
                        success = false,
                        message = "Command timed out after ${action.timeoutMillis}ms: $renderedCommand",
                    )
                }

                outputReaderThread.join(2_000)
                val output = outputBuffer.toString().trim()

                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    return@withContext HookActionExecutionResult(
                        success = false,
                        message = if (output.isBlank()) {
                            "Command failed (exit=$exitCode): $renderedCommand"
                        } else {
                            "Command failed (exit=$exitCode): $output"
                        },
                    )
                }

                HookActionExecutionResult(
                    success = true,
                    message = if (output.isBlank()) {
                        "Command succeeded: $renderedCommand"
                    } else {
                        "Command succeeded: $output"
                    },
                )
            } catch (e: Exception) {
                HookActionExecutionResult(
                    success = false,
                    message = "Command execution error: ${e.message}",
                )
            }
        }

        fun showNotification(project: Project, action: HookAction, message: String) {
            val type = when (action.level) {
                HookNotificationLevel.INFO -> NotificationType.INFORMATION
                HookNotificationLevel.WARNING -> NotificationType.WARNING
                HookNotificationLevel.ERROR -> NotificationType.ERROR
            }
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("SpecCoding.Notifications")
                .createNotification(message, type)
                .notify(project)
        }

        fun renderTemplate(raw: String, context: HookTriggerContext): String {
            var rendered = raw
            context.filePath?.let { filePath ->
                rendered = rendered.replace("{{file.path}}", filePath)
            }
            context.specStage?.let { specStage ->
                rendered = rendered.replace("{{spec.stage}}", specStage)
            }
            context.metadata.forEach { (key, value) ->
                rendered = rendered.replace("{{meta.$key}}", value)
            }
            return rendered
        }

        private const val MAX_COMMAND_OUTPUT_CHARS = 8_192
    }
}

internal data class HookActionExecutionResult(
    val success: Boolean,
    val message: String,
)
