package com.eacape.speccodingplugin.spec

/**
 * Spec 文档验证器
 * 负责验证各阶段文档的完整性和正确性
 */
object SpecValidator {
    private data class SectionRequirement(
        val displayName: String,
        val markers: List<String>,
    )

    /**
     * 验证 Spec 文档
     */
    fun validate(document: SpecDocument): ValidationResult {
        return when (document.phase) {
            SpecPhase.SPECIFY -> validateRequirements(document)
            SpecPhase.DESIGN -> validateDesign(document)
            SpecPhase.IMPLEMENT -> validateImplementation(document)
        }
    }

    /**
     * 验证需求文档（Specify 阶段）
     */
    private fun validateRequirements(document: SpecDocument): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        val content = document.content

        // 检查必需章节（兼容标题式与条目式输出）
        val requiredSections = listOf(
            SectionRequirement(
                displayName = "功能需求 (Functional Requirements)",
                markers = listOf("## 功能需求", "功能需求", "Functional Requirements"),
            ),
            SectionRequirement(
                displayName = "非功能需求 (Non-Functional Requirements)",
                markers = listOf("## 非功能需求", "非功能需求", "Non-Functional Requirements"),
            ),
            SectionRequirement(
                displayName = "用户故事 (User Stories)",
                markers = listOf("## 用户故事", "用户故事", "User Stories"),
            ),
        )

        requiredSections.forEach { requirement ->
            if (!containsAnyMarker(content, requirement.markers)) {
                errors.add("缺少必需章节: ${requirement.displayName}")
            }
        }

        // 检查内容长度
        if (content.length < 200) {
            warnings.add("需求文档内容过短（${content.length} 字符），建议补充更多细节")
        }

        // 检查是否包含用户故事
        if (!content.contains("作为") && !content.contains("As a")) {
            warnings.add("建议添加用户故事（As a... I want... So that...）")
        }

        // 检查是否包含验收标准
        if (!content.contains("验收标准") && !content.contains("Acceptance Criteria")) {
            suggestions.add("建议添加验收标准以明确需求边界")
        }

        // 检查是否有模糊表述
        val vagueTerms = listOf("可能", "也许", "大概", "应该会", "maybe", "probably")
        vagueTerms.forEach { term ->
            if (content.contains(term, ignoreCase = true)) {
                warnings.add("发现模糊表述: '$term'，建议使用更明确的语言")
            }
        }

