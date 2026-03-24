package com.eacape.speccodingplugin.spec

data class SpecProjectConfig(
    val schemaVersion: Int,
    val defaultTemplate: WorkflowTemplate,
    val templates: Map<WorkflowTemplate, SpecTemplatePolicy>,
    val gate: SpecGatePolicy,
    val rules: Map<String, SpecRulePolicy>,
    val verify: SpecVerifyConfig,
) {
    fun policyFor(template: WorkflowTemplate): SpecTemplatePolicy = templates.getValue(template)

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MIN_SUPPORTED_SCHEMA_VERSION: Int = 0
        const val SUPPORTED_SCHEMA_VERSION: Int = CURRENT_SCHEMA_VERSION
    }
}

data class SpecConfigPin(
    val hash: String,
    val snapshotYaml: String,
)

data class SpecTemplatePolicy(
    val definition: TemplateDefinition,
    val verifyEnabledByDefault: Boolean,
    val implementEnabledByDefault: Boolean,
) {
    fun defaultStagePlan(): WorkflowStagePlan {
        return stagePlan()
    }

    fun stagePlan(
        verifyEnabled: Boolean? = null,
        implementEnabled: Boolean? = null,
    ): WorkflowStagePlan {
        val overrides = defaultActivationOptions().stageOverrides.toMutableMap()
        verifyEnabled?.let { overrides[StageId.VERIFY] = it }
        implementEnabled?.let { overrides[StageId.IMPLEMENT] = it }
        return definition.buildStagePlan(StageActivationOptions(stageOverrides = overrides))
    }

    fun defaultActiveStages(): List<StageId> {
        return defaultStagePlan().activeStages
    }

    private fun defaultActivationOptions(): StageActivationOptions {
        val overrides = linkedMapOf<StageId, Boolean>()
        definition.stagePlan
            .firstOrNull { item -> item.id == StageId.VERIFY && item.optional }
            ?.let { overrides[StageId.VERIFY] = verifyEnabledByDefault }
        definition.stagePlan
            .firstOrNull { item -> item.id == StageId.IMPLEMENT && item.optional }
            ?.let { overrides[StageId.IMPLEMENT] = implementEnabledByDefault }
        return StageActivationOptions(stageOverrides = overrides)
    }
}

data class SpecGatePolicy(
    val allowWarningAdvance: Boolean = true,
    val requireWarningConfirmation: Boolean = true,
    val allowJump: Boolean = true,
    val jumpRequiresMinimalGate: Boolean = true,
    val allowRollback: Boolean = true,
)

data class SpecRulePolicy(
    val enabled: Boolean = true,
    val severityOverride: GateStatus? = null,
)

data class SpecVerifyConfig(
    val defaultWorkingDirectory: String = DEFAULT_WORKING_DIRECTORY,
    val defaultTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val defaultOutputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
    val redactionPatterns: List<String> = emptyList(),
    val commands: List<SpecVerifyCommand> = emptyList(),
) {
    companion object {
        const val DEFAULT_WORKING_DIRECTORY: String = "."
        const val DEFAULT_TIMEOUT_MS: Int = 300_000
        const val DEFAULT_OUTPUT_LIMIT_CHARS: Int = 12_000
    }
}

data class SpecVerifyCommand(
    val id: String,
    val displayName: String? = null,
    val command: List<String>,
    val workingDirectory: String? = null,
    val timeoutMs: Int? = null,
    val outputLimitChars: Int? = null,
    val redactionPatterns: List<String> = emptyList(),
)
