package com.eacape.speccodingplugin.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal enum class WorkflowChatExecutionLaunchSurface {
    TASK_ROW,
    EXECUTION_CARD,
    WORKFLOW_CHAT,
    UNKNOWN,
}

internal enum class WorkflowChatExecutionPresentationSectionKind {
    DEPENDENCIES,
    ARTIFACT_SUMMARIES,
    CLARIFICATION_CONCLUSIONS,
    CODE_CONTEXT,
}

internal data class WorkflowChatExecutionPresentationSection(
    val kind: WorkflowChatExecutionPresentationSectionKind,
    val itemCount: Int,
    val previewItems: List<String> = emptyList(),
    val emptyStateReason: String? = null,
    val truncated: Boolean = false,
)

internal data class WorkflowChatExecutionLaunchPresentation(
    val workflowId: String,
    val taskId: String,
    val taskTitle: String,
    val runId: String,
    val focusedStage: StageId,
    val trigger: ExecutionTrigger,
    val launchSurface: WorkflowChatExecutionLaunchSurface,
    val taskStatusBeforeExecution: TaskStatus? = null,
    val taskPriority: TaskPriority? = null,
    val sections: List<WorkflowChatExecutionPresentationSection> = emptyList(),
    val supplementalInstruction: String? = null,
    val degradationReasons: List<String> = emptyList(),
    val rawPromptDebugAvailable: Boolean = false,
) {
    fun section(kind: WorkflowChatExecutionPresentationSectionKind): WorkflowChatExecutionPresentationSection? {
        return sections.firstOrNull { section -> section.kind == kind }
    }
}

internal data class WorkflowChatExecutionLaunchDebugPayload(
    val rawPrompt: String,
)

internal enum class WorkflowChatExecutionLaunchFallbackReason {
    MISSING_PRESENTATION_METADATA,
    UNRECOGNIZED_LEGACY_PROMPT,
}

internal data class WorkflowChatExecutionLegacyCompactNotice(
    val workflowId: String?,
    val taskId: String?,
    val taskTitle: String?,
    val runId: String?,
    val focusedStage: StageId?,
    val trigger: ExecutionTrigger?,
    val launchSurface: WorkflowChatExecutionLaunchSurface,
    val sectionKinds: Set<WorkflowChatExecutionPresentationSectionKind> = emptySet(),
    val supplementalInstructionPresent: Boolean = false,
    val fallbackReason: WorkflowChatExecutionLaunchFallbackReason,
    val rawPromptDebugAvailable: Boolean = false,
)

internal sealed interface WorkflowChatExecutionLaunchRestorePayload {
    data class Presentation(
        val launch: WorkflowChatExecutionLaunchPresentation,
    ) : WorkflowChatExecutionLaunchRestorePayload

    data class LegacyCompact(
        val notice: WorkflowChatExecutionLegacyCompactNotice,
    ) : WorkflowChatExecutionLaunchRestorePayload
}

internal object WorkflowChatExecutionLaunchRestoreResolver {
    private const val WORKFLOW_PREFIX = "Workflow="
    private const val EXECUTION_ACTION_PREFIX = "Execution action:"
    private const val RUN_ID_PREFIX = "Run ID:"
    private const val TASK_ID_PREFIX = "Task ID:"
    private const val TASK_TITLE_PREFIX = "Task Title:"
    private const val CURRENT_STAGE_PREFIX = "Current stage:"
    private const val ARTIFACT_SUMMARIES_HEADING = "Artifact Summaries"
    private const val CLARIFICATIONS_HEADING = "Confirmed Clarification Conclusions"
    private const val CODE_CONTEXT_HEADING = "Candidate Related Files"
    private const val SUPPLEMENTAL_INSTRUCTION_HEADING = "Supplemental Instruction"
    private val EXECUTION_PROMPT_MARKERS = listOf(
        "## Task",
        "## Stage Context",
        "## Execution Request",
    )

