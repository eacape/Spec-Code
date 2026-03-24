package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.session.displayWorkflowChatCommand
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class SpecCardMetadata(
    val workflowId: String,
    val phase: SpecPhase,
    val status: WorkflowStatus,
    val title: String,
    val revision: Long,
    val sourceCommand: String,
)

/**
 * 将 Spec 卡片结构化信息编码到消息 metadata_json，便于历史恢复和后续卡片组件升级。
 */
internal object SpecCardMetadataCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun encode(metadata: SpecCardMetadata): String {
        val root = buildJsonObject {
            put(FORMAT_KEY, FORMAT_VALUE)
            put(
                SPEC_CARD_KEY,
                buildJsonObject {
                    put("workflow_id", metadata.workflowId)
                    put("phase", metadata.phase.name)
                    put("status", metadata.status.name)
                    put("title", metadata.title)
                    put("revision", metadata.revision)
                    put(
                        "source_command",
                        displayWorkflowChatCommand(metadata.sourceCommand) ?: metadata.sourceCommand,
                    )
                },
            )
        }
        return root.toString()
    }

    fun decode(metadataJson: String?): SpecCardMetadata? {
        if (metadataJson.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(metadataJson).asObject() }
            .getOrNull()
            ?: return null
        if (root.string(FORMAT_KEY) != FORMAT_VALUE) return null
        val card = root.obj(SPEC_CARD_KEY) ?: return null

        val workflowId = card.string("workflow_id")?.trim().orEmpty()
        if (workflowId.isBlank()) return null
        val phase = enumOrNull<SpecPhase>(card.string("phase")) ?: return null
        val status = enumOrNull<WorkflowStatus>(card.string("status")) ?: return null
        val title = card.string("title")?.trim().orEmpty()
        val revision = card.long("revision") ?: return null
        val sourceCommand = displayWorkflowChatCommand(card.string("source_command")).orEmpty()

        return SpecCardMetadata(
            workflowId = workflowId,
            phase = phase,
            status = status,
            title = title,
            revision = revision,
            sourceCommand = sourceCommand,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? {
        val value = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return enumValues<T>().firstOrNull { it.name == value }
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun buildJsonObject(builder: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map.builder()
        return JsonObject(map)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: String) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Long) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: JsonElement) {
        this[key] = value
    }

    private const val FORMAT_KEY = "format"
    private const val FORMAT_VALUE = "spec_card_v1"
    private const val SPEC_CARD_KEY = "spec_card"
}
