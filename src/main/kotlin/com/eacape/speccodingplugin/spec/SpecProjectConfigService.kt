package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Loads `.spec-coding/config.yaml`, validates schema and merges defaults.
 */
class SpecProjectConfigService(
    project: Project,
    private val workspaceInitializer: SpecWorkspaceInitializer = SpecWorkspaceInitializer(project),
) {

    fun load(): SpecProjectConfig {
        val configPath = configPath()
        if (!Files.exists(configPath)) {
            return defaultConfig()
        }

        val raw = Files.readString(configPath, StandardCharsets.UTF_8)
        return parseSnapshotYaml(raw)
    }

    fun parseSnapshotYaml(raw: String): SpecProjectConfig {
        val upgraded = SpecSchemaVersioning.upgradeProjectConfig(SpecYamlCodec.decodeMap(raw))
        return parseConfig(upgraded.document)
    }

    fun configPath(): Path {
        return workspaceInitializer.specCodingDirectory().resolve(CONFIG_FILE_NAME)
    }

    fun createConfigPin(config: SpecProjectConfig = load()): SpecConfigPin {
        val snapshotYaml = SpecYamlCodec.encodeMap(serializeConfig(config))
        return SpecConfigPin(
            hash = sha256Hex(snapshotYaml),
            snapshotYaml = snapshotYaml,
        )
    }

    private fun parseConfig(root: Map<String, Any?>): SpecProjectConfig {
        val schemaVersion = parseSchemaVersion(root["schemaVersion"])
        val defaultTemplate = parseTemplate(root["defaultTemplate"], path = "defaultTemplate", fallback = WorkflowTemplate.FULL_SPEC)
        val templates = parseTemplates(root["templates"])
        val gate = parseGatePolicy(root["gate"])
        val rules = parseRules(root["rules"])
        val verify = parseVerifyConfig(root["verify"])
        return SpecProjectConfig(
            schemaVersion = schemaVersion,
            defaultTemplate = defaultTemplate,
            templates = templates,
            gate = gate,
            rules = rules,
            verify = verify,
        )
    }

    private fun defaultConfig(): SpecProjectConfig {
        return SpecProjectConfig(
            schemaVersion = SpecProjectConfig.CURRENT_SCHEMA_VERSION,
            defaultTemplate = WorkflowTemplate.FULL_SPEC,
            templates = defaultTemplatePolicies(),
            gate = SpecGatePolicy(),
            rules = emptyMap(),
            verify = SpecVerifyConfig(),
        )
    }

    private fun serializeConfig(config: SpecProjectConfig): Map<String, Any?> {
        val templates = linkedMapOf<String, Any?>()
        config.templates
            .entries
            .sortedBy { (template, _) -> template.name }
            .forEach { (template, policy) ->
                val stagePlan = policy.definition.stagePlan.map { item ->
                    linkedMapOf<String, Any?>(
                        "id" to item.id.name,
                        "optional" to item.optional,
                        "defaultEnabled" to item.defaultEnabled,
                    )
                }
                templates[template.name] = linkedMapOf<String, Any?>(
                    "verifyEnabledByDefault" to policy.verifyEnabledByDefault,
                    "implementEnabledByDefault" to policy.implementEnabledByDefault,
                    "stagePlan" to stagePlan,
                )
            }

        val rules = linkedMapOf<String, Any?>()
        config.rules
            .entries
            .sortedBy { (ruleId, _) -> ruleId }
            .forEach { (ruleId, policy) ->
                val ruleMap = linkedMapOf<String, Any?>(
                    "enabled" to policy.enabled,
                )
                policy.severityOverride?.let { severity ->
                    ruleMap["severity"] = severity.name
                }
                rules[ruleId] = ruleMap
            }

        val verify = linkedMapOf<String, Any?>(
            "defaultWorkingDirectory" to config.verify.defaultWorkingDirectory,
            "defaultTimeoutMs" to config.verify.defaultTimeoutMs,
            "defaultOutputLimitChars" to config.verify.defaultOutputLimitChars,
            "redactionPatterns" to config.verify.redactionPatterns,
            "commands" to config.verify.commands
                .sortedBy(SpecVerifyCommand::id)
                .map { command ->
                    linkedMapOf<String, Any?>(
                        "id" to command.id,
                        "displayName" to command.displayName,
                        "command" to command.command,
                        "workingDirectory" to command.workingDirectory,
                        "timeoutMs" to command.timeoutMs,
                        "outputLimitChars" to command.outputLimitChars,
                        "redactionPatterns" to command.redactionPatterns,
                    ).filterValues { value -> value != null }
                },
        )

        return linkedMapOf<String, Any?>(
            "schemaVersion" to config.schemaVersion,
            "defaultTemplate" to config.defaultTemplate.name,
            "templates" to templates,
            "gate" to linkedMapOf<String, Any?>(
                "allowWarningAdvance" to config.gate.allowWarningAdvance,
                "requireWarningConfirmation" to config.gate.requireWarningConfirmation,
                "allowJump" to config.gate.allowJump,
                "jumpRequiresMinimalGate" to config.gate.jumpRequiresMinimalGate,
                "allowRollback" to config.gate.allowRollback,
            ),
            "rules" to rules,
            "verify" to verify,
        )
    }

    private fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(raw.toByteArray(StandardCharsets.UTF_8))
        return HEX_FORMAT.formatHex(hashBytes)
    }

    private fun defaultTemplatePolicies(): Map<WorkflowTemplate, SpecTemplatePolicy> {
        return WorkflowTemplate.entries.associateWith { template ->
            val definition = WorkflowTemplates.definitionOf(template)
            SpecTemplatePolicy(
                definition = definition,
                verifyEnabledByDefault = defaultStageToggle(definition, StageId.VERIFY),
                implementEnabledByDefault = defaultStageToggle(definition, StageId.IMPLEMENT),
            )
        }
    }

    private fun parseSchemaVersion(raw: Any?): Int {
        val version = parseInt(raw, "schemaVersion") ?: SpecProjectConfig.CURRENT_SCHEMA_VERSION
        require(version == SpecProjectConfig.CURRENT_SCHEMA_VERSION) {
            "Unsupported config schemaVersion: $version " +
                "(supported: ${SpecProjectConfig.MIN_SUPPORTED_SCHEMA_VERSION}.." +
                "${SpecProjectConfig.CURRENT_SCHEMA_VERSION})."
        }
        return version
    }

    private fun parseTemplates(raw: Any?): Map<WorkflowTemplate, SpecTemplatePolicy> {
        val defaultPolicies = defaultTemplatePolicies()
        val rawTemplates = parseMap(raw, "templates") ?: emptyMap()

        val overrides = mutableMapOf<WorkflowTemplate, Map<String, Any?>>()
        rawTemplates.forEach { (rawTemplateName, rawConfig) ->
            val templateName = normalizeKey(rawTemplateName, "templates")
            val template = parseTemplate(templateName, path = "templates.$templateName", fallback = null)
            val templateConfig = parseMap(rawConfig, "templates.$templateName")
                ?: throw IllegalArgumentException("templates.$templateName must be a mapping.")
            overrides[template] = templateConfig
        }

        return WorkflowTemplate.entries.associateWith { template ->
            val defaultPolicy = defaultPolicies.getValue(template)
            val templateOverride = overrides[template]
            if (templateOverride == null) {
                return@associateWith defaultPolicy
            }

            val parsedDefinition = parseTemplateDefinitionOverride(
                template = template,
                rawTemplateConfig = templateOverride,
                fallback = defaultPolicy.definition,
            )
            val verifyEnabled = parseBooleanOptional(
                readFirst(templateOverride, "verifyEnabled", "defaultVerifyEnabled"),
                "templates.${template.name}.verifyEnabled",
            ) ?: defaultStageToggle(parsedDefinition, StageId.VERIFY)
            val implementEnabled = parseBooleanOptional(
                readFirst(templateOverride, "implementEnabled", "defaultImplementEnabled"),
                "templates.${template.name}.implementEnabled",
            ) ?: defaultStageToggle(parsedDefinition, StageId.IMPLEMENT)

            SpecTemplatePolicy(
                definition = parsedDefinition,
                verifyEnabledByDefault = verifyEnabled,
                implementEnabledByDefault = implementEnabled,
            )
        }
    }

    private fun parseTemplateDefinitionOverride(
        template: WorkflowTemplate,
        rawTemplateConfig: Map<String, Any?>,
        fallback: TemplateDefinition,
    ): TemplateDefinition {
        val stagePlanRaw = readFirst(rawTemplateConfig, "stagePlan", "stages") ?: return fallback
        val stagePlan = parseStagePlan(stagePlanRaw, "templates.${template.name}.stagePlan")
        return TemplateDefinition(
            template = template,
            stagePlan = stagePlan,
        )
    }

    private fun parseStagePlan(raw: Any?, path: String): List<StagePlanItem> {
        val items = raw as? List<*>
            ?: throw IllegalArgumentException("$path must be a sequence.")
        require(items.isNotEmpty()) {
            "$path cannot be empty."
        }
        return items.mapIndexed { index, rawItem ->
            when (rawItem) {
                is String -> StagePlanItem(id = parseStageId(rawItem, "$path[$index]"))
                is Map<*, *> -> {
                    val map = normalizeMap(rawItem, "$path[$index]")
                    val stageId = parseStageId(
                        value = map["id"],
                        path = "$path[$index].id",
                    )
                    val optional = parseBooleanOptional(
                        map["optional"],
                        "$path[$index].optional",
                    ) ?: false
                    val defaultEnabled = parseBooleanOptional(
                        map["defaultEnabled"],
                        "$path[$index].defaultEnabled",
                    ) ?: !optional
                    require(optional || defaultEnabled) {
                        "$path[$index]: non-optional stage cannot set defaultEnabled=false."
                    }
                    StagePlanItem(
                        id = stageId,
                        optional = optional,
                        defaultEnabled = defaultEnabled,
                    )
                }

                else -> throw IllegalArgumentException(
                    "$path[$index] must be a stage id string or mapping.",
                )
            }
        }
    }

    private fun parseGatePolicy(raw: Any?): SpecGatePolicy {
        val gateMap = parseMap(raw, "gate") ?: return SpecGatePolicy()
        return SpecGatePolicy(
            allowWarningAdvance = parseBooleanOptional(
                readFirst(gateMap, "allowWarningAdvance", "allowWarning"),
                "gate.allowWarningAdvance",
            ) ?: SpecGatePolicy().allowWarningAdvance,
            requireWarningConfirmation = parseBooleanOptional(
                readFirst(gateMap, "requireWarningConfirmation", "requireWarningConfirm"),
                "gate.requireWarningConfirmation",
            ) ?: SpecGatePolicy().requireWarningConfirmation,
            allowJump = parseBooleanOptional(
                readFirst(gateMap, "allowJump"),
                "gate.allowJump",
            ) ?: SpecGatePolicy().allowJump,
            jumpRequiresMinimalGate = parseBooleanOptional(
                readFirst(gateMap, "jumpRequiresMinimalGate", "enforceMinimalGateOnJump"),
                "gate.jumpRequiresMinimalGate",
            ) ?: SpecGatePolicy().jumpRequiresMinimalGate,
            allowRollback = parseBooleanOptional(
                readFirst(gateMap, "allowRollback"),
                "gate.allowRollback",
            ) ?: SpecGatePolicy().allowRollback,
        )
    }

    private fun parseRules(raw: Any?): Map<String, SpecRulePolicy> {
        val rulesMap = parseMap(raw, "rules") ?: return emptyMap()
        return rulesMap.entries
            .map { (rawRuleId, rawRuleConfig) ->
                val ruleId = normalizeKey(rawRuleId, "rules")
                require(ruleId.isNotBlank()) { "rules contains blank rule id." }
                val ruleConfig = parseMap(rawRuleConfig, "rules.$ruleId") ?: emptyMap()
                val enabled = parseBooleanOptional(
                    ruleConfig["enabled"],
                    "rules.$ruleId.enabled",
                ) ?: true
                val severity = parseSeverity(
                    ruleConfig["severity"],
                    "rules.$ruleId.severity",
                )
                ruleId to SpecRulePolicy(
                    enabled = enabled,
                    severityOverride = severity,
                )
            }
            .sortedBy { (ruleId, _) -> ruleId }
            .toMap()
    }

    private fun parseVerifyConfig(raw: Any?): SpecVerifyConfig {
        val verifyMap = parseMap(raw, "verify") ?: return SpecVerifyConfig()
        val defaultWorkingDirectory = parseStringOptional(
            readFirst(verifyMap, "defaultWorkingDirectory", "workingDirectory", "workdir"),
            "verify.defaultWorkingDirectory",
        ) ?: SpecVerifyConfig.DEFAULT_WORKING_DIRECTORY
        val defaultTimeoutMs = parsePositiveInt(
            readFirst(verifyMap, "defaultTimeoutMs", "timeoutMs"),
            "verify.defaultTimeoutMs",
        ) ?: SpecVerifyConfig.DEFAULT_TIMEOUT_MS
        val defaultOutputLimitChars = parsePositiveInt(
            readFirst(verifyMap, "defaultOutputLimitChars", "outputLimitChars", "maxOutputChars"),
            "verify.defaultOutputLimitChars",
        ) ?: SpecVerifyConfig.DEFAULT_OUTPUT_LIMIT_CHARS
        val redactionPatterns = parseRegexStringList(
            verifyMap["redactionPatterns"],
            "verify.redactionPatterns",
        )
        val commands = parseVerifyCommands(verifyMap["commands"])
        return SpecVerifyConfig(
            defaultWorkingDirectory = defaultWorkingDirectory,
            defaultTimeoutMs = defaultTimeoutMs,
            defaultOutputLimitChars = defaultOutputLimitChars,
            redactionPatterns = redactionPatterns,
            commands = commands,
        )
    }

    private fun parseVerifyCommands(raw: Any?): List<SpecVerifyCommand> {
        val items = raw as? List<*> ?: return emptyList()
        val commands = items.mapIndexed { index, rawCommand ->
            val path = "verify.commands[$index]"
            val commandMap = normalizeMap(rawCommand, path)
            val id = parseRequiredString(commandMap["id"], "$path.id")
            val displayName = parseStringOptional(commandMap["displayName"], "$path.displayName")
            val command = parseStringSequence(commandMap["command"], "$path.command")
            val workingDirectory = parseStringOptional(
                readFirst(commandMap, "workingDirectory", "workdir"),
                "$path.workingDirectory",
            )
            val timeoutMs = parsePositiveInt(commandMap["timeoutMs"], "$path.timeoutMs")
            val outputLimitChars = parsePositiveInt(
                readFirst(commandMap, "outputLimitChars", "maxOutputChars"),
                "$path.outputLimitChars",
            )
            val redactionPatterns = parseRegexStringList(
                commandMap["redactionPatterns"],
                "$path.redactionPatterns",
            )
            SpecVerifyCommand(
                id = id,
                displayName = displayName,
                command = command,
                workingDirectory = workingDirectory,
                timeoutMs = timeoutMs,
                outputLimitChars = outputLimitChars,
                redactionPatterns = redactionPatterns,
            )
        }
        val duplicateId = commands
            .groupingBy(SpecVerifyCommand::id)
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }
            ?.key
        require(duplicateId == null) {
            "verify.commands contains duplicate id '$duplicateId'."
        }
        return commands
    }

    private fun parseTemplate(value: Any?, path: String, fallback: WorkflowTemplate?): WorkflowTemplate {
        if (value == null) {
            return fallback ?: throw IllegalArgumentException("$path is required.")
        }
        val normalized = value.toString().trim()
        if (normalized.isBlank()) {
            return fallback ?: throw IllegalArgumentException("$path cannot be blank.")
        }
        val candidate = WorkflowTemplate.entries.firstOrNull { template ->
            template.name.equals(normalized, ignoreCase = true)
        }
        return candidate ?: throw IllegalArgumentException(
            "$path has unsupported template '$normalized'. Supported: ${WorkflowTemplate.entries.joinToString { it.name }}",
        )
    }

    private fun parseStageId(value: Any?, path: String): StageId {
        val normalized = value?.toString()?.trim().orEmpty()
        require(normalized.isNotBlank()) { "$path cannot be blank." }
        return StageId.entries.firstOrNull { stage ->
            stage.name.equals(normalized, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "$path has unsupported stage '$normalized'. Supported: ${StageId.entries.joinToString { it.name }}",
        )
    }

    private fun parseSeverity(value: Any?, path: String): GateStatus? {
        if (value == null) {
            return null
        }
        val normalized = value.toString().trim()
        require(normalized.isNotBlank()) { "$path cannot be blank." }
        return GateStatus.entries.firstOrNull { status ->
            status.name.equals(normalized, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "$path has unsupported severity '$normalized'. Supported: ${GateStatus.entries.joinToString { it.name }}",
        )
    }

    private fun parseBooleanOptional(value: Any?, path: String): Boolean? {
        if (value == null) {
            return null
        }
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> throw IllegalArgumentException("$path must be boolean, but was '$value'.")
            }

            is Number -> when (value.toInt()) {
                1 -> true
                0 -> false
                else -> throw IllegalArgumentException("$path must be boolean (0/1), but was '$value'.")
            }

            else -> throw IllegalArgumentException("$path must be boolean.")
        }
    }

    private fun parseStringOptional(value: Any?, path: String): String? {
        if (value == null) {
            return null
        }
        val normalized = value.toString().trim()
        require(normalized.isNotBlank()) { "$path cannot be blank." }
        return normalized
    }

    private fun parseRequiredString(value: Any?, path: String): String {
        return parseStringOptional(value, path)
            ?: throw IllegalArgumentException("$path is required.")
    }

    private fun parseInt(value: Any?, path: String): Int? {
        if (value == null) {
            return null
        }
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
                ?: throw IllegalArgumentException("$path must be an integer.")

            else -> throw IllegalArgumentException("$path must be an integer.")
        }
    }

    private fun parsePositiveInt(value: Any?, path: String): Int? {
        val parsed = parseInt(value, path) ?: return null
        require(parsed > 0) { "$path must be greater than 0." }
        return parsed
    }

    private fun parseStringSequence(value: Any?, path: String): List<String> {
        val items = value as? List<*>
            ?: throw IllegalArgumentException("$path must be a sequence of strings.")
        require(items.isNotEmpty()) { "$path cannot be empty." }
        return items.mapIndexed { index, rawItem ->
            parseRequiredString(rawItem, "$path[$index]")
        }
    }

    private fun parseRegexStringList(value: Any?, path: String): List<String> {
        val rawPatterns = parseStringListOptional(value, path) ?: return emptyList()
        rawPatterns.forEachIndexed { index, pattern ->
            try {
                Regex(pattern)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "$path[$index] must be a valid regex: ${error.message ?: pattern}",
                    error,
                )
            }
        }
        return rawPatterns.distinct()
    }

    private fun parseStringListOptional(value: Any?, path: String): List<String>? {
        if (value == null) {
            return null
        }
        val items = value as? List<*>
            ?: throw IllegalArgumentException("$path must be a sequence.")
        return items.mapIndexed { index, rawItem ->
            parseRequiredString(rawItem, "$path[$index]")
        }
    }

    private fun parseMap(value: Any?, path: String): Map<String, Any?>? {
        if (value == null) {
            return null
        }
        return normalizeMap(value, path)
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeMap(value: Any?, path: String): Map<String, Any?> {
        val map = value as? Map<*, *>
            ?: throw IllegalArgumentException("$path must be a mapping.")
        return map.entries.associate { (key, rawValue) ->
            normalizeKey(key, path) to rawValue
        }
    }

    private fun normalizeKey(key: Any?, path: String): String {
        val normalized = key?.toString()?.trim().orEmpty()
        require(normalized.isNotBlank()) { "$path contains blank key." }
        return normalized
    }

    private fun readFirst(container: Map<String, Any?>, vararg keys: String): Any? {
        keys.forEach { key ->
            if (container.containsKey(key)) {
                return container[key]
            }
        }
        return null
    }

    private fun defaultStageToggle(definition: TemplateDefinition, stageId: StageId): Boolean {
        val item = definition.stagePlan.firstOrNull { stage -> stage.id == stageId } ?: return false
        return if (item.optional) item.defaultEnabled else true
    }

    companion object {
        private const val CONFIG_FILE_NAME = "config.yaml"
        private val HEX_FORMAT: HexFormat = HexFormat.of()
    }
}
