package com.eacape.speccodingplugin.ui

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.session.WORKFLOW_CHAT_COMMAND_PREFIX
import java.util.Locale

internal enum class ImprovedChatPanelComposerSubmissionKind {
    IGNORE,
    SLASH_COMMAND,
    WORKFLOW_COMMAND,
    CHAT_MESSAGE,
}

internal data class ImprovedChatPanelComposerSubmissionPlan(
    val kind: ImprovedChatPanelComposerSubmissionKind,
    val command: String? = null,
    val workflowCommandSessionTitleSeed: String? = null,
    val clearImageAttachments: Boolean = false,
    val statusMessage: String? = null,
)

internal object ImprovedChatPanelComposerSubmissionCoordinator {

    fun resolve(
        rawInput: String,
        visibleRawInput: String,
        selectedImagePaths: List<String>,
        specMode: Boolean,
        hasActiveWorkflow: Boolean,
    ): ImprovedChatPanelComposerSubmissionPlan {
        val trimmedVisibleInput = visibleRawInput.trim()
        val normalizedInput = rawInput.trim()
        val hasImageAttachments = selectedImagePaths.isNotEmpty()
        if (trimmedVisibleInput.isBlank() && !hasImageAttachments) {
            return ImprovedChatPanelComposerSubmissionPlan(
                kind = ImprovedChatPanelComposerSubmissionKind.IGNORE,
            )
        }
        if (trimmedVisibleInput.startsWith("/")) {
            return ImprovedChatPanelComposerSubmissionPlan(
                kind = ImprovedChatPanelComposerSubmissionKind.SLASH_COMMAND,
                command = rawInput,
                clearImageAttachments = hasImageAttachments,
                statusMessage = if (hasImageAttachments) {
                    SpecCodingBundle.message("toolwindow.image.attach.ignored.command")
                } else {
                    null
                },
            )
        }
        if (specMode && shouldRouteToWorkflowCommand(normalizedInput, hasActiveWorkflow)) {
            return if (normalizedInput.isBlank()) {
                ImprovedChatPanelComposerSubmissionPlan(
                    kind = ImprovedChatPanelComposerSubmissionKind.IGNORE,
                    clearImageAttachments = hasImageAttachments,
                    statusMessage = if (hasImageAttachments) {
                        SpecCodingBundle.message("toolwindow.workflow.attachment.boundary")
                    } else {
                        null
                    },
                )
            } else {
                ImprovedChatPanelComposerSubmissionPlan(
                    kind = ImprovedChatPanelComposerSubmissionKind.WORKFLOW_COMMAND,
                    command = "$WORKFLOW_CHAT_COMMAND_PREFIX $normalizedInput",
                    workflowCommandSessionTitleSeed = normalizedInput,
                    clearImageAttachments = hasImageAttachments,
                    statusMessage = if (hasImageAttachments) {
                        SpecCodingBundle.message("toolwindow.workflow.attachment.boundary")
                    } else {
                        null
                    },
                )
            }
        }
        return ImprovedChatPanelComposerSubmissionPlan(
            kind = ImprovedChatPanelComposerSubmissionKind.CHAT_MESSAGE,
        )
    }

    fun appendImagePathsToPrompt(prompt: String, imagePaths: List<String>): String {
        if (imagePaths.isEmpty()) return prompt
        val normalizedPrompt = prompt.ifBlank { SpecCodingBundle.message("toolwindow.image.default.prompt") }
        val attachmentBlock = buildString {
            appendLine(SpecCodingBundle.message("toolwindow.image.context.header"))
            imagePaths.forEach { path ->
                appendLine("- $path")
            }
        }.trimEnd()
        return "$normalizedPrompt\n\n$attachmentBlock"
    }

    fun appendContextLabelsToPrompt(prompt: String, contextLabels: List<String>): String {
        val normalizedLabels = contextLabels
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (normalizedLabels.isEmpty()) return prompt
        val contextBlock = buildContextPromptBlock(
            prompt = prompt,
            contextLabels = normalizedLabels,
        )
        return if (prompt.isBlank()) contextBlock else "$prompt\n\n$contextBlock"
    }

