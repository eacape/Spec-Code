package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecArtifactSourceCitationWritebackTest {

    @Test
    fun `apply should append sources section to requirements artifact when missing`() {
        val result = SpecArtifactSourceCitationWriteback.apply(
            phase = SpecPhase.SPECIFY,
            existingContent = """
                ## Functional Requirements
                - Keep generated requirements traceable.

                ## Acceptance Criteria
                - [ ] Source references are visible.
            """.trimIndent(),
            citations = listOf(
                ArtifactSourceCitation(
                    sourceId = "SRC-001",
                    storedRelativePath = "sources/SRC-001-client-prd.md",
                    note = "Client PRD.md",
                ),
                ArtifactSourceCitation(
                    sourceId = "SRC-002",
                    storedRelativePath = "sources/SRC-002-sketch.png",
                    note = "sketch.png",
                ),
            ),
        )
        val content = result?.content.orEmpty()

        assertEquals("Sources", result?.sectionTitle)
        assertEquals(2, result?.insertedCount)
        assertTrue(content.contains("## Sources"))
        assertTrue(content.contains("- `SRC-001` `sources/SRC-001-client-prd.md` - Client PRD.md"))
        assertTrue(content.contains("- `SRC-002` `sources/SRC-002-sketch.png` - sketch.png"))
        assertTrue(
            content.indexOf("## Acceptance Criteria") < content.indexOf("## Sources"),
        )
    }

    @Test
    fun `apply should preserve existing references section and append only missing citations`() {
        val result = SpecArtifactSourceCitationWriteback.apply(
            phase = SpecPhase.DESIGN,
            existingContent = """
                ## Architecture Design
                - Preserve stable writeback ordering.

                ## References
                - `SRC-001` `sources/SRC-001-client-prd.md` - Analyst note
            """.trimIndent(),
            citations = listOf(
                ArtifactSourceCitation(
                    sourceId = "SRC-001",
                    storedRelativePath = "sources/SRC-001-client-prd.md",
                    note = "Client PRD.md",
                ),
                ArtifactSourceCitation(
                    sourceId = "SRC-002",
                    storedRelativePath = "sources/SRC-002-architecture-notes.txt",
                    locator = "section 3.2",
                    note = "Architecture Notes.txt",
                ),
            ),
        )

        val content = result?.content.orEmpty()

        assertEquals("References", result?.sectionTitle)
        assertEquals(1, result?.insertedCount)
        assertEquals(1, Regex("""(?m)^- `SRC-001` """).findAll(content).count())
        assertTrue(content.contains("- `SRC-002` `sources/SRC-002-architecture-notes.txt` - section 3.2 | Architecture Notes.txt"))
    }

    @Test
    fun `extract should read citations from sources and references sections`() {
        val citations = SpecArtifactSourceCitationWriteback.extract(
            """
                ## Functional Requirements
                - Keep source traceability.

                ## Sources
                - `SRC-001` `sources/SRC-001-client-prd.md` - Client PRD.md

                ## References
                - `SRC-002` `sources/SRC-002-architecture-notes.txt` - section 3.2 | Architecture Notes.txt
            """.trimIndent(),
        )

        assertEquals(2, citations.size)
        assertEquals("SRC-001", citations[0].sourceId)
        assertEquals("sources/SRC-001-client-prd.md", citations[0].storedRelativePath)
        assertEquals("Client PRD.md", citations[0].note)
        assertEquals("SRC-002", citations[1].sourceId)
        assertEquals("sources/SRC-002-architecture-notes.txt", citations[1].storedRelativePath)
        assertEquals("section 3.2 | Architecture Notes.txt", citations[1].note)
    }
}
