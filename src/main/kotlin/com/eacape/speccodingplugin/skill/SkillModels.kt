package com.eacape.speccodingplugin.skill

/**
 * 技能定义
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val slashCommand: String,
    val promptTemplate: String,
    val contextRequirements: List<ContextRequirement> = emptyList(),
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val scope: SkillScope? = null,
    val sourceType: SkillSourceType = SkillSourceType.BUILTIN,
    val sourcePath: String? = null,
)

enum class SkillScope {
    GLOBAL,
    PROJECT,
}

enum class SkillSourceType {
    BUILTIN,
    YAML,
    MARKDOWN,
}

data class SkillDiscoveryRoot(
    val scope: SkillScope,
    val label: String,
    val path: String,
    val exists: Boolean,
)

data class SkillDiscoverySnapshot(
    val skills: List<Skill>,
    val roots: List<SkillDiscoveryRoot>,
    val scannedAt: Long,
)

/**
 * 上下文要求
 */
enum class ContextRequirement {
    CURRENT_FILE,           // 当前文件内容
    SELECTED_CODE,          // 选中的代码
    PROJECT_STRUCTURE,      // 项目结构
    TEST_FRAMEWORK_CONFIG,  // 测试框架配置
    GIT_STATUS,             // Git 状态
}

/**
 * 技能执行请求
 */
data class SkillExecutionRequest(
    val skill: Skill,
    val arguments: Map<String, String> = emptyMap(),
    val context: SkillContext,
)

/**
 * 技能执行上下文
 */
data class SkillContext(
    val currentFile: String? = null,
    val selectedCode: String? = null,
    val projectStructure: String? = null,
    val additionalContext: Map<String, String> = emptyMap(),
)

/**
 * 技能执行结果
 */
sealed class SkillExecutionResult {
    data class Success(val output: String, val metadata: Map<String, String> = emptyMap()) : SkillExecutionResult()
    data class Failure(val error: String, val cause: Throwable? = null) : SkillExecutionResult()
}
