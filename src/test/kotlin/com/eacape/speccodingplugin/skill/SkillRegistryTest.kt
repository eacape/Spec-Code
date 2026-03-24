package com.eacape.speccodingplugin.skill

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillRegistryTest {

    private lateinit var project: Project
    private lateinit var registry: SkillRegistry

    @BeforeEach
    fun setUp() {
        project = mockk()
        every { project.basePath } returns null
        registry = SkillRegistry(project)
    }

    @Test
    fun `listSkills contains built-in security and tdd skills`() {
        val skills = registry.listSkills()
        val commands = skills.map { it.slashCommand }.toSet()

        assertTrue(commands.contains("review"))
        assertTrue(commands.contains("security-scan"))
        assertTrue(commands.contains("tdd"))
    }

    @Test
    fun `getSkillByCommand supports slash-prefix lookup`() {
        val skill = registry.getSkillByCommand("/security-scan")

        assertNotNull(skill)
        assertEquals("security-scan", skill?.slashCommand)
        assertEquals("Security Scan", skill?.name)
    }

    @Test
    fun `searchSkills can find tdd skill by tag`() {
        val results = registry.searchSkills("tdd")

        assertTrue(results.any { it.id == "tdd-workflow" })
    }
}

