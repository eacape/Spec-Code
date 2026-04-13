package com.eacape.speccodingplugin.spec

enum class DesignSectionId(
    val stableId: String,
    val validationDisplayName: String,
    val canonicalHeading: String,
    private val headingTitles: List<String>,
    val generationRequirementLine: String,
    val reviseFocusLabel: String,
    val draftPlaceholderLine: String,
    val templateBodyLines: List<String>,
) {
    ARCHITECTURE(
        stableId = "architecture-design",
        validationDisplayName = "架构设计 (Architecture Design)",
        canonicalHeading = "架构设计",
        headingTitles = listOf(
            "Architecture Design",
            "Architecture",
            "架构设计",
            "系统架构",
        ),
        generationRequirementLine = "在“架构设计”中说明核心模块、职责与关键数据流。",
        reviseFocusLabel = "architecture decisions",
        draftPlaceholderLine = "TODO: Describe the architecture and module boundaries.",
        templateBodyLines = listOf(
            "采用三层架构：",
            "- 表示层：Web UI",
            "- 业务层：业务逻辑处理",
            "- 数据层：数据持久化",
        ),
    ),
    TECHNOLOGY(
        stableId = "technology-choices",
        validationDisplayName = "技术选型 (Technology Choices / Technology Stack)",
        canonicalHeading = "技术选型",
        headingTitles = listOf(
            "Technology Choices",
            "Technology Stack",
            "技术选型",
            "技术方案",
        ),
        generationRequirementLine = "在“技术选型”中给出技术方案与取舍理由。",
        reviseFocusLabel = "technology choices",
        draftPlaceholderLine = "TODO: List selected technologies and rationale.",
        templateBodyLines = listOf(
            "- 后端：Kotlin + Spring Boot",
            "- 前端：React + TypeScript",
            "- 数据库：PostgreSQL",
        ),
    ),
    DATA_MODEL(
        stableId = "data-model",
        validationDisplayName = "数据模型 (Data Model)",
        canonicalHeading = "数据模型",
        headingTitles = listOf(
            "Data Model",
            "数据模型",
            "实体模型",
        ),
        generationRequirementLine = "在“数据模型”中描述核心实体、字段关系与约束。",
        reviseFocusLabel = "data model changes",
        draftPlaceholderLine = "TODO: Describe key entities and relationships.",
        templateBodyLines = listOf(
            "```kotlin",
            "data class User(",
            "    val id: String,",
            "    val email: String,",
            "    val passwordHash: String",
            ")",
            "```",
        ),
    ),
    API_DESIGN(
        stableId = "api-design",
        validationDisplayName = "接口设计 (API Design)",
        canonicalHeading = "接口设计",
        headingTitles = listOf(
            "API Design",
            "API 设计",
            "接口设计",
        ),
        generationRequirementLine = "在“接口设计”中说明关键接口、输入输出以及与现有工作流/服务的调用边界。",
        reviseFocusLabel = "API contracts",
        draftPlaceholderLine = "TODO: Describe interfaces and contract changes.",
        templateBodyLines = listOf(
            "### POST /api/auth/login",
            "请求：",
            "```json",
            "{",
            "  \"email\": \"user@example.com\",",
            "  \"password\": \"password123\"",
            "}",
            "```",
        ),
    ),
    NON_FUNCTIONAL(
        stableId = "non-functional-design",
        validationDisplayName = "非功能设计 (Non-Functional Design)",
        canonicalHeading = "非功能设计",
        headingTitles = listOf(
            "Non-Functional Design",
            "Non-Functional Requirements",
            "非功能设计",
            "非功能需求",
        ),
        generationRequirementLine = "在“非功能设计”中覆盖性能、安全、可靠性、可观测性与回滚约束。",
        reviseFocusLabel = "non-functional constraints",
        draftPlaceholderLine = "TODO: Capture performance, security, and operability choices.",
        templateBodyLines = listOf(
            "- 性能：关键工作流操作应在可接受时延内完成，并避免阻塞 IDE 主线程",
            "- 安全：敏感配置和执行上下文需要脱敏、审计并限制权限边界",
            "- 可观测性与回滚：关键步骤需要保留验证结果、错误诊断信息和回滚入口",
        ),
    ),
    ;

    fun matchesHeadingTitle(title: String): Boolean {
        val normalized = title.trim()
        return headingTitles.any { heading ->
            normalized.equals(heading, ignoreCase = true)
        }
    }

    val englishHeading: String
        get() = headingTitles.first()

    fun markdownHeading(): String = "## $canonicalHeading"

    fun englishMarkdownHeading(): String = "## $englishHeading"

    fun matchesContentSignal(text: String): Boolean {
        val normalized = text.trim()
        return headingTitles.any { heading ->
            normalized.contains(heading, ignoreCase = true)
        }
    }

    companion object {
        fun fromHeadingTitle(title: String): DesignSectionId? =
            entries.firstOrNull { section -> section.matchesHeadingTitle(title) }
    }
}

