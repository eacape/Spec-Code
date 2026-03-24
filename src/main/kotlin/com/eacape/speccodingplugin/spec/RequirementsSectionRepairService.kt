package com.eacape.speccodingplugin.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.llm.ClaudeCliLlmProvider
import com.eacape.speccodingplugin.llm.CodexCliLlmProvider
import com.eacape.speccodingplugin.llm.LlmMessage
import com.eacape.speccodingplugin.llm.LlmRequest
import com.eacape.speccodingplugin.llm.LlmResponse
import com.eacape.speccodingplugin.llm.LlmRole
import com.eacape.speccodingplugin.llm.LlmRouter
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.UUID

internal data class SectionDraftPatch(
    val sectionId: RequirementsSectionId,
    val heading: String,
    val body: String,
    val renderedBlock: String,
    val insertAfterSectionId: RequirementsSectionId? = null,
    val insertBeforeSectionId: RequirementsSectionId? = null,
)

internal data class RequirementsSectionDraftRequest(
    val workflow: SpecWorkflow,
    val document: SpecDocument,
    val missingSections: List<RequirementsSectionId>,
    val headingStyle: RequirementsHeadingStyle,
    val confirmedContext: String,
)

internal data class RequirementsSectionRepairPreview(
    val workflowId: String,
    val requirementsDocumentPath: Path,
    val expectedRevision: Long?,
    val missingSectionsBefore: List<RequirementsSectionId>,
    val patches: List<SectionDraftPatch>,
    val originalContent: String,
    val updatedContent: String,
) {
    val changed: Boolean
        get() = patches.isNotEmpty() && originalContent != updatedContent
}

internal data class RequirementsSectionRepairApplyResult(
    val workflow: SpecWorkflow,
    val requirementsDocumentPath: Path,
    val preview: RequirementsSectionRepairPreview,
)

