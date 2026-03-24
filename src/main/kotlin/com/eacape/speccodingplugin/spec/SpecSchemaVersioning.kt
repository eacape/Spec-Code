package com.eacape.speccodingplugin.spec

data class SpecSchemaUpgradeStep(
    val fromVersion: Int,
    val toVersion: Int,
    val description: String,
)

data class SpecSchemaUpgradeResult(
    val document: Map<String, Any?>,
    val sourceVersion: Int,
    val targetVersion: Int,
    val appliedSteps: List<SpecSchemaUpgradeStep> = emptyList(),
) {
    val upgraded: Boolean
        get() = appliedSteps.isNotEmpty()
}

object SpecSchemaVersioning {
    private data class SchemaCompatibility(
        val documentType: String,
        val currentVersion: Int,
        val minimumSupportedVersion: Int,
    )

    private data class SchemaUpgradeHook<C>(
        val fromVersion: Int,
        val toVersion: Int,
        val description: String,
        val upgrade: (MutableMap<String, Any?>, C) -> Unit,
    ) {
        init {
            require(toVersion > fromVersion) {
                "Schema upgrade hooks must increase the schema version."
            }
        }

        fun toStep(): SpecSchemaUpgradeStep {
            return SpecSchemaUpgradeStep(
                fromVersion = fromVersion,
                toVersion = toVersion,
                description = description,
            )
        }
    }

    private object NoContext

    private val projectConfigCompatibility = SchemaCompatibility(
        documentType = "config",
        currentVersion = SpecProjectConfig.CURRENT_SCHEMA_VERSION,
        minimumSupportedVersion = SpecProjectConfig.MIN_SUPPORTED_SCHEMA_VERSION,
    )

    private val workflowMetadataCompatibility = SchemaCompatibility(
        documentType = "workflow metadata",
        currentVersion = CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION,
        minimumSupportedVersion = MIN_SUPPORTED_WORKFLOW_METADATA_SCHEMA_VERSION,
    )

    private val projectConfigHooks = listOf(
        SchemaUpgradeHook<NoContext>(
            fromVersion = LEGACY_SCHEMA_VERSION,
            toVersion = SpecProjectConfig.CURRENT_SCHEMA_VERSION,
            description = "Stamp legacy config documents with an explicit schemaVersion field.",
        ) { document, _ ->
            document["schemaVersion"] = SpecProjectConfig.CURRENT_SCHEMA_VERSION
        },
    )

    private val workflowMetadataHooks = listOf(
        SchemaUpgradeHook<String>(
            fromVersion = LEGACY_SCHEMA_VERSION,
            toVersion = CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION,
            description = "Backfill explicit schemaVersion/currentStage for legacy workflow metadata.",
        ) { document, _ ->
            val template = parseEnum(document["template"], WorkflowTemplate.entries) ?: WorkflowTemplate.FULL_SPEC
            val verifyEnabled = parseBoolean(document["verifyEnabled"]) ?: false

            document.putIfAbsent("changeIntent", SpecChangeIntent.FULL.name)
            document.putIfAbsent("template", template.name)
            document.putIfAbsent("verifyEnabled", verifyEnabled)

            if (document["currentStage"] == null) {
                val currentPhase = parseEnum(document["currentPhase"], SpecPhase.entries)
                if (currentPhase != null) {
                    document["currentStage"] = inferLegacyCurrentStage(
                        template = template,
                        currentPhase = currentPhase,
                        verifyEnabled = verifyEnabled,
                    ).name
                }
            }

            document["schemaVersion"] = CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION
        },
    )

    fun upgradeProjectConfig(document: Map<String, Any?>): SpecSchemaUpgradeResult {
        return upgrade(
            document = document,
            compatibility = projectConfigCompatibility,
            hooks = projectConfigHooks,
            context = NoContext,
        )
    }

    fun upgradeWorkflowMetadata(
        workflowId: String,
        document: Map<String, Any?>,
    ): SpecSchemaUpgradeResult {
        return upgrade(
            document = document,
            compatibility = workflowMetadataCompatibility,
            hooks = workflowMetadataHooks,
            context = workflowId,
        )
    }

