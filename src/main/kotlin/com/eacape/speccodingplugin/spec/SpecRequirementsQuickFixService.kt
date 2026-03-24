package com.eacape.speccodingplugin.spec

import com.intellij.openapi.project.Project
import java.nio.file.Path

data class SpecRequirementsQuickFixResult(
    val workflowId: String,
    val requirementsDocumentPath: Path,
    val changed: Boolean,
    val issuesBefore: List<RequirementsDraftIssueKind>,
    val issuesAfter: List<RequirementsDraftIssueKind>,
)

class SpecRequirementsQuickFixService(
    project: Project,
    private val storage: SpecStorage = SpecStorage.getInstance(project),
    private val artifactService: SpecArtifactService = SpecArtifactService(project),
    private val updateDocument: (String, String, Long?) -> Result<SpecWorkflow> = { workflowId, content, expectedRevision ->
        SpecEngine.getInstance(project).updateDocumentContent(
            workflowId = workflowId,
            phase = SpecPhase.SPECIFY,
            content = content,
            expectedRevision = expectedRevision,
        )
    },
) {

    fun repairRequirementsArtifact(
        workflowId: String,
        trigger: String = TRIGGER_GATE_QUICK_FIX,
    ): SpecRequirementsQuickFixResult {
        val normalizedWorkflowId = workflowId.trim()
        require(normalizedWorkflowId.isNotEmpty()) { "workflowId cannot be blank." }

        val workflow = storage.loadWorkflow(normalizedWorkflowId).getOrThrow()
        val existingDocument = workflow.getDocument(SpecPhase.SPECIFY)
        val original = normalizeContent(
            existingDocument?.content
                ?: artifactService.readArtifact(normalizedWorkflowId, StageId.REQUIREMENTS)
                ?: throw IllegalStateException("requirements.md not found for workflow: $normalizedWorkflowId"),
        )
        val issuesBefore = SpecValidator.requirementsDraftIssueKinds(original)
        val repaired = repairMarkdown(workflow, original)
        val issuesAfter = SpecValidator.requirementsDraftIssueKinds(repaired)
        val changed = repaired != original

        if (changed) {
            updateDocument(
                normalizedWorkflowId,
                repaired,
                existingDocument?.metadata?.updatedAt,
            ).getOrThrow()
            storage.appendAuditEvent(
                workflowId = normalizedWorkflowId,
                eventType = SpecAuditEventType.REQUIREMENTS_ARTIFACT_REPAIRED,
                details = linkedMapOf(
                    "trigger" to trigger,
                    "file" to StageId.REQUIREMENTS.artifactFileName.orEmpty(),
                    "issuesBefore" to issuesBefore.size.toString(),
                    "issuesAfter" to issuesAfter.size.toString(),
                ),
            ).getOrThrow()
        }

        return SpecRequirementsQuickFixResult(
            workflowId = normalizedWorkflowId,
            requirementsDocumentPath = artifactService.locateArtifact(normalizedWorkflowId, StageId.REQUIREMENTS),
            changed = changed,
            issuesBefore = issuesBefore,
            issuesAfter = issuesAfter,
        )
    }

    private fun repairMarkdown(workflow: SpecWorkflow, markdown: String): String {
        val style = RequirementsSectionSupport.detectHeadingStyle(markdown)
        val lines = normalizeContent(markdown).split('\n').toMutableList()
        var currentSection: RequirementsSectionId? = null

        lines.indices.forEach { index ->
            val line = lines[index]
            if (line.startsWith("## ")) {
                currentSection = RequirementsSectionId.fromHeadingTitle(line.removePrefix("## ").trim())
                return@forEach
            }
            if (RequirementsDraftIssueKind.containsTodoPlaceholder(line)) {
                lines[index] = repairTodoLine(
                    line = line,
                    sectionId = currentSection,
                    workflow = workflow,
                    style = style,
                )
                return@forEach
            }
            if (RequirementsDraftIssueKind.containsUserStoryTemplate(line)) {
                lines[index] = repairUserStoryLine(
                    line = line,
                    workflow = workflow,
                    style = style,
                )
            }
        }

        return lines.joinToString("\n").trim()
    }

    private fun repairTodoLine(
        line: String,
        sectionId: RequirementsSectionId?,
        workflow: SpecWorkflow,
        style: RequirementsHeadingStyle,
    ): String {
        val trimmed = line.trimStart()
        val indentation = line.take(line.length - trimmed.length)
        val prefix = TODO_LINE_REGEX.matchEntire(trimmed)
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)
            ?: when (sectionId) {
                RequirementsSectionId.USER_STORIES -> ""
                else -> "- "
            }
        val content = when (sectionId) {
            RequirementsSectionId.FUNCTIONAL -> functionalDraft(workflow, style)
            RequirementsSectionId.NON_FUNCTIONAL -> nonFunctionalDraft(workflow, style)
            RequirementsSectionId.ACCEPTANCE_CRITERIA -> acceptanceDraft(workflow, style)
            RequirementsSectionId.USER_STORIES -> userStoryDraft(workflow, style)
            null -> genericDraft(workflow, style)
        }
        return indentation + prefix + content
    }

    private fun repairUserStoryLine(
        line: String,
        workflow: SpecWorkflow,
        style: RequirementsHeadingStyle,
    ): String {
        val trimmed = line.trimStart()
        val indentation = line.take(line.length - trimmed.length)
        val prefix = USER_STORY_BULLET_PREFIX_REGEX.find(trimmed)
            ?.value
            ?.takeIf(String::isNotBlank)
            .orEmpty()
        return indentation + prefix + userStoryDraft(workflow, style)
    }

    private fun functionalDraft(workflow: SpecWorkflow, style: RequirementsHeadingStyle): String {
        val subject = workflowSubject(workflow, style)
        return when (style) {
            RequirementsHeadingStyle.ENGLISH ->
                "Define the core user-visible behavior and scope for $subject."
            RequirementsHeadingStyle.CHINESE ->
                "\u660e\u786e$subject\u7684\u6838\u5fc3\u529f\u80fd\u884c\u4e3a\u4e0e\u4e1a\u52a1\u8fb9\u754c\u3002"
        }
    }

    private fun nonFunctionalDraft(workflow: SpecWorkflow, style: RequirementsHeadingStyle): String {
        val subject = workflowSubject(workflow, style)
        return when (style) {
            RequirementsHeadingStyle.ENGLISH ->
                "Capture measurable performance, security, reliability, and operability constraints for $subject."
            RequirementsHeadingStyle.CHINESE ->
                "\u7ea6\u675f$subject\u7684\u6027\u80fd\u3001\u5b89\u5168\u6027\u3001\u53ef\u9760\u6027\u4e0e\u53ef\u8fd0\u7ef4\u6027\u3002"
        }
    }

    private fun acceptanceDraft(workflow: SpecWorkflow, style: RequirementsHeadingStyle): String {
        val subject = workflowSubject(workflow, style)
        return when (style) {
            RequirementsHeadingStyle.ENGLISH ->
                "$subject has concrete scope, constraints, and acceptance criteria that can be verified."
            RequirementsHeadingStyle.CHINESE ->
                "$subject\u7684\u8303\u56f4\u3001\u7ea6\u675f\u4e0e\u9a8c\u6536\u6807\u51c6\u5df2\u5177\u4f53\u53ef\u9a8c\u8bc1\u3002"
        }
    }

    private fun userStoryDraft(workflow: SpecWorkflow, style: RequirementsHeadingStyle): String {
        val subject = workflowSubject(workflow, style)
        return when (style) {
            RequirementsHeadingStyle.ENGLISH ->
                "As a workflow author, I want $subject to be described concretely, so that the team can continue into design and implementation."
            RequirementsHeadingStyle.CHINESE ->
                "\u4f5c\u4e3a\u89c4\u683c\u64b0\u5199\u8005\uff0c\u6211\u5e0c\u671b$subject\u88ab\u5177\u4f53\u8bf4\u6e05\u695a\uff0c\u4ee5\u4fbf\u56e2\u961f\u53ef\u4ee5\u7ee7\u7eed\u8fdb\u5165\u8bbe\u8ba1\u4e0e\u5b9e\u73b0\u3002"
        }
    }

    private fun genericDraft(workflow: SpecWorkflow, style: RequirementsHeadingStyle): String {
        val subject = workflowSubject(workflow, style)
        return when (style) {
            RequirementsHeadingStyle.ENGLISH ->
                "Define the concrete requirement details for $subject."
            RequirementsHeadingStyle.CHINESE ->
                "\u8865\u5145$subject\u7684\u5177\u4f53\u9700\u6c42\u7ec6\u8282\u3002"
        }
    }

    private fun workflowSubject(workflow: SpecWorkflow, style: RequirementsHeadingStyle): String {
        val title = workflow.title.trim().ifBlank { workflow.id }
        return when (style) {
            RequirementsHeadingStyle.ENGLISH -> "workflow \"$title\""
            RequirementsHeadingStyle.CHINESE -> "\u5de5\u4f5c\u6d41\u201c$title\u201d"
        }
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    companion object {
        const val TRIGGER_GATE_QUICK_FIX: String = "gate-quick-fix"

        private val TODO_LINE_REGEX = Regex("""^([-*]?\s*(?:\[[ xX]\]\s*)?)TODO\s*:\s*.*$""")
        private val USER_STORY_BULLET_PREFIX_REGEX = Regex("""^[-*]\s+(?:\[[ xX]\]\s*)?""")
    }
}
