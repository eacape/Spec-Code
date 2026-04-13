package com.eacape.speccodingplugin.spec

enum class DesignSectionId(
    val stableId: String,
    val validationDisplayName: String,
    private val headingTitles: List<String>,
) {
    ARCHITECTURE(
        stableId = "architecture-design",
        validationDisplayName = "架构设计 (Architecture Design)",
        headingTitles = listOf(
            "Architecture Design",
            "Architecture",
            "架构设计",
            "系统架构",
        ),
    ),
    TECHNOLOGY(
        stableId = "technology-choices",
        validationDisplayName = "技术选型 (Technology Choices / Technology Stack)",
        headingTitles = listOf(
            "Technology Choices",
            "Technology Stack",
            "技术选型",
            "技术方案",
        ),
    ),
    DATA_MODEL(
        stableId = "data-model",
        validationDisplayName = "数据模型 (Data Model)",
        headingTitles = listOf(
            "Data Model",
            "数据模型",
            "实体模型",
        ),
    ),
    API_DESIGN(
        stableId = "api-design",
        validationDisplayName = "接口设计 (API Design)",
        headingTitles = listOf(
            "API Design",
            "API 设计",
        ),
    ),
    NON_FUNCTIONAL(
        stableId = "non-functional-design",
        validationDisplayName = "非功能设计 (Non-Functional Design)",
        headingTitles = listOf(
            "Non-Functional Design",
            "Non-Functional Requirements",
            "非功能设计",
            "非功能需求",
        ),
    ),
    ;

    fun matchesHeadingTitle(title: String): Boolean {
        val normalized = title.trim()
        return headingTitles.any { heading ->
            normalized.equals(heading, ignoreCase = true)
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
        val present = findLevelTwoHeadings(content)
            .map(HeadingMatch::sectionId)
            .toSet()
        return DesignSectionId.entries.filterNot(present::contains)
    }

    fun hasRequiredSections(content: String): Boolean = missingSections(content).isEmpty()

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