    fun resolve(
        presentation: WorkflowChatExecutionLaunchPresentation?,
        rawPromptContent: String?,
        workflowId: String?,
        taskId: String?,
        runId: String?,
        trigger: ExecutionTrigger?,
        launchSurface: WorkflowChatExecutionLaunchSurface = WorkflowChatExecutionLaunchSurface.UNKNOWN,
    ): WorkflowChatExecutionLaunchRestorePayload? {
        presentation?.let { launch ->
            return WorkflowChatExecutionLaunchRestorePayload.Presentation(launch)
        }

        val normalizedPrompt = rawPromptContent?.trim().orEmpty()
        if (normalizedPrompt.isBlank() && workflowId.isNullOrBlank() && taskId.isNullOrBlank() && runId.isNullOrBlank()) {
            return null
        }

        val snapshot = parseLegacyPrompt(normalizedPrompt)
        val notice = WorkflowChatExecutionLegacyCompactNotice(
            workflowId = snapshot?.workflowId ?: workflowId,
            taskId = snapshot?.taskId ?: taskId,
            taskTitle = snapshot?.taskTitle,
            runId = snapshot?.runId ?: runId,
            focusedStage = snapshot?.focusedStage,
            trigger = snapshot?.trigger ?: trigger,
            launchSurface = launchSurface,
            sectionKinds = snapshot?.sectionKinds.orEmpty(),
            supplementalInstructionPresent = snapshot?.supplementalInstructionPresent == true,
            fallbackReason = if (snapshot != null) {
                WorkflowChatExecutionLaunchFallbackReason.MISSING_PRESENTATION_METADATA
            } else {
                WorkflowChatExecutionLaunchFallbackReason.UNRECOGNIZED_LEGACY_PROMPT
            },
            rawPromptDebugAvailable = normalizedPrompt.isNotBlank(),
        )
        return WorkflowChatExecutionLaunchRestorePayload.LegacyCompact(notice)
    }

    private fun parseLegacyPrompt(rawPromptContent: String): LegacyPromptSnapshot? {
        if (rawPromptContent.isBlank() || EXECUTION_PROMPT_MARKERS.any { marker -> !rawPromptContent.contains(marker) }) {
            return null
        }

        var workflowId: String? = null
        var taskId: String? = null
        var taskTitle: String? = null
        var runId: String? = null
        var focusedStage: StageId? = null
        var trigger: ExecutionTrigger? = null
        var currentHeading: String? = null
        val artifactLines = mutableListOf<String>()
        val clarificationLines = mutableListOf<String>()
        val codeContextLines = mutableListOf<String>()
        var supplementalInstructionPresent = false

        rawPromptContent.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                return@forEach
            }

            if (line.startsWith("## ")) {
                currentHeading = line.removePrefix("## ").trim()
                return@forEach
            }

            when {
                line.startsWith(WORKFLOW_PREFIX) -> {
                    workflowId = line.removePrefix(WORKFLOW_PREFIX).substringBefore(" ").trim().ifBlank { null }
                }

                line.startsWith(TASK_ID_PREFIX) -> {
                    taskId = line.substringAfter(':', "").trim().ifBlank { null }
                }

                line.startsWith(TASK_TITLE_PREFIX) -> {
                    taskTitle = line.substringAfter(':', "").trim().ifBlank { null }
                }

                line.startsWith(RUN_ID_PREFIX) -> {
                    runId = line.substringAfter(':', "").trim().ifBlank { null }
                }

                line.startsWith(CURRENT_STAGE_PREFIX) -> {
                    val rawStage = line.substringAfter(':', "").trim()
                    focusedStage = StageId.entries.firstOrNull { stage -> stage.name == rawStage }
                }

                line.startsWith(EXECUTION_ACTION_PREFIX) -> {
                    trigger = actionNameToTrigger(line.substringAfter(':', "").trim())
                }
            }

