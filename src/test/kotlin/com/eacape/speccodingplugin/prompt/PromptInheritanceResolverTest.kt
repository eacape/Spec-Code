package com.eacape.speccodingplugin.prompt

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PromptInheritanceResolverTest : BasePlatformTestCase() {

    private lateinit var promptManager: PromptManager
    private lateinit var globalManager: GlobalPromptManager

    override fun setUp() {
        super.setUp()
        promptManager = PromptManager.getInstance(project)
        globalManager = GlobalPromptManager.getInstance()
    }

    fun `test resolve inheritance chain with project template`() {
        // Create project-level template
        val projectTemplate = PromptTemplate(
            id = "test-project",
            name = "Test Project Template",
            content = "Project level content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)
        promptManager.setActivePrompt("test-project")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)

        assertNotNull(chain.projectLevel)
        assertEquals("test-project", chain.projectLevel?.id)
        assertEquals(PromptScope.PROJECT, chain.getEffectiveLevel())
    }

    fun `test resolve inheritance chain with session override`() {
        // Create session-level override
        val sessionTemplate = PromptTemplate(
            id = "test-session",
            name = "Test Session Template",
            content = "Session level content",
            scope = PromptScope.SESSION
        )
        promptManager.setSessionOverride(sessionTemplate)

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)

        assertNotNull(chain.sessionLevel)
        assertEquals("test-session", chain.sessionLevel?.id)
        assertEquals(PromptScope.SESSION, chain.getEffectiveLevel())
    }

    fun `test resolve inheritance chain with global template`() {
        // Create global-level template
        val globalTemplate = PromptTemplate(
            id = "test-global",
            name = "Test Global Template",
            content = "Global level content",
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)
        promptManager.setActivePrompt("test-global")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)

        assertNotNull(chain.globalLevel)
        assertEquals("test-global", chain.globalLevel?.id)
        assertEquals(PromptScope.GLOBAL, chain.getEffectiveLevel())
    }

    fun `test resolve inheritance chain with all levels`() {
        // Create templates at all levels
        val globalTemplate = PromptTemplate(
            id = "test-all",
            name = "Test Global",
            content = "Global content",
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)

        val projectTemplate = PromptTemplate(
            id = "test-all",
            name = "Test Project",
            content = "Project content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)

        val sessionTemplate = PromptTemplate(
            id = "test-all",
            name = "Test Session",
            content = "Session content",
            scope = PromptScope.SESSION
        )
        promptManager.setSessionOverride(sessionTemplate)

        promptManager.setActivePrompt("test-all")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)

        assertNotNull(chain.globalLevel)
        assertNotNull(chain.projectLevel)
        assertNotNull(chain.sessionLevel)
        assertEquals(PromptScope.SESSION, chain.getEffectiveLevel())
        assertTrue(chain.hasOverride())
    }

    fun `test resolve effective template prioritizes session over project`() {
        val projectTemplate = PromptTemplate(
            id = "test-priority",
            name = "Project Template",
            content = "Project content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)
        promptManager.setActivePrompt("test-priority")

        val sessionTemplate = PromptTemplate(
            id = "test-priority",
            name = "Session Template",
            content = "Session content",
            scope = PromptScope.SESSION
        )
        promptManager.setSessionOverride(sessionTemplate)

        val effective = PromptInheritanceResolver.resolveEffectiveTemplate(project)

        assertEquals("Session content", effective.content)
        assertEquals(PromptScope.SESSION, effective.scope)
    }

    fun `test resolve effective template prioritizes project over global`() {
        val globalTemplate = PromptTemplate(
            id = "test-priority2",
            name = "Global Template",
            content = "Global content",
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)

        val projectTemplate = PromptTemplate(
            id = "test-priority2",
            name = "Project Template",
            content = "Project content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)
        promptManager.setActivePrompt("test-priority2")

        val effective = PromptInheritanceResolver.resolveEffectiveTemplate(project)

        assertEquals("Project content", effective.content)
        assertEquals(PromptScope.PROJECT, effective.scope)
    }

    fun `test resolve merged variables with inheritance`() {
        val globalTemplate = PromptTemplate(
            id = "test-vars",
            name = "Global",
            content = "Content",
            variables = mapOf("global_var" to "global_value", "shared_var" to "global_shared"),
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)

        val projectTemplate = PromptTemplate(
            id = "test-vars",
            name = "Project",
            content = "Content",
            variables = mapOf("project_var" to "project_value", "shared_var" to "project_shared"),
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)
        promptManager.setActivePrompt("test-vars")

        val runtimeVars = mapOf("runtime_var" to "runtime_value", "shared_var" to "runtime_shared")

        val merged = PromptInheritanceResolver.resolveMergedVariables(
            project,
            "test-vars",
            runtimeVars
        )

        // Check all variables are present
        assertTrue(merged.containsKey("global_var"))
        assertTrue(merged.containsKey("project_var"))
        assertTrue(merged.containsKey("runtime_var"))

        // Check priority: runtime > project > global
        assertEquals("runtime_shared", merged["shared_var"])

        // Check system variables
        assertTrue(merged.containsKey("project_name"))
        assertTrue(merged.containsKey("project_path"))
    }

    fun `test inheritance path description`() {
        val globalTemplate = PromptTemplate(
            id = "test-path",
            name = "Global Template",
            content = "Content",
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)

        val projectTemplate = PromptTemplate(
            id = "test-path",
            name = "Project Template",
            content = "Content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)
        promptManager.setActivePrompt("test-path")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)
        val path = chain.getInheritancePath()

        assertTrue(path.contains("Global: Global Template"))
        assertTrue(path.contains("Project: Project Template"))
        assertTrue(path.contains("→"))
    }

    fun `test get all levels returns correct order`() {
        val globalTemplate = PromptTemplate(
            id = "test-order",
            name = "Global",
            content = "Content",
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)

        val projectTemplate = PromptTemplate(
            id = "test-order",
            name = "Project",
            content = "Content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)

        val sessionTemplate = PromptTemplate(
            id = "test-order",
            name = "Session",
            content = "Content",
            scope = PromptScope.SESSION
        )
        promptManager.setSessionOverride(sessionTemplate)

        promptManager.setActivePrompt("test-order")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)
        val levels = chain.getAllLevels()

        assertEquals(3, levels.size)
        assertEquals(PromptScope.GLOBAL, levels[0].first)
        assertEquals(PromptScope.PROJECT, levels[1].first)
        assertEquals(PromptScope.SESSION, levels[2].first)
    }

    fun `test has override returns true when session or project exists`() {
        val projectTemplate = PromptTemplate(
            id = "test-override",
            name = "Project",
            content = "Content",
            scope = PromptScope.PROJECT
        )
        promptManager.upsertTemplate(projectTemplate)
        promptManager.setActivePrompt("test-override")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)
        assertTrue(chain.hasOverride())
    }

    fun `test has override returns false when only global exists`() {
        val globalTemplate = PromptTemplate(
            id = "test-no-override",
            name = "Global",
            content = "Content",
            scope = PromptScope.GLOBAL
        )
        globalManager.upsertTemplate(globalTemplate)
        promptManager.setActivePrompt("test-no-override")

        val chain = PromptInheritanceResolver.resolveInheritanceChain(project)
        assertFalse(chain.hasOverride())
    }

    fun `test clear session override removes session level`() {
        val sessionTemplate = PromptTemplate(
            id = "test-clear",
            name = "Session",
            content = "Content",
            scope = PromptScope.SESSION
        )
        promptManager.setSessionOverride(sessionTemplate)

        var chain = PromptInheritanceResolver.resolveInheritanceChain(project)
        assertNotNull(chain.sessionLevel)

        promptManager.clearSessionOverride()

        chain = PromptInheritanceResolver.resolveInheritanceChain(project)
        assertNull(chain.sessionLevel)
    }

    override fun tearDown() {
        try {
            // Clean up
            promptManager.clearSessionOverride()
        } finally {
            super.tearDown()
        }
    }
}