    fun buildVisibleInput(
        rawInput: String,
        imagePaths: List<String>,
        contextLabels: List<String> = emptyList(),
    ): String {
        val attachmentLines = mutableListOf<String>()
        if (imagePaths.isNotEmpty()) {
            val names = imagePaths.indices.joinToString(", ") { index -> "image#${index + 1}" }
            attachmentLines += SpecCodingBundle.message("toolwindow.image.visible.entry", names)
        }
        contextLabels
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { label ->
                attachmentLines += SpecCodingBundle.message("toolwindow.context.visible.entry", label)
            }

        if (rawInput.isBlank()) {
            return if (attachmentLines.isNotEmpty()) {
                attachmentLines.joinToString("\n")
            } else {
                SpecCodingBundle.message("toolwindow.image.default.prompt")
            }
        }
        if (attachmentLines.isEmpty()) {
            return rawInput
        }
        return buildString {
            append(rawInput)
            attachmentLines.forEach { line ->
                append('\n')
                append(line)
            }
        }
    }

    fun shouldRouteToWorkflowCommand(
        normalizedInput: String,
        hasActiveWorkflow: Boolean,
    ): Boolean {
        if (!hasActiveWorkflow) {
            return true
        }
        val trimmed = normalizedInput.trim()
        val token = trimmed.substringBefore(" ").trim().lowercase(Locale.ROOT)
        val args = trimmed.substringAfter(" ", "").trim()
        return when (token) {
            "open", "generate" -> true
            in BARE_WORKFLOW_CHAT_COMMAND_TOKENS -> args.isBlank()
            else -> false
        }
    }

    private val BARE_WORKFLOW_CHAT_COMMAND_TOKENS = setOf(
        "status",
        "next",
        "back",
        "complete",
        "help",
    )

    private fun buildContextPromptBlock(prompt: String, contextLabels: List<String>): String {
        val normalizedPrompt = prompt.trim()
        val lines = mutableListOf<String>()
        val isSingleContextIdentityQuestion =
            contextLabels.size == 1 && looksLikeDocumentIdentityQuestion(normalizedPrompt)
        if (isSingleContextIdentityQuestion) {
            lines += "The user's deictic reference (for example \"this\" or \"这个\") refers to the referenced context item below."
            lines += "Answer in this order:"
            lines += "1. Identify what the referenced document/file is in the first sentence."
            lines += "2. Briefly explain its purpose or scope."
            lines += "3. Cite only short supporting evidence."
            lines += "Do not start with a long section-by-section summary."
        } else {
            lines += "The referenced context items below are the subject and evidence for this request."
            lines += "If the user uses deictic wording like \"this\", \"that\", \"这个\", or \"该文档\", resolve it against these referenced items first."
            lines += "Answer directly before summarizing the referenced content."
            lines += "Do not dump long excerpts from the referenced content."
        }
        lines += SpecCodingBundle.message("toolwindow.context.prompt.header")
        contextLabels.forEach { label ->
            lines += "- $label"
        }
        return lines.joinToString("\n")
    }

    private fun looksLikeDocumentIdentityQuestion(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val normalized = prompt.lowercase(Locale.ROOT)
        if (IDENTITY_QUESTION_PATTERNS.any(normalized::contains)) {
            return true
        }
        return IDENTITY_QUESTION_REGEX.containsMatchIn(normalized)
    }

    private val IDENTITY_QUESTION_PATTERNS = listOf(
        "这个是什么文档",
        "这是什么文档",
        "这个文档是什么",
        "这份文档是什么",
        "这个文件是什么",
        "这是什么文件",
        "这个是啥文档",
        "what is this document",
        "what document is this",
        "what is this file",
        "what file is this",
    )

    private val IDENTITY_QUESTION_REGEX = Regex(
        """(?:这个|这份|该)(?:是什么)?(?:文档|文件)|what\s+(?:is\s+)?this\s+(?:document|file)|what\s+(?:document|file)\s+is\s+this""",
        RegexOption.IGNORE_CASE,
    )
}