            when (currentHeading) {
                ARTIFACT_SUMMARIES_HEADING -> maybeCollectListItem(line, artifactLines)
                CLARIFICATIONS_HEADING -> maybeCollectListItem(line, clarificationLines)
                CODE_CONTEXT_HEADING -> maybeCollectListItem(line, codeContextLines)
                SUPPLEMENTAL_INSTRUCTION_HEADING -> if (line.isNotBlank()) supplementalInstructionPresent = true
            }
        }

        return LegacyPromptSnapshot(
            workflowId = workflowId,
            taskId = taskId,
            taskTitle = taskTitle,
            runId = runId,
            focusedStage = focusedStage,
            trigger = trigger,
            sectionKinds = buildSet {
                if (artifactLines.isNotEmpty()) add(WorkflowChatExecutionPresentationSectionKind.ARTIFACT_SUMMARIES)
                if (clarificationLines.isNotEmpty()) add(WorkflowChatExecutionPresentationSectionKind.CLARIFICATION_CONCLUSIONS)
                if (codeContextLines.isNotEmpty()) add(WorkflowChatExecutionPresentationSectionKind.CODE_CONTEXT)
            },
            supplementalInstructionPresent = supplementalInstructionPresent,
        )
    }

    private fun maybeCollectListItem(line: String, sink: MutableList<String>) {
        if (!line.startsWith("- ")) {
            return
        }
        val value = line.removePrefix("- ").trim()
        if (value.startsWith("None", ignoreCase = true) || value.startsWith("No ", ignoreCase = true)) {
            return
        }
        sink += value
    }

    private fun actionNameToTrigger(actionName: String): ExecutionTrigger? {
        return when (actionName) {
            "EXECUTE_WITH_AI" -> ExecutionTrigger.USER_EXECUTE
            "RETRY_EXECUTION" -> ExecutionTrigger.USER_RETRY
            "SYSTEM_RECOVERY" -> ExecutionTrigger.SYSTEM_RECOVERY
            else -> null
        }
    }

    private data class LegacyPromptSnapshot(
        val workflowId: String?,
        val taskId: String?,
        val taskTitle: String?,
        val runId: String?,
        val focusedStage: StageId?,
        val trigger: ExecutionTrigger?,
        val sectionKinds: Set<WorkflowChatExecutionPresentationSectionKind>,
        val supplementalInstructionPresent: Boolean,
    )
}

internal object WorkflowChatExecutionLaunchPresentationCodec {
    fun encodeToJson(
        presentation: WorkflowChatExecutionLaunchPresentation,
    ): JsonObject {
        return buildJsonObject {
            put("workflow_id", presentation.workflowId)
            put("task_id", presentation.taskId)
            put("task_title", presentation.taskTitle)
            put("run_id", presentation.runId)
            put("focused_stage", presentation.focusedStage.name)
            put("trigger", presentation.trigger.name)
            put("launch_surface", presentation.launchSurface.name)
            presentation.taskStatusBeforeExecution?.let { put("task_status_before_execution", it.name) }
            presentation.taskPriority?.let { put("task_priority", it.name) }
            if (presentation.sections.isNotEmpty()) {
                put(
                    "sections",
                    buildJsonArray {
                        presentation.sections.forEach { section ->
                            add(
                                buildJsonObject {
                                    put("kind", section.kind.name)
                                    put("item_count", section.itemCount.toLong())
                                    if (section.previewItems.isNotEmpty()) {
                                        put(
                                            "preview_items",
                                            buildJsonArray {
                                                section.previewItems.forEach { previewItem ->
                                                    add(previewItem)
                                                }
                                            },
                                        )
                                    }
                                    section.emptyStateReason?.takeIf(String::isNotBlank)?.let {
                                        put("empty_state_reason", it)
                                    }
                                    put("truncated", section.truncated)
                                },
                            )
                        }
                    },
                )
            }
            presentation.supplementalInstruction?.takeIf(String::isNotBlank)?.let {
                put("supplemental_instruction", it)
            }
            if (presentation.degradationReasons.isNotEmpty()) {
                put(
                    "degradation_reasons",
                    buildJsonArray {
                        presentation.degradationReasons.forEach { reason ->
                            add(reason)
                        }
                    },
                )
            }
            put("raw_prompt_debug_available", presentation.rawPromptDebugAvailable)
        }
    }