        errors.addAll(requirementsDraftErrors(content))

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            suggestions = suggestions
        )
    }

    /**
     * 验证设计文档（Design 阶段）
     */
    private fun validateDesign(document: SpecDocument): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        val content = document.content

        // 检查必需章节（兼容标题式与条目式输出）
        val requiredSections = listOf(
            SectionRequirement(
                displayName = "架构设计 (Architecture Design)",
                markers = listOf("## 架构设计", "架构设计", "系统架构", "Architecture Design", "Architecture"),
            ),
            SectionRequirement(
                displayName = "技术选型 (Technology Stack)",
                markers = listOf("## 技术选型", "技术选型", "技术方案", "Technology Stack"),
            ),
            SectionRequirement(
                displayName = "数据模型 (Data Model)",
                markers = listOf("## 数据模型", "数据模型", "实体模型", "Data Model"),
            ),
        )

        requiredSections.forEach { requirement ->
            if (!containsAnyMarker(content, requirement.markers)) {
                errors.add("缺少必需章节: ${requirement.displayName}")
            }
        }

        // 检查内容长度
        if (content.length < 300) {
            warnings.add("设计文档内容过短（${content.length} 字符），建议补充更多技术细节")
        }

        // 检查是否包含架构图
        if (!content.contains("```") && !content.contains("图")) {
            suggestions.add("建议添加架构图或流程图以更清晰地展示设计")
        }

        // 检查是否包含 API 设计
        if (!content.contains("API") && !content.contains("接口")) {
            suggestions.add("建议添加 API 设计或接口定义")
        }

        // 检查是否考虑了非功能需求
        val nfrKeywords = listOf("性能", "安全", "可扩展", "可维护", "performance", "security", "scalability")
        val hasNFR = nfrKeywords.any { content.contains(it, ignoreCase = true) }
        if (!hasNFR) {
            warnings.add("建议考虑非功能需求（性能、安全、可扩展性等）")
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            suggestions = suggestions
        )
    }

    /**
     * 验证实现文档（Implement 阶段）
     */
    private fun validateImplementation(document: SpecDocument): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()

        val content = document.content

        // 检查必需章节（兼容标题式与条目式输出）
        val requiredSections = listOf(
            SectionRequirement(
                displayName = "任务列表 (Task List)",
                markers = listOf("## 任务列表", "任务列表", "任务拆解", "Task List"),
            ),
            SectionRequirement(
                displayName = "实现步骤 (Implementation Steps)",
                markers = listOf("## 实现步骤", "实现步骤", "开发步骤", "Implementation Steps"),
            ),
        )

        requiredSections.forEach { requirement ->
            if (!containsAnyMarker(content, requirement.markers)) {
                errors.add("缺少必需章节: ${requirement.displayName}")
            }
        }

        // 检查是否包含任务
        val taskPatterns = listOf(
            Regex("- \\[ \\]"),  // Markdown checkbox
            Regex("\\d+\\."),    // Numbered list
            Regex("Task \\d+", RegexOption.IGNORE_CASE)
        )

        val hasTasks = taskPatterns.any { it.containsMatchIn(content) }
        if (!hasTasks) {
            errors.add("未找到任务列表，请添加具体的实现任务")
        }

        // 检查任务数量
        val taskCount = Regex("- \\[ \\]").findAll(content).count()
        if (taskCount < 3) {
            warnings.add("任务数量较少（$taskCount 个），建议将任务拆分得更细")
        }

        // 检查是否包含测试计划
        if (!content.contains("测试") && !content.contains("test", ignoreCase = true)) {
            warnings.add("建议添加测试计划或测试用例")
        }

        // 检查是否包含时间估算
        if (!content.contains("时间") && !content.contains("工时") && !content.contains("hour", ignoreCase = true)) {
            suggestions.add("建议为每个任务添加时间估算")
        }

        // 检查是否包含优先级
        if (!content.contains("优先级") && !content.contains("priority", ignoreCase = true)) {
            suggestions.add("建议为任务标记优先级（P0/P1/P2）")
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            suggestions = suggestions
        )
    }

    /**
     * 验证阶段转换
     * 检查是否可以从当前阶段进入下一阶段
     */
    fun validatePhaseTransition(
        currentPhase: SpecPhase,
        currentDocument: SpecDocument?,
        nextPhase: SpecPhase
    ): ValidationResult {
        val errors = mutableListOf<String>()

        // 检查阶段顺序
        if (currentPhase.next() != nextPhase) {
            errors.add("无效的阶段转换: ${currentPhase.displayName} -> ${nextPhase.displayName}")
        }

        // 检查当前阶段文档是否存在
        if (currentDocument == null) {
            errors.add("当前阶段（${currentPhase.displayName}）没有文档，无法进入下一阶段")
        }

        // 检查当前阶段文档是否验证通过
        currentDocument?.validationResult?.let { validation ->
            if (!validation.valid) {
                errors.add("当前阶段文档验证未通过，请先修复错误")
                errors.addAll(validation.errors)
            }
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors
        )
    }

    private fun requirementsDraftErrors(content: String): List<String> {
        return requirementsDraftIssueKinds(content).map(RequirementsDraftIssueKind::violationMessage)
    }

    internal fun requirementsDraftIssueKinds(content: String): List<RequirementsDraftIssueKind> {
        return RequirementsDraftIssueKind.detect(content)
    }

    private fun containsAnyMarker(content: String, markers: List<String>): Boolean {
        return markers.any { marker -> content.contains(marker, ignoreCase = true) }
    }

}

enum class RequirementsDraftIssueKind(
    val violationMessage: String,
) {
    TODO_PLACEHOLDERS(
        violationMessage = "requirements.md still contains TODO placeholders. Replace them with concrete requirements before continuing.",
    ),
    USER_STORY_TEMPLATE(
        violationMessage = "requirements.md still uses the user story template placeholders <role>/<capability>/<benefit>.",
    ),
    ;

    companion object {
        private val TODO_LINE_PATTERN = Regex("""(?im)^\s*[-*]?\s*(?:\[[ xX]\]\s*)?TODO\s*:""")
        private val USER_STORY_TEMPLATE_PATTERN = Regex("""(?i)<role>|<capability>|<benefit>""")

        fun detect(content: String): List<RequirementsDraftIssueKind> {
            val normalized = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            return buildList {
                if (TODO_LINE_PATTERN.containsMatchIn(normalized)) {
                    add(TODO_PLACEHOLDERS)
                }
                if (USER_STORY_TEMPLATE_PATTERN.containsMatchIn(normalized)) {
                    add(USER_STORY_TEMPLATE)
                }
            }
        }

        fun fromViolationMessage(message: String): RequirementsDraftIssueKind? {
            return entries.firstOrNull { issue -> issue.violationMessage == message }
        }

        internal fun containsTodoPlaceholder(line: String): Boolean = TODO_LINE_PATTERN.containsMatchIn(line)

        internal fun containsUserStoryTemplate(line: String): Boolean = USER_STORY_TEMPLATE_PATTERN.containsMatchIn(line)
    }
}
