package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.WorkflowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpecCardMetadataCodecTest {

    @Test
    fun `encode and decode should preserve spec card metadata`() {
        val metadata = SpecCardMetadata(
            workflowId = "spec-123",
            phase = SpecPhase.DESIGN,
            status = WorkflowStatus.IN_PROGRESS,
            title = "Auth Module",
            revision = 1739430011223,
            sourceCommand = "/workflow generate optimize login flow",
        )

        val encoded = SpecCardMetadataCodec.encode(metadata)
        val decoded = SpecCardMetadataCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(metadata, decoded)
    }

    @Test
    fun `decode should normalize legacy spec command to workflow`() {
        val decoded = SpecCardMetadataCodec.decode(
            """
            {
              "format":"spec_card_v1",
              "spec_card":{
                "workflow_id":"spec-123",
                "phase":"DESIGN",
                "status":"IN_PROGRESS",
                "title":"Auth Module",
                "revision":1739430011223,
                "source_command":"/spec generate optimize login flow"
              }
            }
            """.trimIndent()
        )

        assertNotNull(decoded)
        assertEquals("/workflow generate optimize login flow", decoded?.sourceCommand)
    }

    @Test
    fun `decode should return null for unsupported format`() {
        val decoded = SpecCardMetadataCodec.decode("""{"format":"chat_trace_v1","trace_events":[]}""")
        assertNull(decoded)
    }

    @Test
    fun `decode should return null when required fields missing`() {
        val decoded = SpecCardMetadataCodec.decode(
            """
            {"format":"spec_card_v1","spec_card":{"workflow_id":"spec-1","phase":"DESIGN"}}
            """.trimIndent()
        )
        assertNull(decoded)
    }
}
