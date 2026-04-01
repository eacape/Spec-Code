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

    fun buildVisibleInput(rawInput: String, imagePaths: List<String>): String {
        if (imagePaths.isEmpty()) {
            return rawInput.ifBlank { SpecCodingBundle.message("toolwindow.image.default.prompt") }
        }
        val names = imagePaths.indices.joinToString(", ") { index -> "image#${index + 1}" }
        val attachmentLine = SpecCodingBundle.message("toolwindow.image.visible.entry", names)
        if (rawInput.isBlank()) {
            return attachmentLine
        }
        return "$rawInput\n$attachmentLine"
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
}
