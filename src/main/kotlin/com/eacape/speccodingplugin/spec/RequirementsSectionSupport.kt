package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle

enum class RequirementsHeadingStyle {
    ENGLISH,
    CHINESE,
}

enum class RequirementsSectionId(
    val stableId: String,
    val englishTitle: String,
    val localizedTitle: String,
    private val displayNameKey: String,
) {
    FUNCTIONAL(
        stableId = "functional-requirements",
        englishTitle = "Functional Requirements",
        localizedTitle = "功能需求",
        displayNameKey = "spec.requirements.section.functional",
    ),
    NON_FUNCTIONAL(
        stableId = "non-functional-requirements",
        englishTitle = "Non-Functional Requirements",
        localizedTitle = "非功能需求",
        displayNameKey = "spec.requirements.section.nonFunctional",
    ),
    USER_STORIES(
        stableId = "user-stories",
        englishTitle = "User Stories",
        localizedTitle = "用户故事",
        displayNameKey = "spec.requirements.section.userStories",
    ),
    ACCEPTANCE_CRITERIA(
        stableId = "acceptance-criteria",
        englishTitle = "Acceptance Criteria",
        localizedTitle = "验收标准",
        displayNameKey = "spec.requirements.section.acceptanceCriteria",
    ),
    ;

    internal val markers: List<String>
        get() = listOf("## $englishTitle", "## $localizedTitle")

    fun displayName(): String = SpecCodingBundle.message(displayNameKey)

    fun heading(style: RequirementsHeadingStyle): String = "## " + when (style) {
        RequirementsHeadingStyle.ENGLISH -> englishTitle
        RequirementsHeadingStyle.CHINESE -> localizedTitle
    }

    fun matchesHeadingTitle(title: String): Boolean {
        val normalized = title.trim()
        return normalized.equals(englishTitle, ignoreCase = true) ||
            normalized.equals(localizedTitle, ignoreCase = true)
    }

    companion object {
        fun fromStableId(stableId: String): RequirementsSectionId? =
            entries.firstOrNull { section -> section.stableId == stableId.trim() }

        fun fromHeadingTitle(title: String): RequirementsSectionId? =
            entries.firstOrNull { section -> section.matchesHeadingTitle(title) }
    }
}

object RequirementsSectionSupport {

    data class HeadingMatch(
        val sectionId: RequirementsSectionId,
        val title: String,
        val startOffset: Int,
        val endOffsetExclusive: Int,
    )

    fun missingSections(content: String): List<RequirementsSectionId> {
        val present = findLevelTwoHeadings(content)
            .map(HeadingMatch::sectionId)
            .toSet()
        return RequirementsSectionId.entries.filterNot(present::contains)
    }

    fun hasRequiredSections(content: String): Boolean = missingSections(content).isEmpty()

    fun describeSections(sections: List<RequirementsSectionId>): String {
        return sections.joinToString(", ") { section -> section.displayName() }
    }

    fun detectHeadingStyle(content: String): RequirementsHeadingStyle {
        val normalized = normalizeContent(content)
        val hasLocalizedHeading = RequirementsSectionId.entries.any { section ->
            normalized.contains("## ${section.localizedTitle}", ignoreCase = true)
        }
        return if (hasLocalizedHeading) {
            RequirementsHeadingStyle.CHINESE
        } else {
            RequirementsHeadingStyle.ENGLISH
        }
    }

    fun findLevelTwoHeadings(content: String): List<HeadingMatch> {
        val normalized = normalizeContent(content)
        return HEADING_REGEX.findAll(normalized)
            .mapNotNull { match ->
                val title = match.groupValues[1].trim()
                val sectionId = RequirementsSectionId.fromHeadingTitle(title) ?: return@mapNotNull null
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