internal class RequirementsSectionRepairService(
    private val project: Project,
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
    draftGenerator: (suspend (RequirementsSectionDraftRequest) -> Result<String>)? = null,
    private val llmRouter: LlmRouter = LlmRouter.getInstance(),
    private val settingsProvider: () -> RequirementsSectionAiSettings = RequirementsSectionAiSupport::loadSettings,
    requestCanceller: ((String?, String) -> Unit)? = null,
) {
    private val requestCanceller: (String?, String) -> Unit = requestCanceller ?: ::cancelRequestAcrossProviders
    private val sectionDraftGenerator: suspend (RequirementsSectionDraftRequest, String?) -> Result<String> =
        if (draftGenerator != null) {
            { request, _ -> draftGenerator(request) }
        } else {
            ::generateSectionDraft
        }

    fun previewRepair(
        workflowId: String,
        requestedMissingSections: List<RequirementsSectionId>,
        confirmedContextOverride: String? = null,
        requestId: String? = null,
    ): RequirementsSectionRepairPreview {
        val normalizedWorkflowId = workflowId.trim()
        require(normalizedWorkflowId.isNotEmpty()) { "workflowId cannot be blank." }

        val workflow = storage.loadWorkflow(normalizedWorkflowId).getOrThrow()
        val document = loadRequirementsDocument(workflow)
        val originalContent = normalizeContent(document.content)
        val actualMissingSections = RequirementsSectionSupport.missingSections(originalContent)
        val targetMissingSections = when {
            requestedMissingSections.isEmpty() -> actualMissingSections
            else -> requestedMissingSections.filter { section -> section in actualMissingSections }
        }
        val requirementsDocumentPath = artifactService.locateArtifact(normalizedWorkflowId, StageId.REQUIREMENTS)
        if (targetMissingSections.isEmpty()) {
            return RequirementsSectionRepairPreview(
                workflowId = normalizedWorkflowId,
                requirementsDocumentPath = requirementsDocumentPath,
                expectedRevision = null,
                missingSectionsBefore = actualMissingSections,
                patches = emptyList(),
                originalContent = originalContent,
                updatedContent = originalContent,
            )
        }

        val headingStyle = RequirementsSectionSupport.detectHeadingStyle(originalContent)
        val request = RequirementsSectionDraftRequest(
            workflow = workflow,
            document = document,
            missingSections = targetMissingSections,
            headingStyle = headingStyle,
            confirmedContext = if (confirmedContextOverride != null) {
                confirmedContextOverride
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim()
            } else {
                workflow.clarificationRetryState
                    ?.confirmedContext
                    ?.replace("\r\n", "\n")
                    ?.replace('\r', '\n')
                    ?.trim()
                    .orEmpty()
                },
        )
        val normalizedRequestId = requestId?.trim()?.takeIf(String::isNotBlank)
        val generatedDraft = runBlocking {
            sectionDraftGenerator(request, normalizedRequestId).getOrThrow()
        }
        val draftedBlocks = parseDraftedSections(
            rawDraft = generatedDraft,
            missingSections = targetMissingSections,
            headingStyle = headingStyle,
        )
        val (patches, updatedContent) = buildPreviewContent(
            originalContent = originalContent,
            draftedBlocks = draftedBlocks,
        )
        return RequirementsSectionRepairPreview(
            workflowId = normalizedWorkflowId,
            requirementsDocumentPath = requirementsDocumentPath,
            expectedRevision = null,
            missingSectionsBefore = actualMissingSections,
            patches = patches,
            originalContent = originalContent,
            updatedContent = updatedContent,
        )
    }

    fun applyPreview(preview: RequirementsSectionRepairPreview): RequirementsSectionRepairApplyResult {
        val workflow = updateDocument(
            preview.workflowId,
            preview.updatedContent,
            preview.expectedRevision,
        ).getOrThrow()
        val requirementsDocumentPath = artifactService.locateArtifact(preview.workflowId, StageId.REQUIREMENTS)
        return RequirementsSectionRepairApplyResult(
            workflow = workflow,
            requirementsDocumentPath = requirementsDocumentPath,
            preview = preview,
        )
    }

    fun cancelPreviewRequest(requestId: String, providerId: String? = null) {
        val normalizedRequestId = requestId.trim()
        if (normalizedRequestId.isBlank()) {
            return
        }
        requestCanceller(providerId, normalizedRequestId)
    }

    private fun loadRequirementsDocument(workflow: SpecWorkflow): SpecDocument {
        workflow.getDocument(SpecPhase.SPECIFY)?.let { document ->
            return document.copy(content = stripRenderedValidationSection(document.content))
        }
        val fallbackContent = artifactService.readArtifact(workflow.id, StageId.REQUIREMENTS)
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.let(::stripRenderedValidationSection)
            ?: throw IllegalStateException("requirements.md not found for workflow: ${workflow.id}")
        return SpecDocument(
            id = "${workflow.id}-${SpecPhase.SPECIFY.name.lowercase()}",
            phase = SpecPhase.SPECIFY,
            content = fallbackContent,
            metadata = SpecMetadata(
                title = "${SpecPhase.SPECIFY.displayName} Document",
                description = "Requirements document repair preview",
                updatedAt = workflow.updatedAt,
            ),
        )
    }

    private fun buildPreviewContent(
        originalContent: String,
        draftedBlocks: LinkedHashMap<RequirementsSectionId, String>,
    ): Pair<List<SectionDraftPatch>, String> {
        var currentContent = originalContent.trimEnd()
        val patches = mutableListOf<SectionDraftPatch>()
        draftedBlocks.forEach { (sectionId, renderedBlock) ->
            val anchor = resolveInsertionAnchor(currentContent, sectionId)
            currentContent = insertSectionBlock(
                content = currentContent,
                renderedBlock = renderedBlock,
                anchor = anchor,
            )
            patches += SectionDraftPatch(
                sectionId = sectionId,
                heading = renderedBlock.lineSequence().firstOrNull().orEmpty(),
                body = sectionBody(renderedBlock),
                renderedBlock = renderedBlock,
                insertAfterSectionId = anchor.insertAfterSectionId,
                insertBeforeSectionId = anchor.insertBeforeSectionId,
            )
        }
        return patches to currentContent.trimEnd()
    }

    private fun resolveInsertionAnchor(
        content: String,
        sectionId: RequirementsSectionId,
    ): InsertionAnchor {
        val headings = RequirementsSectionSupport.findLevelTwoHeadings(content)
        val presentSections = headings.map(RequirementsSectionSupport.HeadingMatch::sectionId).toSet()
        val previous = RequirementsSectionId.entries
            .takeWhile { current -> current != sectionId }
            .lastOrNull { candidate -> candidate in presentSections }
        if (previous != null) {
            return InsertionAnchor(insertAfterSectionId = previous)
        }
        val next = RequirementsSectionId.entries
            .dropWhile { current -> current != sectionId }
            .drop(1)
            .firstOrNull { candidate -> candidate in presentSections }
        return InsertionAnchor(insertBeforeSectionId = next)
    }

    private fun insertSectionBlock(
        content: String,
        renderedBlock: String,
        anchor: InsertionAnchor,
    ): String {
        val normalized = content.trimEnd()
        val headings = RequirementsSectionSupport.findLevelTwoHeadings(normalized)
        val trailingFenceOffset = trailingFenceInsertionOffset(normalized)
        val insertionOffset = when {
            anchor.insertAfterSectionId != null -> {
                val previousHeading = headings.first { heading -> heading.sectionId == anchor.insertAfterSectionId }
                headings.firstOrNull { heading -> heading.startOffset > previousHeading.startOffset }?.startOffset
                    ?: trailingFenceOffset
                    ?: normalized.length
            }

            anchor.insertBeforeSectionId != null -> {
                headings.first { heading -> heading.sectionId == anchor.insertBeforeSectionId }.startOffset
            }

            else -> trailingFenceOffset ?: normalized.length
        }

        val prefix = normalized.substring(0, insertionOffset).trimEnd()
        val suffix = normalized.substring(insertionOffset).trimStart('\n')
        return buildString {
            if (prefix.isNotBlank()) {
                append(prefix)
                append("\n\n")
            }
            append(renderedBlock.trim())
            if (suffix.isNotBlank()) {
                append("\n\n")
                append(suffix)
            }
        }.trimEnd()
    }

    private fun parseDraftedSections(
        rawDraft: String,
        missingSections: List<RequirementsSectionId>,
        headingStyle: RequirementsHeadingStyle,
    ): LinkedHashMap<RequirementsSectionId, String> {
        val sanitized = SpecMarkdownSanitizer.sanitize(rawDraft)
        if (sanitized.isBlank()) {
            throw IllegalStateException(
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.error.blankDraft"),
            )
        }

        val headingMatches = SECTION_HEADING_REGEX.findAll(sanitized).toList()
        val extractedBlocks = linkedMapOf<RequirementsSectionId, String>()
        headingMatches.forEachIndexed { index, match ->
            val sectionId = RequirementsSectionId.fromHeadingTitle(match.groupValues[1]) ?: return@forEachIndexed
            val nextOffset = headingMatches.getOrNull(index + 1)?.range?.first ?: sanitized.length
            extractedBlocks.putIfAbsent(sectionId, sanitized.substring(match.range.first, nextOffset).trim())
        }

        val missingFromDraft = missingSections.filterNot(extractedBlocks::containsKey)
        if (missingFromDraft.isNotEmpty()) {
            throw IllegalStateException(
                SpecCodingBundle.message(
                    "spec.toolwindow.gate.quickFix.aiFill.error.missingDraftSections",
                    RequirementsSectionSupport.describeSections(missingFromDraft),
                ),
            )
        }

        return linkedMapOf<RequirementsSectionId, String>().apply {
            missingSections.forEach { sectionId ->
                val rawBlock = extractedBlocks.getValue(sectionId)
                val body = sectionBody(rawBlock)
                if (body.isBlank()) {
                    throw IllegalStateException(
                        SpecCodingBundle.message(
                            "spec.toolwindow.gate.quickFix.aiFill.error.emptySection",
                            sectionId.displayName(),
                        ),
                    )
                }
                put(
                    sectionId,
                    buildString {
                        append(sectionId.heading(headingStyle))
                        append('\n')
                        append(body.trim())
                    },
                )
            }
        }
    }

    private suspend fun generateSectionDraft(
        request: RequirementsSectionDraftRequest,
        requestId: String?,
    ): Result<String> {
        return runCatching {
            val settings = settingsProvider()
            val providerId = RequirementsSectionAiSupport.resolvePreferredRealProviderId(
                llmRouter = llmRouter,
                settingsProvider = { settings },
            )
                ?: throw IllegalStateException(
                    SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.error.aiUnavailable"),
                )
            val effectiveRequestId = requestId ?: UUID.randomUUID().toString()
            val model = settings.selectedCliModel
                ?.trim()
                ?.takeIf { configuredModel -> configuredModel.isNotEmpty() }
            val llmRequest = LlmRequest(
                messages = listOf(
                    LlmMessage(LlmRole.SYSTEM, systemPrompt()),
                    LlmMessage(LlmRole.USER, userPrompt(request)),
                ),
                model = model,
                temperature = 0.2,
                maxTokens = 1400,
                metadata = mapOf(
                    "specQuickFix" to "requirements-section-repair",
                    "requestId" to effectiveRequestId,
                ),
                workingDirectory = project.basePath,
            )
            val response = requestLlmResponse(providerId, llmRequest)
            val content = SpecMarkdownSanitizer.sanitize(response.content)
            if (content.isBlank()) {
                throw IllegalStateException(
                    SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.error.blankDraft"),
                )
            }
            content
        }
    }

    private suspend fun requestLlmResponse(
        providerId: String,
        request: LlmRequest,
    ): LlmResponse {
        val direct = runCatching { llmRouter.generate(providerId = providerId, request = request) }
        val directResponse = direct.getOrNull()
        if (directResponse != null && directResponse.content.isNotBlank()) {
            return directResponse
        }

        val streamed = StringBuilder()
        val streamAttempt = runCatching {
            val response = llmRouter.stream(providerId = providerId, request = request) { chunk ->
                if (chunk.delta.isNotEmpty()) {
                    streamed.append(chunk.delta)
                }
            }
            response.copy(content = streamed.toString().ifBlank { response.content })
        }
        streamAttempt.getOrNull()?.let { response ->
            if (response.content.isNotBlank()) {
                return response
            }
        }

        throw streamAttempt.exceptionOrNull()
            ?: direct.exceptionOrNull()
            ?: IllegalStateException(
                SpecCodingBundle.message("spec.toolwindow.gate.quickFix.aiFill.error.blankDraft"),
            )
    }

    private fun cancelRequestAcrossProviders(providerId: String?, requestId: String) {
        llmRouter.cancel(providerId = providerId, requestId = requestId)
        llmRouter.cancel(providerId = ClaudeCliLlmProvider.ID, requestId = requestId)
        llmRouter.cancel(providerId = CodexCliLlmProvider.ID, requestId = requestId)
    }

    private fun systemPrompt(): String {
        return """
            You repair missing top-level sections in requirements.md.
            Return markdown only.
            Never rewrite or repeat existing sections.
            Never add explanations, code fences, YAML, or commentary.
            Only output the requested level-2 sections and their bodies.
        """.trimIndent()
    }

    private fun userPrompt(request: RequirementsSectionDraftRequest): String {
        val sectionList = request.missingSections.joinToString(separator = "\n") { section ->
            "- ${section.heading(request.headingStyle)}"
        }
        val clarificationContext = request.confirmedContext
            .takeIf { context -> context.isNotBlank() }
            ?.let { context ->
                """
                Confirmed clarification context:
                ```markdown
                $context
                ```
                """.trimIndent()
            }
            .orEmpty()
        return buildString {
            appendLine("Repair the missing requirements sections listed below.")
            appendLine("Keep the output specific to the current workflow. Existing sections are authoritative and must not be rewritten.")
            appendLine()
            appendLine("Workflow title: ${request.workflow.title.ifBlank { request.workflow.id }}")
            appendLine("Workflow description: ${request.workflow.description.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Missing sections to generate:")
            appendLine(sectionList)
            appendLine()
            appendLine("Current requirements.md content:")
            appendLine("```markdown")
            appendLine(request.document.content)
            appendLine("```")
            if (clarificationContext.isNotBlank()) {
                appendLine()
                appendLine(clarificationContext)
            }
            appendLine()
            appendLine("Output rules:")
            appendLine("- Use exactly the requested level-2 headings.")
            appendLine("- Do not include any other headings.")
            appendLine("- Do not include introductory or closing text.")
            appendLine("- The result must be ready to append into the existing requirements.md.")
        }
    }

    private fun sectionBody(renderedBlock: String): String {
        val normalized = normalizeContent(renderedBlock).trim()
        val firstNewline = normalized.indexOf('\n')
        if (firstNewline < 0) {
            return ""
        }
        return normalized.substring(firstNewline + 1).trim()
    }

    private fun trailingFenceInsertionOffset(content: String): Int? {
        val closingMatch = FENCE_CLOSE_REGEX.findAll(content).lastOrNull() ?: return null
        if (content.substring(closingMatch.range.last + 1).isNotBlank()) {
            return null
        }
        val beforeClosing = content.substring(0, closingMatch.range.first)
        return if (FENCE_OPEN_REGEX.containsMatchIn(beforeClosing)) {
            closingMatch.range.first
        } else {
            null
        }
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun stripRenderedValidationSection(content: String): String {
        return TRAILING_VALIDATION_SECTION_REGEX.replace(normalizeContent(content).trim(), "").trim()
    }

    private data class InsertionAnchor(
        val insertAfterSectionId: RequirementsSectionId? = null,
        val insertBeforeSectionId: RequirementsSectionId? = null,
    )

    companion object {
        private val SECTION_HEADING_REGEX = Regex("""(?m)^##\s+(.+?)\s*$""")
        private val FENCE_OPEN_REGEX = Regex("""(?m)^(```|~~~)\w*\s*$""")
        private val FENCE_CLOSE_REGEX = Regex("""(?m)^(```|~~~)\s*$""")
        private val TRAILING_VALIDATION_SECTION_REGEX =
            Regex("""(?s)\n---\n\n##\s+(?:验证结果|Validation Result)\s*\n.*$""")
    }
}
