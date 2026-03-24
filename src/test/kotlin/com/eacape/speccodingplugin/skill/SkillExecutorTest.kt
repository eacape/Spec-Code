package com.eacape.speccodingplugin.skill

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillExecutorTest {

    private lateinit var project: Project
    private lateinit var registry: SkillRegistry
    private lateinit var executor: SkillExecutor

    @BeforeEach
    fun setUp() {
        project = mockk()
        registry = mockk()
        every { project.getService(SkillRegistry::class.java) } returns registry

        executor = SkillExecutor(project)
    }

    @Test
    fun `parseSlashCommand supports hyphen command and key-value args`() {
        val parsed = executor.parseSlashCommand("/security-scan severity=high scope=auth")

        assertNotNull(parsed)
        assertEquals("security-scan", parsed?.first)
        assertEquals("high", parsed?.second?.get("severity"))
        assertEquals("auth", parsed?.second?.get("scope"))
    }

    @Test
    fun `parseSlashCommand returns null for non-slash text`() {
        val parsed = executor.parseSlashCommand("review this code")
        assertNull(parsed)
    }

    @Test
    fun `executeFromCommand returns failure for unknown command`() {
        every { registry.getSkillByCommand("unknown") } returns null

        val result = runBlocking {
            executor.executeFromCommand("/unknown", SkillContext())
        }

        assertTrue(result is SkillExecutionResult.Failure)
        val failure = result as SkillExecutionResult.Failure
        assertEquals("Unknown skill command: /unknown", failure.error)
    }

    @Test
    fun `executeFromCommand renders tdd template with context and arguments`() {
        val skill = Skill(
            id = "tdd-workflow",
            name = "TDD Workflow",
            description = "Drive implementation with red-green-refactor cycle",
            slashCommand = "tdd",
            promptTemplate = "Implement with TDD: {{selected_code}} using {{framework}}",
            contextRequirements = listOf(
                ContextRequirement.SELECTED_CODE,
                ContextRequirement.TEST_FRAMEWORK_CONFIG,
            ),
            tags = listOf("built-in", "testing", "tdd"),
        )
        every { registry.getSkillByCommand("tdd") } returns skill

        val result = runBlocking {
            executor.executeFromCommand(
                command = "/tdd",
                context = SkillContext(selectedCode = "fun sum(a:Int,b:Int)=a+b"),
                arguments = mapOf("framework" to "junit5"),
            )
        }

        assertTrue(result is SkillExecutionResult.Success)
        val success = result as SkillExecutionResult.Success
        assertTrue(success.output.contains("fun sum(a:Int,b:Int)=a+b"))
        assertTrue(success.output.contains("junit5"))
        assertEquals("tdd-workflow", success.metadata["skill_id"])
    }

    @Test
    fun `executeFromCommand parses inline args from command`() {
        val skill = Skill(
            id = "tdd-workflow",
            name = "TDD Workflow",
            description = "Drive implementation with red-green-refactor cycle",
            slashCommand = "tdd",
            promptTemplate = "Implement with {{framework}} for {{selected_code}}",
            contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
            tags = listOf("built-in", "testing", "tdd"),
        )
        every { registry.getSkillByCommand("tdd") } returns skill

        val result = runBlocking {
            executor.executeFromCommand(
                command = "/tdd framework=junit5",
                context = SkillContext(selectedCode = "fun sum(a:Int,b:Int)=a+b"),
            )
        }

        assertTrue(result is SkillExecutionResult.Success)
        val success = result as SkillExecutionResult.Success
        assertTrue(success.output.contains("junit5"))
        assertTrue(success.output.contains("fun sum(a:Int,b:Int)=a+b"))
    }

    @Test
    fun `executeFromCommand fails when required selected code is missing`() {
        val skill = Skill(
            id = "security-scan",
            name = "Security Scan",
            description = "Scan code for vulnerabilities with fix suggestions",
            slashCommand = "security-scan",
            promptTemplate = "scan {{selected_code}}",
            contextRequirements = listOf(ContextRequirement.SELECTED_CODE),
            tags = listOf("built-in", "security"),
        )
        every { registry.getSkillByCommand("security-scan") } returns skill

        val result = runBlocking {
            executor.executeFromCommand("/security-scan", SkillContext())
        }

        assertTrue(result is SkillExecutionResult.Failure)
        val failure = result as SkillExecutionResult.Failure
        assertTrue(failure.error.contains("requires selected code"))
    }

    @Test
    fun `parsePipelineStages supports pipe and arrow syntax`() {
        val pipe = executor.parsePipelineStages("/pipeline /review | /refactor | test")
        assertEquals(listOf("/review", "/refactor", "/test"), pipe)

        val arrow = executor.parsePipelineStages("/pipeline review -> /test")
        assertEquals(listOf("/review", "/test"), arrow)

        val invalid = executor.parsePipelineStages("/pipeline   ")
        assertNull(invalid)
    }

    @Test
    fun `executePipelineFromCommand should chain outputs across steps`() {
        val review = Skill(
            id = "review",
            name = "Review",
            description = "review",
            slashCommand = "review",
            promptTemplate = "R({{selected_code}})",
        )
        val refactor = Skill(
            id = "refactor",
            name = "Refactor",
            description = "refactor",
            slashCommand = "refactor",
            promptTemplate = "F({{selected_code}})",
        )
        every { registry.getSkillByCommand("review") } returns review
        every { registry.getSkillByCommand("refactor") } returns refactor

        val result = runBlocking {
            executor.executePipelineFromCommand(
                "/pipeline /review | /refactor",
                SkillContext(selectedCode = "CODE")
            )
        }

        assertTrue(result is SkillExecutionResult.Success)
        val success = result as SkillExecutionResult.Success
        assertTrue(success.output.contains("[Step 1] /review"))
        assertTrue(success.output.contains("R(CODE)"))
        assertTrue(success.output.contains("[Step 2] /refactor"))
        assertTrue(success.output.contains("F(R(CODE))"))
        assertEquals("true", success.metadata["pipeline"])
        assertEquals("2", success.metadata["pipeline_steps"])
    }

    @Test
    fun `executePipelineFromCommand should fail on unknown stage`() {
        every { registry.getSkillByCommand("review") } returns Skill(
            id = "review",
            name = "Review",
            description = "review",
            slashCommand = "review",
            promptTemplate = "R({{selected_code}})",
        )
        every { registry.getSkillByCommand("unknown") } returns null

        val result = runBlocking {
            executor.executePipelineFromCommand(
                "/pipeline /review | /unknown",
                SkillContext(selectedCode = "CODE")
            )
        }

        assertTrue(result is SkillExecutionResult.Failure)
        val failure = result as SkillExecutionResult.Failure
        assertTrue(failure.error.contains("Pipeline failed at step 2"))
    }
}