object DesignSectionSupport {

    data class HeadingMatch(
        val sectionId: DesignSectionId,
        val title: String,
        val startOffset: Int,
        val endOffsetExclusive: Int,
    )

    fun missingSections(content: String): List<DesignSectionId> {
        return DesignSectionId.entries.filterNot(presentSections(content)::contains)
    }

    fun hasRequiredSections(content: String): Boolean = missingSections(content).isEmpty()

    fun presentSections(content: String): Set<DesignSectionId> {
        return findLevelTwoHeadings(content)
            .map(HeadingMatch::sectionId)
            .toSet()
    }

    fun containsSectionSignal(text: String): Boolean {
        return DesignSectionId.entries.any { section -> section.matchesContentSignal(text) }
    }

    fun canonicalMarkdownHeadings(): List<String> {
        return DesignSectionId.entries.map(DesignSectionId::markdownHeading)
    }

    fun canonicalMarkdownHeadingSummary(): String = canonicalMarkdownHeadings().joinToString("、")

    fun generationRequirementLines(): List<String> {
        return DesignSectionId.entries.map(DesignSectionId::generationRequirementLine)
    }

    fun englishHeadingSummary(): String {
        val headings = DesignSectionId.entries.map(DesignSectionId::englishHeading)
        return when (headings.size) {
            0 -> ""
            1 -> headings.first()
            2 -> headings.joinToString(" and ")
            else -> headings.dropLast(1).joinToString(", ") + ", and " + headings.last()
        }
    }

    fun reviseFocusSummary(): String {
        val focuses = DesignSectionId.entries.map(DesignSectionId::reviseFocusLabel)
        return when (focuses.size) {
            0 -> ""
            1 -> focuses.first()
            2 -> focuses.joinToString(" and ")
            else -> focuses.dropLast(1).joinToString(", ") + ", and " + focuses.last()
        }
    }

    fun canonicalTemplateMarkdown(documentTitle: String = "# 设计文档"): String {
        return buildString {
            appendLine(documentTitle)
            appendLine()
            DesignSectionId.entries.forEachIndexed { index, section ->
                appendLine(section.markdownHeading())
                appendLine()
                section.templateBodyLines.forEach(::appendLine)
                if (index != DesignSectionId.entries.lastIndex) {
                    appendLine()
                }
            }
        }.trim()
    }

    fun draftPlaceholderLines(): List<String> {
        return DesignSectionId.entries.map(DesignSectionId::draftPlaceholderLine)
    }

    fun artifactDraftMarkdown(documentTitle: String = "# Design Document"): String {
        return buildString {
            appendLine(documentTitle)
            appendLine()
            DesignSectionId.entries.forEachIndexed { index, section ->
                appendLine(section.englishMarkdownHeading())
                appendLine("- ${section.draftPlaceholderLine}")
                if (index != DesignSectionId.entries.lastIndex) {
                    appendLine()
                }
            }
        }.trim()
    }

    fun findLevelTwoHeadings(content: String): List<HeadingMatch> {
        val normalized = normalizeContent(content)
        return HEADING_REGEX.findAll(normalized)
            .mapNotNull { match ->
                val title = match.groupValues[1].trim()
                val sectionId = DesignSectionId.fromHeadingTitle(title) ?: return@mapNotNull null
                HeadingMatch(
                    sectionId = sectionId,
                    title = title,
                    startOffset = match.range.first,
                    endOffsetExclusive = match.range.last + 1,
                )
            }
            .toList()
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private val HEADING_REGEX = Regex("""(?m)^##\s+(.+?)\s*$""")
}