    fun decodeFromJson(element: JsonElement?): WorkflowChatExecutionLaunchPresentation? {
        val root = element as? JsonObject ?: return null
        val workflowId = root.string("workflow_id")?.trim().orEmpty()
        val taskId = root.string("task_id")?.trim().orEmpty()
        val taskTitle = root.string("task_title")?.trim().orEmpty()
        val runId = root.string("run_id")?.trim().orEmpty()
        val focusedStage = enumOrNull<StageId>(root.string("focused_stage")) ?: return null
        val trigger = enumOrNull<ExecutionTrigger>(root.string("trigger")) ?: return null
        val launchSurface = enumOrNull<WorkflowChatExecutionLaunchSurface>(root.string("launch_surface"))
            ?: WorkflowChatExecutionLaunchSurface.UNKNOWN
        if (workflowId.isBlank() || taskId.isBlank() || taskTitle.isBlank() || runId.isBlank()) {
            return null
        }

        val sections = (root["sections"] as? JsonArray)
            ?.mapNotNull { sectionElement ->
                val sectionObject = sectionElement as? JsonObject ?: return@mapNotNull null
                val kind = enumOrNull<WorkflowChatExecutionPresentationSectionKind>(sectionObject.string("kind"))
                    ?: return@mapNotNull null
                val itemCount = sectionObject.long("item_count")?.coerceAtLeast(0L)?.toInt() ?: return@mapNotNull null
                val previewItems = (sectionObject["preview_items"] as? JsonArray)
                    ?.mapNotNull { preview -> (preview as? JsonPrimitive)?.contentOrNull?.trim() }
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
                WorkflowChatExecutionPresentationSection(
                    kind = kind,
                    itemCount = itemCount,
                    previewItems = previewItems,
                    emptyStateReason = sectionObject.string("empty_state_reason")?.trim()?.ifBlank { null },
                    truncated = sectionObject.boolean("truncated") ?: false,
                )
            }
            .orEmpty()

        val degradationReasons = (root["degradation_reasons"] as? JsonArray)
            ?.mapNotNull { reason -> (reason as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter(String::isNotEmpty)
            .orEmpty()

        return WorkflowChatExecutionLaunchPresentation(
            workflowId = workflowId,
            taskId = taskId,
            taskTitle = taskTitle,
            runId = runId,
            focusedStage = focusedStage,
            trigger = trigger,
            launchSurface = launchSurface,
            taskStatusBeforeExecution = enumOrNull<TaskStatus>(root.string("task_status_before_execution")),
            taskPriority = enumOrNull<TaskPriority>(root.string("task_priority")),
            sections = sections,
            supplementalInstruction = root.string("supplemental_instruction")?.trim()?.ifBlank { null },
            degradationReasons = degradationReasons,
            rawPromptDebugAvailable = root.boolean("raw_prompt_debug_available") ?: false,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? {
        val value = name?.trim()?.takeIf(String::isNotBlank) ?: return null
        return enumValues<T>().firstOrNull { entry -> entry.name == value }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun buildJsonObject(builder: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map.builder()
        return JsonObject(map)
    }

    private fun buildJsonArray(builder: MutableList<JsonElement>.() -> Unit): JsonArray {
        val list = mutableListOf<JsonElement>()
        list.builder()
        return JsonArray(list)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: String) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Boolean) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Long) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: JsonElement) {
        this[key] = value
    }

    private fun MutableList<JsonElement>.add(value: JsonObject) {
        this += value
    }

    private fun MutableList<JsonElement>.add(value: String) {
        this += JsonPrimitive(value)
    }
}

internal object WorkflowChatExecutionLaunchDebugPayloadCodec {
    fun encodeToJson(
        payload: WorkflowChatExecutionLaunchDebugPayload,
    ): JsonObject {
        return JsonObject(
            linkedMapOf(
                "raw_prompt" to JsonPrimitive(payload.rawPrompt),
            ),
        )
    }

    fun decodeFromJson(element: JsonElement?): WorkflowChatExecutionLaunchDebugPayload? {
        val root = element as? JsonObject ?: return null
        val rawPrompt = (root["raw_prompt"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (rawPrompt.isBlank()) {
            return null
        }
        return WorkflowChatExecutionLaunchDebugPayload(rawPrompt = rawPrompt)
    }
}

internal fun TaskExecutionSessionMetadataCodec.DecodedMetadata.resolveExecutionLaunchRestorePayload(
    rawContent: String?,
): WorkflowChatExecutionLaunchRestorePayload? {
    return WorkflowChatExecutionLaunchRestoreResolver.resolve(
        presentation = launchPresentation,
        rawPromptContent = resolveExecutionLaunchRawPrompt() ?: rawContent,
        workflowId = workflowId,
        taskId = taskId,
        runId = runId,
        trigger = trigger,
    )
}

internal fun TaskExecutionSessionMetadataCodec.DecodedMetadata.resolveExecutionLaunchRawPrompt(): String? {
    return launchDebugPayload?.rawPrompt?.takeIf(String::isNotBlank)
}
