package com.eacape.speccodingplugin.spec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecClarificationWritebackTest {

    @Test
    fun `apply should write checklist clarification into requirements artifact without raw qa markers`() {
        val result = SpecClarificationWriteback.apply(
            phase = SpecPhase.SPECIFY,
            existingContent = """
                ## Functional Requirements
                - Existing requirement

                ## User Stories
                - Existing user story
            """.trimIndent(),
            payload = ConfirmedClarificationPayload(
                confirmedContext = """
                    **Confirmed Clarification Points**
                    - Do we need multi-region disaster recovery?
                      - Detail: At least two sites and three centers, RPO < 5s, RTO < 30s

                    **Not Applicable Clarification Points**
                    - Do we need SSO integration?
                """.trimIndent(),
                questionsMarkdown = "1. Do we need multi-region disaster recovery?\n2. Do we need SSO integration?",
                structuredQuestions = listOf(
                    "Do we need multi-region disaster recovery?",
                    "Do we need SSO integration?",
                ),
                clarificationRound = 2,
            ),
            atMillis = 0L,
        )

        assertEquals("Clarifications", result?.sectionTitle)
        assertEquals(1, result?.confirmedCount)
        assertEquals(1, result?.notApplicableCount)
        assertTrue(result?.content.orEmpty().contains("## Clarifications"))
        assertTrue(result?.content.orEmpty().contains("### 1970-01-01T00:00:00Z"))
        assertTrue(
            result?.content.orEmpty().contains(
                "- Do we need multi-region disaster recovery: At least two sites and three centers, RPO < 5s, RTO < 30s",
            ),
        )
        assertTrue(
            result?.content.orEmpty().contains(
                "- Do we need SSO integration: Not applicable for this change.",
            ),
        )
        assertFalse(result?.content.orEmpty().contains("**Confirmed Clarification Points**"))
        assertFalse(result?.content.orEmpty().contains("Detail:"))
    }

    @Test
    fun `apply should append narrative clarification into existing decisions section`() {
        val result = SpecClarificationWriteback.apply(
            phase = SpecPhase.DESIGN,
            existingContent = """
                ## Architecture
                - Existing design

                ## Decisions

                ### 2026-03-12T10:00:00Z
                - Keep Kotlin on the JVM.
            """.trimIndent(),
            payload = ConfirmedClarificationPayload(
                confirmedContext = """
                    Prefer PostgreSQL for metadata storage.

                    Use Redis only as a cache, not as the source of truth.
                """.trimIndent(),
            ),
            atMillis = 1_710_000_000_000,
        )

        assertEquals("Decisions", result?.sectionTitle)
        assertTrue(result?.content.orEmpty().contains("- Keep Kotlin on the JVM."))
        assertTrue(result?.content.orEmpty().contains("- Prefer PostgreSQL for metadata storage."))
        assertTrue(
            result?.content.orEmpty().contains(
                "- Use Redis only as a cache, not as the source of truth.",
            ),
        )
    }
}
