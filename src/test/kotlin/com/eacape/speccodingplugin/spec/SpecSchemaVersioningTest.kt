package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecSchemaVersioningTest {

    @Test
    fun `upgradeProjectConfig should stamp legacy config with current schema version`() {
        val upgraded = SpecSchemaVersioning.upgradeProjectConfig(
            linkedMapOf(
                "defaultTemplate" to "QUICK_TASK",
            ),
        )

        assertEquals(0, upgraded.sourceVersion)
        assertEquals(SpecProjectConfig.CURRENT_SCHEMA_VERSION, upgraded.targetVersion)
        assertTrue(upgraded.upgraded)
        assertEquals(
            SpecProjectConfig.CURRENT_SCHEMA_VERSION,
            upgraded.document["schemaVersion"],
        )
    }

    @Test
    fun `upgradeWorkflowMetadata should infer tasks stage for quick task legacy workflows`() {
        val upgraded = SpecSchemaVersioning.upgradeWorkflowMetadata(
            workflowId = "wf-legacy",
            document = linkedMapOf(
                "template" to "QUICK_TASK",
                "currentPhase" to "IMPLEMENT",
                "verifyEnabled" to true,
            ),
        )

        assertEquals(0, upgraded.sourceVersion)
        assertEquals(
            SpecSchemaVersioning.CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION,
            upgraded.targetVersion,
        )
        assertEquals(StageId.TASKS.name, upgraded.document["currentStage"])
        assertEquals(
            SpecSchemaVersioning.CURRENT_WORKFLOW_METADATA_SCHEMA_VERSION,
            upgraded.document["schemaVersion"],
        )
    }

    @Test
    fun `upgradeProjectConfig should reject newer schema versions`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SpecSchemaVersioning.upgradeProjectConfig(
                linkedMapOf("schemaVersion" to 99),
            )
        }

        assertTrue(error.message?.contains("Unsupported config schemaVersion: 99") == true)
    }
}
