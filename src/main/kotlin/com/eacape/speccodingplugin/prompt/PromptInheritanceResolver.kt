package com.eacape.speccodingplugin.prompt

import com.intellij.openapi.project.Project

/**
 * Prompt 继承解析器
 * 用于可视化和调试三层继承关系
 */
object PromptInheritanceResolver {

    /**
     * 解析提示词的完整继承链
     */
    fun resolveInheritanceChain(
        project: Project,
        promptId: String? = null
    ): PromptInheritanceChain {
        val promptManager = PromptManager.getInstance(project)
        val globalManager = GlobalPromptManager.getInstance()

        // 获取会话级覆盖
        val sessionTemplate = promptManager.getSessionOverride()

        // 获取活跃的提示词 ID
        val activeId = promptId ?: promptManager.getActivePromptId()

        // 查找项目级模板
        val projectTemplates = promptManager.listPromptTemplates()
        val projectTemplate = projectTemplates.firstOrNull { it.id == activeId && it.scope == PromptScope.PROJECT }

        // 查找全局级模板
        val globalTemplate = globalManager.getTemplate(activeId)

        return PromptInheritanceChain(
            sessionLevel = sessionTemplate,
            projectLevel = projectTemplate,
            globalLevel = globalTemplate,
            activeId = activeId
        )
    }

    /**
     * 获取最终生效的提示词模板（应用继承规则）
     */
    fun resolveEffectiveTemplate(
        project: Project,
        promptId: String? = null
    ): PromptTemplate {
        val chain = resolveInheritanceChain(project, promptId)

        // 优先级：会话级 > 项目级 > 全局级
        return chain.sessionLevel
            ?: chain.projectLevel
            ?: chain.globalLevel
            ?: getDefaultTemplate()
    }

    /**
     * 合并所有层级的变量（应用继承规则）
     */
    fun resolveMergedVariables(
        project: Project,
        promptId: String? = null,
        runtimeVariables: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val chain = resolveInheritanceChain(project, promptId)
        val promptManager = PromptManager.getInstance(project)

        // 变量优先级：运行时 > 会话级 > 项目级 > 全局级 > 系统级
        val systemVariables = mapOf(
            "user_home" to (System.getProperty("user.home") ?: ""),
            "os_name" to (System.getProperty("os.name") ?: ""),
            "project_name" to project.name,
            "project_path" to (project.basePath ?: "")
        )

        val globalVariables = chain.globalLevel?.variables ?: emptyMap()
        val projectVariables = chain.projectLevel?.variables ?: emptyMap()
        val sessionVariables = chain.sessionLevel?.variables ?: emptyMap()

        return systemVariables + globalVariables + projectVariables + sessionVariables + runtimeVariables
    }

    /**
     * 获取默认模板
     */
    private fun getDefaultTemplate(): PromptTemplate {
        return PromptTemplate(
            id = "fallback",
            name = "Fallback Assistant",
            content = "You are a helpful AI coding assistant.",
            scope = PromptScope.GLOBAL,
            tags = listOf("fallback")
        )
    }
}

/**
 * Prompt 继承链数据模型
 */
data class PromptInheritanceChain(
    val sessionLevel: PromptTemplate?,
    val projectLevel: PromptTemplate?,
    val globalLevel: PromptTemplate?,
    val activeId: String
) {
    /**
     * 获取生效的层级
     */
    fun getEffectiveLevel(): PromptScope {
        return when {
            sessionLevel != null -> PromptScope.SESSION
            projectLevel != null -> PromptScope.PROJECT
            globalLevel != null -> PromptScope.GLOBAL
            else -> PromptScope.PROJECT
        }
    }

    /**
     * 获取继承路径描述
     */
    fun getInheritancePath(): String {
        val levels = mutableListOf<String>()
        if (globalLevel != null) levels.add("Global: ${globalLevel.name}")
        if (projectLevel != null) levels.add("Project: ${projectLevel.name}")
        if (sessionLevel != null) levels.add("Session: ${sessionLevel.name}")

        return if (levels.isEmpty()) {
            "No template found"
        } else {
            levels.joinToString(" → ")
        }
    }

    /**
     * 检查是否有覆盖
     */
    fun hasOverride(): Boolean {
        return sessionLevel != null || projectLevel != null
    }

    /**
     * 获取所有层级的模板（从低到高）
     */
    fun getAllLevels(): List<Pair<PromptScope, PromptTemplate>> {
        val result = mutableListOf<Pair<PromptScope, PromptTemplate>>()
        globalLevel?.let { result.add(PromptScope.GLOBAL to it) }
        projectLevel?.let { result.add(PromptScope.PROJECT to it) }
        sessionLevel?.let { result.add(PromptScope.SESSION to it) }
        return result
    }
}
