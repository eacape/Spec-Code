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
    private val englishTitleAliases: List<String> = emptyList(),
    private val localizedTitleAliases: List<String> = emptyList(),
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
        englishTitleAliases = listOf("Overall Acceptance Criteria"),
        localizedTitleAliases = listOf("总体验收标准", "首版总体验收标准"),
    ),
    ;

    internal val markers: List<String>
        get() = titleAliases
            .flatMap { alias -> listOf("## $alias", alias) }
            .distinct()

    internal val titleAliases: List<String>
        get() = englishHeadingTitles + localizedHeadingTitles

    fun displayName(): String = SpecCodingBundle.message(displayNameKey)

    fun heading(style: RequirementsHeadingStyle): String = "## " + when (style) {
        RequirementsHeadingStyle.ENGLISH -> englishTitle
        RequirementsHeadingStyle.CHINESE -> localizedTitle
    }

    fun matchesHeadingTitle(title: String): Boolean {
        val normalized = normalizeTitleToken(title)
        return titleAliases.any { candidate -> normalizeTitleToken(candidate) == normalized }
    }

    internal fun matchesHeadingTitle(title: String, style: RequirementsHeadingStyle): Boolean {
        val normalized = normalizeTitleToken(title)
        return headingTitles(style).any { candidate -> normalizeTitleToken(candidate) == normalized }
    }

    companion object {
        fun fromStableId(stableId: String): RequirementsSectionId? =
            entries.firstOrNull { section -> section.stableId == stableId.trim() }

        fun fromHeadingTitle(title: String): RequirementsSectionId? =
            entries.firstOrNull { section -> section.matchesHeadingTitle(title) }

        internal fun normalizeTitleToken(title: String): String {
            return title
                .trim()
                .replace(TITLE_EDGE_DECORATION_REGEX, "")
                .replace(NORMALIZED_TRAILING_TITLE_DELIMITER_REGEX, "")
                .replace(TITLE_EDGE_DECORATION_REGEX, "")
                .let(::stripLeadingTitleNumbering)
                .replace(TITLE_WHITESPACE_REGEX, " ")
                .replace("-", "")
                .trim()
                .lowercase()
        }

        private fun stripLeadingTitleNumbering(title: String): String {
            var normalized = title.trim()
            while (true) {
                val updated = LEADING_TITLE_NUMBERING_REGEXES
                    .fold(normalized) { current, regex -> regex.replaceFirst(current, "") }
                    .trimStart()
                if (updated == normalized) {
                    return normalized
                }
                normalized = updated
            }
        }

        private val TITLE_EDGE_DECORATION_REGEX = Regex("""^[*_`~#\s]+|[*_`~#\s]+$""")
        private val NORMALIZED_TRAILING_TITLE_DELIMITER_REGEX = Regex("""\s*[:\uFF1A]\s*$""")
        private val TITLE_WHITESPACE_REGEX = Regex("""\s+""")
        private val LEADING_TITLE_NUMBERING_REGEXES = listOf(
            Regex("""^[\(\uFF08]\d+[\)\uFF09]\s*"""),
            Regex("""^\d+(?:[.\uFF0E]\d+)*(?:\s*[.)\]\uFF09\u3001:\uFF1A-]\s*|\s+)"""),
            Regex("""^[\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341\u767E\u5343]+(?:\s*[\u3001.\uFF0E:\uFF1A-]\s*|\s+)"""),
        )
    }

    private val englishHeadingTitles: List<String>
        get() = listOf(englishTitle) + englishTitleAliases

    private val localizedHeadingTitles: List<String>
        get() = listOf(localizedTitle) + localizedTitleAliases

    private fun headingTitles(style: RequirementsHeadingStyle): List<String> = when (style) {
        RequirementsHeadingStyle.ENGLISH -> englishHeadingTitles
        RequirementsHeadingStyle.CHINESE -> localizedHeadingTitles
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
        val headingTitles = HEADING_REGEX.findAll(normalized)
            .map { match -> match.groupValues[1].trim() }
            .toList()
        val hasLocalizedHeading = headingTitles.any { title ->
            RequirementsSectionId.entries.any { section ->
                section.matchesHeadingTitle(title, RequirementsHeadingStyle.CHINESE)
            }
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