    fun inferLegacyCurrentStage(
        template: WorkflowTemplate,
        currentPhase: SpecPhase,
        verifyEnabled: Boolean,
    ): StageId {
        val activeStages = WorkflowTemplates.definitionOf(template)
            .activeStages(verifyEnabled = verifyEnabled)
            .filterNot { stageId ->
                stageId == StageId.VERIFY || stageId == StageId.ARCHIVE
            }
        val preferredStages = when (currentPhase) {
            SpecPhase.SPECIFY -> listOf(StageId.REQUIREMENTS)
            SpecPhase.DESIGN -> listOf(StageId.DESIGN)
            SpecPhase.IMPLEMENT -> listOf(StageId.TASKS, StageId.IMPLEMENT)
        }

        return preferredStages.firstOrNull(activeStages::contains)
            ?: activeStages.firstOrNull()
            ?: currentPhase.toStageId()
    }

    fun buildLegacyStageTrail(
        template: WorkflowTemplate,
        currentPhase: SpecPhase,
        verifyEnabled: Boolean,
    ): List<StageId> {
        val activeStages = WorkflowTemplates.definitionOf(template)
            .activeStages(verifyEnabled = verifyEnabled)
            .filterNot { stageId ->
                stageId == StageId.VERIFY || stageId == StageId.ARCHIVE
            }
        val currentStage = inferLegacyCurrentStage(
            template = template,
            currentPhase = currentPhase,
            verifyEnabled = verifyEnabled,
        )
        if (activeStages.isEmpty()) {
            return listOf(currentStage)
        }
        val currentIndex = activeStages.indexOf(currentStage)
        return if (currentIndex >= 0) {
            activeStages.take(currentIndex + 1)
        } else {
            listOf(currentStage)
        }
    }

    private fun <C> upgrade(
        document: Map<String, Any?>,
        compatibility: SchemaCompatibility,
        hooks: List<SchemaUpgradeHook<C>>,
        context: C,
    ): SpecSchemaUpgradeResult {
        val sourceVersion = detectVersion(
            raw = document["schemaVersion"],
            compatibility = compatibility,
        )
        val migrated = LinkedHashMap(document)
        var version = sourceVersion
        val appliedSteps = mutableListOf<SpecSchemaUpgradeStep>()

        while (version < compatibility.currentVersion) {
            val hook = hooks.firstOrNull { it.fromVersion == version }
                ?: throw IllegalArgumentException(
                    "No upgrade hook registered for ${compatibility.documentType} schemaVersion $version.",
                )
            hook.upgrade(migrated, context)
            version = hook.toVersion
            migrated["schemaVersion"] = version
            appliedSteps += hook.toStep()
        }

        return SpecSchemaUpgradeResult(
            document = migrated,
            sourceVersion = sourceVersion,
            targetVersion = version,
            appliedSteps = appliedSteps,
        )
    }

    private fun detectVersion(
        raw: Any?,
        compatibility: SchemaCompatibility,
    ): Int {
        val version = parseSchemaVersion(raw)
            ?: compatibility.minimumSupportedVersion
        require(version >= compatibility.minimumSupportedVersion) {
            "Unsupported ${compatibility.documentType} schemaVersion: $version " +
                "(supported: ${compatibility.minimumSupportedVersion}..${compatibility.currentVersion})."
        }
        require(version <= compatibility.currentVersion) {
            "Unsupported ${compatibility.documentType} schemaVersion: $version " +
                "(supported: ${compatibility.minimumSupportedVersion}..${compatibility.currentVersion})."
        }
        return version
    }

    private fun parseSchemaVersion(raw: Any?): Int? {
        if (raw == null) {
            return null
        }
        val parsed = when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
                ?: throw IllegalArgumentException("schemaVersion must be an integer.")
            else -> throw IllegalArgumentException("schemaVersion must be an integer.")
        }
        require(parsed >= LEGACY_SCHEMA_VERSION) {
            "schemaVersion must be greater than or equal to $LEGACY_SCHEMA_VERSION."
        }
        return parsed
    }

    private fun parseBoolean(raw: Any?): Boolean? {
        return when (raw) {
            null -> null
            is Boolean -> raw
            is Number -> when (raw.toInt()) {
                1 -> true
                0 -> false
                else -> null
            }

            is String -> when (raw.trim().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> null
            }

            else -> null
        }
    }

    private fun <E : Enum<E>> parseEnum(
        raw: Any?,
        candidates: Iterable<E>,
    ): E? {
        val normalized = raw?.toString()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return candidates.firstOrNull { candidate ->
            candidate.name.equals(normalized, ignoreCase = true)
        }
    }

    private const val LEGACY_SCHEMA_VERSION: Int = 0
    const val CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION: Int = 1
    const val MIN_SUPPORTED_WORKFLOW_METADATA_SCHEMA_VERSION: Int = LEGACY_SCHEMA_VERSION
}
