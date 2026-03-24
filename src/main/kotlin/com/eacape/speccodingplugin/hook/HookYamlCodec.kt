package com.eacape.speccodingplugin.hook

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.StringWriter

internal object HookYamlCodec {
    private val loadYaml = Yaml(SafeConstructor(LoaderOptions()))
    private val dumperOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        isPrettyFlow = true
        indent = 2
    }
    private val dumpYaml = Yaml(dumperOptions)

    @Suppress("UNCHECKED_CAST")
    fun deserialize(raw: String): List<HookDefinition> {
        if (raw.isBlank()) {
            return emptyList()
        }

        val root = loadYaml.load(raw) as? Map<String, Any?> ?: return emptyList()
        val hookList = root["hooks"] as? List<*> ?: return emptyList()

        return hookList
            .mapNotNull { entry -> parseHook(entry as? Map<String, Any?> ?: return@mapNotNull null) }
    }

    fun serialize(hooks: List<HookDefinition>): String {
        val root = linkedMapOf(
            "version" to 1,
            "hooks" to hooks.map { hook ->
                linkedMapOf<String, Any?>(
                    "id" to hook.id,
                    "name" to hook.name,
                    "event" to hook.event.name,
                    "enabled" to hook.enabled,
                    "conditions" to linkedMapOf(
                        "filePattern" to hook.conditions.filePattern,
                        "specStage" to hook.conditions.specStage,
                    ),
                    "actions" to hook.actions.map { action ->
                        linkedMapOf<String, Any?>(
                            "type" to action.type.name,
                            "command" to action.command,
                            "args" to action.args,
                            "timeoutMillis" to action.timeoutMillis,
                            "message" to action.message,
                            "level" to action.level.name,
                        )
                    },
                )
            },
        )

        val writer = StringWriter()
        dumpYaml.dump(root, writer)
        return writer.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseHook(raw: Map<String, Any?>): HookDefinition? {
        val id = raw["id"]?.toString()?.trim().orEmpty()
        val name = raw["name"]?.toString()?.trim().orEmpty()
        val event = parseEvent(raw["event"])
        if (id.isBlank() || name.isBlank() || event == null) {
            return null
        }

        val enabled = (raw["enabled"] as? Boolean) ?: true
        val conditions = parseConditions(raw["conditions"] as? Map<String, Any?>)
        val actions = (raw["actions"] as? List<*>)
            .orEmpty()
            .mapNotNull { parseAction(it as? Map<String, Any?> ?: return@mapNotNull null) }

        if (actions.isEmpty()) {
            return null
        }

        return HookDefinition(
            id = id,
            name = name,
            event = event,
            enabled = enabled,
            conditions = conditions,
            actions = actions,
        )
    }

    private fun parseEvent(raw: Any?): HookEvent? {
        val name = raw?.toString()?.trim()?.uppercase().orEmpty()
        if (name.isBlank()) {
            return null
        }
        return HookEvent.entries.firstOrNull { it.name == name }
    }

    private fun parseConditions(raw: Map<String, Any?>?): HookConditions {
        if (raw == null) {
            return HookConditions()
        }

        val filePattern = raw["filePattern"]?.toString()?.trim()?.ifBlank { null }
        val specStage = raw["specStage"]?.toString()?.trim()?.ifBlank { null }
        return HookConditions(
            filePattern = filePattern,
            specStage = specStage,
        )
    }

    private fun parseAction(raw: Map<String, Any?>): HookAction? {
        val type = parseActionType(raw["type"]) ?: return null
        val command = raw["command"]?.toString()?.trim()?.ifBlank { null }
        val args = (raw["args"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.ifBlank { null } }
        val timeoutMillis = parseTimeout(raw["timeoutMillis"])
        val message = raw["message"]?.toString()?.trim()?.ifBlank { null }
        val level = parseNotificationLevel(raw["level"])

        val action = HookAction(
            type = type,
            command = command,
            args = args,
            timeoutMillis = timeoutMillis,
            message = message,
            level = level,
        )

        return when (action.type) {
            HookActionType.RUN_COMMAND -> {
                if (action.command.isNullOrBlank()) null else action
            }

            HookActionType.SHOW_NOTIFICATION -> {
                if (action.message.isNullOrBlank()) null else action
            }
        }
    }

    private fun parseActionType(raw: Any?): HookActionType? {
        val name = raw?.toString()?.trim()?.uppercase().orEmpty()
        if (name.isBlank()) {
            return null
        }
        return HookActionType.entries.firstOrNull { it.name == name }
    }

    private fun parseNotificationLevel(raw: Any?): HookNotificationLevel {
        val name = raw?.toString()?.trim()?.uppercase().orEmpty()
        if (name.isBlank()) {
            return HookNotificationLevel.INFO
        }
        return HookNotificationLevel.entries.firstOrNull { it.name == name }
            ?: HookNotificationLevel.INFO
    }

    private fun parseTimeout(raw: Any?): Long {
        val parsed = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: 60_000L

        return parsed.coerceIn(1_000L, 10 * 60_000L)
    }
}
